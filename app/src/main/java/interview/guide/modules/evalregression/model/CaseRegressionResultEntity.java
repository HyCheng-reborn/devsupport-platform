package interview.guide.modules.evalregression.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * 案例回归逐项结果实体。
 * <p>
 * retrievedEvidenceIds / matchedKeyPoints / missingKeyPoints / topkSnapshot
 * 均以序列化后的 JSON 字符串存储在 TEXT 列中。
 */
@Entity
@Table(name = "case_regression_results", indexes = {
  @Index(name = "idx_case_regression_results_run_id", columnList = "run_id"),
  @Index(name = "idx_case_regression_results_item_id", columnList = "item_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CaseRegressionResultEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "run_id", nullable = false)
  private Long runId;

  @Column(name = "item_id", nullable = false)
  private Long itemId;

  @Column(nullable = false)
  @Builder.Default
  private Boolean passed = false;

  @Column(name = "retrieved_evidence_ids", columnDefinition = "TEXT")
  private String retrievedEvidenceIds;

  @Column(name = "matched_key_points", columnDefinition = "TEXT")
  private String matchedKeyPoints;

  @Column(name = "missing_key_points", columnDefinition = "TEXT")
  private String missingKeyPoints;

  @Column(name = "topk_snapshot", columnDefinition = "TEXT")
  private String topkSnapshot;

  @Column(name = "failure_reason", columnDefinition = "TEXT")
  private String failureReason;
}
