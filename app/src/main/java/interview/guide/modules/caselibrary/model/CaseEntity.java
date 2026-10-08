package interview.guide.modules.caselibrary.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 案例实体
 */
@Entity
@Table(name = "cases", indexes = {
  @Index(name = "idx_cases_status", columnList = "status"),
  @Index(name = "idx_cases_active", columnList = "active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 200)
  private String title;

  @Column(name = "problem_description", columnDefinition = "TEXT")
  private String problemDescription;

  @Column(name = "root_cause", columnDefinition = "TEXT")
  private String rootCause;

  @Column(name = "resolution_steps", columnDefinition = "TEXT")
  private String resolutionSteps;

  @Column(name = "resolution_result", columnDefinition = "TEXT")
  private String resolutionResult;

  @Column(name = "affected_versions", length = 500)
  private String affectedVersions;

  @Column(length = 100)
  private String environment;

  @Column(length = 100)
  private String service;

  @Column(name = "ai_generated_content", columnDefinition = "TEXT")
  private String aiGeneratedContent;

  @Column(name = "user_confirmed_content", columnDefinition = "TEXT")
  private String userConfirmedContent;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  @Builder.Default
  private CaseStatus status = CaseStatus.DRAFT;

  @Column(name = "version_no", nullable = false)
  @Builder.Default
  private Integer versionNo = 1;

  @Column(name = "source_session_id")
  private Long sourceSessionId;

  @Column(name = "source_message_id")
  private Long sourceMessageId;

  @Column(nullable = false)
  @Builder.Default
  private Boolean active = false;

  @Column(name = "vector_cleanup_pending", nullable = false)
  @Builder.Default
  private Boolean vectorCleanupPending = false;

  @Column(name = "created_by", length = 100)
  private String createdBy;

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
