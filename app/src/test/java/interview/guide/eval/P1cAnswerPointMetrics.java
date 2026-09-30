package interview.guide.eval;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * P1-C 要点覆盖指标计算：AnswerPointCoverage@K、FullCoverage@K。
 *
 * <p>与 P1-A 的 chunk 级指标不同，P1-C APC/FC 在<b>答案要点</b>级别计算：
 * <ul>
 *   <li>APC@K：前 K 条检索结果覆盖了多少答案要点（每个要点独立判定）。</li>
 *   <li>FC@K：前 K 条检索结果是否覆盖了全部答案要点（1.0 / 0.0）。</li>
 * </ul>
 *
 * <p>答案要点定义来自 candidate-gold.json 的 answerPoints，每个要点有 supportingChunkIds。
 * 覆盖判定：要点的支持 chunk 集合与 top-K 检索结果有交集，则该要点被覆盖。
 */
public final class P1cAnswerPointMetrics {

  private P1cAnswerPointMetrics() {
  }

  /**
   * 单题答案要点覆盖结果。
   *
   * @param totalPoints     该题的答案要点总数
   * @param coveredPoints   被 top-K 覆盖的要点数
   * @param apc             AnswerPointCoverage@K = coveredPoints / totalPoints
   * @param fullCoverage    FullCoverage@K = 1.0 iff coveredPoints == totalPoints
   */
  public record PointCoverageResult(
      int totalPoints,
      int coveredPoints,
      double apc,
      boolean fullCoverage) {
  }

  /**
   * 答案要点。
   *
   * @param answerPointIndex   要点序号（从 1 开始）
   * @param supportingChunkIds 支持该要点的 chunk ID 列表
   */
  public record AnswerPoint(int answerPointIndex, List<String> supportingChunkIds) {
  }

  /**
   * 计算单题的 APC@K 和 FC@K。
   *
   * @param answerPoints 该题的答案要点列表
   * @param topKChunkIds 前 K 条检索结果的 chunk ID 列表（按排名顺序）
   * @return 覆盖结果
   * @throws IllegalArgumentException 当要点列表为空或要点支持 chunk 为空时
   */
  public static PointCoverageResult compute(List<AnswerPoint> answerPoints,
      List<String> topKChunkIds) {
    if (answerPoints == null || answerPoints.isEmpty()) {
      throw new IllegalArgumentException("答案要点列表不能为空");
    }

    Set<String> retrievedSet = new HashSet<>(topKChunkIds);
    int totalPoints = answerPoints.size();
    int coveredPoints = 0;

    for (AnswerPoint point : answerPoints) {
      if (point.supportingChunkIds() == null || point.supportingChunkIds().isEmpty()) {
        throw new IllegalArgumentException(
            "答案要点 " + point.answerPointIndex() + " 的支持 chunk 列表不能为空");
      }
      boolean covered = false;
      for (String chunkId : point.supportingChunkIds()) {
        if (retrievedSet.contains(chunkId)) {
          covered = true;
          break;
        }
      }
      if (covered) {
        coveredPoints++;
      }
    }

    double apc = (double) coveredPoints / totalPoints;
    boolean fullCoverage = coveredPoints == totalPoints;

    return new PointCoverageResult(totalPoints, coveredPoints, apc, fullCoverage);
  }
}
