package interview.guide.modules.knowledgebase;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.QueryRequest;
import interview.guide.modules.knowledgebase.model.RetrievalResult;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseConflictService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseDeleteService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseListService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseUploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * KnowledgeBaseController 流式端点（queryKnowledgeBaseStream）错误兜底语义测试。
 *
 * <p>聚焦 Phase 1 后本端点错误处理的定点恢复：区分「建立 RetrievalResult 时同步抛错」与
 * 「Flux 订阅后异步出错」，验证失败被显式标记为【错误】而非伪装成正常回答。不调用真实 LLM / DB。
 */
@DisplayName("知识库流式查询端点错误兜底测试")
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseControllerStreamTest {

  @Mock
  private KnowledgeBaseUploadService uploadService;
  @Mock
  private KnowledgeBaseQueryService queryService;
  @Mock
  private KnowledgeBaseListService listService;
  @Mock
  private KnowledgeBaseDeleteService deleteService;
  @Mock
  private KnowledgeBaseConflictService conflictService;

  private KnowledgeBaseController controller;

  private static final List<Long> KB_IDS = List.of(1L);
  private static final String QUESTION = "项目后端端口是多少？";

  @BeforeEach
  void setUp() {
    controller = new KnowledgeBaseController(
        uploadService, queryService, listService, deleteService, conflictService);
  }

  private QueryRequest request() {
    return new QueryRequest(KB_IDS, QUESTION);
  }

  private RetrievalResult resultWith(Flux<String> contentStream) {
    return new RetrievalResult(contentStream, List.of());
  }

  // ========== 正常路径不回归 ==========

  @Test
  @DisplayName("内容流正常完成时原样透传，不追加任何错误标记")
  void normalStreamPassesThroughWithoutErrorMarker() {
    when(queryService.answerQuestionStream(KB_IDS, QUESTION))
        .thenReturn(resultWith(Flux.just("后端端口", "是 8080")));

    StepVerifier.create(controller.queryKnowledgeBaseStream(request()))
        .expectNext("后端端口")
        .expectNext("是 8080")
        .verifyComplete();
  }

  // ========== (A) 建立 RetrievalResult 时同步抛错 ==========

  @Nested
  @DisplayName("建立 RetrievalResult 阶段的同步抛错")
  class SynchronousConstructionFailure {

    @Test
    @DisplayName("answerQuestionStream 同步抛业务异常时，整条流以带真实原因的【错误】文本兜底并正常结束")
    void syncThrowYieldsExplicitErrorTextAndCompletes() {
      when(queryService.answerQuestionStream(KB_IDS, QUESTION))
          .thenThrow(new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "向量检索不可用"));

      List<String> emitted = controller.queryKnowledgeBaseStream(request()).collectList().block();

      assertThat(emitted).hasSize(1);
      String text = emitted.get(0);
      assertThat(text).startsWith("【错误】知识库查询失败：");
      assertThat(text).contains("向量检索不可用");
    }

    @Test
    @DisplayName("同步抛错且异常无 message 时，回退到通用不可用文案而非空串")
    void syncThrowWithoutMessageFallsBackToGenericText() {
      when(queryService.answerQuestionStream(KB_IDS, QUESTION))
          .thenThrow(new IllegalStateException());

      List<String> emitted = controller.queryKnowledgeBaseStream(request()).collectList().block();

      assertThat(emitted).hasSize(1);
      assertThat(emitted.get(0)).startsWith("【错误】知识库查询失败：");
      assertThat(emitted.get(0)).contains("AI服务暂时不可用");
    }
  }

  // ========== (B) Flux 订阅后异步出错 ==========

  @Nested
  @DisplayName("Flux 订阅后的异步出错")
  class AsyncSubscriptionFailure {

    @Test
    @DisplayName("订阅即失败、未产出任何内容时，整串以【错误】文本兜底（伪装防护）")
    void errorBeforeAnyContentEmitsWholeStreamErrorText() {
      when(queryService.answerQuestionStream(KB_IDS, QUESTION))
          .thenReturn(resultWith(Flux.error(new RuntimeException("LLM连接失败"))));

      List<String> emitted = controller.queryKnowledgeBaseStream(request()).collectList().block();

      assertThat(emitted).hasSize(1);
      assertThat(emitted.get(0)).startsWith("【错误】知识库查询失败：");
      assertThat(emitted.get(0)).contains("LLM连接失败");
    }

    @Test
    @DisplayName("已输出部分内容后中断：保留已发内容并追加固定【错误】标记，不裸抛错误")
    void errorAfterPartialContentAppendsMarker() {
      when(queryService.answerQuestionStream(KB_IDS, QUESTION))
          .thenReturn(resultWith(
              Flux.just("已输出的部分回答").concatWith(Flux.error(new RuntimeException("流中断")))));

      StepVerifier.create(controller.queryKnowledgeBaseStream(request()))
          .expectNext("已输出的部分回答")
          .expectNextMatches(chunk -> chunk.contains("【错误】知识库查询失败：")
              && chunk.contains("AI服务暂时不可用"))
          // 错误已被兜底转换，流以正常完成收场，绝不向客户端裸传播错误
          .verifyComplete();
    }

    @Test
    @DisplayName("失败不会伪装成 NO_RESULT 正常回答：错误分支输出必含【错误】标记")
    void failureIsNotDisguisedAsNormalAnswer() {
      when(queryService.answerQuestionStream(KB_IDS, QUESTION))
          .thenReturn(resultWith(Flux.error(new RuntimeException("上游超时"))));

      List<String> emitted = controller.queryKnowledgeBaseStream(request()).collectList().block();

      assertThat(emitted).isNotNull();
      String joined = String.join("", emitted);
      assertThat(joined).contains("【错误】");
      assertThat(joined).doesNotContain("未检索到相关信息");
    }
  }

  // ========== 透传的无结果文案（非错误）不受影响 ==========

  @Test
  @DisplayName("内容流为固定无结果文案时原样透传，不判定为错误")
  void noResultContentPassesThroughUntouched() {
    String noResult = "抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。";
    when(queryService.answerQuestionStream(anyList(), anyString()))
        .thenReturn(resultWith(Flux.just(noResult)));

    StepVerifier.create(controller.queryKnowledgeBaseStream(request()))
        .expectNext(noResult)
        .verifyComplete();
  }
}
