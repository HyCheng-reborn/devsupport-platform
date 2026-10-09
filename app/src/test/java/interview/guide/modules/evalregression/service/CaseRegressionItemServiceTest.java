package interview.guide.modules.evalregression.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.infrastructure.mapper.CaseRegressionMapper;
import interview.guide.modules.caselibrary.model.CaseEntity;
import interview.guide.modules.caselibrary.model.CaseStatus;
import interview.guide.modules.caselibrary.repository.CaseRepository;
import interview.guide.modules.evalregression.model.CaseRegressionItemEntity;
import interview.guide.modules.evalregression.model.RegressionItemDTO;
import interview.guide.modules.evalregression.repository.CaseRegressionItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 案例回归项服务单元测试（零付费，纯 Mockito）。
 * <p>
 * 重点覆盖 query / keyPoints / expectedEvidence 的<b>确定性派生规则</b>：
 * query = title 与 problemDescription 各 trim 后非空者以单空格拼接；
 * keyPoints 先按换行再按中英文分号切分、剥离枚举前缀、过滤空串并按首次出现顺序去重；
 * 剥前缀后的要点仍是原始内容的连续子串，保证后续"逐字包含"判定成立。
 * <p>
 * JSON 编解码使用真实的 {@link RegressionJsonCodec}（真实 ObjectMapper），
 * 以便断言落库的 JSON 字符串可被正确反序列化。
 */
@DisplayName("案例回归项服务单元测试")
@ExtendWith(MockitoExtension.class)
class CaseRegressionItemServiceTest {

  @Mock private CaseRegressionItemRepository itemRepository;
  @Mock private CaseRepository caseRepository;
  @Mock private EmbeddingMetadataResolver embeddingMetadataResolver;
  @Mock private CaseRegressionMapper mapper;

  /** 真实编解码器，保证 JSON 断言贴近生产落库形态 */
  private final RegressionJsonCodec jsonCodec = new RegressionJsonCodec(new ObjectMapper());

  private CaseRegressionItemService service;

  @BeforeEach
  void setUp() {
    service = new CaseRegressionItemService(
      itemRepository, caseRepository, jsonCodec, embeddingMetadataResolver, mapper);
    // 默认元数据解析结果；部分用例不触发时以 lenient 避免 UnnecessaryStubbing
    lenient().when(embeddingMetadataResolver.resolveModelName()).thenReturn("text-embedding-v3");
    lenient().when(embeddingMetadataResolver.resolveDimension()).thenReturn(1024);
    lenient().when(itemRepository.save(any(CaseRegressionItemEntity.class)))
      .thenAnswer(inv -> inv.getArgument(0));
  }

  // ─────────── 辅助方法 ───────────

  private CaseEntity newCase(Long id, String title, String problem, String steps, String result) {
    return CaseEntity.builder()
      .id(id)
      .title(title)
      .problemDescription(problem)
      .resolutionSteps(steps)
      .resolutionResult(result)
      .status(CaseStatus.PUBLISHED)
      .build();
  }

  /** 捕获 save 的回归项实体，便于断言派生字段 */
  private CaseRegressionItemEntity captureSaved() {
    ArgumentCaptor<CaseRegressionItemEntity> captor =
      ArgumentCaptor.forClass(CaseRegressionItemEntity.class);
    verify(itemRepository).save(captor.capture());
    return captor.getValue();
  }

  // ═══════════════ query 拼接规则 ═══════════════

  @Nested
  @DisplayName("query 由 title 与 problemDescription 确定性拼接")
  class BuildQuery {

    @BeforeEach
    void stubNew() {
      when(itemRepository.findByCaseId(anyLong())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("title 与 problem 均非空时以单空格拼接")
    void bothPresent_joinedWithSingleSpace() {
      service.upsertOnPublish(newCase(1L, "登录接口超时", "网关连接池耗尽导致超时", null, null), List.of());

      assertThat(captureSaved().getQuery()).isEqualTo("登录接口超时 网关连接池耗尽导致超时");
    }

    @Test
    @DisplayName("title 与 problem 之间的多余空白被 trim，仅保留单空格")
    void surroundingWhitespace_trimmed() {
      service.upsertOnPublish(newCase(1L, "  标题A  ", "  问题B  ", null, null), List.of());

      assertThat(captureSaved().getQuery()).isEqualTo("标题A 问题B");
    }

    @Test
    @DisplayName("problem 为 null 时 query 仅含 title，无尾随空格")
    void problemNull_queryIsTitle() {
      service.upsertOnPublish(newCase(1L, "只有标题", null, null, null), List.of());

      assertThat(captureSaved().getQuery()).isEqualTo("只有标题");
    }

    @Test
    @DisplayName("problem 为纯空白时视作缺失，query 仅含 title")
    void problemBlank_queryIsTitle() {
      service.upsertOnPublish(newCase(1L, "只有标题", "   \t  ", null, null), List.of());

      assertThat(captureSaved().getQuery()).isEqualTo("只有标题");
    }

    @Test
    @DisplayName("title 为 null 时 query 仅含 problem")
    void titleNull_queryIsProblem() {
      service.upsertOnPublish(newCase(1L, null, "只有问题描述", null, null), List.of());

      assertThat(captureSaved().getQuery()).isEqualTo("只有问题描述");
    }

    @Test
    @DisplayName("title 与 problem 均为空时 query 为空串")
    void bothBlank_queryEmpty() {
      service.upsertOnPublish(newCase(1L, "  ", null, null, null), List.of());

      assertThat(captureSaved().getQuery()).isEmpty();
    }
  }

  // ═══════════════ keyPoints 提取规则 ═══════════════

  @Nested
  @DisplayName("keyPoints 从解决步骤/结果确定性提取")
  class ExtractKeyPoints {

    @BeforeEach
    void stubNew() {
      when(itemRepository.findByCaseId(anyLong())).thenReturn(Optional.empty());
    }

    private List<String> keyPointsOf(CaseEntity caseEntity) {
      service.upsertOnPublish(caseEntity, List.of());
      return jsonCodec.toStringList(captureSaved().getKeyPoints());
    }

    @Test
    @DisplayName("按换行切分为多个要点")
    void splitByNewline() {
      List<String> points = keyPointsOf(
        newCase(1L, "t", "p", "重启服务\n扩容连接池\n清理缓存", null));

      assertThat(points).containsExactly("重启服务", "扩容连接池", "清理缓存");
    }

    @Test
    @DisplayName("同一行内按中英文分号切分")
    void splitBySemicolon_bothChineseAndEnglish() {
      List<String> points = keyPointsOf(
        newCase(1L, "t", "p", "调大超时；重试三次; 熔断降级", null));

      assertThat(points).containsExactly("调大超时", "重试三次", "熔断降级");
    }

    @Test
    @DisplayName("换行与分号混合切分")
    void splitByNewlineAndSemicolon() {
      List<String> points = keyPointsOf(
        newCase(1L, "t", "p", "第一步;第二步\n第三步；第四步", null));

      assertThat(points).containsExactly("第一步", "第二步", "第三步", "第四步");
    }

    @Test
    @DisplayName("剥离有序列表前缀（1. / 2、 / 3) / 4））")
    void stripOrderedPrefix() {
      List<String> points = keyPointsOf(
        newCase(1L, "t", "p", "1. 检查网络\n2、调整连接池\n3) 增加重试\n4）重启节点", null));

      assertThat(points).containsExactly("检查网络", "调整连接池", "增加重试", "重启节点");
    }

    @Test
    @DisplayName("剥离无序列表前缀（- / * / • / ·）")
    void stripBulletPrefix() {
      List<String> points = keyPointsOf(
        newCase(1L, "t", "p", "- 采集日志\n* 定位堆栈\n• 复现问题\n· 验证修复", null));

      assertThat(points).containsExactly("采集日志", "定位堆栈", "复现问题", "验证修复");
    }

    @Test
    @DisplayName("过滤空串（连续分号 / 空白行 / 仅前缀）")
    void filterEmptySegments() {
      List<String> points = keyPointsOf(
        newCase(1L, "t", "p", "要点A;;\n   \n要点B\n-", null));

      assertThat(points).containsExactly("要点A", "要点B");
    }

    @Test
    @DisplayName("重复要点去重且保持首次出现顺序")
    void deduplicatePreserveOrder() {
      List<String> points = keyPointsOf(
        newCase(1L, "t", "p", "乙\n甲\n乙\n丙\n甲", null));

      assertThat(points).containsExactly("乙", "甲", "丙");
    }

    @Test
    @DisplayName("resolutionSteps 与 resolutionResult 依次合并，步骤在前")
    void mergeStepsThenResult() {
      List<String> points = keyPointsOf(
        newCase(1L, "t", "p", "步骤一;步骤二", "结果一;结果二"));

      assertThat(points).containsExactly("步骤一", "步骤二", "结果一", "结果二");
    }

    @Test
    @DisplayName("steps 与 result 之间的重复项跨字段去重")
    void deduplicateAcrossFields() {
      List<String> points = keyPointsOf(
        newCase(1L, "t", "p", "共用要点;独有步骤", "共用要点;独有结果"));

      assertThat(points).containsExactly("共用要点", "独有步骤", "独有结果");
    }

    @Test
    @DisplayName("steps 与 result 均为空时要点为空列表")
    void noResolution_emptyKeyPoints() {
      List<String> points = keyPointsOf(newCase(1L, "t", "p", null, "   "));

      assertThat(points).isEmpty();
    }

    @Test
    @DisplayName("剥离前缀后的要点仍是原始文本的连续子串（保证逐字包含）")
    void strippedPointsAreSubstringsOfOriginal() {
      String steps = "1. 检查网络连通性\n2、调整连接池上限\n- 采集完整堆栈日志";
      String result = "问题已彻底解决;服务恢复正常";
      CaseEntity caseEntity = newCase(1L, "t", "p", steps, result);

      List<String> points = keyPointsOf(caseEntity);

      assertThat(points).isNotEmpty();
      // 每个要点都应能在原始内容中逐字找到，确保召回内容 contains 判定成立
      assertThat(points).allSatisfy(point ->
        assertThat(steps.contains(point) || result.contains(point))
          .as("要点 [%s] 应为原始解决内容的连续子串", point)
          .isTrue());
    }
  }

  // ═══════════════ expectedEvidence 与元数据绑定 ═══════════════

  @Nested
  @DisplayName("expectedEvidence 与 embedding 元数据绑定")
  class EvidenceAndMetadata {

    @BeforeEach
    void stubNew() {
      // null / 缺 ID 的用例会提前返回，不触发 findByCaseId，故用 lenient
      lenient().when(itemRepository.findByCaseId(anyLong())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("expectedEvidence 落库为证据 ID 列表且可反序列化还原")
    void expectedEvidenceStoredAsEvidenceIds() {
      List<String> evidence = List.of("ev-1", "ev-2", "ev-3");

      service.upsertOnPublish(newCase(1L, "t", "p", "s", "r"), evidence);

      CaseRegressionItemEntity saved = captureSaved();
      assertThat(jsonCodec.toStringList(saved.getExpectedEvidence())).containsExactlyElementsOf(evidence);
    }

    @Test
    @DisplayName("expectedEvidence 为 null 时落库为空列表 JSON")
    void nullEvidence_storedAsEmptyList() {
      service.upsertOnPublish(newCase(1L, "t", "p", "s", "r"), null);

      CaseRegressionItemEntity saved = captureSaved();
      assertThat(jsonCodec.toStringList(saved.getExpectedEvidence())).isEmpty();
    }

    @Test
    @DisplayName("active 置为 true，embedding 模型名与维度来自解析器")
    void activeAndEmbeddingMetadataBound() {
      service.upsertOnPublish(newCase(1L, "t", "p", "s", "r"), List.of("ev-1"));

      CaseRegressionItemEntity saved = captureSaved();
      assertThat(saved.getActive()).isTrue();
      assertThat(saved.getEmbeddingModel()).isEqualTo("text-embedding-v3");
      assertThat(saved.getEmbeddingDimension()).isEqualTo(1024);
      assertThat(saved.getCaseId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("案例为 null 时跳过写入")
    void nullCase_skipped() {
      service.upsertOnPublish(null, List.of("ev-1"));

      verify(itemRepository, never()).save(any());
    }

    @Test
    @DisplayName("案例缺少 ID 时跳过写入")
    void caseWithoutId_skipped() {
      service.upsertOnPublish(newCase(null, "t", "p", "s", "r"), List.of("ev-1"));

      verify(itemRepository, never()).save(any());
    }
  }

  // ═══════════════ 停用与重新发布 ═══════════════

  @Nested
  @DisplayName("废弃停用与重新发布恢复")
  class DeactivateAndRepublish {

    @Test
    @DisplayName("deactivateOnDeprecate 将已存在回归项置为 active=false")
    void deactivate_setsActiveFalse() {
      CaseRegressionItemEntity existing = CaseRegressionItemEntity.builder()
        .id(10L).caseId(1L).query("q").active(true).build();
      when(itemRepository.findByCaseId(1L)).thenReturn(Optional.of(existing));

      service.deactivateOnDeprecate(1L);

      CaseRegressionItemEntity saved = captureSaved();
      assertThat(saved.getActive()).isFalse();
      assertThat(saved.getId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("deactivateOnDeprecate 无对应回归项时不写入")
    void deactivate_noItem_noSave() {
      when(itemRepository.findByCaseId(1L)).thenReturn(Optional.empty());

      service.deactivateOnDeprecate(1L);

      verify(itemRepository, never()).save(any());
    }

    @Test
    @DisplayName("重新发布已停用回归项时恢复 active=true 并更新派生字段")
    void republish_reactivatesExistingItem() {
      CaseRegressionItemEntity existing = CaseRegressionItemEntity.builder()
        .id(10L).caseId(1L).query("旧query").active(false).build();
      when(itemRepository.findByCaseId(1L)).thenReturn(Optional.of(existing));

      service.upsertOnPublish(newCase(1L, "新标题", "新问题", "新步骤", "新结果"), List.of("ev-9"));

      CaseRegressionItemEntity saved = captureSaved();
      assertThat(saved.getId()).isEqualTo(10L);
      assertThat(saved.getActive()).isTrue();
      assertThat(saved.getQuery()).isEqualTo("新标题 新问题");
      assertThat(jsonCodec.toStringList(saved.getExpectedEvidence())).containsExactly("ev-9");
      assertThat(jsonCodec.toStringList(saved.getKeyPoints())).containsExactly("新步骤", "新结果");
    }
  }

  // ═══════════════ listItems ═══════════════

  @Nested
  @DisplayName("listItems 列出全部回归项并补齐案例信息")
  class ListItems {

    @Test
    @DisplayName("无回归项时返回空列表")
    void emptyItems_returnsEmpty() {
      when(itemRepository.findAllByOrderByIdAsc()).thenReturn(List.of());

      assertThat(service.listItems()).isEmpty();
    }

    @Test
    @DisplayName("返回项携带关联案例标题与状态")
    void itemsCarryCaseTitleAndStatus() {
      CaseRegressionItemEntity item = CaseRegressionItemEntity.builder()
        .id(10L).caseId(1L).query("q")
        .expectedEvidence("[\"ev-1\"]").keyPoints("[\"kp-1\"]")
        .embeddingModel("m").embeddingDimension(1024).active(true).build();
      CaseEntity caseEntity = newCase(1L, "案例标题X", "p", "s", "r");
      caseEntity.setStatus(CaseStatus.PUBLISHED);

      when(itemRepository.findAllByOrderByIdAsc()).thenReturn(List.of(item));
      when(caseRepository.findAllById(List.of(1L))).thenReturn(List.of(caseEntity));
      when(mapper.toItemDTO(any(), anyString(), anyString())).thenAnswer(inv ->
        new RegressionItemDTO(10L, 1L, inv.getArgument(1), inv.getArgument(2),
          "q", List.of("ev-1"), List.of("kp-1"), "m", 1024, true, "SOURCE"));

      List<RegressionItemDTO> result = service.listItems();

      assertThat(result).hasSize(1);
      assertThat(result.getFirst().caseTitle()).isEqualTo("案例标题X");
      assertThat(result.getFirst().caseStatus()).isEqualTo("PUBLISHED");
      verify(mapper).toItemDTO(item, "案例标题X", "PUBLISHED");
    }

    @Test
    @DisplayName("关联案例缺失时标题回退为“未知案例”、状态回退为 DRAFT")
    void missingCase_fallbackTitleAndStatus() {
      CaseRegressionItemEntity item = CaseRegressionItemEntity.builder()
        .id(10L).caseId(99L).query("q").active(true).build();
      when(itemRepository.findAllByOrderByIdAsc()).thenReturn(List.of(item));
      when(caseRepository.findAllById(List.of(99L))).thenReturn(List.of());
      when(mapper.toItemDTO(any(), anyString(), anyString())).thenAnswer(inv ->
        new RegressionItemDTO(10L, 99L, inv.getArgument(1), inv.getArgument(2),
          "q", List.of(), List.of(), null, null, true, "MISSING"));

      List<RegressionItemDTO> result = service.listItems();

      assertThat(result.getFirst().caseTitle()).isEqualTo("未知案例");
      assertThat(result.getFirst().caseStatus()).isEqualTo(CaseStatus.DRAFT.name());
    }
  }
}
