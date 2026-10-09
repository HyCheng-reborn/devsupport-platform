package interview.guide.modules.evalregression.model;

import java.util.List;

/**
 * 回归项 DTO（API 响应）。
 * <p>
 * caseTitle / caseStatus 来自关联案例，非 case_regression_items 表字段。
 * evidenceSource 标记期望证据来源：SOURCE（原始 KB chunk ID）/ MISSING（无来源证据）/ SELF（旧数据使用自身向量 ID）。
 */
public record RegressionItemDTO(
  Long id,
  Long caseId,
  String caseTitle,
  String caseStatus,
  String query,
  List<String> expectedEvidence,
  List<String> keyPoints,
  String embeddingModel,
  Integer embeddingDimension,
  Boolean active,
  String evidenceSource
) {}
