package interview.guide.modules.knowledgebase;

import interview.guide.common.ai.tools.DependencyHealthTools;
import interview.guide.common.ai.tools.ToolCallRecord;
import interview.guide.common.ai.tools.ToolInvocationRecorder;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Spring AI ToolContext 传递机制验证测试
 *
 * <p>验证 {@code prompt.toolContext → @Tool ToolContext → ToolInvocationRecorder}
 * 的请求级隔离正确性。不使用付费 API，通过 mock 基础设施验证工具调用链路。
 *
 * <p>核心验证点：
 * <ul>
 *   <li>每个请求的 recorder 独立，并发请求不串写</li>
 *   <li>DependencyHealthTools 从正确的 ToolContext 获取 recorder</li>
 *   <li>recorder 记录的元信息（toolName、status、durationMs、timestamp）完整</li>
 * </ul>
 */
@DisplayName("Spring AI ToolContext 传递机制验证")
@ExtendWith(MockitoExtension.class)
class RagChatToolIsolationTest {

  @Mock private DataSource dataSource;
  @Mock private RedissonClient redissonClient;
  @Mock private S3Client s3Client;

  private StorageConfigProperties storageConfig;
  private DependencyHealthTools tools;

  @BeforeEach
  void setUp() {
    storageConfig = new StorageConfigProperties();
    storageConfig.setBucket("test-bucket");
    tools = new DependencyHealthTools(dataSource, redissonClient, s3Client, storageConfig, Runnable::run);
  }

  // ========== 辅助方法 ==========

  private ToolContext toolContextWith(ToolInvocationRecorder recorder) {
    return new ToolContext(Map.of(DependencyHealthTools.RECORDER_KEY, recorder));
  }

  private void mockAllHealthy() throws Exception {
    Connection conn = mock(Connection.class);
    when(conn.isValid(3)).thenReturn(true);
    when(dataSource.getConnection()).thenReturn(conn);

    @SuppressWarnings("unchecked")
    RBucket<Object> bucket = mock(RBucket.class);
    when(redissonClient.getBucket("__health_check__")).thenReturn(bucket);
    when(bucket.isExists()).thenReturn(false);

    when(s3Client.headBucket(any(HeadBucketRequest.class)))
        .thenReturn(mock(HeadBucketResponse.class));
  }

  // ========== ToolContext → recorder 传递 ==========

  @Nested
  @DisplayName("ToolContext → recorder 传递链路")
  class ToolContextToRecorder {

    @Test
    @DisplayName("ToolContext 中的 recorder 被正确提取并记录调用")
    void recorderExtractedFromToolContext() throws Exception {
      mockAllHealthy();

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
    @DisplayName("不同 ToolContext 携带不同 recorder，记录互不干扰")
    void differentToolContextsHaveIndependentRecorders() throws Exception {
      mockAllHealthy();

      ToolInvocationRecorder recorder1 = new ToolInvocationRecorder();
      ToolInvocationRecorder recorder2 = new ToolInvocationRecorder();
      ToolContext ctx1 = toolContextWith(recorder1);
      ToolContext ctx2 = toolContextWith(recorder2);

      // 请求 1 调用 2 次
      tools.checkDependencyHealth(ctx1);
      tools.checkDependencyHealth(ctx1);

      // 请求 2 调用 1 次
      tools.checkDependencyHealth(ctx2);

      assertThat(recorder1.getRecords()).hasSize(2);
      assertThat(recorder2.getRecords()).hasSize(1);

      // 验证记录内容完整
      assertThat(recorder1.getRecords())
          .allMatch(r -> "checkDependencyHealth".equals(r.toolName()));
      assertThat(recorder2.getRecords())
          .allMatch(r -> "checkDependencyHealth".equals(r.toolName()));
    }
  }

  // ========== 并发请求级隔离 ==========

  @Nested
  @DisplayName("并发请求级隔离")
  class ConcurrentIsolation {

    @Test
    @DisplayName("两个并发请求使用不同 ToolContext，recorder 记录不串线")
    void concurrentRequestsIndependentRecorders() throws Exception {
      mockAllHealthy();

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

      startLatch.countDown();
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
      executor.shutdown();

      // recorder1 有 3 条记录，recorder2 有 1 条记录
      assertThat(recorder1.getRecords()).hasSize(3);
      assertThat(recorder2.getRecords()).hasSize(1);

      // 所有记录的 timestamp 非空
      List<ToolCallRecord> allRecords = new java.util.ArrayList<>();
      allRecords.addAll(recorder1.getRecords());
      allRecords.addAll(recorder2.getRecords());
      assertThat(allRecords).allMatch(r -> r.timestamp() != null);
    }

    @Test
    @DisplayName("并发请求下 LIMIT_EXCEEDED 记录各自独立，不跨 recorder")
    void limitExceededIndependentPerRecorder() throws Exception {
      mockAllHealthy();

      ToolInvocationRecorder recorder1 = new ToolInvocationRecorder();
      ToolInvocationRecorder recorder2 = new ToolInvocationRecorder();
      ToolContext ctx1 = toolContextWith(recorder1);
      ToolContext ctx2 = toolContextWith(recorder2);

      // 请求 1 用尽额度并触发 LIMIT_EXCEEDED
      for (int i = 0; i < 4; i++) {
        tools.checkDependencyHealth(ctx1);
      }

      // 请求 2 正常调用 1 次
      tools.checkDependencyHealth(ctx2);

      // recorder1: 3 SUCCESS + 1 LIMIT_EXCEEDED
      assertThat(recorder1.getRecords()).hasSize(4);
      assertThat(recorder1.getRecords().subList(0, 3))
          .allMatch(r -> "SUCCESS".equals(r.status()));
      assertThat(recorder1.getRecords().getLast().status()).isEqualTo("LIMIT_EXCEEDED");

      // recorder2: 1 SUCCESS，无 LIMIT_EXCEEDED
      assertThat(recorder2.getRecords()).hasSize(1);
      assertThat(recorder2.getRecords().getFirst().status()).isEqualTo("SUCCESS");
    }
  }

  // ========== 记录元信息完整性 ==========

  @Nested
  @DisplayName("记录元信息完整性")
  class RecordMetadata {

    @Test
    @DisplayName("SUCCESS 记录包含完整的 toolName、status、result、durationMs、timestamp")
    void successRecordHasCompleteMetadata() throws Exception {
      mockAllHealthy();

      ToolInvocationRecorder recorder = new ToolInvocationRecorder();
      ToolContext ctx = toolContextWith(recorder);

      tools.checkDependencyHealth(ctx);

      ToolCallRecord record = recorder.getRecords().getFirst();
      assertThat(record.toolName()).isEqualTo("checkDependencyHealth");
      assertThat(record.status()).isEqualTo("SUCCESS");
      assertThat(record.result()).isInstanceOf(Map.class);
      assertThat(record.durationMs()).isGreaterThanOrEqualTo(0);
      assertThat(record.timestamp()).isNotNull();
      assertThat(record.demo()).isFalse();

      // result 包含 3 个组件状态
      @SuppressWarnings("unchecked")
      Map<String, Object> resultMap = (Map<String, Object>) record.result();
      assertThat(resultMap).containsKeys("postgresql", "redis", "objectStorage");
    }

    @Test
    @DisplayName("LIMIT_EXCEEDED 记录的 result 包含超限提示信息")
    void limitExceededRecordHasMessage() throws Exception {
      mockAllHealthy();

      ToolInvocationRecorder recorder = new ToolInvocationRecorder();
      ToolContext ctx = toolContextWith(recorder);

      // 用尽额度
      for (int i = 0; i < 3; i++) {
        tools.checkDependencyHealth(ctx);
      }
      // 触发 LIMIT_EXCEEDED
      tools.checkDependencyHealth(ctx);

      ToolCallRecord limitRecord = recorder.getRecords().getLast();
      assertThat(limitRecord.status()).isEqualTo("LIMIT_EXCEEDED");
      assertThat(limitRecord.result()).isInstanceOf(Map.class);

      @SuppressWarnings("unchecked")
      Map<String, Object> result = (Map<String, Object>) limitRecord.result();
      assertThat(result).containsKey("message");
    }
  }
}
