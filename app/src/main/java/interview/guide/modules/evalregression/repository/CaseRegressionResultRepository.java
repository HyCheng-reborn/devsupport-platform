package interview.guide.modules.evalregression.repository;

import interview.guide.modules.evalregression.model.CaseRegressionResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 案例回归逐项结果 Repository
 */
@Repository
public interface CaseRegressionResultRepository extends JpaRepository<CaseRegressionResultEntity, Long> {

  /**
   * 查找某次运行的全部逐项结果，按 ID 升序保证展示顺序稳定
   */
  List<CaseRegressionResultEntity> findByRunIdOrderByIdAsc(Long runId);
}
