package org.springframework.ai.chat.client;

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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Spring AI 真实工具分发链路端到端测试。
 * <p>
 * 本测试验证完整的 Spring AI 工具分发链路：
 * <pre>
 * fake ChatModel 返回 tool_call
 * → Spring AI ToolCallingAdvisor 拦截
 * → ToolCallingManager 执行注册的工具（DependencyHealthTools）
 * → 工具结果进入下一次模型请求（Prompt 中包含 ToolResponseMessage）
 * → fake ChatModel 返回最终回答
 * </pre>
 * <p>
 * <b>关键验证点</b>：
 * <ul>
 *   <li>工具经过 Spring AI 框架分发执行，而非直接调用工具方法</li>
 *   <li>第二次模型请求的 Prompt 中包含工具执行结果（ToolResponseMessage）</li>
 *   <li>并发请求各自 recorder 独立</li>
 *   <li>超限后不执行额外探测</li>
 * </ul>
 */
@DisplayName("Spring AI 真实工具分发链路端到端测试")
@ExtendWith(MockitoExtension.class)
class SpringAiToolDispatchTest {

  @Mock private DataSource dataSource;
  @Mock private RedissonClient redissonClient;
  @Mock private S3Client s3Client;

  private StorageConfigProperties storageConfig;
  private DependencyHealthTools dependencyHealthTools;

  @BeforeEach
  void setUp() {
    storageConfig = new StorageConfigProperties();
    storageConfig.setBucket("test-bucket");
    dependencyHealthTools = new DependencyHealthTools(dataSource, redissonClient, s3Client, storageConfig, Runnable::run);
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

  /**
   * 构建包含 tool_call 的 ChatResponse。
   * 模拟 LLM 返回工具调用请求。
   */
  private ChatResponse buildToolCallResponse(String toolName) {
    AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
        "call_test_1", "function", toolName, "{}");
    AssistantMessage assistantMessage = AssistantMessage.builder()
        .content("")
        .toolCalls(List.of(toolCall))
        .build();
    Generation generation = new Generation(assistantMessage,
        ChatGenerationMetadata.builder().finishReason("tool_calls").build());
    return new ChatResponse(List.of(generation));
  }

  /**
   * 构建包含最终文本回答的 ChatResponse。
   */
  private ChatResponse buildTextResponse(String text) {
    AssistantMessage assistantMessage = new AssistantMessage(text);
    Generation generation = new Generation(assistantMessage,
        ChatGenerationMetadata.builder().finishReason("stop").build());
    return new ChatResponse(List.of(generation));
  }

  // ========== 测试用例 ==========

  @Nested
  @DisplayName("工具分发成功路径")
  class ToolDispatchSuccessPath {

    @Test
    @DisplayName("fake ChatModel 返回 tool_call → Spring AI 执行工具 → 第二次请求包含工具结果 → 最终回答")
    void fullToolDispatchChain() throws Exception {
      mockAllHealthy();

      // 构建 fake ChatModel：第一次返回 tool_call，第二次返回最终文本
      AtomicInteger callCount = new AtomicInteger(0);
      List<Prompt> capturedPrompts = new CopyOnWriteArrayList<>();

      ChatModel fakeModel = createFakeModel(callCount, capturedPrompts, 2, "健康检查完成，所有组件正常");

      // 构建真实 ChatClient（经过 Spring AI advisor 链）
      ChatClient chatClient = ChatClient.builder(fakeModel).build();

      // 创建 per-request recorder
      ToolInvocationRecorder recorder = new ToolInvocationRecorder();
      ToolContext toolContext = new ToolContext(Map.of(DependencyHealthTools.RECORDER_KEY, recorder));

      // 执行：经过真实 Spring AI 工具分发链路
      String result = chatClient.prompt()
          .user("检查依赖健康状态")
          .tools(dependencyHealthTools)
          .toolContext(toolContext.getContext())
          .call()
          .content();

      // 验证 1：fake 模型被调用两次
      assertThat(callCount.get()).isEqualTo(2);

      // 验证 2：最终回答正确
      assertThat(result).isEqualTo("健康检查完成，所有组件正常");

      // 验证 3：第二次调用时 Prompt 中包含 ToolResponseMessage
      assertThat(capturedPrompts).hasSize(2);
      Prompt secondPrompt = capturedPrompts.get(1);
      List<Message> secondMessages = secondPrompt.getInstructions();
      boolean hasToolResponse = secondMessages.stream()
          .anyMatch(m -> m.getMessageType() == MessageType.TOOL);
      assertThat(hasToolResponse)
          .as("第二次模型请求的 Prompt 中应包含 ToolResponseMessage")
          .isTrue();

      // 验证 4：ToolResponseMessage 中包含工具执行结果
      ToolResponseMessage toolResponseMsg = secondMessages.stream()
          .filter(m -> m instanceof ToolResponseMessage)
          .map(m -> (ToolResponseMessage) m)
          .findFirst()
          .orElseThrow();
      assertThat(toolResponseMsg.getResponses()).isNotEmpty();
      assertThat(toolResponseMsg.getResponses().getFirst().name()).isEqualTo("checkDependencyHealth");
      // 工具结果 JSON 应包含组件状态
      String responseData = toolResponseMsg.getResponses().getFirst().responseData();
      assertThat(responseData).contains("postgresql");

      // 验证 5：recorder 中有 1 条 SUCCESS 记录
      assertThat(recorder.getRecords()).hasSize(1);
      ToolCallRecord record = recorder.getRecords().getFirst();
      assertThat(record.toolName()).isEqualTo("checkDependencyHealth");
      assertThat(record.status()).isEqualTo("SUCCESS");
      assertThat(record.demo()).isFalse();

      // 验证 6：实际探测执行了 1 次（DataSource 被调用）
      verifyProbesExecuted(1);
    }

    @Test
    @DisplayName("工具结果内容包含三个组件的健康状态")
    void toolResultContainsComponentStatus() throws Exception {
      mockAllHealthy();

      AtomicInteger callCount = new AtomicInteger(0);
      List<Prompt> captured = new CopyOnWriteArrayList<>();
      ChatModel fakeModel = new ChatModel() {
        @Override
        public ChatResponse call(Prompt prompt) {
          captured.add(prompt);
          int count = callCount.incrementAndGet();
          if (count == 1) {
            return buildToolCallResponse("checkDependencyHealth");
          } else {
            // 从 Prompt 中提取工具结果
            String toolResult = prompt.getInstructions().stream()
                .filter(m -> m instanceof ToolResponseMessage)
                .map(m -> (ToolResponseMessage) m)
                .flatMap(m -> m.getResponses().stream())
                .map(ToolResponseMessage.ToolResponse::responseData)
                .findFirst()
                .orElse("");
            return buildTextResponse("结果: " + toolResult);
          }
        }

        @Override
        public ChatOptions getOptions() {
          return ToolCallingChatOptions.builder().build();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
          return Flux.just(call(prompt));
        }
      };

      ChatClient chatClient = ChatClient.builder(fakeModel).build();
      ToolInvocationRecorder recorder = new ToolInvocationRecorder();
      ToolContext toolContext = new ToolContext(Map.of(DependencyHealthTools.RECORDER_KEY, recorder));

      String result = chatClient.prompt()
          .user("检查健康")
          .tools(dependencyHealthTools)
          .toolContext(toolContext.getContext())
          .call()
          .content();

      // 工具结果应包含三个组件状态
      assertThat(result).contains("postgresql");
      assertThat(result).contains("redis");
      assertThat(result).contains("objectStorage");
    }
  }

  @Nested
  @DisplayName("并发隔离")
  class ConcurrentIsolation {

    @Test
    @DisplayName("两个并发请求各自 recorder 独立，不串线")
    void concurrentRequestsIndependentRecorders() throws Exception {
      mockAllHealthy();

      // 为每个请求创建独立的 ChatClient + fake model
      ToolInvocationRecorder recorder1 = new ToolInvocationRecorder();
      ToolInvocationRecorder recorder2 = new ToolInvocationRecorder();

      CountDownLatch latch = new CountDownLatch(2);
      CountDownLatch startLatch = new CountDownLatch(1);

      ExecutorService executor = Executors.newFixedThreadPool(2);

      // 请求 1
      executor.submit(() -> {
        try {
          startLatch.await();
          runSingleRequest(recorder1);
        } catch (Exception e) {
          e.printStackTrace();
        } finally {
          latch.countDown();
        }
      });

      // 请求 2
      executor.submit(() -> {
        try {
          startLatch.await();
          runSingleRequest(recorder2);
        } catch (Exception e) {
          e.printStackTrace();
        } finally {
          latch.countDown();
        }
      });

      startLatch.countDown();
      assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
      executor.shutdown();

      // 验证各自 recorder 独立
      assertThat(recorder1.getRecords()).hasSize(1);
      assertThat(recorder2.getRecords()).hasSize(1);
      assertThat(recorder1.getRecords().getFirst().status()).isEqualTo("SUCCESS");
      assertThat(recorder2.getRecords().getFirst().status()).isEqualTo("SUCCESS");
    }

    private void runSingleRequest(ToolInvocationRecorder recorder) {
      AtomicInteger callCount = new AtomicInteger(0);
      ChatModel fakeModel = new ChatModel() {
        @Override
        public ChatResponse call(Prompt prompt) {
          int count = callCount.incrementAndGet();
          if (count == 1) {
            return buildToolCallResponse("checkDependencyHealth");
          } else {
            return buildTextResponse("完成");
          }
        }

        @Override
        public ChatOptions getOptions() {
          return ToolCallingChatOptions.builder().build();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
          return Flux.just(call(prompt));
        }
      };

      ChatClient chatClient = ChatClient.builder(fakeModel).build();
      ToolContext toolContext = new ToolContext(Map.of(DependencyHealthTools.RECORDER_KEY, recorder));

      chatClient.prompt()
          .user("检查健康")
          .tools(dependencyHealthTools)
          .toolContext(toolContext.getContext())
          .call()
          .content();
    }
  }

  @Nested
  @DisplayName("超限不探测")
  class LimitExceeded {

    @Test
    @DisplayName("fake 模型每次返回 tool_call，实际探测不超过 3 次")
    void maxThreeProbesEvenIfModelKeepsRequesting() throws Exception {
      mockAllHealthy();

      AtomicInteger callCount = new AtomicInteger(0);
      int maxIterations = 6; // 让模型反复请求工具

      ChatModel fakeModel = new ChatModel() {
        @Override
        public ChatResponse call(Prompt prompt) {
          int count = callCount.incrementAndGet();
          if (count <= maxIterations) {
            // 每次都返回 tool_call
            return buildToolCallResponse("checkDependencyHealth");
          } else {
            return buildTextResponse("最终回答");
          }
        }

        @Override
        public ChatOptions getOptions() {
          return ToolCallingChatOptions.builder().build();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
          return Flux.just(call(prompt));
        }
      };

      ChatClient chatClient = ChatClient.builder(fakeModel).build();
      ToolInvocationRecorder recorder = new ToolInvocationRecorder();
      ToolContext toolContext = new ToolContext(Map.of(DependencyHealthTools.RECORDER_KEY, recorder));

      String result = chatClient.prompt()
          .user("反复检查健康")
          .tools(dependencyHealthTools)
          .toolContext(toolContext.getContext())
          .call()
          .content();

      // 验证：实际探测不超过 3 次
      // tryAcquireSlot 被调用了 maxIterations 次，但只有前 3 次返回 true
      assertThat(recorder.getExecutedCount()).isEqualTo(maxIterations);

      // 验证：recorder 中有 3 条 SUCCESS + 1 条 LIMIT_EXCEEDED
      assertThat(recorder.getRecords()).hasSize(4);
      assertThat(recorder.getRecords().subList(0, 3))
          .allMatch(r -> "SUCCESS".equals(r.status()));
      assertThat(recorder.getRecords().get(3).status()).isEqualTo("LIMIT_EXCEEDED");

      // 验证：DataSource 只被调用了 3 次（前 3 次探测，后 3 次被 LIMIT 跳过）
      verifyProbesExecuted(3);

      // 最终回答应来自第 maxIterations+1 次模型调用
      assertThat(result).isEqualTo("最终回答");
    }
  }

  @Nested
  @DisplayName("Fail closed 经过框架分发")
  class FailClosedViaFramework {

    @Test
    @DisplayName("无 recorder 时工具仍被框架分发，但工具内部 fail closed 拒绝探测")
    void failClosedWhenNoRecorderInContext() throws Exception {
      // 不 mock 探测 — 如果探测被执行会因 mock 未配置而失败

      AtomicInteger callCount = new AtomicInteger(0);
      List<Prompt> capturedPrompts = new CopyOnWriteArrayList<>();

      ChatModel fakeModel = createFakeModel(callCount, capturedPrompts, 2, "完成");

      ChatClient chatClient = ChatClient.builder(fakeModel).build();

      // 传入含无关键值的 ToolContext（无 recorder，但非空以通过框架校验）
      ToolContext emptyToolContext = new ToolContext(Map.of("dummy_key", "dummy_value"));

      String result = chatClient.prompt()
          .user("检查健康")
          .tools(dependencyHealthTools)
          .toolContext(emptyToolContext.getContext())
          .call()
          .content();

      // 模型仍被调用两次（框架分发了工具，工具返回了 fail-closed 结果）
      assertThat(callCount.get()).isEqualTo(2);
      assertThat(result).isEqualTo("完成");

      // 第二次 Prompt 中的 ToolResponseMessage 应包含 fail-closed 错误信息
      Prompt secondPrompt = capturedPrompts.get(1);
      ToolResponseMessage toolResponseMsg = secondPrompt.getInstructions().stream()
          .filter(m -> m instanceof ToolResponseMessage)
          .map(m -> (ToolResponseMessage) m)
          .findFirst()
          .orElseThrow();
      String responseData = toolResponseMsg.getResponses().getFirst().responseData();
      assertThat(responseData).contains("tool_unavailable");
      assertThat(responseData).contains("no_recorder_in_context");
    }
  }

  /**
   * 创建 fake ChatModel，返回 ToolCallingChatOptions 以触发框架工具分发链路。
   */
  private ChatModel createFakeModel(AtomicInteger callCount, List<Prompt> capturedPrompts,
                                     int returnTextAtCall, String finalText) {
    return new ChatModel() {
      @Override
      public ChatResponse call(Prompt prompt) {
        capturedPrompts.add(prompt);
        int count = callCount.incrementAndGet();
        if (count < returnTextAtCall) {
          return buildToolCallResponse("checkDependencyHealth");
        } else {
          return buildTextResponse(finalText);
        }
      }

      @Override
      public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
      }

      @Override
      public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(call(prompt));
      }
    };
  }

  // ========== 辅助验证方法 ==========

  /**
   * 验证实际探测执行了指定次数。
   * 通过 DataSource.getConnection() 的调用次数来判定（每次探测调用一次 getConnection）。
   */
  private void verifyProbesExecuted(int expectedTimes) throws Exception {
    // DataSource.getConnection() 在 probePostgresql() 中被调用
    // 由于 mockAllHealthy() 已配置，我们通过验证 mock 调用次数来确认
    // 注意：这里使用 atLeast 因为可能有并发或其他调用
    org.mockito.Mockito.verify(dataSource, org.mockito.Mockito.times(expectedTimes)).getConnection();
  }
}
