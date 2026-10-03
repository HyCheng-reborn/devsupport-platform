package interview.guide.modules.knowledgebase.service;

import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.mapper.KnowledgeBaseMapper;
import interview.guide.modules.knowledgebase.model.ContextKbItem;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.QuestionGenStatus;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * KnowledgeBaseListService.resolveContext 单元测试
 * 覆盖上下文解析的范围过滤、隔离、兼容、空结果、组合等场景
 */
@DisplayName("知识库上下文解析测试")
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseContextResolveTest {

  @Mock private KnowledgeBaseRepository knowledgeBaseRepository;
  @Mock private RagChatMessageRepository ragChatMessageRepository;
  @Mock private KnowledgeBaseMapper knowledgeBaseMapper;
  @Mock private FileStorageService fileStorageService;

  private KnowledgeBaseListService listService;

  @BeforeEach
  void setUp() {
    listService = new KnowledgeBaseListService(
        knowledgeBaseRepository, ragChatMessageRepository, knowledgeBaseMapper, fileStorageService);
  }

  private static KnowledgeBaseEntity buildKb(Long id, String name, String service, String environment) {
    KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
    kb.setId(id);
    kb.setName(name);
    kb.setService(service);
    kb.setEnvironment(environment);
    kb.setFileSize(100L);
    kb.setOriginalFilename(name + ".md");
    kb.setUploadedAt(LocalDateTime.now());
    kb.setAccessCount(0);
    kb.setQuestionCount(0);
    kb.setVectorStatus(VectorStatus.COMPLETED);
    kb.setChunkCount(1);
    kb.setQuestionGenStatus(QuestionGenStatus.NONE);
    return kb;
  }

  @DisplayName("正确范围：service 过滤返回匹配的 KB")
  @Nested
  class CorrectScope {

    @Test
    @DisplayName("service=payment 只返回 payment 服务的知识库")
    void shouldReturnOnlyPaymentKbs() {
      // given
      KnowledgeBaseEntity paymentKb1 = buildKb(1L, "支付文档A", "payment", "生产");
      KnowledgeBaseEntity paymentKb2 = buildKb(2L, "支付文档B", "payment", "测试");
      when(knowledgeBaseRepository.findByServiceOrderByUploadedAtDesc("payment"))
          .thenReturn(List.of(paymentKb1, paymentKb2));

      // when
      List<ContextKbItem> result = listService.resolveContext("payment", null);

      // then
      assertThat(result).hasSize(2);
      assertThat(result).extracting(ContextKbItem::id).containsExactly(1L, 2L);
      assertThat(result).extracting(ContextKbItem::service).containsOnly("payment");
    }
  }

  @DisplayName("跨项目隔离：不同 service 的 KB 互不包含")
  @Nested
  class CrossProjectIsolation {

    @Test
    @DisplayName("service=payment 不包含 service=auth 的知识库")
    void shouldNotIncludeAuthKbsWhenQueryingPayment() {
      // given
      KnowledgeBaseEntity paymentKb = buildKb(1L, "支付文档", "payment", "生产");
      when(knowledgeBaseRepository.findByServiceOrderByUploadedAtDesc("payment"))
          .thenReturn(List.of(paymentKb));

      // when
      List<ContextKbItem> result = listService.resolveContext("payment", null);

      // then
      assertThat(result).hasSize(1);
      assertThat(result.getFirst().service()).isEqualTo("payment");
      assertThat(result).extracting(ContextKbItem::name).doesNotContain("auth", "认证");
    }
  }

  @DisplayName("旧请求兼容：无 service/environment 返回所有 KB")
  @Nested
  class BackwardCompatibility {

    @Test
    @DisplayName("两个参数都为 null 时返回全部知识库")
    void shouldReturnAllKbsWhenNoContext() {
      // given
      KnowledgeBaseEntity kb1 = buildKb(1L, "文档A", "payment", "生产");
      KnowledgeBaseEntity kb2 = buildKb(2L, "文档B", "auth", "测试");
      KnowledgeBaseEntity kb3 = buildKb(3L, "文档C", null, null);
      when(knowledgeBaseRepository.findAllByOrderByUploadedAtDesc())
          .thenReturn(List.of(kb1, kb2, kb3));

      // when
      List<ContextKbItem> result = listService.resolveContext(null, null);

      // then
      assertThat(result).hasSize(3);
      assertThat(result).extracting(ContextKbItem::id).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("两个参数都为空字符串时返回全部知识库")
    void shouldReturnAllKbsWhenEmptyStrings() {
      // given
      KnowledgeBaseEntity kb1 = buildKb(1L, "文档A", "payment", "生产");
      when(knowledgeBaseRepository.findAllByOrderByUploadedAtDesc())
          .thenReturn(List.of(kb1));

      // when
      List<ContextKbItem> result = listService.resolveContext("", "  ");

      // then
      assertThat(result).hasSize(1);
    }
  }

  @DisplayName("空上下文：不存在的 service 返回空列表")
  @Nested
  class EmptyContext {

    @Test
    @DisplayName("查询不存在的 service 返回空列表而非报错")
    void shouldReturnEmptyListForNonExistentService() {
      // given
      when(knowledgeBaseRepository.findByServiceOrderByUploadedAtDesc("nonexistent"))
          .thenReturn(List.of());

      // when
      List<ContextKbItem> result = listService.resolveContext("nonexistent", null);

      // then
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("查询不存在的 environment 返回空列表")
    void shouldReturnEmptyListForNonExistentEnvironment() {
      // given
      when(knowledgeBaseRepository.findAllByOrderByUploadedAtDesc())
          .thenReturn(List.of(buildKb(1L, "文档A", "payment", "生产")));

      // when
      List<ContextKbItem> result = listService.resolveContext(null, "staging");

      // then
      assertThat(result).isEmpty();
    }
  }

  @DisplayName("组合过滤：service + environment 同时生效")
  @Nested
  class CombinedFiltering {

    @Test
    @DisplayName("service=payment + environment=生产 只返回同时匹配两者的 KB")
    void shouldFilterByBothServiceAndEnvironment() {
      // given
      KnowledgeBaseEntity matchKb = buildKb(1L, "支付生产文档", "payment", "生产");
      KnowledgeBaseEntity otherEnvKb = buildKb(2L, "支付测试文档", "payment", "测试");
      when(knowledgeBaseRepository.findByServiceAndEnvironmentOrderByUploadedAtDesc("payment", "生产"))
          .thenReturn(List.of(matchKb));

      // when
      List<ContextKbItem> result = listService.resolveContext("payment", "生产");

      // then
      assertThat(result).hasSize(1);
      assertThat(result.getFirst().id()).isEqualTo(1L);
      assertThat(result.getFirst().service()).isEqualTo("payment");
      assertThat(result.getFirst().environment()).isEqualTo("生产");
    }

    @Test
    @DisplayName("仅 environment 过滤也能正确工作")
    void shouldFilterByEnvironmentOnly() {
      // given
      KnowledgeBaseEntity kb1 = buildKb(1L, "生产文档A", "payment", "生产");
      KnowledgeBaseEntity kb2 = buildKb(2L, "生产文档B", "auth", "生产");
      when(knowledgeBaseRepository.findAllByOrderByUploadedAtDesc())
          .thenReturn(List.of(kb1, kb2));

      // when
      List<ContextKbItem> result = listService.resolveContext(null, "生产");

      // then
      assertThat(result).hasSize(2);
      assertThat(result).extracting(ContextKbItem::environment).containsOnly("生产");
    }
  }
}
