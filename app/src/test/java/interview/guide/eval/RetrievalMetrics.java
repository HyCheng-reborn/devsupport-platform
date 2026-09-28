package interview.guide.eval;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 离线检索指标计算核心：Hit@K、Recall@K、MRR@K。
 *
 * <p>纯计算，无 Spring 上下文、无数据库 / Redis / S3 / 网络 / 模型依赖。
 *
 * <p>指标口径：
 * <ul>
 *   <li>Hit@K：前 K 条是否至少命中一条相关片段（1.0 / 0.0）。</li>
 *   <li>Recall@K：前 K 条命中的<b>不同</b>相关片段数 / 全部标注相关片段数。</li>
 *   <li>MRR@K：前 K 条中第一条相关片段排名的倒数；前 K 条无命中为 0。</li>
 *   <li>宏平均：仅对 {@link Answerability#ANSWERABLE} 且带非空 gold 标注的查询求平均。</li>
 * </ul>
 *
 * <p>命中判定只按 {@code chunkId}，来源知识库(kbId)相同不算片段命中。
 */
public final class RetrievalMetrics {

  private RetrievalMetrics() {
  }

  /**
   * 计算评测报告。
   *
   * @param k          截断排名，必须为正整数
   * @param judgements 查询标注 + 固定检索结果列表
   * @throws IllegalArgumentException 当 K&lt;=0、queryId 重复、检索结果含重复 chunkId，
   *                                  或标注状态与 gold 片段不一致时
   */
  public static EvaluationReport evaluate(int k, List<QueryJudgement> judgements) {
    if (k <= 0) {
      throw new IllegalArgumentException("K 必须为正整数，当前 K=" + k);
    }
    if (judgements == null) {
      throw new IllegalArgumentException("judgements 不能为 null");
    }

    Set<String> seenQueryIds = new HashSet<>();
    List<QueryEvaluation> perQuery = new ArrayList<>();

    int evaluated = 0;
    int noAnswer = 0;
    int unannotated = 0;
    double sumHit = 0.0;
    double sumRecall = 0.0;
    double sumReciprocalRank = 0.0;

    for (QueryJudgement judgement : judgements) {
      if (judgement == null || judgement.query() == null) {
        throw new IllegalArgumentException("judgement 及其 query 不能为 null");
      }
      EvalQuery query = judgement.query();
      String queryId = query.queryId();
      if (queryId == null || queryId.isBlank()) {
        throw new IllegalArgumentException("queryId 不能为空");
      }
      if (!seenQueryIds.add(queryId)) {
        throw new IllegalArgumentException("queryId 重复: " + queryId);
      }
      Answerability answerability = query.answerability();
      if (answerability == null) {
        throw new IllegalArgumentException("answerability 不能为 null: queryId=" + queryId);
      }

      validateGold(queryId, answerability, query.relevantChunkIds());
      List<RetrievalHit> results = validateAndNormalizeResults(queryId, judgement.rankedResults());
      int retrievedCount = results.size();
      int topK = Math.min(k, retrievedCount);

      if (answerability != Answerability.ANSWERABLE) {
        if (answerability == Answerability.NO_ANSWER) {
          noAnswer++;
        } else {
          unannotated++;
        }
        perQuery.add(new QueryEvaluation(
            queryId, answerability, false,
            null, null, null,
            0, retrievedCount, topK));
        continue;
      }

      Set<String> goldSet = new LinkedHashSet<>(query.relevantChunkIds());
      double hit = 0.0;
      double reciprocalRank = 0.0;
      Set<String> distinctGoldHits = new HashSet<>();
      for (int rank = 0; rank < topK; rank++) {
        String chunkId = results.get(rank).chunkId();
        if (goldSet.contains(chunkId)) {
          if (hit == 0.0) {
            hit = 1.0;
            reciprocalRank = 1.0 / (rank + 1);
          }
          distinctGoldHits.add(chunkId);
        }
      }
      double recall = (double) distinctGoldHits.size() / (double) goldSet.size();

      evaluated++;
      sumHit += hit;
      sumRecall += recall;
      sumReciprocalRank += reciprocalRank;

      perQuery.add(new QueryEvaluation(
          queryId, answerability, true,
          hit, recall, reciprocalRank,
          goldSet.size(), retrievedCount, topK));
    }

    int excluded = noAnswer + unannotated;
    Double macroHit = evaluated == 0 ? null : sumHit / evaluated;
    Double macroRecall = evaluated == 0 ? null : sumRecall / evaluated;
    Double macroMrr = evaluated == 0 ? null : sumReciprocalRank / evaluated;

    return new EvaluationReport(
        k,
        "fixture",
        "metric_validation",
        "本报告基于固定虚构夹具，仅用于验证指标计算逻辑，非真实 RAG 检索质量基线。",
        macroHit, macroRecall, macroMrr,
        judgements.size(), evaluated, noAnswer, unannotated, excluded,
        perQuery);
  }

  private static void validateGold(String queryId, Answerability answerability, List<String> gold) {
    boolean answerable = answerability == Answerability.ANSWERABLE;
    if (answerable) {
      if (gold == null || gold.isEmpty()) {
        throw new IllegalArgumentException(
            "ANSWERABLE 查询必须有非空 relevantChunkIds: queryId=" + queryId);
      }
      Set<String> unique = new HashSet<>();
      for (String id : gold) {
        if (id == null || id.isBlank()) {
          throw new IllegalArgumentException("relevantChunkIds 含空值: queryId=" + queryId);
        }
        if (!unique.add(id)) {
          throw new IllegalArgumentException("relevantChunkIds 含重复项: queryId=" + queryId);
        }
      }
    } else if (gold != null && !gold.isEmpty()) {
      throw new IllegalArgumentException(
          answerability + " 查询不应携带 relevantChunkIds: queryId=" + queryId);
    }
  }

  private static List<RetrievalHit> validateAndNormalizeResults(String queryId, List<RetrievalHit> results) {
    if (results == null || results.isEmpty()) {
      return List.of();
    }
    Set<String> seen = new HashSet<>();
    for (RetrievalHit hit : results) {
      if (hit == null || hit.chunkId() == null || hit.chunkId().isBlank()) {
        throw new IllegalArgumentException("检索结果含空 chunkId: queryId=" + queryId);
      }
      if (!seen.add(hit.chunkId())) {
        throw new IllegalArgumentException(
            "检索结果含重复 chunkId: queryId=" + queryId + ", chunkId=" + hit.chunkId());
      }
    }
    return results;
  }
}
