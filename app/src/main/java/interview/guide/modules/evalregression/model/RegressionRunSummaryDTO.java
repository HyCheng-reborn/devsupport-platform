package interview.guide.modules.evalregression.model;

import java.time.LocalDateTime;

/**
 * 回归运行汇总 DTO（API 响应）。
 */
public record RegressionRunSummaryDTO(
  Long id,
  LocalDateTime startedAt,
  LocalDateTime finishedAt,
  Integer totalItems,
  Integer passed,
  Integer failed,
  Integer skipped,
  String triggerSource,
  String embeddingModel,
  String status
) {}
