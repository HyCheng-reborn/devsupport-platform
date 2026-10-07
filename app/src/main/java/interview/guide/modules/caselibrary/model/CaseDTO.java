package interview.guide.modules.caselibrary.model;

import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 案例 DTO（API 响应）
 */
@Builder
public record CaseDTO(
  Long id,
  String title,
  String problemDescription,
  String rootCause,
  String resolutionSteps,
  String resolutionResult,
  String affectedVersions,
  String environment,
  String service,
  String aiGeneratedContent,
  String userConfirmedContent,
  CaseStatus status,
  Integer versionNo,
  Long sourceSessionId,
  Long sourceMessageId,
  Boolean active,
  String createdBy,
  LocalDateTime createdAt,
  LocalDateTime updatedAt
) {}
