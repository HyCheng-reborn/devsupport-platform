package interview.guide.modules.evalregression.model;

import java.util.List;

/**
 * 回归项 DTO（API 响应）。
 * <p>
 * caseTitle / caseStatus 来自关联案例，非 case_regression_items 表字段。
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
  Boolean active
) {}
