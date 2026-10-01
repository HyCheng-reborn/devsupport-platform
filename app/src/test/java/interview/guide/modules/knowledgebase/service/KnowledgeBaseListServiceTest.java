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
        e.getOriginalFilename(), e.getFileSize(), e.getContentType(),
        e.getUploadedAt(), e.getLastAccessedAt(), e.getAccessCount(), e.getQuestionCount(),
        e.getVectorStatus(), e.getVectorError(), e.getChunkCount(),
        e.getQuestionGenStatus(), e.getQuestionGenError());
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
      KnowledgeBaseEntity kb1 = buildKb(1L, "A", "支付", "生产");
      KnowledgeBaseEntity kb2 = buildKb(2L, "B", null, "测试");
      KnowledgeBaseEntity kb3 = buildKb(3L, "C", null, null);
      List<KnowledgeBaseEntity> all = List.of(kb1, kb2, kb3);
      when(knowledgeBaseRepository.findAllByOrderByUploadedAtDesc()).thenReturn(all);
      List<KnowledgeBaseEntity> filtered = all.stream()
          .filter(e -> "测试".equals(e.getEnvironment())).toList();
      when(knowledgeBaseMapper.toListItemDTOList(filtered))
          .thenReturn(filtered.stream().map(KnowledgeBaseListServiceTest::toDTO).toList());

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
      KnowledgeBaseEntity kb2 = buildKb(2L, "B", null, null);
      List<KnowledgeBaseEntity> all = List.of(kb1, kb2);
      when(knowledgeBaseRepository.findAllByOrderByUploadedAtDesc()).thenReturn(all);
      List<KnowledgeBaseEntity> filtered = all.stream()
          .filter(e -> "生产".equals(e.getEnvironment())).toList();
      when(knowledgeBaseMapper.toListItemDTOList(filtered))
          .thenReturn(filtered.stream().map(KnowledgeBaseListServiceTest::toDTO).toList());

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
}
