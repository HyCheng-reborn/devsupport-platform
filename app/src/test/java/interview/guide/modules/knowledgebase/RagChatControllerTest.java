package interview.guide.modules.knowledgebase;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.knowledgebase.model.MessageStatus;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SendMessageRequest;
import interview.guide.modules.knowledgebase.model.RetrievalResult;
import interview.guide.modules.knowledgebase.model.SourceReference;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseCountService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseListService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryProperties;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import interview.guide.modules.knowledgebase.service.RagChatSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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

  private final ObjectMapper objectMapper = new ObjectMapper();
  private RagChatController controller;

  private static final Long SESSION_ID = 1L;
  private static final Long MESSAGE_ID = 100L;
  private static final String QUESTION = "项目后端端口是多少？";

  @BeforeEach
  void setUp() {
    controller = new RagChatController(
        sessionService, queryService, objectMapper);
  }

  // ========== 辅助方法 ==========

  private Document doc() {
    return new Document("项目的后端端口是 8080");
  }

  private List<SourceReference> oneSource() {
    return List.of(new SourceReference(1L, "README.md", "后端端口 8080", 0.9, "支付网关", "生产", null, null, null, null));
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
      when(sessionService.buildSourceReferences(anyList())).thenReturn(oneSource());
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
              eq(MessageStatus.COMPLETED), anyString(), any());
    }

    @Test
    @DisplayName("收到 sources/done 事件的当下即断言 completeStreamMessage 已成功，且有效回答保留非空来源")
    void persistenceVerifiedAtMomentSourcesAndDoneArrive() {
      List<Document> docs = List.of(doc());
      when(sessionService.prepareStreamMessage(SESSION_ID, QUESTION)).thenReturn(MESSAGE_ID);
      when(sessionService.getStreamAnswer(SESSION_ID, QUESTION))
          .thenReturn(resultWith(Flux.just("项目的后端端口是 8080"), docs));
      when(sessionService.buildSourceReferences(anyList())).thenReturn(oneSource());
      when(queryService.resolveFinalStatus("项目的后端端口是 8080", docs))
          .thenReturn(MessageStatus.COMPLETED);

      AtomicBoolean persistedAtSources = new AtomicBoolean(false);
      AtomicBoolean persistedAtDone = new AtomicBoolean(false);

      StepVerifier.create(controller.sendMessageStream(SESSION_ID, request()))
          .expectNextMatches(e -> "data".equals(e.event()))
          .expectNextMatches(e -> {
            if (!"sources".equals(e.event())) {
              return false;
            }
            // 在收到 sources 的瞬间断言：持久化必须已成功完成，且有效回答保留真实来源（非空数组）
            ArgumentCaptor<String> captured = ArgumentCaptor.forClass(String.class);
            verify(sessionService, times(1)).completeStreamMessage(
                eq(MESSAGE_ID), eq("项目的后端端口是 8080"),
                eq(MessageStatus.COMPLETED), captured.capture(), any());
            assertThat(captured.getValue()).contains("README.md");
            persistedAtSources.set(true);
            return e.data() != null && e.data().contains("README.md");
          })
          .expectNextMatches(e -> {
            if (!"done".equals(e.event())) {
              return false;
            }
            // 在收到 done 的瞬间再次确认持久化先于事件完成
            verify(sessionService, times(1)).completeStreamMessage(
                anyLong(), anyString(), eq(MessageStatus.COMPLETED), anyString(), any());
            persistedAtDone.set(true);
            return e.data().contains("COMPLETED");
          })
          .verifyComplete();

      assertThat(persistedAtSources).isTrue();
      assertThat(persistedAtDone).isTrue();
    }

    @Test
    @DisplayName("Controller 协作：service/environment 同时出现在 sources 事件 JSON 与持久化 sourcesJson，且保持 data→持久化成功→sources→done 顺序")
    void sourceTagsAppearInSourcesEventAndPersistedJson() {
      List<Document> docs = List.of(doc());
      when(sessionService.prepareStreamMessage(SESSION_ID, QUESTION)).thenReturn(MESSAGE_ID);
      when(sessionService.getStreamAnswer(SESSION_ID, QUESTION))
          .thenReturn(resultWith(Flux.just("项目的后端端口是 8080"), docs));
      // oneSource() 携带提问时刻标签：service=支付网关、environment=生产
      when(sessionService.buildSourceReferences(anyList())).thenReturn(oneSource());
      when(queryService.resolveFinalStatus("项目的后端端口是 8080", docs))
          .thenReturn(MessageStatus.COMPLETED);

      AtomicBoolean assertedAtSources = new AtomicBoolean(false);

      StepVerifier.create(controller.sendMessageStream(SESSION_ID, request()))
          // 1) 先发 data chunk
          .expectNextMatches(e -> "data".equals(e.event()))
          // 2) 收到 sources 的瞬间：持久化必须已成功，且两处都含标签值与字段名
          .expectNextMatches(e -> {
            if (!"sources".equals(e.event())) {
              return false;
            }
            ArgumentCaptor<String> captured = ArgumentCaptor.forClass(String.class);
            verify(sessionService, times(1)).completeStreamMessage(
                eq(MESSAGE_ID), eq("项目的后端端口是 8080"),
                eq(MessageStatus.COMPLETED), captured.capture(), any());
            // 持久化的 sourcesJson 含标签值与字段名
            assertThat(captured.getValue())
                .contains("\"service\"").contains("支付网关")
                .contains("\"environment\"").contains("生产");
            // sources 事件 data 同样含标签值与字段名
            assertThat(e.data())
                .contains("\"service\"").contains("支付网关")
                .contains("\"environment\"").contains("生产");
            assertedAtSources.set(true);
            return true;
          })
          // 3) done 在 sources 之后发出，携带最终状态
          .expectNextMatches(e -> "done".equals(e.event()) && e.data().contains("COMPLETED"))
          .verifyComplete();

      assertThat(assertedAtSources).isTrue();
    }

    @Test
    @DisplayName("Controller 协作：用真实 resolveFinalStatus 判定正常排查答案，落库 COMPLETED 且 sources 非空")
    void normalAnswerWithRealStatusJudgingPersistsCompletedWithSources() throws Exception {
      // 不 mock 最终状态判定：走真实 resolveFinalStatus，验证 P1 回归修复贯通 Controller。
      KnowledgeBaseQueryProperties props = new KnowledgeBaseQueryProperties();
      props.getRewrite().setEnabled(false);
      KnowledgeBaseQueryService realQueryService = new KnowledgeBaseQueryService(
          mock(LlmProviderRegistry.class), mock(KnowledgeBaseVectorService.class),
          mock(KnowledgeBaseListService.class), mock(KnowledgeBaseCountService.class),
          props, new DefaultResourceLoader());
      RagChatController realController =
          new RagChatController(sessionService, realQueryService, objectMapper);

      List<Document> docs = List.of(doc());
      // 旧版（1ba2a57）会因起始句含"找不到"而误判 NO_RESULTS；修复后应为 COMPLETED。
      String answer = "找不到配置文件时，请先检查工作目录及挂载路径，"
          + "并将配置放在应用指定的位置，随后重新启动服务。";
      when(sessionService.prepareStreamMessage(SESSION_ID, QUESTION)).thenReturn(MESSAGE_ID);
      when(sessionService.getStreamAnswer(SESSION_ID, QUESTION))
          .thenReturn(resultWith(Flux.just(answer), docs));
      when(sessionService.buildSourceReferences(anyList())).thenReturn(oneSource());

      List<ServerSentEvent<String>> events =
          realController.sendMessageStream(SESSION_ID, request()).collectList().block();

      ArgumentCaptor<MessageStatus> statusCap = ArgumentCaptor.forClass(MessageStatus.class);
      ArgumentCaptor<String> sourcesCap = ArgumentCaptor.forClass(String.class);
      verify(sessionService).completeStreamMessage(
          eq(MESSAGE_ID), eq(answer), statusCap.capture(), sourcesCap.capture(), any());
      assertThat(statusCap.getValue()).isEqualTo(MessageStatus.COMPLETED);
      assertThat(sourcesCap.getValue()).contains("README.md").isNotEqualTo("[]");

      ServerSentEvent<String> sources = events.get(events.size() - 2);
      assertThat(sources.event()).isEqualTo("sources");
      assertThat(sources.data()).contains("README.md");
      ServerSentEvent<String> done = events.get(events.size() - 1);
      assertThat(done.data()).contains("COMPLETED");
    }

    @Test
    @DisplayName("Controller 协作：用真实 resolveFinalStatus 判定明确拒答，落库 NO_RESULTS 且 sources 清空为 []")
    void realRefusalWithRealStatusJudgingClearsSources() throws Exception {
      // 同样不 mock 最终状态：走真实 resolveFinalStatus，验证真实拒答仍会清空来源。
      KnowledgeBaseQueryProperties props = new KnowledgeBaseQueryProperties();
      props.getRewrite().setEnabled(false);
      KnowledgeBaseQueryService realQueryService = new KnowledgeBaseQueryService(
          mock(LlmProviderRegistry.class), mock(KnowledgeBaseVectorService.class),
          mock(KnowledgeBaseListService.class), mock(KnowledgeBaseCountService.class),
          props, new DefaultResourceLoader());
      RagChatController realController =
          new RagChatController(sessionService, realQueryService, objectMapper);

      List<Document> docs = List.of(doc());
      // 非固定模板、但明确“无法根据现有资料回答”，有文档时也应判为拒答并清空来源。
      String refusal = "无法根据现有资料回答您的问题，请补充更具体的关键词。";
      when(sessionService.prepareStreamMessage(SESSION_ID, QUESTION)).thenReturn(MESSAGE_ID);
      when(sessionService.getStreamAnswer(SESSION_ID, QUESTION))
          .thenReturn(resultWith(Flux.just(refusal), docs));
      when(sessionService.buildSourceReferences(anyList())).thenReturn(oneSource());

      List<ServerSentEvent<String>> events =
          realController.sendMessageStream(SESSION_ID, request()).collectList().block();

      ArgumentCaptor<MessageStatus> statusCap = ArgumentCaptor.forClass(MessageStatus.class);
      ArgumentCaptor<String> sourcesCap = ArgumentCaptor.forClass(String.class);
      verify(sessionService).completeStreamMessage(
          eq(MESSAGE_ID), eq(refusal), statusCap.capture(), sourcesCap.capture(), any());
      assertThat(statusCap.getValue()).isEqualTo(MessageStatus.NO_RESULTS);
      assertThat(sourcesCap.getValue()).isEqualTo("[]");

      ServerSentEvent<String> sources = events.get(events.size() - 2);
      assertThat(sources.event()).isEqualTo("sources");
      assertThat(sources.data()).isEqualTo("[]");
      ServerSentEvent<String> done = events.get(events.size() - 1);
      assertThat(done.data()).contains("NO_RESULTS");
    }

    @Test
    @DisplayName("Controller 协作：引用为资料名、引用外真实拒答，用真实 resolveFinalStatus 落库 NO_RESULTS 且 sources 为 []")
    void quotedResourceNameRefusalWithRealStatusJudgingClearsSources() throws Exception {
      // 不 mock 最终状态：走真实 resolveFinalStatus，验证“无法根据‘部署说明’回答”不被引用占位切断。
      KnowledgeBaseQueryProperties props = new KnowledgeBaseQueryProperties();
      props.getRewrite().setEnabled(false);
      KnowledgeBaseQueryService realQueryService = new KnowledgeBaseQueryService(
          mock(LlmProviderRegistry.class), mock(KnowledgeBaseVectorService.class),
          mock(KnowledgeBaseListService.class), mock(KnowledgeBaseCountService.class),
          props, new DefaultResourceLoader());
      RagChatController realController =
          new RagChatController(sessionService, realQueryService, objectMapper);

      List<Document> docs = List.of(doc());
      String refusal = "抱歉，无法根据“部署说明”回答您的问题，请补充资料。";
      when(sessionService.prepareStreamMessage(SESSION_ID, QUESTION)).thenReturn(MESSAGE_ID);
      when(sessionService.getStreamAnswer(SESSION_ID, QUESTION))
          .thenReturn(resultWith(Flux.just(refusal), docs));
      when(sessionService.buildSourceReferences(anyList())).thenReturn(oneSource());

      List<ServerSentEvent<String>> events =
          realController.sendMessageStream(SESSION_ID, request()).collectList().block();

      ArgumentCaptor<MessageStatus> statusCap = ArgumentCaptor.forClass(MessageStatus.class);
      ArgumentCaptor<String> sourcesCap = ArgumentCaptor.forClass(String.class);
      verify(sessionService).completeStreamMessage(
          eq(MESSAGE_ID), eq(refusal), statusCap.capture(), sourcesCap.capture(), any());
      assertThat(statusCap.getValue()).isEqualTo(MessageStatus.NO_RESULTS);
      assertThat(sourcesCap.getValue()).isEqualTo("[]");

      ServerSentEvent<String> sources = events.get(events.size() - 2);
      assertThat(sources.event()).isEqualTo("sources");
      assertThat(sources.data()).isEqualTo("[]");
      ServerSentEvent<String> done = events.get(events.size() - 1);
      assertThat(done.data()).contains("NO_RESULTS");
    }

    @Test
    @DisplayName("持久化失败时不向客户端宣告成功：不发 done，且只尝试写入一次")
    void persistenceFailureDoesNotAnnounceSuccess() {
      List<Document> docs = List.of(doc());
      when(sessionService.prepareStreamMessage(SESSION_ID, QUESTION)).thenReturn(MESSAGE_ID);
      when(sessionService.getStreamAnswer(SESSION_ID, QUESTION))
          .thenReturn(resultWith(Flux.just("部分回答"), docs));
      when(sessionService.buildSourceReferences(anyList())).thenReturn(oneSource());
      when(queryService.resolveFinalStatus(anyString(), any())).thenReturn(MessageStatus.COMPLETED);
      doThrow(new RuntimeException("DB down"))
          .when(sessionService).completeStreamMessage(anyLong(), anyString(), any(), anyString(), any());

      StepVerifier.create(controller.sendMessageStream(SESSION_ID, request()))
          .expectNextMatches(e -> "data".equals(e.event()))
          // 持久化失败 → 直接以错误终止，绝不再发 sources/done
          .expectError()
          .verify();

      // 单次护栏：失败的写入不会被 doOnError 二次覆盖为 MODEL_FAILED
      verify(sessionService, times(1))
          .completeStreamMessage(anyLong(), anyString(), any(), anyString(), any());
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
    when(sessionService.buildSourceReferences(anyList())).thenReturn(oneSource());
    // 真实判定逻辑在 Service 层测试；此处按"文档存在但拒答"契约桩为 NO_RESULTS
    when(queryService.resolveFinalStatus(refusal, docs)).thenReturn(MessageStatus.NO_RESULTS);

    List<ServerSentEvent<String>> events =
        controller.sendMessageStream(SESSION_ID, request()).collectList().block();

    assertThat(eventNames(events)).isEqualTo("data,sources,done");

    // 无依据的拒答不保存来源：持久化与 sources 事件均为空数组
    verify(sessionService, times(1))
        .completeStreamMessage(eq(MESSAGE_ID), eq(refusal), eq(MessageStatus.NO_RESULTS), eq("[]"), any());

    ServerSentEvent<String> sources = events.get(1);
    assertThat(sources.event()).isEqualTo("sources");
    assertThat(sources.data()).isEqualTo("[]");

    ServerSentEvent<String> done = events.get(2);
    assertThat(done.data()).contains("NO_RESULTS");
  }

  // ========== 骨架格式 SSE ==========

  @Test
  @DisplayName("骨架格式回答 SSE 传输：data 事件包含骨架章节标题，缺失信息章节为空占位时状态为 COMPLETED")
  void skeletonFormatSseEventsContainSkeletonHeaders() {
    List<Document> docs = List.of(doc());
    String skeletonAnswer = "## 问题理解\n"
        + "端口配置问题。\n\n"
        + "## 可能原因\n"
        + "- 默认端口未修改\n"
        + "- 环境变量未设置\n\n"
        + "## 解决方案\n"
        + "将端口改为 8080。\n\n"
        + "## 缺失信息\n"
        + "无";
    when(sessionService.prepareStreamMessage(SESSION_ID, QUESTION)).thenReturn(MESSAGE_ID);
    when(sessionService.getStreamAnswer(SESSION_ID, QUESTION))
        .thenReturn(resultWith(Flux.just(skeletonAnswer), docs));
    when(sessionService.buildSourceReferences(anyList())).thenReturn(oneSource());
    // "## 缺失信息\n无" 是空占位，不构成 INSUFFICIENT_INFO；有实质排查步骤应为 COMPLETED
    when(queryService.resolveFinalStatus(skeletonAnswer, docs))
        .thenReturn(MessageStatus.COMPLETED);

    List<ServerSentEvent<String>> events =
        controller.sendMessageStream(SESSION_ID, request()).collectList().block();

    assertThat(events).isNotNull();

    // 收集所有 data 事件的文本
    String dataContent = events.stream()
        .filter(e -> "data".equals(e.event()))
        .map(ServerSentEvent::data)
        .reduce("", (a, b) -> a + b);

    // data 事件包含骨架章节标题
    assertThat(dataContent).contains("## 问题理解");
    assertThat(dataContent).contains("## 可能原因");
    assertThat(dataContent).contains("## 解决方案");
    assertThat(dataContent).contains("## 缺失信息");

    // done 事件携带 COMPLETED 状态（缺失信息章节为空占位「无」）
    ServerSentEvent<String> done = events.get(events.size() - 1);
    assertThat(done.event()).isEqualTo("done");
    assertThat(done.data()).contains("COMPLETED");
  }

  // ========== 模型错误 ==========

  @Test
  @DisplayName("内容流出错按 MODEL_FAILED 落库一次，且不发 done")
  void modelErrorPersistsModelFailedOnce() {
    List<Document> docs = List.of(doc());
    when(sessionService.prepareStreamMessage(SESSION_ID, QUESTION)).thenReturn(MESSAGE_ID);
    when(sessionService.getStreamAnswer(SESSION_ID, QUESTION))
        .thenReturn(resultWith(Flux.error(new RuntimeException("LLM服务不可用")), docs));
    when(sessionService.buildSourceReferences(anyList())).thenReturn(oneSource());

    StepVerifier.create(controller.sendMessageStream(SESSION_ID, request()))
        .expectError()
        .verify();

    verify(sessionService, times(1))
        .completeStreamMessage(eq(MESSAGE_ID), anyString(), eq(MessageStatus.MODEL_FAILED), anyString(), any());
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
    when(sessionService.buildSourceReferences(anyList())).thenReturn(oneSource());

    StepVerifier.create(controller.sendMessageStream(SESSION_ID, request()))
        .expectNextMatches(e -> "data".equals(e.event()))
        .thenCancel()
        .verify();

    verify(sessionService, times(1))
        .completeStreamMessage(eq(MESSAGE_ID), eq("已输出的部分"),
            eq(MessageStatus.CLIENT_DISCONNECTED), anyString(), any());
  }
}
