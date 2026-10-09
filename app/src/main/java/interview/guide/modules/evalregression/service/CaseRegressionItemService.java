package interview.guide.modules.evalregression.service;

import interview.guide.infrastructure.mapper.CaseRegressionMapper;
import interview.guide.modules.caselibrary.model.CaseEntity;
import interview.guide.modules.caselibrary.model.CaseStatus;
import interview.guide.modules.caselibrary.repository.CaseRepository;
import interview.guide.modules.evalregression.model.CaseRegressionItemEntity;
import interview.guide.modules.evalregression.model.RegressionItemDTO;
import interview.guide.modules.evalregression.repository.CaseRegressionItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 案例回归项服务。
 * <p>
 * 负责在案例发布 / 废弃时维护对应的回归项，以及对外列出回归项。
 * query、expectedEvidence、keyPoints 全部由案例内容<b>确定性</b>派生，不调用任何 LLM；
 * embedding 模型名 / 维度从 {@code app.ai} 配置解析（见 {@link EmbeddingMetadataResolver}），
 * 因此 {@link #upsertOnPublish} 与 {@link #deactivateOnDeprecate} 只做数据库写入，
 * 不触发 embedding 或任何外部调用，可安全地在既有事务内执行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseRegressionItemService {

  private final CaseRegressionItemRepository itemRepository;
  private final CaseRepository caseRepository;
  private final RegressionJsonCodec jsonCodec;
  private final EmbeddingMetadataResolver embeddingMetadataResolver;
  private final CaseRegressionMapper mapper;

  /**
   * 案例审核发布成功后，创建 / 更新其回归项。
   * <p>
   * 期望证据优先使用案例继承自来源会话的原始 KB chunk ID（{@code caseEntity.sourceChunkIds}），
   * 而非案例自身向量化的 chunk ID，以避免“自我命中”的数据泄漏。
   * 若 sourceChunkIds 为 null 或空，标记为 MISSING（不可评测）。
   *
   * @param caseEntity       已发布案例
   * @param selfEvidenceIds  案例自身向量化的 chunk ID 列表（向量化后取得），仅作为兜底
   */
  @Transactional
  public void upsertOnPublish(CaseEntity caseEntity, List<String> selfEvidenceIds) {
    if (caseEntity == null || caseEntity.getId() == null) {
      log.warn("回归项发布跳过：案例为空或缺少 ID");
      return;
    }

    String query = buildQuery(caseEntity);
    List<String> keyPoints = extractKeyPoints(caseEntity);
    String model = embeddingMetadataResolver.resolveModelName();
    int dimension = embeddingMetadataResolver.resolveDimension();

    // 解析来源 chunk IDs
    List<String> sourceChunkIds = jsonCodec.toStringList(caseEntity.getSourceChunkIds());
    List<String> evidence;
    String evidenceSource;

    if (sourceChunkIds != null && !sourceChunkIds.isEmpty()) {
      // 优先使用原始 KB chunk ID（来源会话）
      evidence = sourceChunkIds;
      evidenceSource = "SOURCE";
    } else if (selfEvidenceIds != null && !selfEvidenceIds.isEmpty()) {
      // 兜底：旧案例没有来源 chunk IDs，使用自身向量化 chunk ID，标记为 SELF
      evidence = selfEvidenceIds;
      evidenceSource = "SELF";
    } else {
      // 完全没有证据
      evidence = List.of();
      evidenceSource = "MISSING";
    }

    CaseRegressionItemEntity item = itemRepository.findByCaseId(caseEntity.getId())
      .orElseGet(() -> CaseRegressionItemEntity.builder().caseId(caseEntity.getId()).build());

    item.setCaseId(caseEntity.getId());
    item.setQuery(query);
    item.setExpectedEvidence(jsonCodec.toJson(evidence));
    item.setKeyPoints(jsonCodec.toJson(keyPoints));
    item.setEmbeddingModel(model);
    item.setEmbeddingDimension(dimension);
    item.setEvidenceSource(evidenceSource);
    item.setActive(true);
    itemRepository.save(item);

    log.info("回归项已发布: caseId={}, itemId={}, evidenceCount={}, evidenceSource={}, keyPointCount={}, embeddingModel={}",
      caseEntity.getId(), item.getId(), evidence.size(), evidenceSource, keyPoints.size(), model);
  }

  /**
   * 案例废弃时，将其回归项置为非激活。
   *
   * @param caseId 案例 ID
   */
  @Transactional
  public void deactivateOnDeprecate(Long caseId) {
    itemRepository.findByCaseId(caseId).ifPresentOrElse(item -> {
      item.setActive(false);
      itemRepository.save(item);
      log.info("回归项已停用: caseId={}, itemId={}", caseId, item.getId());
    }, () -> log.info("回归项停用跳过：案例无对应回归项, caseId={}", caseId));
  }

  /**
   * 列出全部回归项（含非激活），并补齐关联案例标题与状态。
   */
  @Transactional(readOnly = true)
  public List<RegressionItemDTO> listItems() {
    List<CaseRegressionItemEntity> items = itemRepository.findAllByOrderByIdAsc();
    if (items.isEmpty()) {
      return List.of();
    }

    List<Long> caseIds = items.stream()
      .map(CaseRegressionItemEntity::getCaseId)
      .distinct()
      .toList();
    Map<Long, CaseEntity> caseMap = caseRepository.findAllById(caseIds).stream()
      .collect(Collectors.toMap(CaseEntity::getId, Function.identity()));

    List<RegressionItemDTO> result = new ArrayList<>(items.size());
    for (CaseRegressionItemEntity item : items) {
      CaseEntity caseEntity = caseMap.get(item.getCaseId());
      String title = caseEntity != null ? caseEntity.getTitle() : "未知案例";
      String status = caseEntity != null && caseEntity.getStatus() != null
        ? caseEntity.getStatus().name()
        : CaseStatus.DRAFT.name();
      result.add(mapper.toItemDTO(item, title, status));
    }
    return result;
  }

  // ========== 确定性派生规则 ==========

  /**
   * 由案例 title + problemDescription 确定性拼接生成检索 query（不调用 LLM）。
   * 规则：取 title 与 problemDescription 的 trim 结果，非空者以单个空格连接。
   */
  private String buildQuery(CaseEntity caseEntity) {
    StringBuilder sb = new StringBuilder();
    appendTrimmed(sb, caseEntity.getTitle());
    appendTrimmed(sb, caseEntity.getProblemDescription());
    return sb.toString().trim();
  }

  private void appendTrimmed(StringBuilder sb, String part) {
    if (part != null && !part.isBlank()) {
      if (!sb.isEmpty()) {
        sb.append(' ');
      }
      sb.append(part.trim());
    }
  }

  /**
   * 从 resolutionSteps / resolutionResult 确定性提取解决要点列表。
   * 规则：先按换行切分，再按中英文分号切分；对每段去除首尾空白与常见枚举前缀
   * （如 "1."、"1、"、"-"、"*"、"•"），过滤空串并按首次出现顺序去重。
   * 去除前缀后的文本仍是原始内容的连续子串，保证后续"逐字包含"判定成立。
   */
  private List<String> extractKeyPoints(CaseEntity caseEntity) {
    LinkedHashSet<String> points = new LinkedHashSet<>();
    collectKeyPoints(points, caseEntity.getResolutionSteps());
    collectKeyPoints(points, caseEntity.getResolutionResult());
    return new ArrayList<>(points);
  }

  private void collectKeyPoints(LinkedHashSet<String> out, String text) {
    if (text == null || text.isBlank()) {
      return;
    }
    for (String line : text.split("\\r?\\n")) {
      for (String segment : line.split("[；;]")) {
        String point = stripEnumerationPrefix(segment.trim());
        if (!point.isEmpty()) {
          out.add(point);
        }
      }
    }
  }

  /**
   * 去除解决要点前的枚举 / 项目符号前缀，仅剥离前缀，保留其后原始文本。
   */
  private String stripEnumerationPrefix(String value) {
    String result = value.trim();
    // 形如 "1." / "12)" / "3、" 的有序列表前缀
    result = result.replaceFirst("^\\d+\\s*[.、)）]\\s*", "");
    // 形如 "-" / "*" / "•" / "·" 的无序列表前缀
    result = result.replaceFirst("^[-*•·]\\s*", "");
    return result.trim();
  }
}
