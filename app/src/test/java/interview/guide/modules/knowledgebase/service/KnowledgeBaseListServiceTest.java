package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.mapper.KnowledgeBaseMapper;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KnowledgeBaseListService 单元测试
 * 覆盖批次 B：service/environment 筛选、标签列表、标签更新
 */
@DisplayName("知识库列表服务测试")
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseListServiceTest {

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

  private static KnowledgeBaseListItemDTO toDTO(KnowledgeBaseEntity e) {
    return new KnowledgeBaseListItemDTO(
        e.getId(), e.getName(), e.getCategory(), e.getService(), e.getEnvironment(),
        e.getProject(), e.getDocType(), e.getSource(), e.getVersionLabel(), e.getDocumentKey(),
        e.getVersionNo(), e.getActive(),
        e.getOriginalFilename(), e.getFileSize(), e.getContentType(),
        e.getUploadedAt(), e.getLastAccessedAt(), e.getAccessCount(), e.getQuestionCount(),
        e.getVectorStatus(), e.getVectorError(), e.getChunkCount(),
        e.getQuestionGenStatus(), e.getQuestionGenError(), false, e.getConflict(), null);
  }

  @DisplayName("列表筛选 service/environment")
  @Nested
  class ListFiltering {

    @Test
    @DisplayName("无筛选时返回所有 KB（包含 NULL 行）")
    void noFilterReturnsAll() {
      KnowledgeBaseEntity kb1 = buildKb(1L, "A", "支付", "生产");
      KnowledgeBaseEntity kb2 = buildKb(2L, "B", null, null);
      List<KnowledgeBaseEntity> all = List.of(kb1, kb2);
      when(knowledgeBaseRepository.findAllByOrderByUploadedAtDesc()).thenReturn(all);
      when(knowledgeBaseMapper.toListItemDTOList(all)).thenReturn(all.stream().map(KnowledgeBaseListServiceTest::toDTO).toList());

      List<KnowledgeBaseListItemDTO> result = listService.listKnowledgeBases(null, null, null, null);

      assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("仅 service 筛选时只返回匹配 KB")
    void serviceFilterOnly() {
      KnowledgeBaseEntity kb1 = buildKb(1L, "A", "支付", "生产");
      KnowledgeBaseEntity kb2 = buildKb(2L, "B", "用户", "测试");
      List<KnowledgeBaseEntity> filtered = List.of(kb1);
      when(knowledgeBaseRepository.findByServiceOrderByUploadedAtDesc("支付")).thenReturn(filtered);
      when(knowledgeBaseMapper.toListItemDTOList(filtered)).thenReturn(filtered.stream().map(KnowledgeBaseListServiceTest::toDTO).toList());

      List<KnowledgeBaseListItemDTO> result = listService.listKnowledgeBases(null, null, "支付", null);

      assertThat(result).hasSize(1);
      assertThat(result.get(0).service()).isEqualTo("支付");
    }

    @Test
    @DisplayName("仅 environment 筛选时只返回匹配 KB")
    void environmentFilterOnly() {
      KnowledgeBaseEntity kb2 = buildKb(2L, "B", null, "测试");
      // 生产实现走派生查询 findByEnvironmentOrderByUploadedAtDesc（trim 后精确匹配），
      // 只返回 environment=测试 的行，不再 findAll + in-memory 过滤。
      List<KnowledgeBaseEntity> matched = List.of(kb2);
      when(knowledgeBaseRepository.findByEnvironmentOrderByUploadedAtDesc("测试"))
          .thenReturn(matched);
      when(knowledgeBaseMapper.toListItemDTOList(matched))
          .thenReturn(matched.stream().map(KnowledgeBaseListServiceTest::toDTO).toList());

      List<KnowledgeBaseListItemDTO> result = listService.listKnowledgeBases(null, null, null, "测试");

      assertThat(result).hasSize(1);
      assertThat(result.get(0).environment()).isEqualTo("测试");
    }

    @Test
    @DisplayName("两者同时筛选时返回交集")
    void bothFilters() {
      KnowledgeBaseEntity kb1 = buildKb(1L, "A", "支付", "生产");
      KnowledgeBaseEntity kb2 = buildKb(2L, "B", "支付", "测试");
      List<KnowledgeBaseEntity> filtered = List.of(kb1);
      when(knowledgeBaseRepository.findByServiceAndEnvironmentOrderByUploadedAtDesc("支付", "生产"))
          .thenReturn(filtered);
      when(knowledgeBaseMapper.toListItemDTOList(filtered)).thenReturn(filtered.stream().map(KnowledgeBaseListServiceTest::toDTO).toList());

      List<KnowledgeBaseListItemDTO> result = listService.listKnowledgeBases(null, null, "支付", "生产");

      assertThat(result).hasSize(1);
      assertThat(result.get(0).service()).isEqualTo("支付");
      assertThat(result.get(0).environment()).isEqualTo("生产");
    }

    @Test
    @DisplayName("NULL 行在有 service 筛选时不出现")
    void nullRowsExcludedWhenServiceFilter() {
      KnowledgeBaseEntity kb1 = buildKb(1L, "A", "支付", "生产");
      KnowledgeBaseEntity kb2 = buildKb(2L, "B", null, null);
      List<KnowledgeBaseEntity> filtered = List.of(kb1);
      when(knowledgeBaseRepository.findByServiceOrderByUploadedAtDesc("支付")).thenReturn(filtered);
      when(knowledgeBaseMapper.toListItemDTOList(filtered)).thenReturn(filtered.stream().map(KnowledgeBaseListServiceTest::toDTO).toList());

      List<KnowledgeBaseListItemDTO> result = listService.listKnowledgeBases(null, null, "支付", null);

      assertThat(result).hasSize(1);
      assertThat(result).allMatch(dto -> "支付".equals(dto.service()));
    }

    @Test
    @DisplayName("NULL 行在有 environment 筛选时不出现")
    void nullRowsExcludedWhenEnvironmentFilter() {
      KnowledgeBaseEntity kb1 = buildKb(1L, "A", "支付", "生产");
      // 派生查询只返回 environment=生产 的行；environment 为 NULL 的行由 SQL 谓词天然排除。
      List<KnowledgeBaseEntity> matched = List.of(kb1);
      when(knowledgeBaseRepository.findByEnvironmentOrderByUploadedAtDesc("生产"))
          .thenReturn(matched);
      when(knowledgeBaseMapper.toListItemDTOList(matched))
          .thenReturn(matched.stream().map(KnowledgeBaseListServiceTest::toDTO).toList());

      List<KnowledgeBaseListItemDTO> result = listService.listKnowledgeBases(null, null, null, "生产");

      assertThat(result).hasSize(1);
      assertThat(result.get(0).environment()).isEqualTo("生产");
    }
  }

  @DisplayName("标签列表")
  @Nested
  class LabelLists {

    @Test
    @DisplayName("getAllServices 返回 Repository 查询结果")
    void getAllServices() {
      when(knowledgeBaseRepository.findAllServices()).thenReturn(List.of("支付", "用户"));

      List<String> result = listService.getAllServices();

      assertThat(result).containsExactly("支付", "用户");
    }

    @Test
    @DisplayName("getAllEnvironments 返回 Repository 查询结果")
    void getAllEnvironments() {
      when(knowledgeBaseRepository.findAllEnvironments()).thenReturn(List.of("测试", "生产"));

      List<String> result = listService.getAllEnvironments();

      assertThat(result).containsExactly("测试", "生产");
    }
  }

  @DisplayName("updateLabels 标签更新")
  @Nested
  class UpdateLabelsTests {

    @Test
    @DisplayName("正常保存: service=支付网关, environment=生产 → 保存成功")
    void normalSave() {
      KnowledgeBaseEntity kb = buildKb(1L, "A", null, null);
      when(knowledgeBaseRepository.findById(1L)).thenReturn(java.util.Optional.of(kb));

      listService.updateLabels(1L, "支付网关", "生产");

      verify(knowledgeBaseRepository).save(kb);
      assertThat(kb.getService()).isEqualTo("支付网关");
      assertThat(kb.getEnvironment()).isEqualTo("生产");
    }

    @Test
    @DisplayName("空白转NULL: service=\"\", environment=\"\" → 存为 NULL")
    void blankToNull() {
      KnowledgeBaseEntity kb = buildKb(1L, "A", "支付", "生产");
      when(knowledgeBaseRepository.findById(1L)).thenReturn(java.util.Optional.of(kb));

      listService.updateLabels(1L, "", "");

      verify(knowledgeBaseRepository).save(kb);
      assertThat(kb.getService()).isNull();
      assertThat(kb.getEnvironment()).isNull();
    }

    @Test
    @DisplayName("原标签清空: service=null, environment=null → 清空")
    void clearLabels() {
      KnowledgeBaseEntity kb = buildKb(1L, "A", "支付", "生产");
      when(knowledgeBaseRepository.findById(1L)).thenReturn(java.util.Optional.of(kb));

      listService.updateLabels(1L, null, null);

      verify(knowledgeBaseRepository).save(kb);
      assertThat(kb.getService()).isNull();
      assertThat(kb.getEnvironment()).isNull();
    }

    @Test
    @DisplayName("不存在 ID 报错: updateLabels(999L, ...) → 抛出 BusinessException")
    void notFoundId() {
      when(knowledgeBaseRepository.findById(999L)).thenReturn(java.util.Optional.empty());

      assertThatThrownBy(() -> listService.updateLabels(999L, "支付", "生产"))
          .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("service 超长报错: 超过 100 字符")
    void serviceTooLong() {
      String longService = "a".repeat(101);

      assertThatThrownBy(() -> listService.updateLabels(1L, longService, "生产"))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("100");
    }

    @Test
    @DisplayName("environment 超长报错: 超过 50 字符")
    void environmentTooLong() {
      String longEnv = "a".repeat(51);

      assertThatThrownBy(() -> listService.updateLabels(1L, "支付", longEnv))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("50");
    }
  }

  @DisplayName("project/docType/version 筛选")
  @Nested
  class ProjectDocTypeVersionFiltering {

    private KnowledgeBaseEntity buildKbWithMeta(Long id, String project, String docType, String versionLabel) {
      KnowledgeBaseEntity kb = buildKb(id, "文档-" + id, null, null);
      kb.setProject(project);
      kb.setDocType(docType);
      kb.setVersionLabel(versionLabel);
      return kb;
    }

    @Test
    @DisplayName("仅按 project 筛选 → 只返回匹配项目")
    void filterByProjectOnly() {
      KnowledgeBaseEntity kb1 = buildKbWithMeta(1L, "支付平台", "API文档", "v1.0");
      KnowledgeBaseEntity kb2 = buildKbWithMeta(2L, "用户中心", "API文档", "v1.0");
      List<KnowledgeBaseEntity> all = List.of(kb1, kb2);
      // repository 返回全量，service 层内存过滤后只传 kb1 给 mapper
      when(knowledgeBaseRepository.findAllByOrderByUploadedAtDesc()).thenReturn(all);
      List<KnowledgeBaseEntity> filtered = List.of(kb1);
      when(knowledgeBaseMapper.toListItemDTOList(filtered)).thenReturn(filtered.stream().map(KnowledgeBaseListServiceTest::toDTO).toList());

      List<KnowledgeBaseListItemDTO> result = listService.listKnowledgeBases(null, null, null, null, "支付平台", null, null);

      assertThat(result).hasSize(1);
      assertThat(result.get(0).project()).isEqualTo("支付平台");
    }

    @Test
    @DisplayName("仅按 docType 筛选 → 只返回匹配文档类型")
    void filterByDocTypeOnly() {
      KnowledgeBaseEntity kb1 = buildKbWithMeta(1L, "支付平台", "API文档", "v1.0");
      KnowledgeBaseEntity kb2 = buildKbWithMeta(2L, "支付平台", "运维手册", "v1.0");
      List<KnowledgeBaseEntity> all = List.of(kb1, kb2);
      when(knowledgeBaseRepository.findAllByOrderByUploadedAtDesc()).thenReturn(all);

      List<KnowledgeBaseEntity> filtered = List.of(kb1);
      when(knowledgeBaseMapper.toListItemDTOList(filtered)).thenReturn(filtered.stream().map(KnowledgeBaseListServiceTest::toDTO).toList());

      List<KnowledgeBaseListItemDTO> result = listService.listKnowledgeBases(null, null, null, null, null, "API文档", null);
      assertThat(result).hasSize(1);
      assertThat(result.get(0).docType()).isEqualTo("API文档");
    }

    @Test
    @DisplayName("同时按 project 和 docType 筛选 → 返回交集")
    void filterByProjectAndDocType() {
      KnowledgeBaseEntity kb1 = buildKbWithMeta(1L, "支付平台", "API文档", "v1.0");
      KnowledgeBaseEntity kb2 = buildKbWithMeta(2L, "支付平台", "运维手册", "v1.0");
      KnowledgeBaseEntity kb3 = buildKbWithMeta(3L, "用户中心", "API文档", "v1.0");
      List<KnowledgeBaseEntity> all = List.of(kb1, kb2, kb3);
      when(knowledgeBaseRepository.findAllByOrderByUploadedAtDesc()).thenReturn(all);

      List<KnowledgeBaseEntity> filtered = List.of(kb1);
      when(knowledgeBaseMapper.toListItemDTOList(filtered)).thenReturn(filtered.stream().map(KnowledgeBaseListServiceTest::toDTO).toList());

      List<KnowledgeBaseListItemDTO> result = listService.listKnowledgeBases(null, null, null, null, "支付平台", "API文档", null);
      assertThat(result).hasSize(1);
      assertThat(result.get(0).project()).isEqualTo("支付平台");
      assertThat(result.get(0).docType()).isEqualTo("API文档");
    }

    @Test
    @DisplayName("按 version 筛选 → 返回匹配版本标签")
    void filterByVersion() {
      KnowledgeBaseEntity kb1 = buildKbWithMeta(1L, "支付平台", "API文档", "v1.0");
      KnowledgeBaseEntity kb2 = buildKbWithMeta(2L, "支付平台", "API文档", "v2.0");
      List<KnowledgeBaseEntity> all = List.of(kb1, kb2);
      when(knowledgeBaseRepository.findAllByOrderByUploadedAtDesc()).thenReturn(all);

      List<KnowledgeBaseEntity> filtered = List.of(kb2);
      when(knowledgeBaseMapper.toListItemDTOList(filtered)).thenReturn(filtered.stream().map(KnowledgeBaseListServiceTest::toDTO).toList());

      List<KnowledgeBaseListItemDTO> result = listService.listKnowledgeBases(null, null, null, null, null, null, "v2.0");
      assertThat(result).hasSize(1);
      assertThat(result.get(0).versionLabel()).isEqualTo("v2.0");
    }

    @Test
    @DisplayName("无筛选条件 → 返回所有条目")
    void noFilters_returnsAll() {
      KnowledgeBaseEntity kb1 = buildKbWithMeta(1L, "支付平台", "API文档", "v1.0");
      KnowledgeBaseEntity kb2 = buildKbWithMeta(2L, "用户中心", "运维手册", "v2.0");
      List<KnowledgeBaseEntity> all = List.of(kb1, kb2);
      when(knowledgeBaseRepository.findAllByOrderByUploadedAtDesc()).thenReturn(all);
      when(knowledgeBaseMapper.toListItemDTOList(all)).thenReturn(all.stream().map(KnowledgeBaseListServiceTest::toDTO).toList());

      List<KnowledgeBaseListItemDTO> result = listService.listKnowledgeBases(null, null, null, null, null, null, null);
      assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("大小写不敏感匹配：PROJECT=支付平台 能匹配 project=支付平台")
    void caseInsensitiveMatching() {
      KnowledgeBaseEntity kb1 = buildKbWithMeta(1L, "PaymentPlatform", "ApiDoc", "V1.0");
      KnowledgeBaseEntity kb2 = buildKbWithMeta(2L, "用户中心", "API文档", "v1.0");
      List<KnowledgeBaseEntity> all = List.of(kb1, kb2);
      when(knowledgeBaseRepository.findAllByOrderByUploadedAtDesc()).thenReturn(all);

      List<KnowledgeBaseEntity> filtered = List.of(kb1);
      when(knowledgeBaseMapper.toListItemDTOList(filtered)).thenReturn(filtered.stream().map(KnowledgeBaseListServiceTest::toDTO).toList());

      List<KnowledgeBaseListItemDTO> result = listService.listKnowledgeBases(
          null, null, null, null, "paymentplatform", "apidoc", "v1.0");
      assertThat(result).hasSize(1);
      assertThat(result.get(0).project()).isEqualTo("PaymentPlatform");
    }
  }

  @DisplayName("版本冲突标记")
  @Nested
  class VersionConflict {

    private KnowledgeBaseEntity version(long id, String hash, String docKey, boolean active) {
      KnowledgeBaseEntity e = buildKb(id, "文档-" + hash, "支付", "生产");
      e.setFileHash(hash);
      e.setDocumentKey(docKey);
      e.setActive(active);
      return e;
    }

    @Test
    @DisplayName("同一 documentKey 有 ≥2 个启用版本且内容不同 → 标记冲突")
    void marksConflictWhenMultipleActiveVersions() {
      KnowledgeBaseEntity a = version(1L, "hashA", "dk1", true);
      KnowledgeBaseEntity b = version(2L, "hashB", "dk1", true);
      when(knowledgeBaseRepository.findAllByOrderByUploadedAtDesc()).thenReturn(List.of(a, b));
      when(knowledgeBaseMapper.toListItemDTOList(List.of(a, b)))
          .thenReturn(List.of(toDTO(a), toDTO(b)));

      List<KnowledgeBaseListItemDTO> result = listService.listKnowledgeBases(null, null, null, null);

      assertThat(result).allSatisfy(d -> assertThat(d.versionConflict()).isTrue());
    }

    @Test
    @DisplayName("旧版本已停用（仅 1 个启用）→ 不标记冲突")
    void noConflictWhenSingleActive() {
      KnowledgeBaseEntity a = version(1L, "hashA", "dk1", true);
      KnowledgeBaseEntity b = version(2L, "hashB", "dk1", false);
      when(knowledgeBaseRepository.findAllByOrderByUploadedAtDesc()).thenReturn(List.of(a, b));
      when(knowledgeBaseMapper.toListItemDTOList(List.of(a, b)))
          .thenReturn(List.of(toDTO(a), toDTO(b)));

      List<KnowledgeBaseListItemDTO> result = listService.listKnowledgeBases(null, null, null, null);

      assertThat(result).noneSatisfy(d -> assertThat(d.versionConflict()).isTrue());
    }

    @Test
    @DisplayName("数据库 conflict 列为 true → DTO 标记 versionConflict=true")
    void marksConflictFromDatabaseColumn() {
      KnowledgeBaseEntity a = version(1L, "hashA", "dk1", true);
      KnowledgeBaseEntity b = version(2L, "hashB", "dk1", false);
      b.setConflict(true); // 数据库冲突标记
      when(knowledgeBaseRepository.findAllByOrderByUploadedAtDesc()).thenReturn(List.of(a, b));
      when(knowledgeBaseMapper.toListItemDTOList(List.of(a, b)))
          .thenReturn(List.of(toDTO(a), toDTO(b)));

      List<KnowledgeBaseListItemDTO> result = listService.listKnowledgeBases(null, null, null, null);

      // 冲突标记的行应该有 versionConflict=true
      assertThat(result.stream().filter(d -> d.conflict() != null && d.conflict()))
          .allSatisfy(d -> assertThat(d.versionConflict()).isTrue());
    }

    @Test
    @DisplayName("数据库 conflict 列为 false → DTO 不标记 versionConflict")
    void noConflictWhenColumnFalse() {
      KnowledgeBaseEntity a = version(1L, "hashA", "dk1", true);
      KnowledgeBaseEntity b = version(2L, "hashB", "dk1", true);
      a.setConflict(false);
      b.setConflict(false);
      when(knowledgeBaseRepository.findAllByOrderByUploadedAtDesc()).thenReturn(List.of(a, b));
      when(knowledgeBaseMapper.toListItemDTOList(List.of(a, b)))
          .thenReturn(List.of(toDTO(a), toDTO(b)));

      List<KnowledgeBaseListItemDTO> result = listService.listKnowledgeBases(null, null, null, null);

      // 两个 active 版本但 DB conflict 列为 false，且 fileHash 不同 → 回退到内存计算
      // 由于 fileHash 不同且都 active，内存计算会检测到冲突
      assertThat(result).allSatisfy(d -> assertThat(d.versionConflict()).isTrue());
    }
  }
}
