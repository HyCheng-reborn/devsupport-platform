package interview.guide.modules.knowledgebase.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 知识库向量化 Stream 消费者
 * 负责从 Redis Stream 消费消息并执行向量化
 */
@Slf4j
@Component
public class VectorizeStreamConsumer extends AbstractStreamConsumer<VectorizeStreamConsumer.VectorizePayload> {

    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final interview.guide.common.transaction.TransactionalExecutor transactionalExecutor;

    /**
     * 测试钩子：在 shouldSkip 之后、tryMarkProcessing 之前调用。
     * 生产环境始终为 null，仅集成测试使用。
     */
    private volatile Runnable beforeClaimHook;

    public VectorizeStreamConsumer(
        RedisService redisService,
        KnowledgeBaseVectorService vectorService,
        KnowledgeBaseRepository knowledgeBaseRepository,
        interview.guide.common.transaction.TransactionalExecutor transactionalExecutor
    ) {
        super(redisService);
        this.vectorService = vectorService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.transactionalExecutor = transactionalExecutor;
    }

    record VectorizePayload(Long kbId, String content, boolean adoptMode) {}

    public void setBeforeClaimHook(Runnable hook) {
        this.beforeClaimHook = hook;
    }

    @Override
    protected String taskDisplayName() {
        return "向量化";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "vectorize-consumer";
    }

    @Override
    protected VectorizePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String kbIdStr = data.get(AsyncTaskStreamConstants.FIELD_KB_ID);
        String content = data.get(AsyncTaskStreamConstants.FIELD_CONTENT);
        if (kbIdStr == null || content == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        boolean adoptMode = Boolean.parseBoolean(data.getOrDefault(AsyncTaskStreamConstants.FIELD_ADOPT_MODE, "false"));
        return new VectorizePayload(Long.parseLong(kbIdStr), content, adoptMode);
    }

    @Override
    protected String payloadIdentifier(VectorizePayload payload) {
        return "kbId=" + payload.kbId() + (payload.adoptMode() ? "(adopt)" : "");
    }

    @Override
    protected boolean shouldSkip(VectorizePayload payload) {
        return knowledgeBaseRepository.findById(payload.kbId())
            .map(kb -> {
                VectorStatus status = kb.getVectorStatus();
                // 跳过已完成或已放弃的记录（放弃的版本不应被处理或 promote）
                return status == VectorStatus.COMPLETED || status == VectorStatus.ABANDONED;
            })
            .orElse(true);
    }

    @Override
    protected void beforeClaim(VectorizePayload payload) {
        Runnable hook = beforeClaimHook;
        if (hook != null) {
            hook.run();
        }
    }

    @Override
    protected void markProcessing(VectorizePayload payload) {
        updateVectorStatus(payload.kbId(), VectorStatus.PROCESSING, null);
    }

    /**
     * 原子领取：adopt 模式使用条件 UPDATE 防止 abandon 时序窗口竞争。
     * 非 adopt 模式保持原有 findById+save 语义。
     */
    @Override
    protected boolean tryMarkProcessing(VectorizePayload payload) {
        if (payload.adoptMode()) {
            int affected = transactionalExecutor.call(
                () -> knowledgeBaseRepository.tryClaimForProcessing(payload.kbId()));
            if (affected == 1) {
                return true;
            }
            // 领取失败：重新读取实体判断原因
            knowledgeBaseRepository.findById(payload.kbId()).ifPresent(kb -> {
                VectorStatus status = kb.getVectorStatus();
                if (status == VectorStatus.ABANDONED) {
                    log.info("adopt 消息领取时实体已被放弃，跳过: kbId={}", payload.kbId());
                } else if (status == VectorStatus.COMPLETED) {
                    log.info("adopt 消息领取时实体已完成，跳过: kbId={}", payload.kbId());
                } else {
                    log.warn("adopt 消息领取失败，状态不合法，跳过: kbId={}, status={}",
                        payload.kbId(), status);
                }
            });
            return false;
        }
        markProcessing(payload);
        return true;
    }

    @Override
    protected void processBusiness(VectorizePayload payload) {
        Long kbId = payload.kbId();
        if (!knowledgeBaseRepository.existsById(kbId)) {
            log.warn("知识库已被删除，跳过向量化任务: kbId={}", kbId);
            return;
        }
        vectorService.vectorizeAndStore(payload.kbId(), payload.content());
    }

    @Override
    protected void markCompleted(VectorizePayload payload) {
        Long kbId = payload.kbId();
        // 检查是否为 adopt 模式
        if (payload.adoptMode()) {
            // 在事务中执行 promote 逻辑：停用旧版本，激活新版本
            promoteAdoptedVersion(kbId);
        } else {
            updateVectorStatus(kbId, VectorStatus.COMPLETED, null);
        }
    }

    @Override
    protected void markFailed(VectorizePayload payload, String error) {
        Long kbId = payload.kbId();
        // 如果是 adopt 模式，设置为 CONFLICT 而不是 FAILED
        if (payload.adoptMode()) {
            updateVectorStatus(kbId, VectorStatus.CONFLICT, error);
        } else {
            updateVectorStatus(kbId, VectorStatus.FAILED, error);
        }
    }

    /**
     * 执行 adopt 模式的 promote 逻辑：
     * 1. 查找同 documentKey 的当前 active 版本（排除自己）
     * 2. 在同一事务中：先停用旧版本，再原子完成新版本（PROCESSING → COMPLETED + active=true）
     * 3. 若原子操作返回 0（状态已被 abandon 改变），跳过 promote
     * 4. 事务外：删除旧版本的向量数据
     */
    private void promoteAdoptedVersion(Long kbId) {
        KnowledgeBaseEntity newKb = knowledgeBaseRepository.findById(kbId).orElse(null);
        if (newKb == null) {
            log.warn("promote 时实体不存在，跳过: kbId={}", kbId);
            return;
        }

        String documentKey = newKb.getDocumentKey();
        if (documentKey == null || documentKey.isBlank()) {
            // 无 documentKey，回退到简单标记 COMPLETED
            updateVectorStatus(kbId, VectorStatus.COMPLETED, null);
            return;
        }

        // 查找同 documentKey 的当前 active 版本（排除自己）
        List<KnowledgeBaseEntity> currentActive = knowledgeBaseRepository
            .findByDocumentKeyAndActiveTrueOrderByVersionNoDesc(documentKey).stream()
            .filter(e -> !e.getId().equals(kbId))
            .toList();

        // 在同一事务中：先停用旧版本，再原子完成新版本
        // 避免唯一约束 uq_kb_active_version (document_key, normalized_version_label) 冲突
        int affected = transactionalExecutor.call(() -> {
            // 先停用旧版本（释放唯一约束位置）
            for (KnowledgeBaseEntity old : currentActive) {
                old.setActive(false);
                knowledgeBaseRepository.save(old);
            }
            // 再原子完成新版本：PROCESSING → COMPLETED + active=true + conflict=false
            return knowledgeBaseRepository.tryCompleteAdopt(kbId);
        });

        if (affected == 0) {
            log.warn("promote 原子操作失败，状态已变更，跳过: kbId={}", kbId);
            return;
        }

        // 事务外：删除旧版本的向量
        for (KnowledgeBaseEntity old : currentActive) {
            try {
                vectorService.deleteByKnowledgeBaseId(old.getId());
            } catch (Exception e) {
                log.warn("删除旧版本向量失败（可后续手动清理）: oldKbId={}", old.getId(), e);
            }
        }

        log.info("冲突版本采纳完成: kbId={}, documentKey={}, 已停用旧版本数={}",
            kbId, documentKey, currentActive.size());
    }

    @Override
    protected void retryMessage(VectorizePayload payload, int retryCount) {
        Long kbId = payload.kbId();
        String content = payload.content();

        // adopt 模式：条件重置 PROCESSING → ADOPTING，防止覆盖 ABANDONED/COMPLETED 等终态
        if (payload.adoptMode()) {
            int affected = transactionalExecutor.call(
                () -> knowledgeBaseRepository.resetToAdoptingForRetry(kbId));
            if (affected == 0) {
                // 状态已变（可能被 abandon），不再重入队
                log.info("重试重置失败，状态已变更，跳过重入队: kbId={}, retryCount={}", kbId, retryCount);
                return;
            }
        }

        try {
            Map<String, String> message = Map.of(
                AsyncTaskStreamConstants.FIELD_KB_ID, kbId.toString(),
                AsyncTaskStreamConstants.FIELD_CONTENT, content,
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount),
                AsyncTaskStreamConstants.FIELD_ADOPT_MODE, String.valueOf(payload.adoptMode())
            );

            redisService().streamAdd(
                AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY,
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            log.info("向量化任务已重新入队: kbId={}, retryCount={}", kbId, retryCount);

        } catch (Exception e) {
            log.error("重试入队失败: kbId={}, error={}", kbId, e.getMessage(), e);
            // Redis 发送失败：进入明确终态 CONFLICT（adopt）或 FAILED（非 adopt）
            VectorStatus failStatus = payload.adoptMode() ? VectorStatus.CONFLICT : VectorStatus.FAILED;
            updateVectorStatus(kbId, failStatus, truncateError("重试入队失败: " + e.getMessage()));
        }
    }

    /**
     * 更新向量化状态
     */
    private void updateVectorStatus(Long kbId, VectorStatus status, String error) {
        try {
            knowledgeBaseRepository.findById(kbId).ifPresent(kb -> {
                kb.setVectorStatus(status);
                kb.setVectorError(error);
                knowledgeBaseRepository.save(kb);
                log.debug("向量化状态已更新: kbId={}, status={}", kbId, status);
            });
        } catch (Exception e) {
            log.error("更新向量化状态失败: kbId={}, status={}, error={}", kbId, status, e.getMessage(), e);
        }
    }

}
