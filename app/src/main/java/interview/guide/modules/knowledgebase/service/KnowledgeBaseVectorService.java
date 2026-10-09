package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.caselibrary.repository.CaseRepository;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 知识库向量存储服务
 * 负责文档分块、向量化和检索
 */
@Slf4j
@Service
public class KnowledgeBaseVectorService {
    
    /**
     * 阿里云 DashScope Embedding API 批量大小限制
     */
    private static final int MAX_BATCH_SIZE = 10;
    private static final String TEMP_KB_ID_PREFIX = "pending:";
    private static final String METADATA_KB_ID = "kb_id";
    private static final String METADATA_TARGET_KB_ID = "kb_target_id";
    private static final String METADATA_VECTOR_JOB_ID = "kb_vector_job_id";
    private final VectorStore vectorStore;
    private final TextSplitter textSplitter;
    private final VectorRepository vectorRepository;
    private final TransactionalExecutor transactionalExecutor;
    private final CaseRepository caseRepository;

    @Autowired
    public KnowledgeBaseVectorService(
        VectorStore vectorStore,
        VectorRepository vectorRepository,
        TransactionalExecutor transactionalExecutor,
        CaseRepository caseRepository
    ) {
        this.vectorStore = vectorStore;
        this.vectorRepository = vectorRepository;
        this.transactionalExecutor = transactionalExecutor;
        this.caseRepository = caseRepository;
        // 使用 TokenTextSplitter 默认配置，每个 chunk 约 800 tokens，基于标点边界切分（无重叠）
        this.textSplitter = TokenTextSplitter.builder().build();
    }

    KnowledgeBaseVectorService(VectorStore vectorStore, VectorRepository vectorRepository) {
        this(vectorStore, vectorRepository, null, null);
    }

    /**
     * 将知识库内容向量化并存储
     * @param knowledgeBaseId 知识库ID
     * @param content 知识库文本内容
     */
    public void vectorizeAndStore(Long knowledgeBaseId, String content) {
        String jobId = null;
        try {
            if (knowledgeBaseId == null) {
                throw new IllegalArgumentException("knowledgeBaseId不能为空");
            }
            jobId = UUID.randomUUID().toString();
            log.info("开始向量化知识库: kbId={}, jobId={}, contentLength={}",
                knowledgeBaseId, jobId, content.length());

            // 1. 将文本分块
            List<Document> chunks = textSplitter.apply(
                List.of(new Document(content))
            );
            
            log.info("文本分块完成: {} 个chunks", chunks.size());
            
            // 2. 为每个 chunk 添加临时 metadata，成功后再提升为正式 kb_id。
            applyPendingMetadata(chunks, knowledgeBaseId, jobId);

            // 3. 分批向量化并存储（阿里云 DashScope API 限制 batch size <= 10）
            int totalChunks = chunks.size();
            int batchCount = (totalChunks + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE; // 向上取整
            log.info("开始分批向量化: 总共 {} 个chunks，分 {} 批处理，每批最多 {} 个",
                    totalChunks, batchCount, MAX_BATCH_SIZE);
            for (int i = 0; i < batchCount; i++) {
                int start = i * MAX_BATCH_SIZE;
                int end = Math.min(start + MAX_BATCH_SIZE, totalChunks);
                List<Document> batch = chunks.subList(start, end);
                log.debug("处理第 {}/{} 批: chunks {}-{}", i + 1, batchCount, start + 1, end);
                vectorStore.add(batch);
            }
            activateVectorJob(knowledgeBaseId, jobId);
            log.info("知识库向量化完成: kbId={}, jobId={}, chunks={}, batches={}",
                    knowledgeBaseId, jobId, totalChunks, batchCount);
        } catch (Exception e) {
            cleanupPendingVectorJob(knowledgeBaseId, jobId);
            log.error("向量化知识库失败: kbId={}, jobId={}, error={}",
                knowledgeBaseId, jobId, e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                "向量化知识库失败: " + e.getMessage());
        }
    }

    private void applyPendingMetadata(List<Document> chunks, Long knowledgeBaseId, String jobId) {
        String pendingKbId = TEMP_KB_ID_PREFIX + knowledgeBaseId + ":" + jobId;
        chunks.forEach(chunk -> {
            chunk.getMetadata().put(METADATA_KB_ID, pendingKbId);
            chunk.getMetadata().put(METADATA_TARGET_KB_ID, knowledgeBaseId.toString());
            chunk.getMetadata().put(METADATA_VECTOR_JOB_ID, jobId);
            chunk.getMetadata().put("source_type", "KB");
        });
    }
    
    /**
     * 基于多个知识库进行相似度搜索
     * 
     * @param query 查询文本
     * @param knowledgeBaseIds 知识库ID列表（如果为空则搜索所有）
     * @param topK 返回top K个结果
     * @return 相关文档列表
     */
    public List<Document> similaritySearch(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        return similaritySearch(query, knowledgeBaseIds, topK, minScore, false);
    }

    /**
     * 基于多个知识库进行相似度搜索，支持 KB-only 过滤。
     * <p>
     * 当 {@code requireKbOnly} 为 true 时，在 filterExpression 中限制 {@code source_type = 'KB'}，
     * 确保 CASE 向量不进入检索结果。用于回归评测隔离。
     *
     * @param query            查询文本
     * @param knowledgeBaseIds 知识库ID列表（如果为空则搜索所有）
     * @param topK             返回top K个结果
     * @param minScore         最低相似度阈值
     * @param requireKbOnly    是否仅检索 KB 来源向量
     * @return 相关文档列表
     */
    public List<Document> similaritySearch(String query, List<Long> knowledgeBaseIds, int topK,
                                           double minScore, boolean requireKbOnly) {
        log.info("向量相似度搜索: query={}, kbIds={}, topK={}, minScore={}",
            query, knowledgeBaseIds, topK, minScore);
        
        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(Math.max(topK, 1));

            if (minScore > 0) {
                builder.similarityThreshold(minScore);
            }

            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                builder.filterExpression(buildKbFilterExpression(knowledgeBaseIds));
            } else if (requireKbOnly) {
                builder.filterExpression("source_type == 'KB'");
            }

            List<Document> results = vectorStore.similaritySearch(builder.build());
            if (results == null) {
                return List.of();
            }

            // Apply topK limiting in case VectorStore returns more than requested
            List<Document> limitedResults = results.stream()
                .limit(topK)
                .collect(Collectors.toList());

            log.info("搜索完成: 找到 {} 个相关文档", limitedResults.size());
            return limitedResults;
            
        } catch (Exception e) {
            log.warn("向量搜索前置过滤失败，回退到本地过滤: {}", e.getMessage());
            return similaritySearchFallback(query, knowledgeBaseIds, topK, minScore, requireKbOnly);
        }
    }

    private List<Document> similaritySearchFallback(String query, List<Long> knowledgeBaseIds, int topK,
                                                     double minScore, boolean requireKbOnly) {
        try {
            // 回退检索仍保留 topK/minScore，避免兜底路径引入过多弱相关命中
            SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(Math.max(topK * 3, topK));
            if (minScore > 0) {
                builder.similarityThreshold(minScore);
            }

            List<Document> allResults = vectorStore.similaritySearch(builder.build());
            if (allResults == null || allResults.isEmpty()) {
                return List.of();
            }

            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                allResults = allResults.stream()
                    .filter(doc -> isDocInKnowledgeBases(doc, knowledgeBaseIds))
                    .collect(Collectors.toList());
            } else if (requireKbOnly) {
                allResults = allResults.stream()
                    .filter(doc -> "KB".equals(doc.getMetadata().get("source_type")))
                    .collect(Collectors.toList());
            }

            List<Document> results = allResults.stream()
                .limit(topK)
                .collect(Collectors.toList());

            log.info("回退检索完成: 找到 {} 个相关文档", results.size());
            return results;
        } catch (Exception e) {
            log.error("向量搜索失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED,
                "向量搜索失败: " + e.getMessage());
        }
    }

    private boolean isDocInKnowledgeBases(Document doc, List<Long> knowledgeBaseIds) {
        Object kbId = doc.getMetadata().get("kb_id");
        if (kbId == null) {
            return false;
        }
        try {
            Long kbIdLong = kbId instanceof Long
                ? (Long) kbId
                : Long.parseLong(kbId.toString());
            return knowledgeBaseIds.contains(kbIdLong);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String buildKbFilterExpression(List<Long> knowledgeBaseIds) {
        String values = knowledgeBaseIds.stream()
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .map(id -> "'" + id + "'")
            .collect(Collectors.joining(", "));
        return "kb_id in [" + values + "]";
    }
    
    /**
     * 删除指定知识库的所有向量数据
     * 委托给 VectorRepository 处理
     * 
     * @param knowledgeBaseId 知识库ID
     */
    public void deleteByKnowledgeBaseId(Long knowledgeBaseId) {
        try {
            deleteByKnowledgeBaseIdStrict(knowledgeBaseId);
        } catch (Exception e) {
            log.error("删除向量数据失败: kbId={}, error={}", knowledgeBaseId, e.getMessage(), e);
            // 不抛出异常，允许继续执行其他删除操作
            // 如果确实需要严格保证，可以取消下面的注释
            // throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_DELETE_FAILED, "删除向量数据失败");
        }
    }

    private void deleteByKnowledgeBaseIdStrict(Long knowledgeBaseId) {
        runVectorRepositoryMutation(() -> vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId));
    }

    private void activateVectorJob(Long knowledgeBaseId, String jobId) {
        runVectorRepositoryMutation(() -> {
            vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId);
            vectorRepository.promoteVectorJob(knowledgeBaseId, jobId);
        });
    }

    private void cleanupPendingVectorJob(Long knowledgeBaseId, String jobId) {
        if (jobId == null) {
            return;
        }
        try {
            runVectorRepositoryMutation(() -> vectorRepository.deleteByVectorJobId(jobId));
        } catch (Exception cleanupError) {
            log.warn("清理临时向量数据失败，可后续按 jobId 补偿: kbId={}, jobId={}, error={}",
                knowledgeBaseId, jobId, cleanupError.getMessage(), cleanupError);
        }
    }

    private void runVectorRepositoryMutation(Runnable action) {
        if (transactionalExecutor == null) {
            action.run();
            return;
        }
        transactionalExecutor.run(action);
    }

    /**
     * 搜索已发布案例的向量（source_type='CASE'）。
     * 用于将案例检索结果与知识库检索结果合并。
     *
     * @param query  查询文本
     * @param topK   返回条数
     * @param minScore 最低相似度阈值
     * @return 匹配的案例文档列表
     */
    public List<Document> searchCaseVectors(String query, int topK, double minScore) {
        // 无上下文时不执行全局案例召回
        log.info("案例向量检索（无上下文过滤，跳过）: query={}", query);
        return List.of();
    }

    /**
     * 按上下文过滤搜索已发布案例的向量。
     * <p>
     * contextFilter 可包含 service / environment 键，用于限定检索范围；
     * excludeCaseIds 用于排除 vector_cleanup_pending=true 的案例。
     * 若 contextFilter 为空（无 service 且无 environment），不执行全局召回，返回空列表。
     *
     * @param query          查询文本
     * @param contextFilter  上下文过滤条件（service/environment），为空时不召回
     * @param excludeCaseIds 需排除的案例 ID 列表（待清理向量）
     * @param topK           返回条数
     * @param minScore       最低相似度阈值
     * @return 匹配的案例文档列表
     */
    public List<Document> searchCaseVectors(String query, Map<String, String> contextFilter,
                                            List<Long> excludeCaseIds, int topK, double minScore) {
        // 无上下文 = 不全局召回案例
        if (contextFilter == null || contextFilter.isEmpty()) {
            log.info("案例向量检索（无上下文过滤，跳过）: query={}", query);
            return List.of();
        }

        // 合并外部排除列表与 vectorCleanupPending=true 的案例 ID
        // Fail-closed: 如果查询待清理案例失败，不执行案例检索，避免召回已废弃案例
        Set<Long> pendingCleanupIds;
        if (caseRepository != null) {
          try {
            pendingCleanupIds = new HashSet<>(caseRepository.findIdsWithVectorCleanupPending());
          } catch (Exception e) {
            log.error("Failed to query vectorCleanupPending cases, skipping case search", e);
            return Collections.emptyList();
          }
        } else {
          pendingCleanupIds = new HashSet<>();
        }
        List<Long> effectiveExcludes = new ArrayList<>(
            excludeCaseIds != null ? excludeCaseIds : List.of());
        effectiveExcludes.addAll(pendingCleanupIds);

        log.info("案例向量检索: query={}, contextFilter={}, excludeCaseIds={}, topK={}, minScore={}",
            query, contextFilter, effectiveExcludes, topK, minScore);
        try {
            String filterExpression = buildCaseFilterExpression(contextFilter, effectiveExcludes);
            SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(Math.max(topK, 1))
                .filterExpression(filterExpression);

            if (minScore > 0) {
                builder.similarityThreshold(minScore);
            }

            List<Document> results = vectorStore.similaritySearch(builder.build());
            if (results == null) {
                return List.of();
            }

            List<Document> limited = results.stream().limit(topK).collect(Collectors.toList());
            log.info("案例向量检索完成: 命中 {} 条", limited.size());
            return limited;
        } catch (Exception e) {
            log.warn("案例向量检索失败，跳过案例检索: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 构建案例检索的过滤表达式。
     * 基础条件：source_type == 'CASE'，按 contextFilter 追加 service/environment，
     * 按 excludeCaseIds 排除待清理案例。
     */
    private String buildCaseFilterExpression(Map<String, String> contextFilter,
                                             List<Long> excludeCaseIds) {
        List<String> conditions = new ArrayList<>();
        conditions.add("source_type == 'CASE'");

        String service = contextFilter.get("service");
        if (service != null && !service.isBlank()) {
            conditions.add("service == '" + escapeFilterValue(service) + "'");
        }
        String environment = contextFilter.get("environment");
        if (environment != null && !environment.isBlank()) {
            conditions.add("environment == '" + escapeFilterValue(environment) + "'");
        }
        String affectedVersions = contextFilter.get("affected_versions");
        if (affectedVersions != null && !affectedVersions.isBlank()) {
            conditions.add("affected_versions == '" + escapeFilterValue(affectedVersions) + "'");
        }

        if (excludeCaseIds != null && !excludeCaseIds.isEmpty()) {
            String excluded = excludeCaseIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
            conditions.add("case_id_long not in [" + excluded + "]");
        }

        return String.join(" and ", conditions);
    }

    /**
     * 转义过滤表达式中的单引号，防止注入。
     */
    private String escapeFilterValue(String value) {
        return value.replace("'", "\\'");
    }
}
