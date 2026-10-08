package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.mapper.KnowledgeBaseMapper;
import interview.guide.infrastructure.mapper.RagChatMapper;
import interview.guide.modules.caselibrary.model.CaseEntity;
import interview.guide.modules.caselibrary.repository.CaseRepository;
import interview.guide.modules.knowledgebase.model.ContextKbItem;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import interview.guide.modules.knowledgebase.model.MessageStatus;
import interview.guide.modules.knowledgebase.model.RagChatDTO.CreateSessionRequest;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionDetailDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionListItemDTO;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.model.RetrievalResult;
import interview.guide.modules.knowledgebase.model.SourceReference;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * RAG 聊天会话服务
 * 提供RAG聊天会话的创建、获取、更新、删除等操作
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagChatSessionService {

    private final RagChatSessionRepository sessionRepository;
    private final RagChatMessageRepository messageRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final CaseRepository caseRepository;
    private final KnowledgeBaseQueryService queryService;
    private final KnowledgeBaseListService listService;
    private final RagChatMapper ragChatMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseQueryProperties queryProperties;

    /**
     * 创建新会话。
     * <p>检索范围语义：service/environment 解析出的 KB 集合是「限制范围」，实际会话 KB
     * 必须落在该范围内；显式 knowledgeBaseIds 只能在范围内缩小选择（取交集，范围外的 ID
     * 被安全排除），不能扩大范围。范围为空、或与显式选择无交集时直接报错，绝不退回全量检索。
     * 未提供 service/environment 时保持旧的显式 knowledgeBaseIds 行为。后端是该约束的最终校验方。
     */
    @Transactional
    public SessionDTO createSession(CreateSessionRequest request) {
        List<Long> explicitIds = request.knowledgeBaseIds();
        boolean hasExplicit = explicitIds != null && !explicitIds.isEmpty();

        String service = request.service();
        String environment = request.environment();
        boolean hasService = service != null && !service.isBlank();
        boolean hasEnvironment = environment != null && !environment.isBlank();
        boolean hasContext = hasService || hasEnvironment;

        Set<Long> finalKbIds;

        if (hasContext) {
            // service/environment 是限制范围：解析出允许的 KB ID 集合
            List<ContextKbItem> contextItems = listService.resolveContext(
                hasService ? service : null,
                hasEnvironment ? environment : null);
            Set<Long> scope = contextItems.stream()
                .map(ContextKbItem::id)
                .collect(Collectors.toSet());

            // 范围为空：明确报错，不退回全量检索
            if (scope.isEmpty()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "所选 service/environment 没有匹配的知识库，无法创建会话");
            }

            if (hasExplicit) {
                // 显式选择只能在范围内缩小：取交集，范围外的 ID 被安全排除
                finalKbIds = new HashSet<>();
                for (Long id : explicitIds) {
                    if (scope.contains(id)) {
                        finalKbIds.add(id);
                    }
                }
                // 与范围无交集：明确报错，不退回全量检索
                if (finalKbIds.isEmpty()) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "显式选择的知识库不在所选 service/environment 范围内，无法创建会话");
                }
            } else {
                // 未手动缩小：使用整个上下文范围
                finalKbIds = scope;
            }
        } else {
            // 无上下文：保持旧的显式 knowledgeBaseIds 行为
            if (!hasExplicit) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "至少需要一个知识库：请指定 knowledgeBaseIds 或提供 service/environment 上下文");
            }
            finalKbIds = new HashSet<>(explicitIds);
        }

        // 验证知识库存在
        List<KnowledgeBaseEntity> knowledgeBases = knowledgeBaseRepository
            .findAllById(finalKbIds);

        if (knowledgeBases.size() != finalKbIds.size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "部分知识库不存在");
        }

        // 创建会话
        RagChatSessionEntity session = new RagChatSessionEntity();
        session.setTitle(request.title() != null && !request.title().isBlank()
            ? request.title()
            : generateTitle(knowledgeBases));
        session.setKnowledgeBases(new HashSet<>(knowledgeBases));

        session = sessionRepository.save(session);

        log.info("创建 RAG 聊天会话: id={}, title={}, finalKbIds={}, hasContext={}",
            session.getId(), session.getTitle(), finalKbIds, hasContext);

        return ragChatMapper.toSessionDTO(session);
    }

    /**
     * 获取会话列表
     */
    public List<SessionListItemDTO> listSessions() {
        return sessionRepository.findAllOrderByPinnedAndUpdatedAtDesc()
            .stream()
            .map(ragChatMapper::toSessionListItemDTO)
            .toList();
    }

    /**
     * 获取会话详情（包含消息）
     * 分两次查询避免笛卡尔积问题
     */
    public SessionDetailDTO getSessionDetail(Long sessionId) {
        // 先加载会话和知识库
        RagChatSessionEntity session = sessionRepository
            .findByIdWithKnowledgeBases(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        // 再单独加载消息（避免笛卡尔积）
        List<RagChatMessageEntity> messages = messageRepository
            .findBySessionIdOrderByMessageOrderAsc(sessionId);

        // 转换知识库列表
        List<KnowledgeBaseListItemDTO> kbDTOs = knowledgeBaseMapper.toListItemDTOList(
            new java.util.ArrayList<>(session.getKnowledgeBases())
        );

        return ragChatMapper.toSessionDetailDTO(session, messages, kbDTOs);
    }

    /**
     * 准备流式消息（保存用户消息，创建 AI 消息占位）
     *
     * @return AI 消息的 ID
     */
    @Transactional
    public Long prepareStreamMessage(Long sessionId, String question) {
        RagChatSessionEntity session = sessionRepository.findByIdWithKnowledgeBases(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        // 获取当前消息数量作为起始顺序
        int nextOrder = session.getMessageCount();

        // 保存用户消息
        RagChatMessageEntity userMessage = new RagChatMessageEntity();
        userMessage.setSession(session);
        userMessage.setType(RagChatMessageEntity.MessageType.USER);
        userMessage.setContent(question);
        userMessage.setMessageOrder(nextOrder);
        userMessage.setCompleted(true);
        messageRepository.save(userMessage);

        // 创建 AI 消息占位（未完成）
        RagChatMessageEntity assistantMessage = new RagChatMessageEntity();
        assistantMessage.setSession(session);
        assistantMessage.setType(RagChatMessageEntity.MessageType.ASSISTANT);
        assistantMessage.setContent("");
        assistantMessage.setMessageOrder(nextOrder + 1);
        assistantMessage.setCompleted(false);
        assistantMessage = messageRepository.save(assistantMessage);

        // 更新会话消息数量
        session.setMessageCount(nextOrder + 2);
        sessionRepository.save(session);

        log.info("准备流式消息: sessionId={}, messageId={}", sessionId, assistantMessage.getId());

        return assistantMessage.getId();
    }

    /**
     * 流式响应完成后更新消息
     *
     * @param messageId 消息ID
     * @param content AI回答内容
     * @param status 消息完成状态
     * @param sourcesJson 来源信息JSON（可为null）
     */
    @Transactional
    public void completeStreamMessage(Long messageId, String content, MessageStatus status, String sourcesJson) {
        RagChatMessageEntity message = messageRepository.findById(messageId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "消息不存在"));

        message.setContent(content);
        message.setCompleted(true);
        message.setStatus(status);
        message.setSourcesJson(sourcesJson);
        messageRepository.save(message);

        log.info("完成流式消息: messageId={}, contentLength={}, status={}", messageId, content.length(), status);
    }

    /**
     * 获取流式回答（带多轮上下文）
     * <p>
     * 从会话关联的知识库中提取 service/environment 上下文，传递给案例检索实现范围隔离。
     *
     * @return 检索结果（包含流式响应和来源文档）
     */
    public RetrievalResult getStreamAnswer(Long sessionId, String question) {
        RagChatSessionEntity session = sessionRepository.findByIdWithKnowledgeBases(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        List<Long> kbIds = session.getKnowledgeBaseIds();
        List<Message> history = queryProperties.getHistory().isEnabled()
            ? loadHistoryMessages(sessionId) : List.of();

        // 从会话关联的 KB 中提取 service/environment/version 作为案例检索上下文
        String service = null;
        String environment = null;
        String affectedVersions = null;
        if (session.getKnowledgeBases() != null && !session.getKnowledgeBases().isEmpty()) {
            KnowledgeBaseEntity firstKb = session.getKnowledgeBases().iterator().next();
            service = firstKb.getService();
            environment = firstKb.getEnvironment();
            // 使用 KB 的 versionLabel 作为案例检索的版本过滤条件
            affectedVersions = firstKb.getVersionLabel();
        }

        log.info("加载历史上下文: sessionId={}, historySize={}", sessionId, history.size());
        return queryService.answerQuestionStream(kbIds, question, history, service, environment, affectedVersions);
    }

    /** Markdown 标题匹配：# 、 ## 或 ### 开头的行 */
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,3})\\s+(.+)$", Pattern.MULTILINE);

    /**
     * 从检索文档列表构建来源引用。
     * 从每个 Document 的 metadata 中提取来源类型：
     * - source_type="CASE"：案例来源，提取 case_id 查询案例标题
     * - source_type="KB" 或无标识：知识库来源，提取 kb_id 查询原始文件名
     * 来源顺序与入参 docs 保持一致，不改变字段含义与 SSE 序列化契约。
     *
     * @param docs 检索到的文档列表
     * @return 来源引用列表
     */
    public List<SourceReference> buildSourceReferences(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }

        // 分离 KB 来源和 CASE 来源
        Set<Long> kbIds = new HashSet<>();
        Set<Long> caseIds = new HashSet<>();
        for (Document doc : docs) {
            Map<String, Object> metadata = doc.getMetadata();
            if (metadata == null) continue;
            String sourceType = (String) metadata.get("source_type");
            if ("CASE".equals(sourceType)) {
                Object caseIdObj = metadata.get("case_id_long");
                if (caseIdObj instanceof Number) {
                    caseIds.add(((Number) caseIdObj).longValue());
                }
            } else {
                Object kbIdObj = metadata.get("kb_id");
                if (kbIdObj instanceof Number) {
                    kbIds.add(((Number) kbIdObj).longValue());
                }
            }
        }

        // 批量查询知识库
        Map<Long, KnowledgeBaseEntity> kbMap = new HashMap<>();
        if (!kbIds.isEmpty()) {
            List<KnowledgeBaseEntity> kbs = knowledgeBaseRepository.findAllById(kbIds);
            for (KnowledgeBaseEntity kb : kbs) {
                kbMap.put(kb.getId(), kb);
            }
        }

        // 批量查询案例
        Map<Long, CaseEntity> caseMap = new HashMap<>();
        if (!caseIds.isEmpty()) {
            List<CaseEntity> cases = caseRepository.findAllById(caseIds);
            for (CaseEntity c : cases) {
                caseMap.put(c.getId(), c);
            }
        }

        // 构建来源引用（顺序与 docs 一致）
        return docs.stream()
            .map(doc -> buildSingleSourceReference(doc, kbMap, caseMap))
            .toList();
    }

    private SourceReference buildSingleSourceReference(Document doc,
                                                       Map<Long, KnowledgeBaseEntity> kbMap,
                                                       Map<Long, CaseEntity> caseMap) {
        Map<String, Object> metadata = doc.getMetadata();
        String sourceType = metadata != null ? (String) metadata.get("source_type") : null;
        String text = doc.getText();
        String snippet = text != null && text.length() > 200 ? text.substring(0, 200) + "..." : text;
        Double score = doc.getScore();
        String sectionTitle = extractSectionTitle(text);

        if ("CASE".equals(sourceType)) {
            // 案例来源
            Object caseIdObj = metadata != null ? metadata.get("case_id_long") : null;
            Long caseId = caseIdObj instanceof Number ? ((Number) caseIdObj).longValue() : null;
            CaseEntity caseEntity = caseId != null ? caseMap.get(caseId) : null;
            String caseTitle = caseEntity != null ? caseEntity.getTitle() : "未知案例";
            String service = caseEntity != null ? caseEntity.getService() : null;
            String environment = caseEntity != null ? caseEntity.getEnvironment() : null;
            return new SourceReference(
                null, caseTitle, snippet, score, service, environment,
                null, null, null, sectionTitle,
                "CASE", caseId, caseTitle
            );
        }

        // 知识库来源（默认）
        Object kbIdObj = metadata != null ? metadata.get("kb_id") : null;
        Long kbId = kbIdObj instanceof Number ? ((Number) kbIdObj).longValue() : null;
        KnowledgeBaseEntity kb = kbId != null ? kbMap.get(kbId) : null;
        String docName = kb != null ? kb.getOriginalFilename() : "未知文档";
        String service = kb != null ? kb.getService() : null;
        String environment = kb != null ? kb.getEnvironment() : null;
        String versionLabel = kb != null ? kb.getVersionLabel() : null;
        Integer versionNo = kb != null ? kb.getVersionNo() : null;
        String documentKey = kb != null ? kb.getDocumentKey() : null;
        return new SourceReference(
            kbId, docName, snippet, score, service, environment,
            versionLabel, versionNo, documentKey, sectionTitle,
            "KB", null, null
        );
    }

    /**
     * 从文档内容中提取第一个 Markdown 标题作为 sectionTitle。
     * 扫描以 # 、 ## 或 ### 开头的行，取第一个匹配项。
     *
     * @param content 文档内容
     * @return 第一个标题文本，无标题时返回 null
     */
    private String extractSectionTitle(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        Matcher matcher = HEADING_PATTERN.matcher(content);
        if (matcher.find()) {
            return matcher.group(2).trim();
        }
        return null;
    }

    /**
     * 更新会话标题
     */
    @Transactional
    public void updateSessionTitle(Long sessionId, String title) {
        RagChatSessionEntity session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        session.setTitle(title);
        sessionRepository.save(session);

        log.info("更新会话标题: sessionId={}, title={}", sessionId, title);
    }

    /**
     * 切换会话置顶状态
     */
    @Transactional
    public void togglePin(Long sessionId) {
        RagChatSessionEntity session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        // 处理 null 值（兼容旧数据）
        Boolean currentPinned = session.getIsPinned() != null ? session.getIsPinned() : false;
        session.setIsPinned(!currentPinned);
        sessionRepository.save(session);

        log.info("切换会话置顶状态: sessionId={}, isPinned={}", sessionId, session.getIsPinned());
    }

    /**
     * 更新会话的知识库关联
     */
    @Transactional
    public void updateSessionKnowledgeBases(Long sessionId, List<Long> knowledgeBaseIds) {
        RagChatSessionEntity session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        List<KnowledgeBaseEntity> knowledgeBases = knowledgeBaseRepository
            .findAllById(knowledgeBaseIds);

        session.setKnowledgeBases(new HashSet<>(knowledgeBases));
        sessionRepository.save(session);

        log.info("更新会话知识库: sessionId={}, kbIds={}", sessionId, knowledgeBaseIds);
    }

    /**
     * 删除会话
     */
    @Transactional
    public void deleteSession(Long sessionId) {
        if (!sessionRepository.existsById(sessionId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        sessionRepository.deleteById(sessionId);

        log.info("删除会话: sessionId={}", sessionId);
    }

    // ========== 私有方法 ==========

    /**
     * 加载会话中最近的历史消息作为多轮上下文。
     * 排除当前轮的 user 消息（prepareStreamMessage 中 completed=true 但尚未回答）。
     */
    private List<Message> loadHistoryMessages(Long sessionId) {
        int limit = queryProperties.getHistory().getMaxMessages() + 1;
        List<RagChatMessageEntity> recent = messageRepository
            .findRecentCompletedBySessionId(sessionId, PageRequest.of(0, limit));

        if (recent.isEmpty()) {
            return List.of();
        }

        // 查询结果按 messageOrder DESC 排列，最后一条（DESC 首条）是当前轮的 user 消息，排除
        List<RagChatMessageEntity> historyMessages = recent.size() <= 1
            ? List.of()
            : recent.subList(1, recent.size());

        // 反转为正序（时间从早到晚）
        return historyMessages.reversed().stream()
            .map(m -> m.getType() == RagChatMessageEntity.MessageType.USER
                ? (Message) new UserMessage(m.getContent())
                : (Message) new AssistantMessage(m.getContent()))
            .toList();
    }

    private String generateTitle(List<KnowledgeBaseEntity> knowledgeBases) {
        if (knowledgeBases.isEmpty()) {
            return "新对话";
        }
        if (knowledgeBases.size() == 1) {
            return knowledgeBases.getFirst().getName();
        }
        return knowledgeBases.size() + " 个知识库对话";
    }
}
