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
    @DisplayName("resolveFinalStatus_有效长回答正文偶带'信息不足'应为_COMPLETED（不被误标为_INSUFFICIENT_INFO）")
    void effectiveLongAnswerMentioningInsufficientInfo_returnsCOMPLETED() {
        // 长回答中偶带"信息不足"等描述性用语，但提供了实质性排查步骤，应为 COMPLETED。
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
    @DisplayName("resolveFinalStatus_有效回答中偶带'信息不足'但给出了实质步骤_COMPLETED")
    void descriptivePhraseInsideAnswer_returnsCOMPLETED() {
      // 回答包含实质排查步骤（改端口），"信息不足"只是附带条件说明，不应误标为 INSUFFICIENT_INFO。
      List<Document> docs = List.of(createDoc("一些相关内容", 1L, 0.9));
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

    @Test
    @DisplayName("反例A_条件中的拒答表达（如果…无法回答…请检查）_COMPLETED")
    void conditionalMentionOfInability_returnsCOMPLETED() {
      List<Document> docs = List.of(createDoc("API Key 与超时配置", 1L, 0.9));
      // "无法回答" 处于"如果…，请…"的假设从句，当前回答并未拒答，而是给出排查步骤。
      String answer = "如果模型无法回答，请先检查 API Key 和超时配置，然后重新发起请求。";
      assertThat(queryService.resolveFinalStatus(answer, docs)).isEqualTo(MessageStatus.COMPLETED);
    }

    @Test
    @DisplayName("反例B_引号内的拒答表达（日志出现‘无法回答’）_COMPLETED")
    void quotedMentionOfInability_returnsCOMPLETED() {
      List<Document> docs = List.of(createDoc("模型连接配置", 1L, 0.9));
      // “无法回答” 是被引用的日志文本（提及），不是当前回答在拒答。
      String answer = "日志出现‘无法回答’时，请检查模型连接配置并重启服务。";
      assertThat(queryService.resolveFinalStatus(answer, docs)).isEqualTo(MessageStatus.COMPLETED);
    }

    @Test
    @DisplayName("反例C_否定作用范围限定（并非无法连接，但无法回答）_NO_RESULTS")
    void negationScopedToOtherVerbRealRefusalRemains_returnsNO_RESULTS() {
      List<Document> docs = List.of(createDoc("一些相关内容", 1L, 0.9));
      // "并非无法连接" 只否定"连接"，后面的"无法根据现有资料回答" 是真实拒答，不能被放行。
      String answer = "并非无法连接服务，但无法根据现有资料回答您的问题，请补充文档。";
      assertThat(queryService.resolveFinalStatus(answer, docs)).isEqualTo(MessageStatus.NO_RESULTS);
    }

    @Test
    @DisplayName("对偶_否定拒答本身（并非无法回答，解决方法…）_COMPLETED")
    void negationOfRefusalItself_returnsCOMPLETED() {
      List<Document> docs = List.of(createDoc("端口配置说明", 1L, 0.9));
      String answer = "并非无法回答，解决方法是将端口改为8080。";
      assertThat(queryService.resolveFinalStatus(answer, docs)).isEqualTo(MessageStatus.COMPLETED);
    }

    @Test
    @DisplayName("对偶_引用提及后仍真实拒答（‘无法回答’…但仍无法回答您的问题）_NO_RESULTS")
    void quotedMentionThenGenuineRefusal_returnsNO_RESULTS() {
      List<Document> docs = List.of(createDoc("一些相关内容", 1L, 0.9));
      // 引号里的“无法回答” 是提及；逗号后是当前回答的真实拒答，不能整句放行。
      String answer = "日志出现‘无法回答’，但根据现有资料仍无法回答您的问题。";
      assertThat(queryService.resolveFinalStatus(answer, docs)).isEqualTo(MessageStatus.NO_RESULTS);
    }

    @Test
    @DisplayName("对偶_条件句与独立拒答同时出现（如果…请补充日志；目前无法…回答）_NO_RESULTS")
    void conditionalClausePlusIndependentRefusal_returnsNO_RESULTS() {
      List<Document> docs = List.of(createDoc("一些相关内容", 1L, 0.9));
      // 前一分句是条件；分号后的"无法根据现有资料回答您的问题" 是独立真实拒答。
      String answer = "如果需要详细步骤，请补充日志；目前无法根据现有资料回答您的问题。";
      assertThat(queryService.resolveFinalStatus(answer, docs)).isEqualTo(MessageStatus.NO_RESULTS);
    }

    @Test
    @DisplayName("反例A2_ASCII单引号引用（'无法回答'）_COMPLETED")
    void asciiSingleQuotedMention_returnsCOMPLETED() {
      List<Document> docs = List.of(createDoc("模型连接配置", 1L, 0.9));
      // 注意：这里是 ASCII 单引号，不是中文弯引号；其中的"无法回答"只是被引用的日志文本。
      String answer = "日志出现'无法回答'时，请检查模型连接配置并重启服务。";
      assertThat(queryService.resolveFinalStatus(answer, docs)).isEqualTo(MessageStatus.COMPLETED);
    }

    @Test
    @DisplayName("反例B2_句号位于引用内部（“无法回答。”）_COMPLETED")
    void periodInsideQuote_returnsCOMPLETED() {
      List<Document> docs = List.of(createDoc("模型连接配置", 1L, 0.9));
      // 引用内部的句号不得提前截断句子；整段是条件排查而非拒答。
      String answer = "日志出现“无法回答。”时，请检查模型连接配置并重启服务。";
      assertThat(queryService.resolveFinalStatus(answer, docs)).isEqualTo(MessageStatus.COMPLETED);
    }

    @Test
    @DisplayName("反例C2_条件排查构造（如果…，…无法回答时请检查…）_COMPLETED")
    void conditionalWhenThenInstruction_returnsCOMPLETED() {
      List<Document> docs = List.of(createDoc("API Key 与超时配置", 1L, 0.9));
      // "如果服务启动失败"与"模型无法回答"都是假设条件，"时请…"是条件排查指引，不是当前拒答。
      String answer = "如果服务启动失败，模型无法回答时请检查 API Key 并重试。";
      assertThat(queryService.resolveFinalStatus(answer, docs)).isEqualTo(MessageStatus.COMPLETED);
    }

    @Test
    @DisplayName("对偶_引用内含换行的“无法回答”_COMPLETED")
    void newlineInsideQuote_returnsCOMPLETED() {
      List<Document> docs = List.of(createDoc("模型连接配置", 1L, 0.9));
      // 引用内部的换行不应截断引用，也不应让"无法"/"回答"跨行拼接。
      String answer = "日志出现\"无法\n回答\"时，请检查配置并重启服务。";
      assertThat(queryService.resolveFinalStatus(answer, docs)).isEqualTo(MessageStatus.COMPLETED);
    }

    @Test
    @DisplayName("对偶_引用中分别提及“无法”与“回答”_COMPLETED")
    void quotedSeparateWordsNotConcatenated_returnsCOMPLETED() {
      List<Document> docs = List.of(createDoc("术语表", 1L, 0.9));
      // "无法"与"回答"分别被引用，是术语定义而非拒答；屏蔽后不能拼接成"无法…回答"。
      String answer = "关于“无法”与“回答”这两个词的定义，请参见术语表。";
      assertThat(queryService.resolveFinalStatus(answer, docs)).isEqualTo(MessageStatus.COMPLETED);
    }

    @Test
    @DisplayName("对偶_引号内仅是被提及的界面字符串_外部无拒答_COMPLETED")
    void quotedUiTokenMention_returnsCOMPLETED() {
      List<Document> docs = List.of(createDoc("排错手册", 1L, 0.9));
      // 只有引号内的“无法回答”是被提及的界面字符串，引用外没有拒答构造 → 正常作答。
      String answer = "“无法回答”这个提示通常表示模型连接异常，请检查网络后重试。";
      assertThat(queryService.resolveFinalStatus(answer, docs)).isEqualTo(MessageStatus.COMPLETED);
    }

    @Test
    @DisplayName("对偶_ASCII单引号提及后仍有独立真实拒答_NO_RESULTS")
    void asciiQuoteThenGenuineRefusal_returnsNO_RESULTS() {
      List<Document> docs = List.of(createDoc("一些相关内容", 1L, 0.9));
      // ASCII 单引号里的"无法回答"是提及；逗号后是当前回答的真实拒答。
      String answer = "日志出现'无法回答'，但仍无法根据现有资料回答您的问题。";
      assertThat(queryService.resolveFinalStatus(answer, docs)).isEqualTo(MessageStatus.NO_RESULTS);
    }

    @Test
    @DisplayName("对偶_条件指引之后仍有独立真实拒答_NO_RESULTS")
    void conditionalThenIndependentRefusal_returnsNO_RESULTS() {
      List<Document> docs = List.of(createDoc("一些相关内容", 1L, 0.9));
      // 分号前是条件排查指引；分号后的"无法根据现有资料回答您的问题"是独立真实拒答。
      String answer = "如果服务启动失败，请检查配置；目前无法根据现有资料回答您的问题。";
      assertThat(queryService.resolveFinalStatus(answer, docs)).isEqualTo(MessageStatus.NO_RESULTS);
    }

    @Test
    @DisplayName("resolveFinalStatus_检索到文档且输出含缺失信息章节有实质内容_INSUFFICIENT_INFO")
    void missingInfoSectionWithContent_returnsINSUFFICIENT_INFO() {
      List<Document> docs = List.of(createDoc("一些相关内容", 1L, 0.9));
      String answer = "## 问题理解\n端口配置问题。\n\n## 解决方案\n改为 8080。\n\n## 缺失信息\n有实质内容需要补充。";
      MessageStatus status = queryService.resolveFinalStatus(answer, docs);
      assertThat(status).isEqualTo(MessageStatus.INSUFFICIENT_INFO);
    }

    @Test
    @DisplayName("resolveFinalStatus_检索到文档且输出含信息不足_INSUFFICIENT_INFO")
    void containsInsufficientInfoPhrase_returnsINSUFFICIENT_INFO() {
      List<Document> docs = List.of(createDoc("一些相关内容", 1L, 0.9));
      String answer = "根据现有资料，信息不足，无法给出完整方案。";
      MessageStatus status = queryService.resolveFinalStatus(answer, docs);
      assertThat(status).isEqualTo(MessageStatus.INSUFFICIENT_INFO);
    }

    @Test
    @DisplayName("resolveFinalStatus_检索到文档且输出含无法确定_INSUFFICIENT_INFO")
    void containsCannotDeterminePhrase_returnsINSUFFICIENT_INFO() {
      List<Document> docs = List.of(createDoc("一些相关内容", 1L, 0.9));
      String answer = "根据现有资料无法确定具体配置值，请补充文档。";
      MessageStatus status = queryService.resolveFinalStatus(answer, docs);
      assertThat(status).isEqualTo(MessageStatus.INSUFFICIENT_INFO);
    }

    @Test
    @DisplayName("resolveFinalStatus_未检索到文档不返回INSUFFICIENT_INFO而返回NO_RESULTS")
    void noDocuments_shouldNotReturnINSUFFICIENT_INFO() {
      String answer = "## 缺失信息\n有实质内容。";
      MessageStatus status = queryService.resolveFinalStatus(answer, List.of());
      assertThat(status).isEqualTo(MessageStatus.NO_RESULTS);
      assertThat(status).isNotEqualTo(MessageStatus.INSUFFICIENT_INFO);
    }

    @Test
    @DisplayName("resolveFinalStatus_正常完整回答不返回INSUFFICIENT_INFO而返回COMPLETED")
    void normalCompleteAnswer_shouldNotReturnINSUFFICIENT_INFO() {
      List<Document> docs = List.of(createDoc("端口配置说明", 1L, 0.9));
      String answer = "项目的后端端口是 8080，可通过 SERVER_PORT 环境变量覆盖。";
      MessageStatus status = queryService.resolveFinalStatus(answer, docs);
      assertThat(status).isEqualTo(MessageStatus.COMPLETED);
      assertThat(status).isNotEqualTo(MessageStatus.INSUFFICIENT_INFO);
    }

    @Test
    @DisplayName("Codex六次_P1_引用为资料名_外部无法…回答_NO_RESULTS")
    void refusalSpanningQuotedResourceName_returnsNO_RESULTS() {
      List<Document> docs = List.of(createDoc("部署手册", 1L, 0.9));
      // 引号内是资料名称“部署说明”，引用外的“无法…回答您的问题”是真实拒答，不能被占位切断。
      String answer = "抱歉，无法根据“部署说明”回答您的问题，请补充资料。";
      assertThat(queryService.resolveFinalStatus(answer, docs)).isEqualTo(MessageStatus.NO_RESULTS);
    }

    @Test
    @DisplayName("Codex六次_P1_引用为资料名_外部未找到…信息_NO_RESULTS")
    void emptyRetrievalSpanningQuotedResourceName_returnsNO_RESULTS() {
      List<Document> docs = List.of(createDoc("索引文档", 1L, 0.9));
      // 引号内是资料名“索引配置”，引用外“未找到关于…的相关信息”是真实无结果拒答。
      String answer = "目前未找到关于“索引配置”的相关信息，请补充文档。";
      assertThat(queryService.resolveFinalStatus(answer, docs)).isEqualTo(MessageStatus.NO_RESULTS);
    }

    @Test
    @DisplayName("Codex六次_P1_引用内部含换行不干扰_外部真实拒答仍_NO_RESULTS")
    void newlineInsideQuoteDoesNotBreakExternalRefusal_returnsNO_RESULTS() {
      List<Document> docs = List.of(createDoc("部署手册", 1L, 0.9));
      // 引用内含换行，屏蔽后不应把外部"无法…回答"截断。
      String answer = "抱歉，无法根据\u201c部署\n说明\u201d回答您的问题，请补充资料。";
      assertThat(queryService.resolveFinalStatus(answer, docs)).isEqualTo(MessageStatus.NO_RESULTS);
    }
  }

  @Nested
  @DisplayName("流式路径：answerQuestionStream → normalizeStreamOutput → resolveFinalStatus")
  class StreamingPathTests {

    @Test
    @DisplayName("流式路径_短回答信息不足_保留原始输出且状态为_INSUFFICIENT_INFO")
    void insufficientInfo_shortAnswer_preservesStatus() {
      // 文档存在（检索命中）
      List<Document> docs = List.of(createDoc("一些相关内容", 1L, 0.8));
      when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
          .thenReturn(docs);
      // 模型返回短回答，表明信息不足
      String modelOutput = "## 缺失信息\n需要更多日志信息来确定根因";
      mockStreamChain(Flux.just(modelOutput));

      RetrievalResult result = queryService.answerQuestionStream(List.of(1L), "根因是什么？");

      // 收集流式输出的完整内容
      String fullContent = result.contentStream().collectList().block().stream()
          .reduce("", (a, b) -> a + b);

      // 实际模型输出被保留（未被替换为 NO_RESULT_RESPONSE）
      assertThat(fullContent).contains("缺失信息");
      assertThat(fullContent).contains("需要更多日志信息");

      // resolveFinalStatus 应判为 INSUFFICIENT_INFO（不是 NO_RESULTS）
      MessageStatus status = queryService.resolveFinalStatus(fullContent, result.sourceDocuments());
      assertThat(status).isEqualTo(MessageStatus.INSUFFICIENT_INFO);
    }

    @Test
    @DisplayName("流式路径_答案提及信息不足但给出实质步骤_状态为_COMPLETED")
    void answerMentionsInsufficientButProvidesSteps_notMisclassified() {
      List<Document> docs = List.of(createDoc("一些相关内容", 1L, 0.8));
      when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
          .thenReturn(docs);
      // 模型返回包含排查步骤的答案，"缺失信息"章节为空占位
      String modelOutput = "## 检查步骤\n1. 检查日志\n2. 检查配置\n\n## 缺失信息\n暂无额外信息";
      mockStreamChain(Flux.just(modelOutput));

      RetrievalResult result = queryService.answerQuestionStream(List.of(1L), "如何排查？");

      String fullContent = result.contentStream().collectList().block().stream()
          .reduce("", (a, b) -> a + b);

      // 实际输出被保留
      assertThat(fullContent).contains("检查步骤");
      assertThat(fullContent).contains("检查日志");

      // 有实质排查步骤，即使提及"缺失信息"也应为 COMPLETED
      MessageStatus status = queryService.resolveFinalStatus(fullContent, result.sourceDocuments());
      assertThat(status).isEqualTo(MessageStatus.COMPLETED);
    }

    @Test
    @DisplayName("流式路径_无文档短回答_NO_RESULTS 与 有文档信息不足_INSUFFICIENT_INFO 的区分")
    void noResultsVsInsufficientInfo_distinction() {
      // 路径 A：无文档 + 短回答 → NO_RESULTS
      when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
          .thenReturn(List.of());

      RetrievalResult noResultRetrieval = queryService.answerQuestionStream(List.of(1L), "根因是什么？");
      String noResultContent = noResultRetrieval.contentStream().collectList().block().stream()
          .reduce("", (a, b) -> a + b);

      // 零命中时来源为空，状态为 NO_RESULTS
      assertThat(noResultRetrieval.sourceDocuments()).isEmpty();
      MessageStatus statusA = queryService.resolveFinalStatus(noResultContent, noResultRetrieval.sourceDocuments());
      assertThat(statusA).isEqualTo(MessageStatus.NO_RESULTS);

      // 路径 B：有文档 + "信息不足"短回答 → INSUFFICIENT_INFO
      List<Document> docs = List.of(createDoc("一些相关内容", 1L, 0.8));
      when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
          .thenReturn(docs);
      String insufficientOutput = "信息不足，需要补充更多日志才能确定根因。";
      mockStreamChain(Flux.just(insufficientOutput));

      RetrievalResult insufficientResult = queryService.answerQuestionStream(List.of(1L), "根因是什么？");
      String insufficientContent = insufficientResult.contentStream().collectList().block().stream()
          .reduce("", (a, b) -> a + b);

      // 模型输出被保留（未被替换为 NO_RESULT_RESPONSE）
      assertThat(insufficientContent).contains("信息不足");
      // 有文档且输出表明信息不足 → INSUFFICIENT_INFO
      MessageStatus statusB = queryService.resolveFinalStatus(insufficientContent, insufficientResult.sourceDocuments());
      assertThat(statusB).isEqualTo(MessageStatus.INSUFFICIENT_INFO);
    }
  }
}
