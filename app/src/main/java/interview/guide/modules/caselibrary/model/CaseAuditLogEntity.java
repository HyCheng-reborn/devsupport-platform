package interview.guide.modules.caselibrary.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 案例审计日志实体
 */
@Entity
@Table(name = "case_audit_logs", indexes = {
  @Index(name = "idx_case_audit_logs_case_id", columnList = "caseId")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CaseAuditLogEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "case_id", nullable = false)
  private Long caseId;

  @Column(nullable = false, length = 50)
  private String action;

  @Column(name = "previous_status", length = 20)
  private String previousStatus;

  @Column(name = "new_status", length = 20)
  private String newStatus;

  @Column(length = 100)
  private String operator;

  @Column(columnDefinition = "TEXT")
  private String remark;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @PrePersist
  protected void onCreate() {
    this.createdAt = LocalDateTime.now();
  }
}
