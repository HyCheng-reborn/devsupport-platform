package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.knowledgebase.model.MessageStatus;
import interview.guide.modules.knowledgebase.model.RetrievalResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;
import org.springframework.core.io.DefaultResourceLoader;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * KnowledgeBaseQueryService 单元测试
 * 覆盖 answerQuestionStream 契约与 resolveFinalStatus 最终状态判定
 */
@DisplayName("知识库查询服务测试")
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseQueryServiceTest {

  @Mock
  private LlmProviderRegistry llmProviderRegistry;
  @Mock
  private KnowledgeBaseVectorService vectorService;
  @Mock
  private KnowledgeBaseListService listService;
  @Mock
  private KnowledgeBaseCountService countService;

  private KnowledgeBaseQueryService queryService;

  @BeforeEach
  void setUp() throws Exception {
    KnowledgeBaseQueryProperties properties = new KnowledgeBaseQueryProperties();
    // 禁用 query rewrite，简化 mock 链路
    properties.getRewrite().setEnabled(false);

    queryService = new KnowledgeBaseQueryService(
        llmProviderRegistry,
        vectorService,
        listService,
        countService,
        properties,
        new DefaultResourceLoader()
    );
  }

  // ========== 辅助方法 ==========

  /**
   * 构建带 kb_id metadata 和 score 的 Document
   */
  private Document createDoc(String text, Long kbId, Double score) {
    Map<String, Object> metadata = new HashMap<>();
    if (kbId != null) {
      metadata.put("kb_id", kbId);
    }
    Document doc = new Document(text, metadata);
    if (score != null) {
      setScore(doc, score);
    }
    return doc;
  }

  /**
   * 通过反射设置 Document 的 score（Spring AI 2.0.0 未暴露 setScore 方法）
   */
  private static void setScore(Document doc, Double score) {
    try {
      Field scoreField = Document.class.getDeclaredField("score");
      scoreField.setAccessible(true);
      scoreField.set(doc, score);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException("Failed to set Document score via reflection", e);
    }
  }

  /**
   * Mock ChatClient 流式调用链，返回指定的 Flux<String>
   */
  private void mockStreamChain(Flux<String> contentFlux) {
    ChatClient chatClient = mock(ChatClient.class);
    ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
    ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);

    when(llmProviderRegistry.getDefaultChatClient()).thenReturn(chatClient);
    when(chatClient.prompt()).thenReturn(requestSpec);
    lenient().when(requestSpec.system(anyString())).thenReturn(requestSpec);
    lenient().when(requestSpec.user(anyString())).thenReturn(requestSpec);
    lenient().when(requestSpec.messages(anyList())).thenReturn(requestSpec);
    when(requestSpec.stream()).thenReturn(streamSpec);
    when(streamSpec.content()).thenReturn(contentFlux);
  }

  // ========== answerQuestionStream 测试 ==========

  @Nested
  @DisplayName("answerQuestionStream 契约测试")
  class AnswerQuestionStreamTests {

    @Test
    @DisplayName("answerQuestionStream_返回RetrievalResult_包含来源文档")
    void returnsRetrievalResult_withSourceDocuments() {
      List<Document> docs = List.of(
          createDoc("机器学习是人工智能的一个分支", 1L, 0.85),
          createDoc("深度学习使用神经网络进行特征提取", 1L, 0.72)
      );
      when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
          .thenReturn(docs);
      // normalizeStreamOutput 会在探测窗口（120字符）内缓冲，然后在完成时一次性发出归一化后的内容
      mockStreamChain(Flux.just("机器学习是人工智能的子领域"));

      RetrievalResult result = queryService.answerQuestionStream(List.of(1L), "什么是机器学习？");

      // 验证来源文档非空
      assertThat(result.sourceDocuments()).hasSize(2);
      assertThat(result.sourceDocuments().get(0).getText()).isEqualTo("机器学习是人工智能的一个分支");

      // 验证 contentStream 可以正常消费（normalizeStreamOutput 缓冲后一次性发出）
      StepVerifier.create(result.contentStream())
          .expectNextMatches(s -> s.contains("机器学习"))
          .verifyComplete();
    }

    @Test
    @DisplayName("answerQuestionStream_零命中_来源列表为空")
    void zeroHits_sourceDocumentsEmpty() {
      when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
          .thenReturn(List.of());

      RetrievalResult result = queryService.answerQuestionStream(List.of(1L), "什么是量子计算？");

      // 零命中时返回固定回复，来源列表为空
      assertThat(result.sourceDocuments()).isEmpty();

      // contentStream 仍可消费（返回"未找到"固定回复）
      StepVerifier.create(result.contentStream())
          .expectNextCount(1)
          .verifyComplete();
    }

    @Test
    @DisplayName("answerQuestionStream_模型异常_错误传播而非文本吞并")
    void modelException_errorPropagated() {
      List<Document> docs = List.of(createDoc("一些相关内容", 1L, 0.8));
      when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
          .thenReturn(docs);
      mockStreamChain(Flux.error(new RuntimeException("LLM服务不可用")));

      RetrievalResult result = queryService.answerQuestionStream(List.of(1L), "什么是机器学习？");

      // 验证异常向上传播，不再被 onErrorResume 转为文本
      StepVerifier.create(result.contentStream())
          .expectError(RuntimeException.class)
          .verify();
    }
  }

  @Nested
  @DisplayName("resolveFinalStatus 最终状态判定测试")
  class ResolveFinalStatusTests {

    @Test
    @DisplayName("resolveFinalStatus_未检索到文档_NO_RESULTS")
    void noDocuments_returnsNO_RESULTS() {
      MessageStatus status = queryService.resolveFinalStatus("随便一段文字", List.of());
      assertThat(status).isEqualTo(MessageStatus.NO_RESULTS);
    }

    @Test
    @DisplayName("resolveFinalStatus_检索到文档且实质性回答_COMPLETED")
    void documentsAndRealAnswer_returnsCOMPLETED() {
      List<Document> docs = List.of(createDoc("后端端口是 8080", 1L, 0.9));
      MessageStatus status = queryService.resolveFinalStatus("项目的后端端口是 8080。", docs);
      assertThat(status).isEqualTo(MessageStatus.COMPLETED);
    }

    @Test
    @DisplayName("resolveFinalStatus_检索到文档但输出无结果文本_NO_RESULTS")
    void documentsButNoResultOutput_returnsNO_RESULTS() {
      List<Document> docs = List.of(createDoc("一些相关内容", 1L, 0.9));
      // 模拟检索命中但模型拒答（与 normalizeAnswer/NO_RESULT_RESPONSE 同形）
      MessageStatus status = queryService.resolveFinalStatus(
          "抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。", docs);
      assertThat(status).isEqualTo(MessageStatus.NO_RESULTS);
    }

    @Test
    @DisplayName("resolveFinalStatus_检索到文档但输出为空_NO_RESULTS")
    void documentsButBlankOutput_returnsNO_RESULTS() {
      List<Document> docs = List.of(createDoc("一些相关内容", 1L, 0.9));
      assertThat(queryService.resolveFinalStatus("   ", docs)).isEqualTo(MessageStatus.NO_RESULTS);
      assertThat(queryService.resolveFinalStatus(null, docs)).isEqualTo(MessageStatus.NO_RESULTS);
    }

    @Test
    @DisplayName("resolveFinalStatus_有效长回答正文偶带'信息不足'仍_COMPLETED")
    void effectiveLongAnswerMentioningInsufficientInfo_returnsCOMPLETED() {
      // 反例：前段是正常解释，后文出现"信息不足"等描述性用语，但整体是有依据的实质回答。
      List<Document> docs = List.of(createDoc("默认服务端口为 8080，日志级别可调", 1L, 0.9));
      String answer =
          "该项目使用 Spring Boot，默认后端服务监听在 8080 端口，可通过 SERVER_PORT 环境变量覆盖。"
        + "排查时如果日志信息不足，请提高日志级别；实际解决方法是将服务端口改为8080。";
      MessageStatus status = queryService.resolveFinalStatus(answer, docs);
      assertThat(status).isEqualTo(MessageStatus.COMPLETED);
    }

    @Test
    @DisplayName("resolveFinalStatus_起始句为明确拒答（非固定模板）_NO_RESULTS")
    void explicitRefusalNonTemplate_returnsNO_RESULTS() {
      List<Document> docs = List.of(createDoc("一些相关内容", 1L, 0.9));
      // 不是固定无结果模板，但第一句本身就是整段式明确拒答。
      MessageStatus status = queryService.resolveFinalStatus(
          "抱歉，我未检索到相关信息，无法根据提供内容回答这个问题。", docs);
      assertThat(status).isEqualTo(MessageStatus.NO_RESULTS);
    }

    @Test
    @DisplayName("resolveFinalStatus_有效回答中出现'信息不足'不误判为拒答")
    void descriptivePhraseInsideAnswer_returnsCOMPLETED() {
      List<Document> docs = List.of(createDoc("一些相关内容", 1L, 0.9));
      // 第一句正常，"信息不足"仅作为正文描述出现，不构成拒答。
      MessageStatus status = queryService.resolveFinalStatus(
          "解决方法是将服务端口改为 8080。如果日志信息不足，请提高日志级别后重试。", docs);
      assertThat(status).isEqualTo(MessageStatus.COMPLETED);
    }

    @Test
    @DisplayName("回归1：'找不到配置文件'开头的正常排查答案应为_COMPLETED")
    void troubleshootingStartingWithNotFound_returnsCOMPLETED() {
      // Codex 真实判定：1ba2a57 因起始句 contains "找不到" 而误判为拒答。
      List<Document> docs = List.of(createDoc("配置加载路径说明", 1L, 0.9));
      String answer = "找不到配置文件时，请先检查工作目录及挂载路径，"
          + "并将配置放在应用指定的位置，随后重新启动服务。";
      assertThat(queryService.resolveFinalStatus(answer, docs)).isEqualTo(MessageStatus.COMPLETED);
    }

    @Test
    @DisplayName("回归2：'知识库中未配置索引版本字段'的正常排查答案应为_COMPLETED")
    void troubleshootingStartingWithKbNotConfigured_returnsCOMPLETED() {
      // Codex 真实判定：1ba2a57 因起始句 contains "知识库中未" 而误判为拒答。
      List<Document> docs = List.of(createDoc("向量索引构建流程", 1L, 0.9));
      String answer = "知识库中未配置索引版本字段，需要先添加该字段并重新构建向量索引。";
      assertThat(queryService.resolveFinalStatus(answer, docs)).isEqualTo(MessageStatus.COMPLETED);
    }

    @Test
    @DisplayName("反例矩阵_条件句排查答案_COMPLETED")
    void conditionalTroubleshooting_returnsCOMPLETED() {
      List<Document> docs = List.of(createDoc("依赖安装步骤", 1L, 0.9));
      // 条件句里出现"找不到"，但并非"无法回答"，是正常操作指导。
      String answer = "如果找不到某个依赖，请先执行安装命令，然后重新启动服务。";
      assertThat(queryService.resolveFinalStatus(answer, docs)).isEqualTo(MessageStatus.COMPLETED);
    }

    @Test
    @DisplayName("反例矩阵_引用错误文本的答案_COMPLETED")
    void quotingErrorTextAnswer_returnsCOMPLETED() {
      List<Document> docs = List.of(createDoc("配置加载机制", 1L, 0.9));
      // 正文引用了一段含"找不到"的报错文本，但整体是有效排查回答。
      String answer = "启动报错信息为 '找不到 config.yaml'，请把该文件放到 classpath 根目录后重启。";
      assertThat(queryService.resolveFinalStatus(answer, docs)).isEqualTo(MessageStatus.COMPLETED);
    }

    @Test
    @DisplayName("反例矩阵_否定式拒答（并非无法回答）_COMPLETED")
    void negatedRefusalPhrase_returnsCOMPLETED() {
      List<Document> docs = List.of(createDoc("端口配置说明", 1L, 0.9));
      // 起始句含"无法回答"但是被"并非"否定，应视为正常作答。
      String answer = "并非无法回答，解决方法是将端口改为 8080 后重启。";
      assertThat(queryService.resolveFinalStatus(answer, docs)).isEqualTo(MessageStatus.COMPLETED);
    }

    @Test
    @DisplayName("反例矩阵_明确非模板拒答（无法根据现有资料回答）_NO_RESULTS")
    void explicitNonTemplateRefusalInability_returnsNO_RESULTS() {
      List<Document> docs = List.of(createDoc("一些相关内容", 1L, 0.9));
      // 不是固定模板，但起始句明确"无法根据……回答"，应判为拒答。
      String refusal = "无法根据现有资料回答您的问题，请补充更具体的关键词。";
      assertThat(queryService.resolveFinalStatus(refusal, docs)).isEqualTo(MessageStatus.NO_RESULTS);
    }

    @Test
    @DisplayName("反例矩阵_明确非模板拒答（未检索到相关信息）_NO_RESULTS")
    void explicitNonTemplateRefusalEmptyRetrieval_returnsNO_RESULTS() {
      List<Document> docs = List.of(createDoc("一些相关内容", 1L, 0.9));
      // 信息类空检索句式，属于整段式拒答。
      String refusal = "抱歉，没有找到相关信息，暂时无法回答。";
      assertThat(queryService.resolveFinalStatus(refusal, docs)).isEqualTo(MessageStatus.NO_RESULTS);
    }
  }
}
