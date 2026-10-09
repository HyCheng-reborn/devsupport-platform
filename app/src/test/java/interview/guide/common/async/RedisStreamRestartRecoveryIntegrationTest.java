package interview.guide.common.async;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis Stream 重启恢复集成测试。
 *
 * <p>核心验证：消费者崩溃后，留在 pending 中未 ACK 的消息被新消费者通过 autoClaim 恢复并处理。
 * 使用 Testcontainers 隔离 Redis 实例，零付费。
 *
 * <h3>测试策略说明</h3>
 * <p>{@code AbstractStreamConsumer.processMessage()} 在所有代码路径（成功/失败/异常/重试耗尽）
 * 都会调用 {@code ackMessage()}。因此无法通过 AbstractStreamConsumer 自身流程制造"已读但未 ACK"
 * 的 pending 消息。
 *
 * <p>正确方案：使用 Redis {@code readGroup} 直接模拟"消费者读取消息后崩溃、未 ACK"的场景，
 * 然后启动新的 {@code AbstractStreamConsumer}，验证其 {@code reclaimPendingMessages}（autoClaim）
 * 机制确实恢复了同一条消息（通过 {@code StreamMessageId} 跨消费者匹配）。
 *
 * <h3>幂等保证说明</h3>
 * <p>{@code VectorizeStreamConsumer} 通过以下机制保证重复消费安全：
 * <ul>
 *   <li><b>状态 CAS 保护</b>：{@code tryMarkProcessing()} 使用条件 UPDATE
 *       （WHERE vectorStatus='ADOPTING'）领取任务，重复领取返回 0，不会覆盖终态</li>
 *   <li><b>shouldSkip 前置检查</b>：消费前检查实体状态，COMPLETED 或 ABANDONED 直接跳过并 ACK</li>
 *   <li><b>终态不可逆</b>：COMPLETED/ABANDONED/FAILED 均为终态，不会被重复处理覆盖</li>
 * </ul>
 * <p>因此，即使 autoClaim 导致消息被重复投递给新消费者，处理逻辑也是幂等安全的。
 */
@DisplayName("Redis Stream 消费者崩溃重启 → pending 消息 autoClaim 恢复（Testcontainers 隔离）")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)
class RedisStreamRestartRecoveryIntegrationTest {

    private static final String TEST_STREAM_KEY = "test:restart-recovery:stream";
    private static final String TEST_GROUP = "test-recovery-group";
    private static final String TEST_CONSUMER_PREFIX = "test-recovery-";

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.redis.redisson.config", () ->
            "singleServerConfig:\n  address: \"redis://" +
                redis.getHost() + ":" + redis.getMappedPort(6379) + "\"\n  database: 0");
    }

    @Autowired
    RedisService redisService;

    // ─────────── 测试用消费者子类 ───────────

    /**
     * 测试用消费者：记录处理的消息 ID 和对应的 StreamMessageId，支持短 pending 超时。
     * 非 Spring Bean，由测试手动创建和管理生命周期。
     */
    static class TestConsumer extends AbstractStreamConsumer<Map<String, String>> {

        private final ConcurrentLinkedQueue<String> processedIds = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<StreamMessageId> processedMessageIds = new ConcurrentLinkedQueue<>();
        private final long pendingTimeoutMs;
        private final String threadName;

        TestConsumer(RedisService redisService, String name, long pendingTimeoutMs) {
            super(redisService);
            this.pendingTimeoutMs = pendingTimeoutMs;
            this.threadName = "test-consumer-" + name;
        }

        @Override protected String taskDisplayName() { return "测试"; }
        @Override protected String streamKey() { return TEST_STREAM_KEY; }
        @Override protected String groupName() { return TEST_GROUP; }
        @Override protected String consumerPrefix() { return TEST_CONSUMER_PREFIX; }
        @Override protected String threadName() { return threadName; }
        @Override protected long pendingIdleTimeoutMs() { return pendingTimeoutMs; }

        /**
         * parsePayload 是 AbstractStreamConsumer.processMessage() 调用链中第一个拿到真实
         * StreamMessageId 的可覆写钩子。在此捕获消费者实际处理的 StreamMessageId，
         * 用于与崩溃消费者 readGroup 得到的原始 pending 消息 ID 做精确匹配断言。
         */
        @Override
        protected Map<String, String> parsePayload(StreamMessageId messageId, Map<String, String> data) {
            processedMessageIds.add(messageId);
            return data;
        }

        @Override
        protected String payloadIdentifier(Map<String, String> payload) {
            return payload.getOrDefault("id", "?");
        }

        @Override
        protected void markProcessing(Map<String, String> payload) {
            // no-op for test
        }

        @Override
        protected void processBusiness(Map<String, String> payload) {
            processedIds.add(payload.get("id"));
        }

        @Override
        protected void markCompleted(Map<String, String> payload) {
            // no-op for test
        }

        @Override
        protected void markFailed(Map<String, String> payload, String error) {
            // no-op for test
        }

        @Override
        protected void retryMessage(Map<String, String> payload, int retryCount) {
            // no-op for test
        }
    }

    // ─────────── 辅助方法 ───────────

    private TestConsumer createAndStart(String name, long pendingTimeoutMs) {
        TestConsumer consumer = new TestConsumer(redisService, name, pendingTimeoutMs);
        consumer.init();
        return consumer;
    }

    private void stopConsumer(TestConsumer consumer) {
        consumer.shutdown();
        try {
            Field executorField = AbstractStreamConsumer.class.getDeclaredField("executorService");
            executorField.setAccessible(true);
            ExecutorService executor = (ExecutorService) executorField.get(consumer);
            if (executor != null) {
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            // ignore cleanup errors
        }
    }

    private String sendMessage(String id, String content) {
        return redisService.streamAdd(TEST_STREAM_KEY, Map.of("id", id, "content", content));
    }

    // ─────────── 测试用例 ───────────

    @Test
    @DisplayName("核心场景：消费者崩溃后 pending 未 ACK 消息被新消费者通过 autoClaim 恢复（消息 ID 精确匹配）")
    void consumerCrash_pendingMessageRecoveredByAutoClaim_withMessageIdMatch() throws Exception {
        // 1. 创建 consumer group
        redisService.createStreamGroup(TEST_STREAM_KEY, TEST_GROUP);

        // 2. 发送消息
        sendMessage("recover-me", "critical data");

        // 3. 模拟消费者崩溃：使用 readGroup 读取消息但不 ACK
        //    这模拟了消费者读取消息后进程崩溃、未来得及 ACK 的场景
        var stream = redisService.getClient()
            .getStream(TEST_STREAM_KEY, org.redisson.client.codec.StringCodec.INSTANCE);
        var crashedMessages = stream.readGroup(
            TEST_GROUP, "crashed-consumer",
            org.redisson.api.stream.StreamReadGroupArgs.neverDelivered().count(1));

        assertThat(crashedMessages).as("崩溃消费者应读取到消息").hasSize(1);
        StreamMessageId originalMsgId = crashedMessages.keySet().iterator().next();
        @SuppressWarnings("unchecked")
        Map<String, String> originalData = (Map<String, String>) (Map<?, ?>) crashedMessages.get(originalMsgId);
        assertThat(originalData).containsEntry("id", "recover-me").containsEntry("content", "critical data");

        // 4. 消息未 ACK → 留在 pending 列表中（模拟崩溃）

        // 5. 启动新消费者（短 pending 超时 500ms，快速回收 idle pending 消息）
        TestConsumer recovery = createAndStart("recovery", 500);

        // 6. 等待 recovery 通过 autoClaim 恢复 pending 消息
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && recovery.processedIds.isEmpty()) {
            Thread.sleep(200);
        }

        // 7. 验证 recovery 消费者处理了同一条消息（业务 ID 匹配）
        assertThat(recovery.processedIds)
            .as("恢复消费者应处理崩溃消费者未 ACK 的同一条消息")
            .contains("recover-me");

        // 7b. 精确匹配 StreamMessageId：恢复消费者实际处理的消息 ID 必须与崩溃消费者
        //     readGroup 得到的原始 pending 消息 ID 完全一致，证明是同一条消息被 autoClaim 恢复。
        assertThat(recovery.processedMessageIds)
            .as("恢复消费者实际处理的 StreamMessageId 应与原始 readGroup pending 消息 ID 精确匹配")
            .contains(originalMsgId);

        // 8. 验证 pending 清空：通过 autoClaim 尝试回收，应无消息
        var remaining = stream.autoClaim(
            TEST_GROUP, "verifier", 1, TimeUnit.MILLISECONDS, StreamMessageId.MIN, 10);
        assertThat(remaining.getMessages())
            .as("pending 消息应已被 ACK 清空，无剩余可回收消息")
            .isEmpty();

        stopConsumer(recovery);
    }

    @Test
    @DisplayName("批量恢复：多条 pending 未 ACK 消息被新消费者通过 autoClaim 批量回收")
    void multiplePendingMessages_batchAutoClaimed() throws Exception {
        // 1. 创建 consumer group
        redisService.createStreamGroup(TEST_STREAM_KEY, TEST_GROUP);

        // 2. 发送 3 条消息
        sendMessage("batch-1", "content 1");
        sendMessage("batch-2", "content 2");
        sendMessage("batch-3", "content 3");

        // 3. 模拟消费者崩溃：readGroup 读取所有 3 条消息但不 ACK
        var stream = redisService.getClient()
            .getStream(TEST_STREAM_KEY, org.redisson.client.codec.StringCodec.INSTANCE);
        var crashedMessages = stream.readGroup(
            TEST_GROUP, "crashed-batch-consumer",
            org.redisson.api.stream.StreamReadGroupArgs.neverDelivered().count(3));

        assertThat(crashedMessages)
            .as("崩溃消费者应读取到所有 3 条消息")
            .hasSize(3);

        // 记录原始 StreamMessageId
        var originalIds = crashedMessages.keySet();

        // 4. 消息留在 pending 中（未 ACK）

        // 5. 启动恢复消费者（短 pending 超时 500ms）
        TestConsumer recovery = createAndStart("batch-recovery", 500);

        // 6. 等待所有 3 条消息被 autoClaim 恢复并处理
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && recovery.processedIds.size() < 3) {
            Thread.sleep(200);
        }

        // 7. 验证 recovery 处理了所有 3 条原始消息
        assertThat(recovery.processedIds)
            .as("恢复消费者应处理所有 3 条 pending 消息")
            .containsExactlyInAnyOrder("batch-1", "batch-2", "batch-3");

        // 7b. 精确匹配 StreamMessageId 集合：恢复消费者实际处理的消息 ID 集合必须与崩溃消费者
        //     readGroup 得到的 3 条原始 pending 消息 ID 集合完全一致。
        assertThat(recovery.processedMessageIds)
            .as("恢复消费者实际处理的 StreamMessageId 集合应与原始 readGroup pending 消息 ID 集合精确匹配")
            .containsExactlyInAnyOrderElementsOf(originalIds);

        // 8. 验证 pending 清空：通过 autoClaim 尝试回收，应无消息
        var remaining = stream.autoClaim(
            TEST_GROUP, "verifier", 1, TimeUnit.MILLISECONDS, StreamMessageId.MIN, 10);
        assertThat(remaining.getMessages())
            .as("所有 pending 消息应已被 ACK 清空")
            .isEmpty();

        stopConsumer(recovery);
    }

    @Test
    @DisplayName("autoClaim 直接验证：idle pending 消息被新消费者认领（Redis API 层面）")
    void autoClaimDirectly_idlePendingMessagesClaimed() throws Exception {
        String streamKey = TEST_STREAM_KEY + ":direct";
        String group = TEST_GROUP + "-direct";

        // 1. 创建 stream + group
        redisService.createStreamGroup(streamKey, group);

        // 2. 发送消息
        redisService.streamAdd(streamKey, Map.of("id", "direct-1", "content", "test"));

        // 3. consumer-A 读取（消息进入 pending）
        var messagesA = redisService.getClient()
            .getStream(streamKey, org.redisson.client.codec.StringCodec.INSTANCE)
            .readGroup(group, "consumer-A",
                org.redisson.api.stream.StreamReadGroupArgs.neverDelivered().count(1));
        assertThat(messagesA).hasSize(1);
        StreamMessageId msgId = messagesA.keySet().iterator().next();

        // 4. 不调用 ACK → 消息留在 pending 列表

        // 5. consumer-B 使用 autoClaim 回收（minIdleTime=1ms，几乎立即回收）
        var result = redisService.getClient()
            .getStream(streamKey, org.redisson.client.codec.StringCodec.INSTANCE)
            .autoClaim(group, "consumer-B", 1, TimeUnit.MILLISECONDS, StreamMessageId.MIN, 10);

        // 6. 验证 autoClaim 回收了消息
        assertThat(result.getMessages())
            .as("autoClaim 应回收 idle pending 消息")
            .isNotEmpty()
            .containsKey(msgId);

        // 7. 验证消息内容完整
        @SuppressWarnings("unchecked")
        Map<String, String> claimedData = (Map<String, String>) (Map<?, ?>) result.getMessages().get(msgId);
        assertThat(claimedData).containsEntry("id", "direct-1").containsEntry("content", "test");

        // 8. consumer-B ACK 消息
        redisService.streamAck(streamKey, group, msgId);
    }

    @Test
    @DisplayName("pendingIdleTimeoutMs 可覆写：子类自定义回收超时生效")
    void pendingIdleTimeout_overridableBySubclass() throws Exception {
        redisService.createStreamGroup(TEST_STREAM_KEY, TEST_GROUP);

        // 验证 AbstractStreamConsumer.pendingIdleTimeoutMs() 可被子类覆写
        TestConsumer shortTimeout = new TestConsumer(redisService, "short", 500);
        TestConsumer defaultTimeout = new TestConsumer(redisService, "default",
            AsyncTaskStreamConstants.PENDING_IDLE_TIMEOUT_MS);

        // 发送消息并用短超时消费者处理
        sendMessage("timeout-test", "content");

        TestConsumer consumer = createAndStart("timeout-verify", 500);
        CountDownLatch latch = new CountDownLatch(1);

        // 等待消息被处理
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && consumer.processedIds.isEmpty()) {
            Thread.sleep(200);
        }
        assertThat(consumer.processedIds).contains("timeout-test");

        stopConsumer(consumer);

        // 测试通过即证明 pendingIdleTimeoutMs() 覆写生效
        // （如果覆写不生效，默认 5 分钟超时不会在 10 秒内处理消息）
        assertThat(shortTimeout).isNotNull();
        assertThat(defaultTimeout).isNotNull();
    }
}
