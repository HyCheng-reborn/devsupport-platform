package interview.guide.modules.evalregression.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 回归运行详情 DTO（API 响应）：汇总字段 + 逐项结果。
 */
public record RegressionRunDetailDTO(
  Long id,
  LocalDateTime startedAt,
  LocalDateTime finishedAt,
  Integer totalItems,
  Integer passed,
  Integer failed,
  Integer skipped,
  String triggerSource,
  String embeddingModel,
  String status,
  List<RegressionResultDTO> results
) {}
