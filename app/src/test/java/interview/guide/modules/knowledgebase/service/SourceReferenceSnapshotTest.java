package interview.guide.modules.knowledgebase.service;

import interview.guide.infrastructure.mapper.KnowledgeBaseMapper;
import interview.guide.infrastructure.mapper.RagChatMapper;
import interview.guide.modules.knowledgebase.model.SourceReference;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import interview.guide.modules.caselibrary.repository.CaseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.*;

/**
 * 批次 C：SourceReference 标签快照测试。
 * 验证 buildSourceReferences 正确地从 KnowledgeBaseEntity 提取 service/environment 标签。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("批次 C - SourceReference 标签快照测试")
class SourceReferenceSnapshotTest {

  @Mock
  private KnowledgeBaseRepository knowledgeBaseRepository;
  @Mock
  private CaseRepository caseRepository;
  @Mock
  private RagChatSessionRepository sessionRepository;
  @Mock
  private RagChatMessageRepository messageRepository;
  @Mock
  private KnowledgeBaseQueryService queryService;
  @Mock
  private KnowledgeBaseListService listService;
  @Mock
  private RagChatMapper ragChatMapper;
  @Mock
  private KnowledgeBaseMapper knowledgeBaseMapper;
  @Mock
  private KnowledgeBaseQueryProperties queryProperties;

  private RagChatSessionService createService() {
    return new RagChatSessionService(
        sessionRepository,
        messageRepository,
        knowledgeBaseRepository,
        caseRepository,
        queryService,
        listService,
        ragChatMapper,
        knowledgeBaseMapper,
        queryProperties
    );
  }

  // ========== 辅助方法 ==========

  private Document docWithKbId(Long kbId, String text) {
    Map<String, Object> metadata = new HashMap<>();
    if (kbId != null) {
      metadata.put("kb_id", kbId);
    }
    Document doc = new Document(text, metadata);
    setScore(doc, 0.85);
    return doc;
  }

  private KnowledgeBaseEntity kb(Long id, String filename, String service, String environment) {
    KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
    entity.setId(id);
    entity.setOriginalFilename(filename);
    entity.setService(service);
    entity.setEnvironment(environment);
    return entity;
  }

  private static void setScore(Document doc, Double score) {
    try {
      Field scoreField = Document.class.getDeclaredField("score");
      scoreField.setAccessible(true);
      scoreField.set(doc, score);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException("Failed to set Document score via reflection", e);
    }
  }

  // ========== 正常标签映射 ==========

  @Nested
  @DisplayName("正常标签映射")
  class NormalTagMapping {

    @Test
    @DisplayName("KB 有 service/environment → 来源包含标签")
    void kbWithTags_sourceContainsTags() {
      KnowledgeBaseEntity kb1 = kb(1L, "支付文档.pdf", "支付网关", "生产");
      when(knowledgeBaseRepository.findAllById(anyIterable())).thenReturn(List.of(kb1));

      RagChatSessionService service = createService();
      List<Document> docs = List.of(docWithKbId(1L, "支付相关片段"));

      List<SourceReference> sources = service.buildSourceReferences(docs);

      assertThat(sources).hasSize(1);
      SourceReference ref = sources.get(0);
      assertThat(ref.kbId()).isEqualTo(1L);
      assertThat(ref.documentName()).isEqualTo("支付文档.pdf");
      assertThat(ref.service()).isEqualTo("支付网关");
      assertThat(ref.environment()).isEqualTo("生产");
    }

    @Test
    @DisplayName("多个 KB 各有不同标签")
    void multipleKbs_differentTags() {
      KnowledgeBaseEntity kb1 = kb(1L, "文档A.pdf", "支付网关", "生产");
      KnowledgeBaseEntity kb2 = kb(2L, "文档B.pdf", "用户中心", "预发");
      when(knowledgeBaseRepository.findAllById(anyIterable())).thenReturn(List.of(kb1, kb2));

      RagChatSessionService service = createService();
      List<Document> docs = List.of(
          docWithKbId(1L, "片段A"),
          docWithKbId(2L, "片段B")
      );

      List<SourceReference> sources = service.buildSourceReferences(docs);

      assertThat(sources).hasSize(2);
      assertThat(sources.get(0).service()).isEqualTo("支付网关");
      assertThat(sources.get(0).environment()).isEqualTo("生产");
      assertThat(sources.get(1).service()).isEqualTo("用户中心");
      assertThat(sources.get(1).environment()).isEqualTo("预发");
    }
  }

  // ========== NULL 标签 ==========

  @Nested
  @DisplayName("NULL 标签处理")
  class NullTagHandling {

    @Test
    @DisplayName("KB 的 service/environment 为 null → 来源包含 null")
    void kbWithNullTags_sourceContainsNull() {
      KnowledgeBaseEntity kb1 = kb(1L, "未分类文档.pdf", null, null);
      when(knowledgeBaseRepository.findAllById(anyIterable())).thenReturn(List.of(kb1));

      RagChatSessionService service = createService();
      List<Document> docs = List.of(docWithKbId(1L, "未分类片段"));

      List<SourceReference> sources = service.buildSourceReferences(docs);

      assertThat(sources).hasSize(1);
      assertThat(sources.get(0).service()).isNull();
      assertThat(sources.get(0).environment()).isNull();
    }

    @Test
    @DisplayName("KB 只有 service 没有 environment")
    void kbWithOnlyService() {
      KnowledgeBaseEntity kb1 = kb(1L, "部分标签.pdf", "支付网关", null);
      when(knowledgeBaseRepository.findAllById(anyIterable())).thenReturn(List.of(kb1));

      RagChatSessionService service = createService();
      List<Document> docs = List.of(docWithKbId(1L, "部分标签片段"));

      List<SourceReference> sources = service.buildSourceReferences(docs);

      assertThat(sources).hasSize(1);
      assertThat(sources.get(0).service()).isEqualTo("支付网关");
      assertThat(sources.get(0).environment()).isNull();
    }
  }

  // ========== 缺失 KB ==========

  @Nested
  @DisplayName("缺失 KB 处理")
  class MissingKbHandling {

    @Test
    @DisplayName("kbId 在数据库中不存在 → 来源包含 null（不报错）")
    void kbNotFound_sourceContainsNullNoError() {
      when(knowledgeBaseRepository.findAllById(anyIterable())).thenReturn(List.of());

      RagChatSessionService service = createService();
      List<Document> docs = List.of(docWithKbId(999L, "孤儿片段"));

      List<SourceReference> sources = service.buildSourceReferences(docs);

      assertThat(sources).hasSize(1);
      SourceReference ref = sources.get(0);
      assertThat(ref.kbId()).isEqualTo(999L);
      assertThat(ref.documentName()).isEqualTo("未知文档");
      assertThat(ref.service()).isNull();
      assertThat(ref.environment()).isNull();
    }

    @Test
    @DisplayName("部分 KB 存在，部分 KB 缺失")
    void someKbsMissing() {
      KnowledgeBaseEntity kb1 = kb(1L, "存在的文档.pdf", "支付网关", "生产");
      when(knowledgeBaseRepository.findAllById(anyIterable())).thenReturn(List.of(kb1));

      RagChatSessionService service = createService();
      List<Document> docs = List.of(
          docWithKbId(1L, "存在的片段"),
          docWithKbId(2L, "缺失的片段")
      );

      List<SourceReference> sources = service.buildSourceReferences(docs);

      assertThat(sources).hasSize(2);
      assertThat(sources.get(0).documentName()).isEqualTo("存在的文档.pdf");
      assertThat(sources.get(0).service()).isEqualTo("支付网关");
      assertThat(sources.get(1).documentName()).isEqualTo("未知文档");
      assertThat(sources.get(1).service()).isNull();
    }
  }

  // ========== 批量一次查询 ==========

  @Nested
  @DisplayName("批量查询验证")
  class BatchQueryVerification {

    @Test
    @DisplayName("findAllById 只调用一次（不逐条查）")
    void findAllByIdCalledOnce() {
      KnowledgeBaseEntity kb1 = kb(1L, "文档A.pdf", "服务A", "环境A");
      KnowledgeBaseEntity kb2 = kb(2L, "文档B.pdf", "服务B", "环境B");
      KnowledgeBaseEntity kb3 = kb(3L, "文档C.pdf", "服务C", "环境C");
      when(knowledgeBaseRepository.findAllById(anyIterable())).thenReturn(List.of(kb1, kb2, kb3));

      RagChatSessionService service = createService();
      List<Document> docs = List.of(
          docWithKbId(1L, "片段A"),
          docWithKbId(2L, "片段B"),
          docWithKbId(3L, "片段C")
      );

      service.buildSourceReferences(docs);

      verify(knowledgeBaseRepository, times(1)).findAllById(anyIterable());
      verify(knowledgeBaseRepository, never()).findById(anyLong());
    }
  }

  // ========== 来源顺序 ==========

  @Nested
  @DisplayName("来源顺序验证")
  class SourceOrderVerification {

    @Test
    @DisplayName("来源顺序与检索结果一致")
    void sourceOrderMatchesRetrievalResults() {
      KnowledgeBaseEntity kb1 = kb(1L, "文档A.pdf", "服务A", "环境A");
      KnowledgeBaseEntity kb2 = kb(2L, "文档B.pdf", "服务B", "环境B");
      when(knowledgeBaseRepository.findAllById(anyIterable())).thenReturn(List.of(kb1, kb2));

      RagChatSessionService service = createService();
      List<Document> docs = List.of(
          docWithKbId(2L, "先出现的B片段"),
          docWithKbId(1L, "后出现的A片段")
      );

      List<SourceReference> sources = service.buildSourceReferences(docs);

      assertThat(sources).hasSize(2);
      // 顺序与 docs 一致，不是按 kbId 排序
      assertThat(sources.get(0).kbId()).isEqualTo(2L);
      assertThat(sources.get(0).documentName()).isEqualTo("文档B.pdf");
      assertThat(sources.get(1).kbId()).isEqualTo(1L);
      assertThat(sources.get(1).documentName()).isEqualTo("文档A.pdf");
    }
  }

  // ========== 空输入 ==========

  @Nested
  @DisplayName("空输入处理")
  class EmptyInputHandling {

    @Test
    @DisplayName("空文档列表返回空来源列表")
    void emptyDocsReturnsEmptySources() {
      RagChatSessionService service = createService();

      List<SourceReference> sources = service.buildSourceReferences(List.of());

      assertThat(sources).isEmpty();
      verify(knowledgeBaseRepository, never()).findAllById(anyIterable());
    }

    @Test
    @DisplayName("null 文档列表返回空来源列表")
    void nullDocsReturnsEmptySources() {
      RagChatSessionService service = createService();

      List<SourceReference> sources = service.buildSourceReferences(null);

      assertThat(sources).isEmpty();
      verify(knowledgeBaseRepository, never()).findAllById(anyIterable());
    }
  }
}
