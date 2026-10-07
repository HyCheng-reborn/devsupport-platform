package interview.guide.modules.caselibrary.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.mapper.CaseLibraryMapper;
import interview.guide.modules.caselibrary.model.CaseAuditLogEntity;
import interview.guide.modules.caselibrary.model.CaseDTO;
import interview.guide.modules.caselibrary.model.CaseEntity;
import interview.guide.modules.caselibrary.model.CaseStatus;
import interview.guide.modules.caselibrary.repository.CaseAuditLogRepository;
import interview.guide.modules.caselibrary.repository.CaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 案例生命周期服务
 */
@Service
@RequiredArgsConstructor
public class CaseLifecycleService {

  private final CaseRepository caseRepository;
  private final CaseAuditLogRepository auditLogRepository;
  private final CaseLibraryMapper caseLibraryMapper;

  /**
   * 废弃案例（PUBLISHED → DEPRECATED）
   *
   * @param caseId 案例ID
   * @return 案例DTO
   */
  @Transactional
  public CaseDTO deprecate(Long caseId) {
    CaseEntity caseEntity = caseRepository.findById(caseId)
      .orElseThrow(() -> new BusinessException(ErrorCode.CASE_NOT_FOUND));

    if (caseEntity.getStatus() != CaseStatus.PUBLISHED) {
      throw new BusinessException(ErrorCode.CASE_INVALID_OPERATION,
        "只有 PUBLISHED 状态可以废弃，当前状态: " + caseEntity.getStatus());
    }

    caseEntity.setStatus(CaseStatus.DEPRECATED);
    caseEntity.setActive(false);
    CaseEntity saved = caseRepository.save(caseEntity);

    // 写入审计日志
    CaseAuditLogEntity auditLog = CaseAuditLogEntity.builder()
      .caseId(caseId)
      .action("DEPRECATED")
      .previousStatus(CaseStatus.PUBLISHED.name())
      .newStatus(CaseStatus.DEPRECATED.name())
      .remark("案例已废弃并停用")
      .build();
    auditLogRepository.save(auditLog);

    return caseLibraryMapper.toDTO(saved);
  }

  /**
   * 列出案例（支持按状态和服务标签过滤）
   *
   * @param status  状态（可选）
   * @param service 服务标签（可选）
   * @return 案例列表
   */
  public List<CaseDTO> listCases(String status, String service) {
    List<CaseEntity> entities;

    if (status != null && service != null) {
      CaseStatus caseStatus = CaseStatus.valueOf(status);
      entities = caseRepository.findByStatus(caseStatus).stream()
        .filter(e -> service.equals(e.getService()))
        .toList();
    } else if (status != null) {
      CaseStatus caseStatus = CaseStatus.valueOf(status);
      entities = caseRepository.findByStatus(caseStatus);
    } else if (service != null) {
      entities = caseRepository.findByService(service);
    } else {
      entities = caseRepository.findAll();
    }

    return caseLibraryMapper.toDTOList(entities);
  }

  /**
   * 获取单个案例
   *
   * @param caseId 案例ID
   * @return 案例DTO
   */
  public CaseDTO getCase(Long caseId) {
    CaseEntity entity = caseRepository.findById(caseId)
      .orElseThrow(() -> new BusinessException(ErrorCode.CASE_NOT_FOUND));
    return caseLibraryMapper.toDTO(entity);
  }
}
