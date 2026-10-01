package interview.guide.modules.knowledgebase;

import interview.guide.modules.knowledgebase.model.MessageStatus;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SendMessageRequest;
import interview.guide.modules.knowledgebase.model.RetrievalResult;
import interview.guide.modules.knowledgebase.model.SourceReference;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService;
import interview.guide.modules.knowledgebase.service.RagChatSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RagChatController 流式接口测试 — 聚焦服务端最终状态与事件顺序。
 * 不调用真实 LLM，检索/模型输出用 Flux 直接构造。
 */
@DisplayName("RAG 聊天流式控制器测试")
@ExtendWith(MockitoExtension.class)
class RagChatControllerTest {

  @Mock
  private RagChatSessionService sessionService;
  @Mock
  private KnowledgeBaseQueryService queryService;
  @Mock
  private KnowledgeBaseRepository knowledgeBaseRepository;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private RagChatController controller;

  private static final Long SESSION_ID = 1L;
  private static final Long MESSAGE_ID = 100L;
  private static final String QUESTION = "项目后端端口是多少？";

  @BeforeEach
  void setUp() {
    controller = new RagChatController(
        sessionService, queryService, knowledgeBaseRepository, objectMapper);
  }

  // ========== 辅助方法 ==========

  private Document doc() {
    return new Document("项目的后端端口是 8080");
  }

  private List<SourceReference> oneSource() {
    return List.of(new SourceReference(1L, "README.md", "后端端口 8080", 0.9));
  }

  private RetrievalResult resultWith(Flux<String> contentStream, List<Document> docs) {
    return new RetrievalResult(contentStream, docs);
  }

  private SendMessageRequest request() {
    return new SendMessageRequest(QUESTION);
  }

  private String eventNames(List<ServerSentEvent<String>> events) {
    return events.stream().map(ServerSentEvent::event).reduce((a, b) -> a + "," + b).orElse("");
  }

  // ========== 事件顺序与成功持久化 ==========

  @Nested
  @DisplayName("成功路径")
  class SuccessPath {

    @Test
    @DisplayName("done 在持久化成功之后发出，事件顺序为 data...sources...done")
    void doneEmittedAfterSuccessfulPersistence() {
      List<Document> docs = List.of(doc());
      when(sessionService.prepareStreamMessage(SESSION_ID, QUESTION)).thenReturn(MESSAGE_ID);
      when(sessionService.getStreamAnswer(SESSION_ID, QUESTION))
          .thenReturn(resultWith(Flux.just("项目的后端端口", "是 8080"), docs));
      when(queryService.buildSourceReferences(anyList(), eq(knowledgeBaseRepository))).thenReturn(oneSource());
      when(queryService.resolveFinalStatus("项目的后端端口是 8080", docs))
          .thenReturn(MessageStatus.COMPLETED);

      List<ServerSentEvent<String>> events =
          controller.sendMessageStream(SESSION_ID, request()).collectList().block();

      assertThat(events).isNotNull();
      assertThat(eventNames(events)).isEqualTo("data,data,sources,done");

      // done 携带最终状态，供下一项前端任务使用
      ServerSentEvent<String> done = events.get(events.size() - 1);
      assertThat(done.event()).isEqualTo("done");
      assertThat(done.data()).contains("COMPLETED");

      // sources 事件保留现有契约：JSON 数组
      ServerSentEvent<String> sources = events.get(events.size() - 2);
      assertThat(sources.event()).isEqualTo("sources");
      assertThat(sources.data()).startsWith("[");

      // 持久化以 COMPLETED 且保留来源写入
      verify(sessionService, times(1))
          .completeStreamMessage(eq(MESSAGE_ID), eq("项目的后端端口是 8080"),
              eq(MessageStatus.COMPLETED), anyString());
    }

    @Test
    @DisplayName("持久化失败时不向客户端宣告成功：不发 done，且只尝试写入一次")
    void persistenceFailureDoesNotAnnounceSuccess() {
      List<Document> docs = List.of(doc());
      when(sessionService.prepareStreamMessage(SESSION_ID, QUESTION)).thenReturn(MESSAGE_ID);
      when(sessionService.getStreamAnswer(SESSION_ID, QUESTION))
          .thenReturn(resultWith(Flux.just("部分回答"), docs));
      when(queryService.buildSourceReferences(anyList(), eq(knowledgeBaseRepository))).thenReturn(oneSource());
      when(queryService.resolveFinalStatus(anyString(), any())).thenReturn(MessageStatus.COMPLETED);
      doThrow(new RuntimeException("DB down"))
          .when(sessionService).completeStreamMessage(anyLong(), anyString(), any(), anyString());

      StepVerifier.create(controller.sendMessageStream(SESSION_ID, request()))
          .expectNextMatches(e -> "data".equals(e.event()))
          // 持久化失败 → 直接以错误终止，绝不再发 sources/done
          .expectError()
          .verify();

      // 单次护栏：失败的写入不会被 doOnError 二次覆盖为 MODEL_FAILED
      verify(sessionService, times(1))
          .completeStreamMessage(anyLong(), anyString(), any(), anyString());
    }
  }

  // ========== 检索到文档但模型输出"无结果" ==========

  @Test
  @DisplayName("检索到文档却输出无结果文本，不保存为有依据的 COMPLETED")
  void documentsButRefusalStoredAsNoResults() {
    List<Document> docs = List.of(doc());
    String refusal = "抱歉，在选定的知识库中未检索到相关信息。";
    when(sessionService.prepareStreamMessage(SESSION_ID, QUESTION)).thenReturn(MESSAGE_ID);
    when(sessionService.getStreamAnswer(SESSION_ID, QUESTION))
        .thenReturn(resultWith(Flux.just(refusal), docs));
    when(queryService.buildSourceReferences(anyList(), eq(knowledgeBaseRepository))).thenReturn(oneSource());
    // 真实判定逻辑在 Service 层测试；此处按"文档存在但拒答"契约桩为 NO_RESULTS
    when(queryService.resolveFinalStatus(refusal, docs)).thenReturn(MessageStatus.NO_RESULTS);

    List<ServerSentEvent<String>> events =
        controller.sendMessageStream(SESSION_ID, request()).collectList().block();

    assertThat(eventNames(events)).isEqualTo("data,sources,done");

    // 无依据的拒答不保存来源：持久化与 sources 事件均为空数组
    verify(sessionService, times(1))
        .completeStreamMessage(eq(MESSAGE_ID), eq(refusal), eq(MessageStatus.NO_RESULTS), eq("[]"));

    ServerSentEvent<String> sources = events.get(1);
    assertThat(sources.event()).isEqualTo("sources");
    assertThat(sources.data()).isEqualTo("[]");

    ServerSentEvent<String> done = events.get(2);
    assertThat(done.data()).contains("NO_RESULTS");
  }

  // ========== 模型错误 ==========

  @Test
  @DisplayName("内容流出错按 MODEL_FAILED 落库一次，且不发 done")
  void modelErrorPersistsModelFailedOnce() {
    List<Document> docs = List.of(doc());
    when(sessionService.prepareStreamMessage(SESSION_ID, QUESTION)).thenReturn(MESSAGE_ID);
    when(sessionService.getStreamAnswer(SESSION_ID, QUESTION))
        .thenReturn(resultWith(Flux.error(new RuntimeException("LLM服务不可用")), docs));
    when(queryService.buildSourceReferences(anyList(), eq(knowledgeBaseRepository))).thenReturn(oneSource());

    StepVerifier.create(controller.sendMessageStream(SESSION_ID, request()))
        .expectError()
        .verify();

    verify(sessionService, times(1))
        .completeStreamMessage(eq(MESSAGE_ID), anyString(), eq(MessageStatus.MODEL_FAILED), anyString());
    // 错误路径不应触发最终状态判定（内容流从未正常完成）
    verify(queryService, never()).resolveFinalStatus(anyString(), any());
  }

  // ========== 客户端取消 ==========

  @Test
  @DisplayName("客户端中途取消按 CLIENT_DISCONNECTED 落库一次，且不与其它终止态重复写入")
  void cancelPersistsClientDisconnectedOnce() {
    List<Document> docs = List.of(doc());
    when(sessionService.prepareStreamMessage(SESSION_ID, QUESTION)).thenReturn(MESSAGE_ID);
    when(sessionService.getStreamAnswer(SESSION_ID, QUESTION))
        .thenReturn(resultWith(Flux.just("已输出的部分").concatWith(Flux.never()), docs));
    when(queryService.buildSourceReferences(anyList(), eq(knowledgeBaseRepository))).thenReturn(oneSource());

    StepVerifier.create(controller.sendMessageStream(SESSION_ID, request()))
        .expectNextMatches(e -> "data".equals(e.event()))
        .thenCancel()
        .verify();

    verify(sessionService, times(1))
        .completeStreamMessage(eq(MESSAGE_ID), eq("已输出的部分"),
            eq(MessageStatus.CLIENT_DISCONNECTED), anyString());
  }
}
