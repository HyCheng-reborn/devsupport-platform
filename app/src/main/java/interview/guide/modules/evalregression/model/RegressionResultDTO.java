package interview.guide.modules.evalregression.model;

import java.util.List;

/**
 * 回归逐项结果 DTO（API 响应）。
 * <p>
 * caseTitle 来自逐项结果关联的回归项所对应的案例，非 case_regression_results 表字段。
 */
public record RegressionResultDTO(
  Long id,
  Long itemId,
  String caseTitle,
  Boolean passed,
  List<String> retrievedEvidenceIds,
  List<String> matchedKeyPoints,
  List<String> missingKeyPoints,
  List<TopKSnapshotEntry> topKSnapshot,
  String failureReason
) {}
