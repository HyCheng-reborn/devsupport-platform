package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.RetrievalResult;
import interview.guide.modules.knowledgebase.model.SourceReference;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
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
 * 覆盖 answerQuestionStream 契约和 buildSourceReferences 来源提取逻辑
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

  // ========== buildSourceReferences 测试 ==========

  @Nested
  @DisplayName("buildSourceReferences 来源提取测试")
  class BuildSourceReferencesTests {

    @Mock
    private KnowledgeBaseRepository kbRepo;

    @Test
    @DisplayName("buildSourceReferences_从Document提取来源")
    void extractsSourceReferencesFromDocuments() {
      List<Document> docs = List.of(
          createDoc("这是一段来自简历的文本内容，用于测试来源引用功能", 10L, 0.92),
          createDoc("A".repeat(300), 20L, 0.75) // 长文本，测试截断
      );

      KnowledgeBaseEntity kb10 = new KnowledgeBaseEntity();
      kb10.setId(10L);
      kb10.setOriginalFilename("张三_简历.pdf");

      KnowledgeBaseEntity kb20 = new KnowledgeBaseEntity();
      kb20.setId(20L);
      kb20.setOriginalFilename("李四_简历.docx");

      when(kbRepo.findAllById(anySet())).thenReturn(List.of(kb10, kb20));

      List<SourceReference> refs = queryService.buildSourceReferences(docs, kbRepo);

      assertThat(refs).hasSize(2);

      // 第一个来源
      assertThat(refs.get(0).kbId()).isEqualTo(10L);
      assertThat(refs.get(0).documentName()).isEqualTo("张三_简历.pdf");
      assertThat(refs.get(0).contentSnippet()).isEqualTo("这是一段来自简历的文本内容，用于测试来源引用功能");
      assertThat(refs.get(0).score()).isEqualTo(0.92);

      // 第二个来源 — 验证截断
      assertThat(refs.get(1).kbId()).isEqualTo(20L);
      assertThat(refs.get(1).documentName()).isEqualTo("李四_简历.docx");
      assertThat(refs.get(1).contentSnippet()).hasSize(203); // 200 + "..."
      assertThat(refs.get(1).contentSnippet()).endsWith("...");
      assertThat(refs.get(1).score()).isEqualTo(0.75);
    }

    @Test
    @DisplayName("buildSourceReferences_空列表返回空")
    void emptyDocs_returnsEmpty() {
      List<SourceReference> refs = queryService.buildSourceReferences(List.of(), kbRepo);
      assertThat(refs).isEmpty();
    }

    @Test
    @DisplayName("buildSourceReferences_null列表返回空")
    void nullDocs_returnsEmpty() {
      List<SourceReference> refs = queryService.buildSourceReferences(null, kbRepo);
      assertThat(refs).isEmpty();
    }
  }
}
