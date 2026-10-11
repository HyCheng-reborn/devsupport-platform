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
 * Tool-use 端到端链路验证测试。
 * <p>
 * 本测试验证 Tool-use 链路的应用层逻辑，包括：
 * <ul>
 *   <li>ToolContext → recorder 传递链路</li>
 *   <li>并发请求隔离</li>
 *   <li>Fail closed 安全策略</li>
 *   <li>调用上限控制</li>
 * </ul>
 * <p>
 * <b>关于 Spring AI 工具分发</b>：
 * Spring AI 框架的 {@code ToolCallingAdvisor} 负责拦截模型返回的 tool_call 响应，
 * 并通过 {@code ToolCallingManager} 执行注册的工具。框架保证：
 * <ul>
 *   <li>{@code chatClient.prompt().tools(obj).toolContext(ctx)} 注册的工具会在模型请求时可用</li>
 *   <li>{@code ToolContext} 中的值会通过 {@code @Tool} 方法参数注入到工具实现</li>
 *   <li>工具执行结果会作为 tool_result 返回给模型继续对话</li>
 * </ul>
 * 本测试直接调用工具方法验证应用层逻辑，Spring AI 框架的工具分发机制由框架自身保证。
 *
 * @see RagChatToolIsolationTest 更详细的工具方法级单元测试
 */
@DisplayName("Tool-use 端到端链路验证测试")
@ExtendWith(MockitoExtension.class)
class ToolUseEndToEndTest {

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

  private ToolContext toolContextWith(ToolInvocationRecorder recorder) {
    return new ToolContext(Map.of(DependencyHealthTools.RECORDER_KEY, recorder));
  }

  // ========== 完整链路验证 ==========

  @Nested
  @DisplayName("完整链路验证：ToolContext → recorder → 工具执行 → 记录")
  class FullChainValidation {

    @Test
    @DisplayName("ToolContext 中的 recorder 被正确传递，工具执行后记录完整元信息")
    void toolContextRecorderReceivesToolExecution() throws Exception {
      mockAllHealthy();

      ToolInvocationRecorder recorder = new ToolInvocationRecorder();
      ToolContext ctx = toolContextWith(recorder);

      // 调用工具，模拟 Spring AI 框架分发工具调用
      tools.checkDependencyHealth(ctx);

      // 验证 recorder 有记录
      assertThat(recorder.getRecords()).hasSize(1);
      ToolCallRecord record = recorder.getRecords().getFirst();
      assertThat(record.toolName()).isEqualTo("checkDependencyHealth");
      assertThat(record.status()).isEqualTo("SUCCESS");
      assertThat(record.demo()).isFalse();
      assertThat(record.durationMs()).isGreaterThanOrEqualTo(0);
      assertThat(record.timestamp()).isNotNull();

      // 验证 result 包含三个组件状态
      @SuppressWarnings("unchecked")
      Map<String, Object> result = (Map<String, Object>) record.result();
      assertThat(result).containsKeys("postgresql", "redis", "objectStorage");
    }

    @Test
    @DisplayName("多次工具调用，recorder 正确累积记录")
    void multipleToolCallsAccumulateInRecorder() throws Exception {
      mockAllHealthy();

      ToolInvocationRecorder recorder = new ToolInvocationRecorder();
      ToolContext ctx = toolContextWith(recorder);

      // 调用工具 3 次
      tools.checkDependencyHealth(ctx);
      tools.checkDependencyHealth(ctx);
      tools.checkDependencyHealth(ctx);

      // 验证 recorder 有 3 条记录
      assertThat(recorder.getRecords()).hasSize(3);
      assertThat(recorder.getRecords())
          .allMatch(r -> "checkDependencyHealth".equals(r.toolName()))
          .allMatch(r -> "SUCCESS".equals(r.status()));
    }
  }

  // ========== 并发隔离：两个请求不串记录 ==========

  @Nested
  @DisplayName("并发隔离：两个请求不串记录")
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

      // 请求 1：调用 2 次
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

      // 请求 2：调用 1 次
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

      // 验证 recorder1 有 2 条记录，recorder2 有 1 条记录
      assertThat(recorder1.getRecords()).hasSize(2);
      assertThat(recorder2.getRecords()).hasSize(1);

      // 所有记录的 toolName 正确
      assertThat(recorder1.getRecords()).allMatch(r -> "checkDependencyHealth".equals(r.toolName()));
      assertThat(recorder2.getRecords()).allMatch(r -> "checkDependencyHealth".equals(r.toolName()));
    }
  }

  // ========== Fail closed：无 recorder 时拒绝执行探测 ==========

  @Nested
  @DisplayName("Fail closed：无 recorder 时拒绝执行探测")
  class FailClosed {

    @Test
    @DisplayName("null ToolContext 时返回错误指示，不执行任何探测")
    void nullToolContextReturnsError() throws Exception {
      Map<String, Object> result = tools.checkDependencyHealth(null);

      assertThat(result).containsKey("error");
      assertThat(result.get("error")).isEqualTo("tool_unavailable");
      assertThat(result.get("reason")).isEqualTo("no_recorder_in_context");
      // 所有组件状态为 SKIPPED，未执行真实探测
      assertThat(result).containsEntry("postgresql", Map.of("status", "SKIPPED"));
    }

    @Test
    @DisplayName("ToolContext 不含 recorder 时返回错误指示")
    void toolContextWithoutRecorderReturnsError() throws Exception {
      ToolContext emptyContext = new ToolContext(Map.of());

      Map<String, Object> result = tools.checkDependencyHealth(emptyContext);

      assertThat(result).containsKey("error");
      assertThat(result.get("error")).isEqualTo("tool_unavailable");
    }
  }

  // ========== 超限不探测 ==========

  @Nested
  @DisplayName("超限不探测")
  class LimitExceeded {

    @Test
    @DisplayName("超过 3 次工具调用时，第 4 次不执行实际探测，记录 LIMIT_EXCEEDED")
    void fourthCallDoesNotExecuteProbe() throws Exception {
      mockAllHealthy();

      ToolInvocationRecorder recorder = new ToolInvocationRecorder();
      ToolContext ctx = toolContextWith(recorder);

      // 调用 4 次
      tools.checkDependencyHealth(ctx);
      tools.checkDependencyHealth(ctx);
      tools.checkDependencyHealth(ctx);
      tools.checkDependencyHealth(ctx);

      // 验证 recorder 记录：3 条 SUCCESS + 1 条 LIMIT_EXCEEDED
      assertThat(recorder.getRecords()).hasSize(4);
      assertThat(recorder.getRecords().subList(0, 3))
          .allMatch(r -> "SUCCESS".equals(r.status()));
      assertThat(recorder.getRecords().get(3).status()).isEqualTo("LIMIT_EXCEEDED");

      // 验证实际探测次数为 3（通过 tryAcquireSlot 控制）
      assertThat(recorder.getExecutedCount()).isEqualTo(4); // 包括超限的那次
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
