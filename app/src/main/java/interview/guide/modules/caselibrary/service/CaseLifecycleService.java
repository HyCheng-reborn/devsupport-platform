package interview.guide.modules.caselibrary.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.mapper.CaseLibraryMapper;
import interview.guide.modules.caselibrary.model.CaseAuditLogEntity;
import interview.guide.modules.caselibrary.model.CaseDTO;
import interview.guide.modules.caselibrary.model.CaseEntity;
import interview.guide.modules.caselibrary.model.CaseStatus;
import interview.guide.modules.caselibrary.repository.CaseAuditLogRepository;
import interview.guide.modules.caselibrary.repository.CaseRepository;
import interview.guide.modules.evalregression.service.CaseRegressionItemService;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 案例生命周期服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseLifecycleService {

  private final CaseRepository caseRepository;
  private final CaseAuditLogRepository auditLogRepository;
  private final CaseLibraryMapper caseLibraryMapper;
  private final VectorRepository vectorRepository;
  private final TransactionalExecutor transactionalExecutor;
  private final CaseRegressionItemService caseRegressionItemService;

  /**
   * 废弃案例（PUBLISHED → DEPRECATED）
   * <p>
   * 向量清理采用“先标记后删除”两阶段策略：
   * Phase 1（独立事务）：设置 status=DEPRECATED, active=false, vectorCleanupPending=true + 审计日志
   * Phase 2（事务外）：尝试删除向量
   *   - 成功：通过新事务清除 vectorCleanupPending 标记
   *   - 失败：保留标记供后续 retryVectorCleanup 补偿
   * <p>
   * 不使用 @Transactional，而是通过 TransactionalExecutor 显式控制事务边界，
   * 确保 Phase 1 在向量删除之前就已提交，即使向量删除失败也不会丢失废弃状态。
   *
   * @param caseId   案例ID
   * @param operator 操作者（来自 X-Operator header，默认 anonymous）
   * @return 案例DTO
   */
  public CaseDTO deprecate(Long caseId, String operator) {
    // Phase 1: 独立事务内标记废弃 + vectorCleanupPending=true + 审计日志
    CaseEntity saved = transactionalExecutor.call(() -> {
      CaseEntity caseEntity = caseRepository.findById(caseId)
        .orElseThrow(() -> new BusinessException(ErrorCode.CASE_NOT_FOUND));

      if (caseEntity.getStatus() != CaseStatus.PUBLISHED) {
        throw new BusinessException(ErrorCode.CASE_INVALID_OPERATION,
          "只有 PUBLISHED 状态可以废弃，当前状态: " + caseEntity.getStatus());
      }

      caseEntity.setStatus(CaseStatus.DEPRECATED);
      caseEntity.setActive(false);
      caseEntity.setVectorCleanupPending(true);
      CaseEntity entity = caseRepository.save(caseEntity);

      CaseAuditLogEntity auditLog = CaseAuditLogEntity.builder()
        .caseId(caseId)
        .action("DEPRECATED")
        .previousStatus(CaseStatus.PUBLISHED.name())
        .newStatus(CaseStatus.DEPRECATED.name())
        .operator(operator)
        .remark("案例已废弃并停用")
        .build();
      auditLogRepository.save(auditLog);

      // 同步停用对应回归项（仅写库，与废弃状态同一事务提交）
      caseRegressionItemService.deactivateOnDeprecate(caseId);

      return entity;
    });

    // Phase 1 事务已提交，废弃状态已持久化

    // Phase 2: 事务外尝试删除向量
    try {
      int deleted = vectorRepository.deleteByCaseId(caseId);
      log.info("废弃案例已清理向量: caseId={}, deletedRows={}", caseId, deleted);
      // Phase 3: 向量删除成功，通过新事务清除标记
      markVectorCleanupComplete(caseId);
    } catch (Exception e) {
      // 向量删除失败，保留 vectorCleanupPending=true 标记，后续可通过 retryVectorCleanup 补偿
      log.warn("废弃案例向量删除失败，已标记待清理: caseId={}, error={}", caseId, e.getMessage(), e);
    }

    return caseLibraryMapper.toDTO(saved);
  }

  /**
   * 在新事务中清除 vectorCleanupPending 标记。
   * 使用 REQUIRES_NEW 确保与 deprecate() 事务分离。
   */
  private void markVectorCleanupComplete(Long caseId) {
    transactionalExecutor.runRequiresNew(() -> {
      CaseEntity entity = caseRepository.findById(caseId)
        .orElseThrow(() -> new BusinessException(ErrorCode.CASE_NOT_FOUND));
      entity.setVectorCleanupPending(false);
      caseRepository.save(entity);
    });
  }

  /**
   * 重试向量清理：针对 vectorCleanupPending=true 的案例重新尝试删除向量。
   *
   * @param caseId 案例ID
   * @return 是否清理成功
   */
  @Transactional
  public boolean retryVectorCleanup(Long caseId) {
    CaseEntity caseEntity = caseRepository.findById(caseId)
      .orElseThrow(() -> new BusinessException(ErrorCode.CASE_NOT_FOUND));

    if (!Boolean.TRUE.equals(caseEntity.getVectorCleanupPending())) {
      log.info("案例无需向量清理: caseId={}", caseId);
      return true;
    }

    try {
      int deleted = vectorRepository.deleteByCaseId(caseId);
      log.info("重试向量清理成功: caseId={}, deletedRows={}", caseId, deleted);
      caseEntity.setVectorCleanupPending(false);
      caseRepository.save(caseEntity);
      return true;
    } catch (Exception e) {
      log.warn("重试向量清理失败: caseId={}, error={}", caseId, e.getMessage(), e);
      return false;
    }
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
