package interview.guide.modules.evalregression.repository;

import interview.guide.modules.evalregression.model.CaseRegressionRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 案例回归运行 Repository
 */
@Repository
public interface CaseRegressionRunRepository extends JpaRepository<CaseRegressionRunEntity, Long> {

  /**
   * 按开始时间倒序列出运行历史
   */
  List<CaseRegressionRunEntity> findAllByOrderByStartedAtDesc();
}
