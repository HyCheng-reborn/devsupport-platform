package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.MessageStatus;
import interview.guide.modules.knowledgebase.model.QueryRequest;
import interview.guide.modules.knowledgebase.model.QueryResponse;
import interview.guide.modules.knowledgebase.model.RetrievalResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识库查询服务
 * 基于向量搜索的RAG问答
 */
@Slf4j
@Service
public class KnowledgeBaseQueryService {
    private static final String NO_RESULT_RESPONSE = "抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。";
    private static final int STREAM_PROBE_CHARS = 120;
    private static final int MAX_REWRITE_HISTORY_CHAR = 200;

    /**
     * 最终拒答的判定不再依赖"第一句含宽泛子串"，而是把拒答构造、以及可能削弱它的
     * 引用 / 条件 / 否定分别限定到各自的作用范围后再判断，避免"整句含如果 / 引号 /
     * 否定词就一律放行"：
     * <ul>
     *   <li>{@link #REFUSAL_INABILITY}：同一分句内"无法/不能/难以/没法 … 回答/作答/给出答案"的搭配；</li>
     *   <li>{@link #REFUSAL_EMPTY_RETRIEVAL}："未/没有 + 检索|找到|发现|查到|命中 + (相关)? + 信息|内容|资料|结果|依据"，
     *       对象必须是信息类，不匹配"找不到配置文件""知识库中未配置……字段"这类描述性排查用语；</li>
     *   <li>{@link #QUOTED_SPAN}：成对引号内的内容是"被引用/复述的文本"（提及），判定时先剔除；</li>
     *   <li>{@link #CLAUSE_SPLIT}：以子句为作用域逐句判断，条件与否定只作用于其所在子句；</li>
     *   <li>{@link #NEGATION_BEFORE_MODAL}：否定词紧邻情态词时仅取消该处拒答，不外溢到后面的真实拒答；</li>
     *   <li>{@link #CONDITIONAL_CONNECTIVE}：条件连接词位于拒答之前且同属一个子句时，该拒答是假设。</li>
     * </ul>
     * 固定无结果模板另由 {@code NO_RESULT_RESPONSE} 精确匹配；空输出 / 无文档由 {@code resolveFinalStatus} 处理。
     */
    private static final Pattern REFUSAL_INABILITY = Pattern.compile(
        "(无法|不能|难以|没法)[^。．.！!？?\\n\\r]{0,12}(回答|作答|给出答案|给出答复)");

    private static final Pattern REFUSAL_EMPTY_RETRIEVAL = Pattern.compile(
        "(未|没有|未能|没能)[^。．.！!？?\\n\\r]{0,8}(检索|找到|发现|查到|命中)"
            + "[^。．.！!？?\\n\\r]{0,8}(相关)?(信息|内容|资料|结果|依据)");

    /**
     * 成对引号内的引用 / 提及文本，判定拒答前先屏蔽，避免把复述的"无法回答"当成当前拒答。
     * 覆盖：中文弯单/双引号、ASCII 双引号（允许跨换行）、ASCII 单引号（不跨换行，降低撇号误配）、直角引号。
     * 未闭合的引号不匹配（不会贪婪吞到结尾）。
     */
    private static final Pattern QUOTED_SPAN = Pattern.compile(
        "‘[^’]*’|“[^”]*”|\"[^\"]*\"|'[^'\\n\\r]*'"
            + "|「[^」]*」|『[^』]*』");

    /** 屏蔽引用时用于替换整段引用的中性占位符：保留一个不贡献语义的标记，使引用两侧文本不被拼接，也不被删除。 */
    private static final String QUOTE_MASK = "\u25A1";

    /** 子句切分：条件 / 否定的作用范围只在同一子句内生效；引用占位符不作为边界，以免切断引用外的拒答构造。 */
    private static final Pattern CLAUSE_SPLIT = Pattern.compile("[，,、；;：:。.．！!？?\\n\\r]+");

    /** 紧邻情态词之前的否定词，只取消该处拒答（并非 / 并不是 / 不是 / 并未 / 绝非 / 绝不）。 */
    private static final Pattern NEGATION_BEFORE_MODAL = Pattern.compile(
        "(并非|并不是|不是|并未|绝非|绝不)$");

    /** 位于拒答之前、同属一个子句的条件连接词，表明该拒答是假设而非当前陈述。 */
    private static final Pattern CONDITIONAL_CONNECTIVE = Pattern.compile(
        "(如果|假如|倘若|若是|若|要是|万一|一旦|设若)");

    /** "……无法回答时请检查……"里紧跟"时"之后出现的请求 / 指令词，配合条件排查识别。 */
    private static final Pattern CONDITIONAL_REQUEST_MARKER = Pattern.compile(
        "(请|则|就|那么|建议|应|可|需|当|尝试|检查|重新|重试|补充|换)");

    private final LlmProviderRegistry llmProviderRegistry;
    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseListService listService;
    private final KnowledgeBaseCountService countService;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final PromptTemplate rewritePromptTemplate;
    private final boolean rewriteEnabled;
    private final int shortQueryLength;
    private final int topkShort;
    private final int topkMedium;
    private final int topkLong;
    private final double minScoreShort;
    private final double minScoreDefault;

    public KnowledgeBaseQueryService(
            LlmProviderRegistry llmProviderRegistry,
            KnowledgeBaseVectorService vectorService,
            KnowledgeBaseListService listService,
            KnowledgeBaseCountService countService,
            KnowledgeBaseQueryProperties queryProperties,
            ResourceLoader resourceLoader) throws IOException {
        this.llmProviderRegistry = llmProviderRegistry;
        this.vectorService = vectorService;
        this.listService = listService;
        this.countService = countService;
        this.systemPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getSystemPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.userPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getUserPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.rewritePromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getRewritePromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.rewriteEnabled = queryProperties.getRewrite().isEnabled();
        this.shortQueryLength = queryProperties.getSearch().getShortQueryLength();
        this.topkShort = queryProperties.getSearch().getTopkShort();
        this.topkMedium = queryProperties.getSearch().getTopkMedium();
        this.topkLong = queryProperties.getSearch().getTopkLong();
        this.minScoreShort = queryProperties.getSearch().getMinScoreShort();
        this.minScoreDefault = queryProperties.getSearch().getMinScoreDefault();
    }

    private ChatClient getChatClient() {
        return llmProviderRegistry.getDefaultChatClient();
    }

    /**
     * 基于单个知识库回答用户问题
     *
     * @param knowledgeBaseId 知识库ID
     * @param question 用户问题
     * @return AI回答
     */
    public String answerQuestion(Long knowledgeBaseId, String question) {
        return answerQuestion(List.of(knowledgeBaseId), question);
    }

    /**
     * 基于多个知识库回答用户问题（RAG）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return AI回答
     */
    public String answerQuestion(List<Long> knowledgeBaseIds, String question) {
        log.info("收到知识库提问: kbIds={}, question={}", knowledgeBaseIds, question);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return NO_RESULT_RESPONSE;
        }

        countService.updateQuestionCounts(knowledgeBaseIds);

        QueryContext queryContext = buildQueryContext(question, List.of());
        // 非流式路径无上下文，案例检索被跳过
        List<Document> relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds, Map.of());

        if (!hasEffectiveHit(relevantDocs)) {
            return NO_RESULT_RESPONSE;
        }

        String context = relevantDocs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(context, question);

        try {
            String answer = getChatClient().prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            answer = normalizeAnswer(answer);

            log.info("知识库问答完成: kbIds={}", knowledgeBaseIds);
            return answer;

        } catch (Exception e) {
            log.error("知识库问答失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "知识库查询失败：" + e.getMessage());
        }
    }

    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt() {
        return systemPromptTemplate.render()
            + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
    }

    /**
     * 构建用户提示词
     */
    private String buildUserPrompt(String context, String question) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("context", context);
        variables.put("question", question);
        return userPromptTemplate.render(variables);
    }

    /**
     * 查询知识库并返回完整响应
     */
    public QueryResponse queryKnowledgeBase(QueryRequest request) {
        String answer = answerQuestion(request.knowledgeBaseIds(), request.question());

        // 获取知识库名称（多个知识库用逗号分隔）
        List<String> kbNames = listService.getKnowledgeBaseNames(request.knowledgeBaseIds());
        String kbNamesStr = String.join("、", kbNames);

        // 使用第一个知识库ID作为主要标识（兼容前端）
        Long primaryKbId = request.knowledgeBaseIds().getFirst();

        return new QueryResponse(answer, primaryKbId, kbNamesStr);
    }

    /**
     * 流式查询知识库（SSE，无上下文）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return 检索结果（包含流式响应和来源文档）
     */
    public RetrievalResult answerQuestionStream(List<Long> knowledgeBaseIds, String question) {
        return answerQuestionStream(knowledgeBaseIds, question, List.of());
    }

    /**
     * 流式查询知识库（SSE，支持多轮上下文）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @param history 历史对话消息（可选）
     * @return 检索结果（包含流式响应和来源文档）
     */
    public RetrievalResult answerQuestionStream(List<Long> knowledgeBaseIds, String question, List<Message> history) {
        return answerQuestionStream(knowledgeBaseIds, question, history, null, null);
    }

    /**
     * 流式查询知识库（SSE，支持多轮上下文 + 案例检索范围隔离）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @param history 历史对话消息（可选）
     * @param service 会话关联的服务标签（可为 null）
     * @param environment 会话关联的环境标签（可为 null）
     * @return 检索结果（包含流式响应和来源文档）
     */
    public RetrievalResult answerQuestionStream(List<Long> knowledgeBaseIds, String question,
                                                List<Message> history,
                                                String service, String environment) {
        return answerQuestionStream(knowledgeBaseIds, question, history, service, environment, null);
    }

    /**
     * 流式查询知识库（SSE，支持多轮上下文 + 案例检索范围隔离 + 版本过滤）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @param history 历史对话消息（可选）
     * @param service 会话关联的服务标签（可为 null）
     * @param environment 会话关联的环境标签（可为 null）
     * @param affectedVersions 版本标签（可为 null）
     * @return 检索结果（包含流式响应和来源文档）
     */
    public RetrievalResult answerQuestionStream(List<Long> knowledgeBaseIds, String question,
                                                List<Message> history,
                                                String service, String environment,
                                                String affectedVersions) {
        log.info("收到知识库流式提问: kbIds={}, question={}, historySize={}", knowledgeBaseIds, question,
                history != null ? history.size() : 0);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return new RetrievalResult(Flux.just(NO_RESULT_RESPONSE), List.of());
        }

        try {
            // 1. 验证知识库是否存在并更新问题计数
            countService.updateQuestionCounts(knowledgeBaseIds);

            // 2. Query rewrite + 动态参数检索
            List<Message> effectiveHistory = sanitizeHistory(history);
            QueryContext queryContext = buildQueryContext(question, effectiveHistory);
            Map<String, String> caseContextFilter = buildCaseContextFilter(service, environment, affectedVersions);
            List<Document> relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds, caseContextFilter);

            if (!hasEffectiveHit(relevantDocs)) {
                return new RetrievalResult(Flux.just(NO_RESULT_RESPONSE), List.of());
            }

            // 3. 构建上下文
            String context = relevantDocs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n---\n\n"));

            log.debug("检索到 {} 个相关文档片段", relevantDocs.size());

            // 4. 构建提示词
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(context, question);

            // 5. 流式调用（带历史上下文）+ 探测窗口归一化
            var promptSpec = getChatClient().prompt().system(systemPrompt);
            if (!effectiveHistory.isEmpty()) {
                promptSpec = promptSpec.messages(effectiveHistory);
            }
            Flux<String> responseFlux = promptSpec
                    .user(userPrompt)
                    .stream()
                    .content();

            log.info("开始流式输出知识库回答(探测窗口): kbIds={}", knowledgeBaseIds);
            Flux<String> contentStream = normalizeStreamOutput(responseFlux)
                .doOnComplete(() -> log.info("流式输出完成: kbIds={}", knowledgeBaseIds))
                .onErrorResume(e -> {
                    log.error("流式输出失败: kbIds={}, error={}", knowledgeBaseIds, e.getMessage(), e);
                    return Flux.error(e);
                });

            return new RetrievalResult(contentStream, relevantDocs);

        } catch (Exception e) {
            log.error("知识库流式问答失败: {}", e.getMessage(), e);
            return new RetrievalResult(Flux.error(e), List.of());
        }
    }

    /**
     * 依据实际检索结果与模型最终输出，确定这条回答的最终状态。
     * <ul>
     *   <li>未检索到文档：NO_RESULTS。</li>
     *   <li>检索到文档，但模型最终输出为空或为“整段式明确拒答”：NO_RESULTS——
     *       避免把一段拒答保存为有依据的 COMPLETED 回答。有效长回答即使在正文中偶带
     *       “信息不足”等描述性用语，也不视为拒答（见 {@link #isExplicitRefusal}）。</li>
     *   <li>检索到文档，模型有输出但内容表明信息不足：INSUFFICIENT_INFO。</li>
     *   <li>检索到文档且模型给出了实质性回答：COMPLETED。</li>
     * </ul>
     * 优先级：COMPLETED > INSUFFICIENT_INFO > NO_RESULTS > MODEL_FAILED。
     * 说明：MODEL_FAILED / CLIENT_DISCONNECTED 由调用方在流式终止信号处判定，
     * 本方法只覆盖“流正常完成”后的内容层面状态。
     *
     * @param finalContent     客户端实际收到的完整回答文本
     * @param sourceDocuments  实际检索命中的文档列表
     * @return 最终状态
     */
    public MessageStatus resolveFinalStatus(String finalContent, List<Document> sourceDocuments) {
        if (sourceDocuments == null || sourceDocuments.isEmpty()) {
            return MessageStatus.NO_RESULTS;
        }
        String normalized = finalContent == null ? "" : finalContent.trim();
        if (normalized.isEmpty() || isExplicitRefusal(normalized)) {
            return MessageStatus.NO_RESULTS;
        }
        if (isInsufficientInfo(normalized)) {
            return MessageStatus.INSUFFICIENT_INFO;
        }
        return MessageStatus.COMPLETED;
    }
    
    /**
     * 检测模型输出是否表明信息不足。
     * 判定条件（需同时满足「有文档」前提，由 resolveFinalStatus 保证）：
     * <ul>
     *   <li>包含「缺失信息」章节且有实质内容（非「无」「暂无」等空占位）</li>
     *   <li>或关键短语出现在回答前半段，表明回答主旨是信息不足——
     *       若关键词仅出现在后半段（前半段已给出实质内容），则不视为 INSUFFICIENT_INFO</li>
     * </ul>
     */
    private boolean isInsufficientInfo(String text) {
        // 检查「缺失信息」章节是否有实质内容
        if (hasMissingInfoSectionWithSubstantiveContent(text)) {
            return true;
        }
        // 检查关键短语是否出现在回答前半段（主旨是信息不足）
        return hasKeywordInFirstHalf(text);
    }

    /**
     * 检查信息不足关键短语是否出现在文本前半段。
     * 若关键词出现在后半段，说明前半段已给出实质内容，不应误标为 INSUFFICIENT_INFO。
     */
    private boolean hasKeywordInFirstHalf(String text) {
        String[] keywords = {"信息不足", "无法确定", "需要更多信息", "无法判断", "资料不足"};
        int halfPoint = text.length() / 2;
        for (String keyword : keywords) {
            int idx = text.indexOf(keyword);
            if (idx >= 0 && idx < halfPoint) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 检查是否存在「缺失信息」章节且有实质内容。
     * 匹配 `## 缺失信息` 后跟非空内容，排除「无」「暂无」等空占位。
     */
    private boolean hasMissingInfoSectionWithSubstantiveContent(String text) {
        int idx = text.indexOf("## 缺失信息");
        if (idx < 0) {
            return false;
        }
        String after = text.substring(idx + "## 缺失信息".length()).trim();
        if (after.isEmpty()) {
            return false;
        }
        // 如果紧接着是下一个标题（## 开头），说明该章节无内容
        if (after.startsWith("## ")) {
            return false;
        }
        // 取章节内容直到下一个标题或结尾
        int nextHeading = after.indexOf("\n## ");
        String sectionContent = (nextHeading >= 0 ? after.substring(0, nextHeading) : after).trim();
        // 排除空占位：「无」「暂无」「暂无额外信息」等
        return !sectionContent.isEmpty()
            && !sectionContent.equals("无")
            && !sectionContent.startsWith("暂无");
    }

    private QueryContext buildQueryContext(String originalQuestion, List<Message> history) {
        String normalizedQuestion = normalizeQuestion(originalQuestion);
        String rewrittenQuestion = rewriteQuestion(normalizedQuestion, history);
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(rewrittenQuestion);
        candidates.add(normalizedQuestion);

        SearchParams searchParams = resolveSearchParams(normalizedQuestion);
        return new QueryContext(normalizedQuestion, new ArrayList<>(candidates), searchParams);
    }

    private List<Message> sanitizeHistory(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history;
    }

//       清洗
    private String normalizeQuestion(String question) {
        return question == null ? "" : question.trim();
    }

//    向量检索（知识库 + 案例合并）
    private List<Document> retrieveRelevantDocs(QueryContext queryContext, List<Long> knowledgeBaseIds,
                                                Map<String, String> caseContextFilter) {
        for (String candidateQuery : queryContext.candidateQueries()) {
            if (candidateQuery.isBlank()) {
                continue;
            }
            // 1. 知识库向量检索
            List<Document> kbDocs = vectorService.similaritySearch(
                candidateQuery,
                knowledgeBaseIds,
                queryContext.searchParams().topK(),
                queryContext.searchParams().minScore()
            );
            log.info("检索候选 query='{}'，KB 命中 {} 条", candidateQuery, kbDocs.size());

            // 2. 案例向量检索（带上下文过滤，无上下文时跳过）
            List<Document> caseDocs = vectorService.searchCaseVectors(
                candidateQuery,
                caseContextFilter,
                List.of(),
                Math.max(queryContext.searchParams().topK() / 2, 2),
                queryContext.searchParams().minScore()
            );
            log.info("检索候选 query='{}'，案例命中 {} 条", candidateQuery, caseDocs.size());

            // 3. 合并结果（KB 在前，案例在后；去重）
            List<Document> merged = mergeResults(kbDocs, caseDocs, queryContext.searchParams().topK());

            if (hasEffectiveHit(merged)) {
                return merged;
            }
        }
        return List.of();
    }

    /**
     * 合并知识库与案例检索结果，按 KB 优先、案例补充的顺序排列，总数不超过 topK。
     */
    private List<Document> mergeResults(List<Document> kbDocs, List<Document> caseDocs, int topK) {
        List<Document> merged = new ArrayList<>(kbDocs);
        Set<String> seenIds = kbDocs.stream()
            .map(Document::getId)
            .collect(Collectors.toSet());
        for (Document caseDoc : caseDocs) {
            if (!seenIds.contains(caseDoc.getId()) && merged.size() < topK) {
                merged.add(caseDoc);
                seenIds.add(caseDoc.getId());
            }
        }
        return merged;
    }

    /**
     * 执行"知识库检索 + 案例检索 + 合并"的真实链路，供集成测试直接验证合并行为。
     * 与 {@link #retrieveRelevantDocs} 的区别：跳过 query rewrite 与动态参数解析，
     * 由调用方显式指定 topK / minScore，其余检索与合并逻辑与生产路径完全一致。
     * // visible for testing
     *
     * @param query             检索关键词
     * @param knowledgeBaseIds  知识库 ID 列表
     * @param caseContextFilter 案例检索上下文过滤（service/environment/affected_versions）
     * @param topK              合并后返回的最大文档数
     * @param minScore          相似度阈值
     * @return KB 优先、案例补充的合并结果
     */
    public List<Document> retrieveAndMerge(String query, List<Long> knowledgeBaseIds,
                                           Map<String, String> caseContextFilter,
                                           int topK, double minScore) {
        List<Document> kbDocs = vectorService.similaritySearch(
            query, knowledgeBaseIds, topK, minScore);
        List<Document> caseDocs = vectorService.searchCaseVectors(
            query, caseContextFilter, List.of(), Math.max(topK / 2, 2), minScore);
        return mergeResults(kbDocs, caseDocs, topK);
    }

    private SearchParams resolveSearchParams(String question) {
        int compactLength = question.replaceAll("\\s+", "").length();
        if (compactLength <= shortQueryLength) {
            return new SearchParams(topkShort, minScoreShort);
        }
        if (compactLength <= 12) {
            return new SearchParams(topkMedium, minScoreDefault);
        }
        return new SearchParams(topkLong, minScoreDefault);
    }

//    改写
    private String rewriteQuestion(String question, List<Message> history) {
        if (!rewriteEnabled || question.isBlank()) {
            return question;
        }
        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("question", question);
            variables.put("history", formatHistoryForRewrite(history));
            String rewritePrompt = rewritePromptTemplate.render(variables);
            String rewritten = getChatClient().prompt()
                .user(rewritePrompt)
                .call()
                .content();
            if (rewritten == null || rewritten.isBlank()) {
                return question;
            }
            String normalized = rewritten.trim();
            log.info("Query rewrite: origin='{}', rewritten='{}', historySize={}", question, normalized, history.size());
            return normalized;
        } catch (Exception e) {
            log.warn("Query rewrite 失败，使用原问题继续检索: {}", e.getMessage());
            return question;
        }
    }

    /**
     * 将历史消息格式化为重写 prompt 中的文本摘要。
     * 每条消息格式：用户: xxx / 助手: xxx
     */
    private String formatHistoryForRewrite(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message msg : history) {
            if (msg instanceof UserMessage) {
                sb.append("用户: ").append(msg.getText()).append("\n");
            } else if (msg instanceof AssistantMessage) {
                // 截断过长的助手回复，避免 rewrite prompt 过长
                String text = msg.getText();
                if (text.length() > MAX_REWRITE_HISTORY_CHAR) {
                    text = text.substring(0, MAX_REWRITE_HISTORY_CHAR) + "...";
                }
                sb.append("助手: ").append(text).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private boolean hasEffectiveHit(List<Document> docs) {
        return docs != null && !docs.isEmpty();
    }

    /**
     * 构建案例检索上下文过滤条件。
     * service/environment/affectedVersions 均为空时返回空 Map，触发“无上下文不召回”逻辑。
     */
    private Map<String, String> buildCaseContextFilter(String service, String environment,
                                                        String affectedVersions) {
        Map<String, String> filter = new HashMap<>();
        if (service != null && !service.isBlank()) {
            filter.put("service", service.trim());
        }
        if (environment != null && !environment.isBlank()) {
            filter.put("environment", environment.trim());
        }
        if (affectedVersions != null && !affectedVersions.isBlank()) {
            filter.put("affected_versions", affectedVersions.trim());
        }
        return filter;
    }

    private String normalizeAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return NO_RESULT_RESPONSE;
        }
        String normalized = answer.trim();
        // INSUFFICIENT_INFO  indicators pass through unchanged so resolveFinalStatus
        // can distinguish them from NO_RESULTS (documents exist but info is insufficient).
        if (isInsufficientInfo(normalized)) {
            return normalized;
        }
        if (isNoResultLike(normalized)) {
            return NO_RESULT_RESPONSE;
        }
        return normalized;
    }

    private boolean isNoResultLike(String text) {
        return text.contains("没有找到相关信息")
            || text.contains("未检索到相关信息")
            || text.contains("超出知识库范围")
            || text.contains("无法根据提供内容回答");
    }

    /**
     * 判断最终正文是否为"整段式明确拒答"，区别于一段有效回答中偶带的描述性用语或排查指令。
     * <p>探测窗口 {@code normalizeStreamOutput} 已把"开头即为无信息"的流收敛为固定模板
     * {@code NO_RESULT_RESPONSE}；因此这里识别：
     * <ol>
     *   <li>正文等于/起始于固定无结果模板；</li>
     *   <li>起始句中存在"无法依据资料回答当前问题"的整句式拒答构造
     *       （{@link #REFUSAL_INABILITY} / {@link #REFUSAL_EMPTY_RETRIEVAL}），且该构造不是
     *       被引用的提及、条件假设或被否定词就地取消的表达。</li>
     * </ol>
     * 条件、引用、否定各自的作用范围都被限定到对应子句 / 引号 / 紧邻情态词，
     * 不会因"整句含如果 / 引号 / 否定词"就一律放行，也不会把已生效的真实拒答误判为正常作答。
     */
    private boolean isExplicitRefusal(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        // 固定无结果模板：零命中 / 探测窗口 / 空输出路径都会原样产出它
        if (text.equals(NO_RESULT_RESPONSE) || text.startsWith(NO_RESULT_RESPONSE)) {
            return true;
        }
        // 先屏蔽成对引用（含 ASCII 单引号、引用内的句号与换行），用中性占位符替换整段引用：
        // 引用内部的拒答措辞不再被当作当前拒答，引用内的终止标点也不参与句子/子句切分；
        // 占位符不作为子句边界，因此不会把引用外的“无法…回答”“未找到…信息”构造切断。
        String masked = maskQuotedSpans(text);
        // 再取起始句（第一个句子终止符之前），把描述性/排查性用语限制在整段拒答判定之外
        String leadingSentence = masked.split("[。．.！!？?\\n\\r]", 2)[0].trim();
        return containsGenuineRefusal(leadingSentence);
    }

    /** 用占位符替换成对引号内的整段引用；未闭合引号不匹配，不会吞到结尾。 */
    private String maskQuotedSpans(String text) {
        return QUOTED_SPAN.matcher(text).replaceAll(QUOTE_MASK);
    }

    /**
     * 起始句内是否存在"当前回答本身"的明确拒答：以子句为作用域逐句判定，
     * 引用已在 {@link #maskQuotedSpans} 阶段屏蔽，条件 / 否定只作用于各自子句。
     */
    private boolean containsGenuineRefusal(String sentence) {
        if (sentence.isEmpty()) {
            return false;
        }
        // 以子句为作用域，条件与否定只在各自子句内生效，避免整句一刀切放行
        for (String clause : CLAUSE_SPLIT.split(sentence)) {
            if (clause.isBlank()) {
                continue;
            }
            if (hasUnguardedRefusal(clause)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasUnguardedRefusal(String clause) {
        return hasUnguardedRefusal(REFUSAL_INABILITY.matcher(clause), clause)
            || hasUnguardedRefusal(REFUSAL_EMPTY_RETRIEVAL.matcher(clause), clause);
    }

    /**
     * 遍历子句内的拒答构造，逐个排除"就地否定""同子句条件假设""……时请……条件排查"，
     * 只要有未被取消的出现即判为拒答。
     */
    private boolean hasUnguardedRefusal(Matcher matcher, String clause) {
        while (matcher.find()) {
            String before = clause.substring(0, matcher.start());
            // 否定只作用于紧邻其后的情态词："并非无法回答"取消，但"并非无法连接…但无法回答"的后者不受影响
            if (NEGATION_BEFORE_MODAL.matcher(before.trim()).find()) {
                continue;
            }
            // 条件连接词位于拒答之前且同属一个子句时，该拒答是假设而非当前陈述
            if (CONDITIONAL_CONNECTIVE.matcher(before).find()) {
                continue;
            }
            // "……无法回答时请检查……"：拒答之后紧跟"时"且同子句内出现请求 / 指令词，是条件排查假设
            if (isConditionalTroubleshooting(clause, matcher.end())) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * 识别"……时请……"这类条件排查构造：作用范围只到这一处拒答之后紧邻的"时"，
     * 不按整句一刀切（不因整句含"时 / 请"就放行）。
     */
    private boolean isConditionalTroubleshooting(String clause, int refusalEnd) {
        String after = clause.substring(refusalEnd).trim();
        if (!after.startsWith("时")) {
            return false;
        }
        return CONDITIONAL_REQUEST_MARKER.matcher(after).find();
    }

    /**
     * 先观察前一小段流式内容，快速识别“无信息”模板。
     * - 命中无信息：立即输出固定模板并结束，防止长篇拒答
     * - 非无信息：尽快释放缓冲并继续实时透传
     */
    private Flux<String> normalizeStreamOutput(Flux<String> rawFlux) {
        return Flux.create(sink -> {
            StringBuilder probeBuffer = new StringBuilder();
            AtomicBoolean passthrough = new AtomicBoolean(false);
            AtomicBoolean completed = new AtomicBoolean(false);
            final Disposable[] disposableRef = new Disposable[1];

            disposableRef[0] = rawFlux.subscribe(
                chunk -> {
                    if (completed.get() || sink.isCancelled()) {
                        return;
                    }
                    if (passthrough.get()) {
                        sink.next(chunk);
                        return;
                    }

                    probeBuffer.append(chunk);
                    String probeText = probeBuffer.toString();
                    if (isNoResultLike(probeText)) {
                        completed.set(true);
                        sink.next(NO_RESULT_RESPONSE);
                        sink.complete();
                        if (disposableRef[0] != null) {
                            disposableRef[0].dispose();
                        }
                        return;
                    }

                    if (probeBuffer.length() >= STREAM_PROBE_CHARS) {
                        passthrough.set(true);
                        sink.next(probeText);
                        probeBuffer.setLength(0);
                    }
                },
                sink::error,
                () -> {
                    if (completed.get() || sink.isCancelled()) {
                        return;
                    }
                    if (!passthrough.get()) {
                        sink.next(normalizeAnswer(probeBuffer.toString()));
                    }
                    sink.complete();
                }
            );

            sink.onCancel(() -> {
                if (disposableRef[0] != null) {
                    disposableRef[0].dispose();
                }
            });
        });
    }

    private record SearchParams(int topK, double minScore) {
    }

    private record QueryContext(String originalQuestion, List<String> candidateQueries, SearchParams searchParams) {
    }
}
