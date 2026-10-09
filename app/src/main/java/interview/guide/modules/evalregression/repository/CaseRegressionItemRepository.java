package interview.guide.modules.evalregression.repository;

import interview.guide.modules.evalregression.model.CaseRegressionItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 案例回归项 Repository
 */
@Repository
public interface CaseRegressionItemRepository extends JpaRepository<CaseRegressionItemEntity, Long> {

  /**
   * 根据案例 ID 查找回归项（case_id 唯一）
   */
  Optional<CaseRegressionItemEntity> findByCaseId(Long caseId);

  /**
   * 查找所有激活的回归项，按 ID 升序保证遍历顺序稳定
   */
  List<CaseRegressionItemEntity> findByActiveTrueOrderByIdAsc();

  /**
   * 查找所有回归项，按 ID 升序
   */
  List<CaseRegressionItemEntity> findAllByOrderByIdAsc();
}
