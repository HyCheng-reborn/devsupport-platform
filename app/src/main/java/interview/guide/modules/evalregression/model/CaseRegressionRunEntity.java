package interview.guide.modules.evalregression.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 案例回归运行实体（一次回归执行的汇总）。
 */
@Entity
@Table(name = "case_regression_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CaseRegressionRunEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "started_at", nullable = false)
  private LocalDateTime startedAt;

  @Column(name = "finished_at")
  private LocalDateTime finishedAt;

  @Column(name = "total_items", nullable = false)
  @Builder.Default
  private Integer totalItems = 0;

  @Column(nullable = false)
  @Builder.Default
  private Integer passed = 0;

  @Column(nullable = false)
  @Builder.Default
  private Integer failed = 0;

  @Column(nullable = false)
  @Builder.Default
  private Integer skipped = 0;

  /** 实际评测数（totalItems - skipped），排除不可评测的项 */
  @Column(name = "evaluated_count", nullable = false)
  @Builder.Default
  private Integer evaluatedCount = 0;

  @Column(name = "trigger_source", length = 50)
  private String triggerSource;

  @Column(name = "embedding_model", length = 100)
  private String embeddingModel;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  @Builder.Default
  private RegressionRunStatus status = RegressionRunStatus.RUNNING;

  @PrePersist
  protected void onCreate() {
    if (this.startedAt == null) {
      this.startedAt = LocalDateTime.now();
    }
  }
}
