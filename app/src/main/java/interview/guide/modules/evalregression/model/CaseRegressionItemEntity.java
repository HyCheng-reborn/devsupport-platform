package interview.guide.modules.evalregression.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 案例回归项实体。
 * <p>
 * 由已发布案例确定性生成，一个案例对应一条回归项（case_id 唯一）。
 * expectedEvidence / keyPoints 以序列化后的 JSON 字符串存储在 TEXT 列中，
 * 与既有 rag_chat_messages.sources_json 的存储风格保持一致。
 */
@Entity
@Table(name = "case_regression_items", indexes = {
  @Index(name = "idx_case_regression_items_case_id", columnList = "case_id"),
  @Index(name = "idx_case_regression_items_active", columnList = "active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CaseRegressionItemEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "case_id", nullable = false, unique = true)
  private Long caseId;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String query;

  /** 期望证据 ID 列表（案例自身向量 chunk 的 Document ID），JSON 数组字符串 */
  @Column(name = "expected_evidence", columnDefinition = "TEXT")
  private String expectedEvidence;

  /** 解决要点列表，JSON 数组字符串 */
  @Column(name = "key_points", columnDefinition = "TEXT")
  private String keyPoints;

  @Column(name = "embedding_model", length = 100)
  private String embeddingModel;

  @Column(name = "embedding_dimension")
  private Integer embeddingDimension;

  @Column(nullable = false)
  @Builder.Default
  private Boolean active = true;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @PrePersist
  protected void onCreate() {
    LocalDateTime now = LocalDateTime.now();
    this.createdAt = now;
    this.updatedAt = now;
  }

  @PreUpdate
  protected void onUpdate() {
    this.updatedAt = LocalDateTime.now();
  }
}
