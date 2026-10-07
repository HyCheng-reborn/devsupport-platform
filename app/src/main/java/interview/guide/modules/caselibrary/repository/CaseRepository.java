package interview.guide.modules.caselibrary.repository;

import interview.guide.modules.caselibrary.model.CaseEntity;
import interview.guide.modules.caselibrary.model.CaseStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 案例 Repository
 */
@Repository
public interface CaseRepository extends JpaRepository<CaseEntity, Long> {

  /**
   * 根据状态查找案例
   */
  List<CaseEntity> findByStatus(CaseStatus status);

  /**
   * 查找所有激活的案例
   */
  List<CaseEntity> findByActiveTrue();

  /**
   * 根据服务标签查找案例
   */
  List<CaseEntity> findByService(String service);
}
