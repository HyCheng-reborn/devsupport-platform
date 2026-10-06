package interview.guide.modules.knowledgebase.listener;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 向量化任务生产者
 * 负责发送向量化任务到 Redis Stream
 */
@Slf4j
@Component
public class VectorizeStreamProducer extends AbstractStreamProducer<VectorizeStreamProducer.VectorizeTaskPayload> {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final TransactionalExecutor transactionalExecutor;

    record VectorizeTaskPayload(Long kbId, String content, boolean adoptMode) {
        VectorizeTaskPayload(Long kbId, String content) {
            this(kbId, content, false);
        }
    }

    public VectorizeStreamProducer(RedisService redisService, KnowledgeBaseRepository knowledgeBaseRepository,
                                   TransactionalExecutor transactionalExecutor) {
        super(redisService);
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.transactionalExecutor = transactionalExecutor;
    }

    /**
     * 发送向量化任务到 Redis Stream
     *
     * @param kbId    知识库ID
     * @param content 文档内容
     */
    public void sendVectorizeTask(Long kbId, String content) {
        sendTask(new VectorizeTaskPayload(kbId, content));
    }

    /**
     * 发送向量化任务到 Redis Stream（支持 adopt 模式）
     *
     * @param kbId      知识库ID
     * @param content   文档内容
     * @param adoptMode 是否为冲突版本采纳模式
     */
    public void sendVectorizeTask(Long kbId, String content, boolean adoptMode) {
        sendTask(new VectorizeTaskPayload(kbId, content, adoptMode));
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
    protected Map<String, String> buildMessage(VectorizeTaskPayload payload) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_KB_ID, payload.kbId().toString(),
            AsyncTaskStreamConstants.FIELD_CONTENT, payload.content(),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0",
            AsyncTaskStreamConstants.FIELD_ADOPT_MODE, String.valueOf(payload.adoptMode())
        );
    }

    @Override
    protected String payloadIdentifier(VectorizeTaskPayload payload) {
        return "kbId=" + payload.kbId() + (payload.adoptMode() ? "(adopt)" : "");
    }

    @Override
    protected void onSendFailed(VectorizeTaskPayload payload, String error) {
        updateVectorStatus(payload.kbId(), payload.adoptMode() ? VectorStatus.CONFLICT : VectorStatus.FAILED, truncateError(error));
    }

    /**
     * 更新向量化状态
     */
    private void updateVectorStatus(Long kbId, VectorStatus status, String error) {
        knowledgeBaseRepository.findById(kbId).ifPresent(kb -> {
            kb.setVectorStatus(status);
            if (error != null) {
                kb.setVectorError(error.length() > 500 ? error.substring(0, 500) : error);
            }
            knowledgeBaseRepository.save(kb);
        });
    }

    // ==================== 测试辅助方法 ====================

    /**
     * 模拟 adopt 模式重试：条件重置 PROCESSING → ADOPTING。
     * 仅供集成测试调用，模拟 VectorizeStreamConsumer.retryMessage 的 adopt 重试逻辑。
     * 使用条件更新，仅当实体仍处于 PROCESSING + conflict=true + active=false 时才重置。
     */
    public int retryMessageForAdopt(Long kbId) {
        return transactionalExecutor.call(
            () -> knowledgeBaseRepository.resetToAdoptingForRetry(kbId));
    }

    /**
     * 模拟 adopt 模式最大重试失败：设置 CONFLICT + error。
     * 仅供集成测试调用，模拟 VectorizeStreamConsumer.markFailed 的 adopt 失败逻辑。
     */
    public void markFailedForAdopt(Long kbId, String error) {
        updateVectorStatus(kbId, VectorStatus.CONFLICT, error);
    }
}
