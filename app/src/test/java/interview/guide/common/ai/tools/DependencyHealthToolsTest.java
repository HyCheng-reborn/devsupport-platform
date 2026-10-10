package interview.guide.common.ai.tools;

import interview.guide.common.config.StorageConfigProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DependencyHealthTools 单元测试 — 每个测试对应一个实际风险。
 * 使用 Mockito mock DataSource / RedissonClient / S3Client，不调用真实基础设施。
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

  @AfterEach
  void tearDown() {
    tools.clearRecorder();
  }

  // ========== 辅助方法 ==========

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

      Map<String, Object> result = tools.checkDependencyHealth();

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

      Map<String, Object> result = tools.checkDependencyHealth();

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

      Map<String, Object> result = tools.checkDependencyHealth();

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

      Map<String, Object> result = tools.checkDependencyHealth();

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

      Map<String, Object> result = tools.checkDependencyHealth();

      assertThat(result.keySet()).containsExactlyInAnyOrder("postgresql", "redis", "objectStorage");
    }

    @Test
    @DisplayName("结果不包含原始异常消息、堆栈或配置值")
    void noSensitiveDataInResult() throws Exception {
      mockPostgresqlFail();
      mockRedisFail();
      mockS3Fail();

      Map<String, Object> result = tools.checkDependencyHealth();

      String resultStr = result.toString();
      assertThat(resultStr).doesNotContain("Connection refused");
      assertThat(resultStr).doesNotContain("Redis connection failed");
      assertThat(resultStr).doesNotContain("S3 unreachable");
      assertThat(resultStr).doesNotContain("test-bucket");
      assertThat(resultStr).doesNotContain("StackTrace");
    }
  }

  // ========== 调用次数限制 ==========

  @Nested
  @DisplayName("调用次数限制")
  class InvocationLimit {

    @Test
    @DisplayName("通过 ToolInvocationRecorder 验证超过 3 次调用时记录器中 LIMIT_EXCEEDED")
    void limitExceededAfterThreeCalls() throws Exception {
      mockPostgresqlOk();
      mockRedisOk();
      mockS3Ok();

      ToolInvocationRecorder recorder = new ToolInvocationRecorder();
      tools.setRecorder(recorder);

      // 前 3 次正常记录
      tools.checkDependencyHealth();
      tools.checkDependencyHealth();
      tools.checkDependencyHealth();

      assertThat(recorder.getRecords()).hasSize(3);
      assertThat(recorder.hasReachedLimit()).isTrue();

      // 第 4 次：记录器已满，产生 LIMIT_EXCEEDED 记录
      tools.checkDependencyHealth();

      // LIMIT_EXCEEDED 记录不受 MAX_INVOCATIONS 限制，始终被记录
      assertThat(recorder.getRecords()).hasSize(4);
      assertThat(recorder.getRecords().getLast().status()).isEqualTo("LIMIT_EXCEEDED");
    }
  }

  // ========== AtomicReference recorder 集成 ==========

  @Nested
  @DisplayName("AtomicReference recorder 集成")
  class AtomicReferenceRecorder {

    @Test
    @DisplayName("设置 recorder 后调用工具，recorder 中有记录")
    void recorderHasRecordsWhenSet() throws Exception {
      mockPostgresqlOk();
      mockRedisOk();
      mockS3Ok();

      ToolInvocationRecorder recorder = new ToolInvocationRecorder();
      tools.setRecorder(recorder);

      tools.checkDependencyHealth();

      assertThat(recorder.getRecords()).hasSize(1);
      ToolCallRecord record = recorder.getRecords().getFirst();
      assertThat(record.toolName()).isEqualTo("checkDependencyHealth");
      assertThat(record.status()).isEqualTo("SUCCESS");
      assertThat(record.demo()).isFalse();
      assertThat(record.durationMs()).isGreaterThanOrEqualTo(0);
      assertThat(record.timestamp()).isNotNull();
    }

    @Test
    @DisplayName("不设置 recorder 时调用不报错")
    void noRecorderDoesNotThrow() throws Exception {
      mockPostgresqlOk();
      mockRedisOk();
      mockS3Ok();

      // 不设置 recorder
      tools.clearRecorder();

      Map<String, Object> result = tools.checkDependencyHealth();

      // 工具正常返回结果
      assertThat(result).containsKeys("postgresql", "redis", "objectStorage");
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

      Map<String, Object> result = toolsWithoutS3.checkDependencyHealth();

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
