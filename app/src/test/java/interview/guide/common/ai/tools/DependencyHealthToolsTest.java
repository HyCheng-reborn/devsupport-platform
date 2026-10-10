package interview.guide.common.ai.tools;

import interview.guide.common.config.StorageConfigProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.model.ToolContext;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DependencyHealthTools 单元测试 — 每个测试对应一个实际风险。
 * 使用 Mockito mock DataSource / RedissonClient / S3Client，不调用真实基础设施。
 * 验证 ToolContext 请求隔离、执行上限和并发安全。
 */
@DisplayName("依赖健康检查工具测试")
@ExtendWith(MockitoExtension.class)
class DependencyHealthToolsTest {

  @Mock private DataSource dataSource;
  @Mock private RedissonClient redissonClient;
  @Mock private S3Client s3Client;

  private StorageConfigProperties storageConfig;
  private DependencyHealthTools tools;
  private final Executor testExecutor = Runnable::run;

  @BeforeEach
  void setUp() {
    storageConfig = new StorageConfigProperties();
    storageConfig.setBucket("test-bucket");
    tools = new DependencyHealthTools(dataSource, redissonClient, s3Client, storageConfig, testExecutor);
  }

  // ========== 辅助方法 ==========

  private ToolContext toolContextWith(ToolInvocationRecorder recorder) {
    return new ToolContext(Map.of(DependencyHealthTools.RECORDER_KEY, recorder));
  }

  private void mockPostgresqlOk() throws Exception {
    Connection conn = mock(Connection.class);
    when(conn.isValid(3)).thenReturn(true);
    when(dataSource.getConnection()).thenReturn(conn);
  }

  private void mockPostgresqlFail() throws Exception {
    when(dataSource.getConnection()).thenThrow(new RuntimeException("Connection refused"));
  }

  @SuppressWarnings("unchecked")
  private void mockRedisOk() {
    RBucket<Object> bucket = mock(RBucket.class);
    when(redissonClient.getBucket("__health_check__")).thenReturn(bucket);
    when(bucket.isExists()).thenReturn(false);
  }

  private void mockRedisFail() {
    when(redissonClient.getBucket("__health_check__")).thenThrow(new RuntimeException("Redis connection failed"));
  }

  private void mockS3Ok() {
    when(s3Client.headBucket(any(HeadBucketRequest.class)))
        .thenReturn(mock(HeadBucketResponse.class));
  }

  private void mockS3Fail() {
    when(s3Client.headBucket(any(HeadBucketRequest.class)))
        .thenThrow(new RuntimeException("S3 unreachable"));
  }

  // ========== 全部组件健康 ==========

  @Nested
  @DisplayName("全部组件健康")
  class AllHealthy {

    @Test
    @DisplayName("所有探测成功时返回 3 个 OK 状态")
    void allComponentsOk() throws Exception {
      mockPostgresqlOk();
      mockRedisOk();
      mockS3Ok();

      Map<String, Object> result = tools.checkDependencyHealth(null);

      assertThat(result).containsKeys("postgresql", "redis", "objectStorage");
      assertThat(getStatus(result, "postgresql")).isEqualTo("OK");
      assertThat(getStatus(result, "redis")).isEqualTo("OK");
      assertThat(getStatus(result, "objectStorage")).isEqualTo("OK");
    }
  }

  // ========== 单组件不可达 ==========

  @Nested
  @DisplayName("单组件不可达")
  class SingleComponentUnreachable {

    @Test
    @DisplayName("PostgreSQL 不可达时 postgresql 状态为 UNREACHABLE，其他组件不受影响")
    void postgresqlUnreachable() throws Exception {
      mockPostgresqlFail();
      mockRedisOk();
      mockS3Ok();

      Map<String, Object> result = tools.checkDependencyHealth(null);

      assertThat(getStatus(result, "postgresql")).isEqualTo("UNREACHABLE");
      assertThat(getStatus(result, "redis")).isEqualTo("OK");
      assertThat(getStatus(result, "objectStorage")).isEqualTo("OK");
    }

    @Test
    @DisplayName("Redis 不可达时 redis 状态为 UNREACHABLE")
    void redisUnreachable() throws Exception {
      mockPostgresqlOk();
      mockRedisFail();
      mockS3Ok();

      Map<String, Object> result = tools.checkDependencyHealth(null);

      assertThat(getStatus(result, "postgresql")).isEqualTo("OK");
      assertThat(getStatus(result, "redis")).isEqualTo("UNREACHABLE");
      assertThat(getStatus(result, "objectStorage")).isEqualTo("OK");
    }

    @Test
    @DisplayName("S3 不可达时 objectStorage 状态为 UNREACHABLE")
    void s3Unreachable() throws Exception {
      mockPostgresqlOk();
      mockRedisOk();
      mockS3Fail();

      Map<String, Object> result = tools.checkDependencyHealth(null);

      assertThat(getStatus(result, "postgresql")).isEqualTo("OK");
      assertThat(getStatus(result, "redis")).isEqualTo("OK");
      assertThat(getStatus(result, "objectStorage")).isEqualTo("UNREACHABLE");
    }
  }

  // ========== 白名单验证 ==========

  @Nested
  @DisplayName("白名单与脱敏")
  class WhitelistAndSanitization {

    @Test
    @DisplayName("返回的 Map 只包含 postgresql、redis、objectStorage 三个 key")
    void onlyWhitelistedKeys() throws Exception {
      mockPostgresqlOk();
      mockRedisOk();
      mockS3Ok();

      Map<String, Object> result = tools.checkDependencyHealth(null);

      assertThat(result.keySet()).containsExactlyInAnyOrder("postgresql", "redis", "objectStorage");
    }

    @Test
    @DisplayName("结果不包含原始异常消息、堆栈或配置值")
    void noSensitiveDataInResult() throws Exception {
      mockPostgresqlFail();
      mockRedisFail();
      mockS3Fail();

      Map<String, Object> result = tools.checkDependencyHealth(null);

      String resultStr = result.toString();
      assertThat(resultStr).doesNotContain("Connection refused");
      assertThat(resultStr).doesNotContain("Redis connection failed");
      assertThat(resultStr).doesNotContain("S3 unreachable");
      assertThat(resultStr).doesNotContain("test-bucket");
      assertThat(resultStr).doesNotContain("StackTrace");
    }
  }

  // ========== 执行上限（tryAcquireSlot 前置检查） ==========

  @Nested
  @DisplayName("执行上限")
  class InvocationLimit {

    @Test
    @DisplayName("前 3 次调用正常执行探测，第 4 次跳过探测返回 SKIPPED")
    void fourthCallSkipsProbe() throws Exception {
      mockPostgresqlOk();
      mockRedisOk();
      mockS3Ok();

      ToolInvocationRecorder recorder = new ToolInvocationRecorder();
      ToolContext ctx = toolContextWith(recorder);

      // 前 3 次正常执行
      tools.checkDependencyHealth(ctx);
      tools.checkDependencyHealth(ctx);
      tools.checkDependencyHealth(ctx);

      assertThat(recorder.getRecords()).hasSize(3);
      assertThat(recorder.getRecords()).allMatch(r -> "SUCCESS".equals(r.status()));

      // 第 4 次：跳过实际探测，返回 SKIPPED
      Map<String, Object> result = tools.checkDependencyHealth(ctx);

      assertThat(getStatus(result, "postgresql")).isEqualTo("SKIPPED");
      assertThat(getStatus(result, "redis")).isEqualTo("SKIPPED");
      assertThat(getStatus(result, "objectStorage")).isEqualTo("SKIPPED");

      // 记录器中有 LIMIT_EXCEEDED 记录
      assertThat(recorder.getRecords()).hasSize(4);
      assertThat(recorder.getRecords().getLast().status()).isEqualTo("LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("超限时不触碰真实依赖（通过 mock 验证）")
    void limitExceededDoesNotTouchDependencies() throws Exception {
      mockPostgresqlOk();
      mockRedisOk();
      mockS3Ok();

      ToolInvocationRecorder recorder = new ToolInvocationRecorder();
      ToolContext ctx = toolContextWith(recorder);

      // 用尽 3 次额度
      for (int i = 0; i < 3; i++) {
        tools.checkDependencyHealth(ctx);
      }

      // 重置 mock 计数，验证后续调用不触碰依赖
      org.mockito.Mockito.clearInvocations(dataSource, redissonClient, s3Client);

      // 第 4 次应跳过
      tools.checkDependencyHealth(ctx);

      // 验证没有新的依赖调用
      org.mockito.Mockito.verifyNoMoreInteractions(dataSource, redissonClient, s3Client);
    }
  }

  // ========== ToolContext 请求隔离 ==========

  @Nested
  @DisplayName("ToolContext 请求隔离")
  class ToolContextIsolation {

    @Test
    @DisplayName("通过 ToolContext 传递 recorder，工具调用后有记录")
    void recorderFromToolContextHasRecords() throws Exception {
      mockPostgresqlOk();
      mockRedisOk();
      mockS3Ok();

      ToolInvocationRecorder recorder = new ToolInvocationRecorder();
      ToolContext ctx = toolContextWith(recorder);

      tools.checkDependencyHealth(ctx);

      assertThat(recorder.getRecords()).hasSize(1);
      ToolCallRecord record = recorder.getRecords().getFirst();
      assertThat(record.toolName()).isEqualTo("checkDependencyHealth");
      assertThat(record.status()).isEqualTo("SUCCESS");
      assertThat(record.demo()).isFalse();
      assertThat(record.durationMs()).isGreaterThanOrEqualTo(0);
      assertThat(record.timestamp()).isNotNull();
    }

    @Test
    @DisplayName("null ToolContext 时工具正常执行但不记录")
    void nullToolContextDoesNotThrow() throws Exception {
      mockPostgresqlOk();
      mockRedisOk();
      mockS3Ok();

      Map<String, Object> result = tools.checkDependencyHealth(null);

      assertThat(result).containsKeys("postgresql", "redis", "objectStorage");
    }

    @Test
    @DisplayName("ToolContext 不含 recorder 时工具正常执行但不记录")
    void toolContextWithoutRecorderDoesNotThrow() throws Exception {
      mockPostgresqlOk();
      mockRedisOk();
      mockS3Ok();

      ToolContext emptyCtx = new ToolContext(Map.of());
      Map<String, Object> result = tools.checkDependencyHealth(emptyCtx);

      assertThat(result).containsKeys("postgresql", "redis", "objectStorage");
    }

    @Test
    @DisplayName("两个并发请求使用不同 recorder，记录不串线")
    void concurrentRequestsWithDifferentRecorders() throws Exception {
      mockPostgresqlOk();
      mockRedisOk();
      mockS3Ok();

      ToolInvocationRecorder recorder1 = new ToolInvocationRecorder();
      ToolInvocationRecorder recorder2 = new ToolInvocationRecorder();
      ToolContext ctx1 = toolContextWith(recorder1);
      ToolContext ctx2 = toolContextWith(recorder2);

      ExecutorService executor = Executors.newFixedThreadPool(2);
      CountDownLatch latch = new CountDownLatch(2);
      CountDownLatch startLatch = new CountDownLatch(1);

      executor.submit(() -> {
        try {
          startLatch.await();
          tools.checkDependencyHealth(ctx1);
          tools.checkDependencyHealth(ctx1);
        } catch (Exception e) {
          e.printStackTrace();
        } finally {
          latch.countDown();
        }
      });

      executor.submit(() -> {
        try {
          startLatch.await();
          tools.checkDependencyHealth(ctx2);
        } catch (Exception e) {
          e.printStackTrace();
        } finally {
          latch.countDown();
        }
      });

      // 同时释放两个线程
      startLatch.countDown();
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
      executor.shutdown();

      // 验证 recorder1 有 2 条记录，recorder2 有 1 条记录，不串线
      assertThat(recorder1.getRecords()).hasSize(2);
      assertThat(recorder2.getRecords()).hasSize(1);
    }
  }

  // ========== S3 客户端未配置 ==========

  @Nested
  @DisplayName("S3 客户端未配置")
  class S3NotConfigured {

    @Test
    @DisplayName("S3Client 为 null 时 objectStorage 状态为 UNREACHABLE")
    void s3ClientNull() throws Exception {
      DependencyHealthTools toolsWithoutS3 =
          new DependencyHealthTools(dataSource, redissonClient, null, storageConfig, testExecutor);

      mockPostgresqlOk();
      mockRedisOk();

      Map<String, Object> result = toolsWithoutS3.checkDependencyHealth(null);

      assertThat(getStatus(result, "postgresql")).isEqualTo("OK");
      assertThat(getStatus(result, "redis")).isEqualTo("OK");
      assertThat(getStatus(result, "objectStorage")).isEqualTo("UNREACHABLE");
    }
  }

  // ========== 辅助方法 ==========

  @SuppressWarnings("unchecked")
  private String getStatus(Map<String, Object> result, String component) {
    Object value = result.get(component);
    if (value instanceof Map<?, ?> componentMap) {
      return (String) componentMap.get("status");
    }
    return (String) value;
  }
}
