package interview.guide.modules.knowledgebase;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.tools.DependencyHealthTools;
import interview.guide.common.ai.tools.ToolCallRecord;
import interview.guide.modules.knowledgebase.model.MessageStatus;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SendMessageRequest;
import interview.guide.modules.knowledgebase.model.RetrievalResult;
import interview.guide.modules.knowledgebase.model.SourceReference;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService;
import interview.guide.modules.knowledgebase.service.RagChatSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RAG 聊天工具调用测试 — 验证 Demo 模式 tool_result SSE 事件与旧消息兼容。
 * 不调用真实 LLM，使用 Mockito mock 所有依赖。
 */
@DisplayName("RAG 聊天工具调用测试")
@ExtendWith(MockitoExtension.class)
class RagChatToolCallingTest {

  @Mock private RagChatSessionService sessionService;
  @Mock private KnowledgeBaseQueryService queryService;
  @Mock private LlmProviderRegistry llmProviderRegistry;
  @Mock private DependencyHealthTools dependencyHealthTools;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private RagChatController controller;

  private static final Long SESSION_ID = 1L;
  private static final Long MESSAGE_ID = 100L;

  @BeforeEach
  void setUp() {
    controller = new RagChatController(
        sessionService, queryService, llmProviderRegistry, objectMapper, dependencyHealthTools);
  }

  @AfterEach
  void tearDown() {
    dependencyHealthTools.clearRecorder();
  }

  // ========== 辅助方法 ==========

  private Document doc() {
    return new Document("基础设施健康状态良好");
  }

  private List<SourceReference> oneSource() {
    return List.of(new SourceReference(1L, "README.md", "健康检查", 0.9,
        "支付网关", "生产", null, null, null, null));
  }

  private RetrievalResult resultWith(Flux<String> contentStream, List<Document> docs) {
    return new RetrievalResult(contentStream, docs);
  }

  // ========== Demo 模式 tool_result SSE 事件 ==========

  @Nested
  @DisplayName("Demo 模式 tool_result SSE 事件")
  class DemoToolResult {

    @Test
    @DisplayName("Demo 模式 + 健康关键词 → SSE 流包含 tool_result 事件且 demo=true")
    void demoModeHealthKeywordProducesToolResultEvent() throws Exception {
      String question = "检查依赖健康状态";
      when(llmProviderRegistry.isDemoProfileActive()).thenReturn(true);
      when(sessionService.prepareStreamMessage(SESSION_ID, question)).thenReturn(MESSAGE_ID);
      when(sessionService.getStreamAnswer(SESSION_ID, question))
          .thenReturn(resultWith(Flux.just("所有组件正常"), List.of(doc())));
      when(sessionService.buildSourceReferences(anyList())).thenReturn(oneSource());
      when(queryService.resolveFinalStatus(anyString(), any())).thenReturn(MessageStatus.COMPLETED);

      List<ServerSentEvent<String>> events = controller
          .sendMessageStream(SESSION_ID, new SendMessageRequest(question))
          .collectList().block();

      assertThat(events).isNotNull();

      // 提取 tool_result 事件
      List<ServerSentEvent<String>> toolEvents = events.stream()
          .filter(e -> "tool_result".equals(e.event()))
          .toList();
      assertThat(toolEvents).hasSize(1);

      // 验证 tool_result 内容
      String toolData = toolEvents.getFirst().data();
      assertThat(toolData).isNotNull();
      ToolCallRecord record = objectMapper.readValue(toolData, ToolCallRecord.class);
      assertThat(record.toolName()).isEqualTo("checkDependencyHealth");
      assertThat(record.status()).isEqualTo("SUCCESS");
      assertThat(record.demo()).isTrue();
      assertThat(record.durationMs()).isEqualTo(0);
    }

    @Test
    @DisplayName("Demo 模式 + 健康关键词 → 持久化包含 tool_calls_json 且不为空")
    void demoModeHealthKeywordPersistsToolCallsJson() throws Exception {
      String question = "系统依赖状态如何";
      when(llmProviderRegistry.isDemoProfileActive()).thenReturn(true);
      when(sessionService.prepareStreamMessage(SESSION_ID, question)).thenReturn(MESSAGE_ID);
      when(sessionService.getStreamAnswer(SESSION_ID, question))
          .thenReturn(resultWith(Flux.just("所有组件正常"), List.of(doc())));
      when(sessionService.buildSourceReferences(anyList())).thenReturn(oneSource());
      when(queryService.resolveFinalStatus(anyString(), any())).thenReturn(MessageStatus.COMPLETED);

      controller.sendMessageStream(SESSION_ID, new SendMessageRequest(question))
          .collectList().block();

      // 验证持久化调用包含 toolCallsJson 参数
      ArgumentCaptor<String> toolCallsCaptor = ArgumentCaptor.forClass(String.class);
      verify(sessionService).completeStreamMessage(
          eq(MESSAGE_ID), anyString(), eq(MessageStatus.COMPLETED),
          anyString(), any(), toolCallsCaptor.capture());

      String toolCallsJson = toolCallsCaptor.getValue();
      assertThat(toolCallsJson).isNotNull();
      assertThat(toolCallsJson).isNotEmpty();
      assertThat(toolCallsJson).isNotEqualTo("[]");
      assertThat(toolCallsJson).contains("checkDependencyHealth");
      assertThat(toolCallsJson).contains("\"demo\":true");
    }

    @Test
    @DisplayName("非 Demo 模式 + 健康关键词 → 无 tool_result 事件（无真实工具调用）")
    void nonDemoModeNoToolResult() {
      String question = "检查依赖健康状态";
      when(llmProviderRegistry.isDemoProfileActive()).thenReturn(false);
      when(sessionService.prepareStreamMessage(SESSION_ID, question)).thenReturn(MESSAGE_ID);
      when(sessionService.getStreamAnswer(SESSION_ID, question))
          .thenReturn(resultWith(Flux.just("回答内容"), List.of(doc())));
      when(sessionService.buildSourceReferences(anyList())).thenReturn(oneSource());
      when(queryService.resolveFinalStatus(anyString(), any())).thenReturn(MessageStatus.COMPLETED);

      List<ServerSentEvent<String>> events = controller
          .sendMessageStream(SESSION_ID, new SendMessageRequest(question))
          .collectList().block();

      assertThat(events).isNotNull();
      List<ServerSentEvent<String>> toolEvents = events.stream()
          .filter(e -> "tool_result".equals(e.event()))
          .toList();
      assertThat(toolEvents).isEmpty();
    }

    @Test
    @DisplayName("Demo 模式 + 无健康关键词 → 无 tool_result 事件")
    void demoModeNoHealthKeywordNoToolResult() {
      String question = "项目端口是多少";
      when(llmProviderRegistry.isDemoProfileActive()).thenReturn(true);
      when(sessionService.prepareStreamMessage(SESSION_ID, question)).thenReturn(MESSAGE_ID);
      when(sessionService.getStreamAnswer(SESSION_ID, question))
          .thenReturn(resultWith(Flux.just("端口是8080"), List.of(doc())));
      when(sessionService.buildSourceReferences(anyList())).thenReturn(oneSource());
      when(queryService.resolveFinalStatus(anyString(), any())).thenReturn(MessageStatus.COMPLETED);

      List<ServerSentEvent<String>> events = controller
          .sendMessageStream(SESSION_ID, new SendMessageRequest(question))
          .collectList().block();

      assertThat(events).isNotNull();
      List<ServerSentEvent<String>> toolEvents = events.stream()
          .filter(e -> "tool_result".equals(e.event()))
          .toList();
      assertThat(toolEvents).isEmpty();
    }
  }

  // ========== 事件顺序 ==========

  @Nested
  @DisplayName("事件顺序")
  class EventOrder {

    @Test
    @DisplayName("Demo 模式下事件顺序为 data → tool_result → sources → done")
    void demoModeEventOrder() {
      String question = "健康检查";
      when(llmProviderRegistry.isDemoProfileActive()).thenReturn(true);
      when(sessionService.prepareStreamMessage(SESSION_ID, question)).thenReturn(MESSAGE_ID);
      when(sessionService.getStreamAnswer(SESSION_ID, question))
          .thenReturn(resultWith(Flux.just("回答"), List.of(doc())));
      when(sessionService.buildSourceReferences(anyList())).thenReturn(oneSource());
      when(queryService.resolveFinalStatus(anyString(), any())).thenReturn(MessageStatus.COMPLETED);

      List<ServerSentEvent<String>> events = controller
          .sendMessageStream(SESSION_ID, new SendMessageRequest(question))
          .collectList().block();

      assertThat(events).isNotNull();
      List<String> eventNames = events.stream()
          .map(ServerSentEvent::event)
          .toList();

      // data → tool_result → sources → done
      assertThat(eventNames).containsExactly("data", "tool_result", "sources", "done");
    }
  }

  // ========== 旧消息兼容 ==========

  @Nested
  @DisplayName("旧消息兼容")
  class LegacyCompatibility {

    @Test
    @DisplayName("非 Demo 模式无工具调用时，持久化 toolCallsJson 为空数组")
    void noToolCallsPersistedAsEmptyArray() {
      String question = "项目端口是多少";
      when(llmProviderRegistry.isDemoProfileActive()).thenReturn(false);
      when(sessionService.prepareStreamMessage(SESSION_ID, question)).thenReturn(MESSAGE_ID);
      when(sessionService.getStreamAnswer(SESSION_ID, question))
          .thenReturn(resultWith(Flux.just("端口是8080"), List.of(doc())));
      when(sessionService.buildSourceReferences(anyList())).thenReturn(oneSource());
      when(queryService.resolveFinalStatus(anyString(), any())).thenReturn(MessageStatus.COMPLETED);

      controller.sendMessageStream(SESSION_ID, new SendMessageRequest(question))
          .collectList().block();

      // 验证持久化调用包含 toolCallsJson 参数为空数组
      ArgumentCaptor<String> toolCallsCaptor = ArgumentCaptor.forClass(String.class);
      verify(sessionService).completeStreamMessage(
          eq(MESSAGE_ID), anyString(), eq(MessageStatus.COMPLETED),
          anyString(), any(), toolCallsCaptor.capture());

      assertThat(toolCallsCaptor.getValue()).isEqualTo("[]");
    }
  }
}
