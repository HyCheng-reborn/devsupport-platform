package interview.guide.eval;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P1-C 多 K 截断指标：每题一次 topK=10 检索，离线截断为 K∈{1,3,5,10}。
 *
 * <p>片段级（沿用 P1-A 口径，按 {@code chunkId} 命中）：Hit@K、MRR@K。
 * 要点级（P1-C 新增）：APC@K、FC@K（{@link P1cAnswerPointMetrics}）。
 *
 * <p>两种覆盖口径**名称与分母严格区分**：
 * <ul>
 *   <li>{@code coveredPointsAtK / totalAnswerPoints}：微平均（micro），分母是全部
 *       ANSWERABLE 题的答案要点总数（当前冻结集为 38）；某要点在 top-K 中出现其
 *       任一支持 chunk 即算覆盖，跨题累加。</li>
 *   <li>{@code macroApcAtK}：宏平均（macro），每题 APC@K 的算术平均，
 *       分母是 ANSWERABLE 题数（含检索失败按零计的题）。</li>
 * </ul>
 *
 * <p>RETRIEVAL_FAILED 的 ANSWERABLE 题：Hit/MRR/APC/FC 一律按 0 计入宏平均
 * （状态独立记录于 {@code retrievalStatus}，不伪装成 OK_ZERO_RESULT）；
 * NO_ANSWER 题不产生指标。
 */
public final class P1cMultiKMetrics {

  public static final List<Integer> DEFAULT_KS = List.of(1, 3, 5, 10);

  /** 单题 × 单 K 的指标。 */
  public record PerKResult(
      int k,
      double hitAtK,
      double mrrAtK,
      double apcAtK,
      boolean fullCoverageAtK,
      int totalPoints,
      int coveredPoints) {
  }

  /** 单 K 宏平均 + 微平均覆盖。 */
  public record KAggregate(
      int k,
      double macroHitAtK,
      double macroMrrAtK,
      double macroApcAtK,
      double macroFullCoverageAtK,
      int coveredPointsAtK,
      int totalAnswerPoints) {

    public double microCoverageAtK() {
      return totalAnswerPoints == 0 ? 0.0 : (double) coveredPointsAtK / totalAnswerPoints;
    }
  }

  /** 全部 K 的题级 + 集级结果。 */
  public record Computed(
      Map<Integer, PerKResult> perK,
      Map<Integer, KAggregate> aggregates) {
  }

  private P1cMultiKMetrics() {
  }

  /**
   * 计算单题各 K 指标。
   *
   * @param rankedChunkIds 按排名排序的检索结果 eval_chunk_id（可为空列表）
   * @param answerPoints   ANSWERABLE 题的要点列表；NO_ANSWER 传 null 或空
   * @param retrievalFailed 主+回退均失败（按零计入，但口径独立）
   * @return K → 指标；answerPoints 为空（NO_ANSWER）时返回空 Map
   */
  public static Map<Integer, PerKResult> perQuery(
      List<P1cAnswerPointMetrics.AnswerPoint> answerPoints,
      List<String> rankedChunkIds,
      boolean retrievalFailed) {
    if (answerPoints == null || answerPoints.isEmpty()) {
      return Map.of();
    }
    Map<Integer, PerKResult> out = new LinkedHashMap<>();
    Set<String> goldUnion = new LinkedHashSet<>();
    answerPoints.forEach(p -> goldUnion.addAll(p.supportingChunkIds()));

    for (int k : DEFAULT_KS) {
      List<String> topK = rankedChunkIds.subList(0, Math.min(k, rankedChunkIds.size()));
      if (retrievalFailed) {
        out.put(k, new PerKResult(k, 0.0, 0.0, 0.0, false, answerPoints.size(), 0));
        continue;
      }
      double hit = 0.0;
      double mrr = 0.0;
      for (int i = 0; i < topK.size(); i++) {
        if (goldUnion.contains(topK.get(i))) {
          hit = 1.0;
          mrr = 1.0 / (i + 1);
          break;
        }
      }
      P1cAnswerPointMetrics.PointCoverageResult pvr =
          P1cAnswerPointMetrics.compute(answerPoints, topK);
      out.put(k, new PerKResult(k, hit, mrr, pvr.apc(), pvr.fullCoverage(),
          pvr.totalPoints(), pvr.coveredPoints()));
    }
    return out;
  }

  /**
   * 聚合：仅对 ANSWERABLE 题（含 retrievalFailed=true 的按零题）求宏平均与微平均覆盖。
   *
   * @param answerableQueryMetrics 每题的 perQuery() 结果（NO_ANSWER 题不要传入；
   *                               检索失败的题传入时其 PerK 值已全零）
   */
  public static Map<Integer, KAggregate> aggregate(List<Map<Integer, PerKResult>> answerableQueryMetrics) {
    Map<Integer, KAggregate> out = new LinkedHashMap<>();
    int n = answerableQueryMetrics.size();
    if (n == 0) {
      return Map.of();
    }
    for (int k : DEFAULT_KS) {
      double sumHit = 0, sumMrr = 0, sumApc = 0, sumFc = 0;
      int coveredAtK = 0;
      int totalAnswerPoints = 0;
      for (Map<Integer, PerKResult> q : answerableQueryMetrics) {
        PerKResult r = q.get(k);
        sumHit += r.hitAtK();
        sumMrr += r.mrrAtK();
        sumApc += r.apcAtK();
        sumFc += r.fullCoverageAtK() ? 1.0 : 0.0;
        coveredAtK += r.coveredPoints();
        totalAnswerPoints += r.totalPoints();
      }
      out.put(k, new KAggregate(k,
          sumHit / n, sumMrr / n, sumApc / n, sumFc / n,
          coveredAtK, totalAnswerPoints));
    }
    return out;
  }

  /** 装配便捷入口：单题计算 + 收集。 */
  public static Computed computeForQuery(
      List<P1cAnswerPointMetrics.AnswerPoint> answerPoints,
      List<String> rankedChunkIds, boolean retrievalFailed) {
    Map<Integer, PerKResult> perK = perQuery(answerPoints, rankedChunkIds, retrievalFailed);
    List<Map<Integer, PerKResult>> single = perK.isEmpty()
        ? List.of() : List.of(perK);
    return new Computed(perK, aggregate(single));
  }
}
