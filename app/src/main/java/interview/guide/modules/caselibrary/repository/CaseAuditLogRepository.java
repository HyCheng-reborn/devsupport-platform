package interview.guide.modules.caselibrary.repository;

import interview.guide.modules.caselibrary.model.CaseAuditLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 案例审计日志 Repository
 */
@Repository
public interface CaseAuditLogRepository extends JpaRepository<CaseAuditLogEntity, Long> {

  /**
   * 根据案例 ID 查找审计日志（按创建时间倒序）
   */
  List<CaseAuditLogEntity> findByCaseIdOrderByCreatedAtDesc(Long caseId);
}
