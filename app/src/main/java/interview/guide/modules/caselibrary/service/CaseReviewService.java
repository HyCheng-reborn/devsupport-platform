package interview.guide.modules.caselibrary.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.mapper.CaseLibraryMapper;
import interview.guide.modules.caselibrary.model.CaseAuditLogEntity;
import interview.guide.modules.caselibrary.model.CaseDTO;
import interview.guide.modules.caselibrary.model.CaseEntity;
import interview.guide.modules.caselibrary.model.CaseRequests.CaseUpdateRequest;
import interview.guide.modules.caselibrary.model.CaseStatus;
import interview.guide.modules.caselibrary.repository.CaseAuditLogRepository;
import interview.guide.modules.caselibrary.repository.CaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 案例审核服务
 */
@Service
@RequiredArgsConstructor
public class CaseReviewService {

  private final CaseRepository caseRepository;
  private final CaseAuditLogRepository auditLogRepository;
  private final CaseLibraryMapper caseLibraryMapper;

  /**
   * 更新案例（仅 DRAFT 或 REJECTED 状态可编辑）
   *
   * @param caseId  案例ID
   * @param request 更新请求
   * @return 案例DTO
   */
  @Transactional
  public CaseDTO updateCase(Long caseId, CaseUpdateRequest request) {
    CaseEntity caseEntity = caseRepository.findById(caseId)
      .orElseThrow(() -> new BusinessException(ErrorCode.CASE_NOT_FOUND));

    CaseStatus currentStatus = caseEntity.getStatus();
    if (currentStatus != CaseStatus.DRAFT && currentStatus != CaseStatus.REJECTED) {
      throw new BusinessException(ErrorCode.CASE_NOT_EDITABLE,
        "当前状态 " + currentStatus + " 不可编辑");
    }

    // 更新字段
    if (request.title() != null) {
      caseEntity.setTitle(request.title());
    }
    if (request.problemDescription() != null) {
      caseEntity.setProblemDescription(request.problemDescription());
    }
    if (request.rootCause() != null) {
      caseEntity.setRootCause(request.rootCause());
    }
    if (request.resolutionSteps() != null) {
      caseEntity.setResolutionSteps(request.resolutionSteps());
    }
    if (request.resolutionResult() != null) {
      caseEntity.setResolutionResult(request.resolutionResult());
    }
    if (request.affectedVersions() != null) {
      caseEntity.setAffectedVersions(request.affectedVersions());
    }
    if (request.environment() != null) {
      caseEntity.setEnvironment(request.environment());
    }
    if (request.service() != null) {
      caseEntity.setService(request.service());
    }
    if (request.aiGeneratedContent() != null) {
      caseEntity.setAiGeneratedContent(request.aiGeneratedContent());
    }
    if (request.userConfirmedContent() != null) {
      caseEntity.setUserConfirmedContent(request.userConfirmedContent());
    }

    // 如果从 REJECTED 编辑，状态回到 DRAFT
    CaseStatus previousStatus = caseEntity.getStatus();
    if (previousStatus == CaseStatus.REJECTED) {
      caseEntity.setStatus(CaseStatus.DRAFT);
    }

    CaseEntity saved = caseRepository.save(caseEntity);

    // 写入审计日志
    CaseAuditLogEntity auditLog = CaseAuditLogEntity.builder()
      .caseId(caseId)
      .action("EDITED")
      .previousStatus(previousStatus.name())
      .newStatus(saved.getStatus().name())
      .build();
    auditLogRepository.save(auditLog);

    return caseLibraryMapper.toDTO(saved);
  }

  /**
   * 提交审核（DRAFT → PENDING_REVIEW）
   *
   * @param caseId 案例ID
   * @return 案例DTO
   */
  @Transactional
  public CaseDTO submitForReview(Long caseId) {
    CaseEntity caseEntity = caseRepository.findById(caseId)
      .orElseThrow(() -> new BusinessException(ErrorCode.CASE_NOT_FOUND));

    if (caseEntity.getStatus() != CaseStatus.DRAFT) {
      throw new BusinessException(ErrorCode.CASE_INVALID_OPERATION,
        "只有 DRAFT 状态可以提交审核，当前状态: " + caseEntity.getStatus());
    }

    caseEntity.setStatus(CaseStatus.PENDING_REVIEW);
    CaseEntity saved = caseRepository.save(caseEntity);

    // 写入审计日志
    CaseAuditLogEntity auditLog = CaseAuditLogEntity.builder()
      .caseId(caseId)
      .action("SUBMITTED")
      .previousStatus(CaseStatus.DRAFT.name())
      .newStatus(CaseStatus.PENDING_REVIEW.name())
      .build();
    auditLogRepository.save(auditLog);

    return caseLibraryMapper.toDTO(saved);
  }

  /**
   * 审核通过（PENDING_REVIEW → PUBLISHED）
   *
   * @param caseId 案例ID
   * @return 案例DTO
   */
  @Transactional
  public CaseDTO approve(Long caseId) {
    CaseEntity caseEntity = caseRepository.findById(caseId)
      .orElseThrow(() -> new BusinessException(ErrorCode.CASE_NOT_FOUND));

    if (caseEntity.getStatus() != CaseStatus.PENDING_REVIEW) {
      throw new BusinessException(ErrorCode.CASE_INVALID_OPERATION,
        "只有 PENDING_REVIEW 状态可以审核通过，当前状态: " + caseEntity.getStatus());
    }

    caseEntity.setStatus(CaseStatus.PUBLISHED);
    caseEntity.setActive(true);
    CaseEntity saved = caseRepository.save(caseEntity);

    // 写入审计日志
    CaseAuditLogEntity auditLog = CaseAuditLogEntity.builder()
      .caseId(caseId)
      .action("APPROVED")
      .previousStatus(CaseStatus.PENDING_REVIEW.name())
      .newStatus(CaseStatus.PUBLISHED.name())
      .remark("审核通过，案例已激活")
      .build();
    auditLogRepository.save(auditLog);

    // TODO: 触发向量化（后续添加）

    return caseLibraryMapper.toDTO(saved);
  }

  /**
   * 审核拒绝（PENDING_REVIEW → REJECTED）
   *
   * @param caseId 案例ID
   * @param remark 拒绝原因
   * @return 案例DTO
   */
  @Transactional
  public CaseDTO reject(Long caseId, String remark) {
    CaseEntity caseEntity = caseRepository.findById(caseId)
      .orElseThrow(() -> new BusinessException(ErrorCode.CASE_NOT_FOUND));

    if (caseEntity.getStatus() != CaseStatus.PENDING_REVIEW) {
      throw new BusinessException(ErrorCode.CASE_INVALID_OPERATION,
        "只有 PENDING_REVIEW 状态可以拒绝，当前状态: " + caseEntity.getStatus());
    }

    caseEntity.setStatus(CaseStatus.REJECTED);
    CaseEntity saved = caseRepository.save(caseEntity);

    // 写入审计日志
    CaseAuditLogEntity auditLog = CaseAuditLogEntity.builder()
      .caseId(caseId)
      .action("REJECTED")
      .previousStatus(CaseStatus.PENDING_REVIEW.name())
      .newStatus(CaseStatus.REJECTED.name())
      .remark(remark)
      .build();
    auditLogRepository.save(auditLog);

    return caseLibraryMapper.toDTO(saved);
  }
}
