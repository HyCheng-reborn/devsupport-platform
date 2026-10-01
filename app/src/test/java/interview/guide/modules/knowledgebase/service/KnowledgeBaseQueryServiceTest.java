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
  }
}
