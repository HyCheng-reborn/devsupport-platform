package interview.guide.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link RetrievalMetrics} 单元测试（纯计算，手工可算例）。
 *
 * <p>不启动 Spring，不连接数据库 / Redis / S3 / 网络 / 模型。
 */
@DisplayName("离线检索指标计算测试")
class RetrievalMetricsTest {

  private static final double DELTA = 1e-9;

  // ==================== 辅助构造 ====================

  private static RetrievalHit hit(String chunkId) {
    return new RetrievalHit(chunkId, "kb-" + chunkId, null);
  }

  private static RetrievalHit hit(String chunkId, String kbId, Double score) {
    return new RetrievalHit(chunkId, kbId, score);
  }

  private static QueryJudgement judgement(
      String queryId, Answerability answerability, List<String> gold, RetrievalHit... hits) {
    return new QueryJudgement(
        new EvalQuery(queryId, "question-" + queryId, answerability, gold),
        List.of(hits));
  }

  private static QueryEvaluation only(EvaluationReport report) {
    assertEquals(1, report.perQuery().size(), "应只有一题");
    return report.perQuery().getFirst();
  }

  // ==================== 指标口径 ====================

  @Nested
  @DisplayName("Hit/Recall/MRR 计算")
  class MetricTests {

    @Test
    @DisplayName("首位命中：Hit=1, Recall=1, RR=1")
    void firstPositionHit() {
      EvaluationReport report = RetrievalMetrics.evaluate(
          3, List.of(judgement("q1", Answerability.ANSWERABLE, List.of("c1"),
              hit("c1"), hit("c2"), hit("c3"))));
      QueryEvaluation e = only(report);
      assertEquals(1.0, e.hitAtK(), DELTA);
      assertEquals(1.0, e.recallAtK(), DELTA);
      assertEquals(1.0, e.reciprocalRank(), DELTA);
      assertTrue(e.includedInMacroAverage());
      assertEquals(3, e.evaluatedTopK());
    }

    @Test
    @DisplayName("后位命中：第 3 位命中，RR=1/3")
    void laterPositionHit() {
      EvaluationReport report = RetrievalMetrics.evaluate(
          3, List.of(judgement("q1", Answerability.ANSWERABLE, List.of("c3"),
              hit("c1"), hit("c2"), hit("c3"))));
      QueryEvaluation e = only(report);
      assertEquals(1.0, e.hitAtK(), DELTA);
      assertEquals(1.0, e.recallAtK(), DELTA);
      assertEquals(1.0 / 3.0, e.reciprocalRank(), DELTA);
    }

    @Test
    @DisplayName("多相关片段部分命中：gold={c1,c2,c4}, top3={c1,c3,c2} → Recall=2/3, RR=1")
    void multiRelevantPartialHit() {
      EvaluationReport report = RetrievalMetrics.evaluate(
          3, List.of(judgement("q1", Answerability.ANSWERABLE, List.of("c1", "c2", "c4"),
              hit("c1"), hit("c3"), hit("c2"))));
      QueryEvaluation e = only(report);
      assertEquals(1.0, e.hitAtK(), DELTA);
      assertEquals(2.0 / 3.0, e.recallAtK(), DELTA);
      assertEquals(1.0, e.reciprocalRank(), DELTA);
      assertEquals(3, e.goldRelevantCount());
    }

    @Test
    @DisplayName("无命中：Hit=0, Recall=0, RR=0")
    void noHit() {
      EvaluationReport report = RetrievalMetrics.evaluate(
          3, List.of(judgement("q1", Answerability.ANSWERABLE, List.of("c9"),
              hit("c1"), hit("c2"), hit("c3"))));
      QueryEvaluation e = only(report);
      assertEquals(0.0, e.hitAtK(), DELTA);
      assertEquals(0.0, e.recallAtK(), DELTA);
      assertEquals(0.0, e.reciprocalRank(), DELTA);
      assertTrue(e.includedInMacroAverage(), "无命中但仍是有标注可答题，参与宏平均");
    }

    @Test
    @DisplayName("空结果：正常计算为 0，不报错")
    void emptyResults() {
      EvaluationReport report = RetrievalMetrics.evaluate(
          3, List.of(judgement("q1", Answerability.ANSWERABLE, List.of("c1"))));
      QueryEvaluation e = only(report);
      assertEquals(0.0, e.hitAtK(), DELTA);
      assertEquals(0.0, e.recallAtK(), DELTA);
      assertEquals(0.0, e.reciprocalRank(), DELTA);
      assertEquals(0, e.retrievedCount());
      assertEquals(0, e.evaluatedTopK());
    }

    @Test
    @DisplayName("结果少于 K：按实际条数计算，top3={c1,c2} → 命中 c2, RR=1/2")
    void fewerThanK() {
      EvaluationReport report = RetrievalMetrics.evaluate(
          5, List.of(judgement("q1", Answerability.ANSWERABLE, List.of("c2"),
              hit("c1"), hit("c2"))));
      QueryEvaluation e = only(report);
      assertEquals(2, e.evaluatedTopK());
      assertEquals(1.0, e.hitAtK(), DELTA);
      assertEquals(1.0, e.recallAtK(), DELTA);
      assertEquals(1.0 / 2.0, e.reciprocalRank(), DELTA);
    }

    @Test
    @DisplayName("相关片段排在 K 之外：不计入，Hit=0, RR=0")
    void relevantBeyondK() {
      EvaluationReport report = RetrievalMetrics.evaluate(
          3, List.of(judgement("q1", Answerability.ANSWERABLE, List.of("c4"),
              hit("c1"), hit("c2"), hit("c3"), hit("c4"))));
      QueryEvaluation e = only(report);
      assertEquals(3, e.evaluatedTopK());
      assertEquals(0.0, e.hitAtK(), DELTA);
      assertEquals(0.0, e.recallAtK(), DELTA);
      assertEquals(0.0, e.reciprocalRank(), DELTA);
    }

    @Test
    @DisplayName("仅来源知识库相同不算片段命中：按 chunkId 判定")
    void kbIdMatchDoesNotCountAsChunkHit() {
      // gold 片段 c1（假定来自 kb1），检索结果 chunkId=c2 但 kbId 同为 kb1
      EvaluationReport report = RetrievalMetrics.evaluate(
          3, List.of(judgement("q1", Answerability.ANSWERABLE, List.of("c1"),
              hit("c2", "kb1", 0.95))));
      QueryEvaluation e = only(report);
      assertEquals(0.0, e.hitAtK(), DELTA, "chunkId 不同即不算命中，即使 kbId 相同");
      assertEquals(0.0, e.recallAtK(), DELTA);
      assertEquals(0.0, e.reciprocalRank(), DELTA);
    }

    @Test
    @DisplayName("score 为 null 不影响排名指标")
    void nullScoreDoesNotAffectRanking() {
      EvaluationReport report = RetrievalMetrics.evaluate(
          3, List.of(judgement("q1", Answerability.ANSWERABLE, List.of("c2"),
              hit("c1", "kb1", null), hit("c2", "kb1", null))));
      QueryEvaluation e = only(report);
      assertEquals(1.0, e.hitAtK(), DELTA);
      assertEquals(1.0 / 2.0, e.reciprocalRank(), DELTA);
    }
  }

  // ==================== 排除与宏平均 ====================

  @Nested
  @DisplayName("排除项与宏平均")
  class AggregationTests {

    @Test
    @DisplayName("无答案题被排除：指标为 null，不参与宏平均")
    void noAnswerExcluded() {
      EvaluationReport report = RetrievalMetrics.evaluate(
          3, List.of(judgement("q1", Answerability.NO_ANSWER, List.of(), hit("c1"))));
      QueryEvaluation e = only(report);
      assertFalse(e.includedInMacroAverage());
      assertNull(e.hitAtK());
      assertNull(e.recallAtK());
      assertNull(e.reciprocalRank());
      assertEquals(1, report.noAnswerQueries());
      assertEquals(1, report.excludedQueries());
      assertEquals(0, report.evaluatedQueries());
      assertNull(report.macroHitAtK(), "无可答题时宏平均为 null，不得除零或回填满分");
      assertNull(report.macroRecallAtK());
      assertNull(report.macroMrrAtK());
    }

    @Test
    @DisplayName("未标注题被单独计数并排除")
    void unannotatedExcluded() {
      EvaluationReport report = RetrievalMetrics.evaluate(
          3, List.of(judgement("q1", Answerability.UNANNOTATED, List.of(), hit("c1"))));
      assertEquals(1, report.unannotatedQueries());
      assertEquals(1, report.excludedQueries());
      assertEquals(0, report.evaluatedQueries());
      assertNull(report.macroHitAtK());
    }

    @Test
    @DisplayName("宏平均仅覆盖可答题：q1 命中 / q2 未命中 / q3 无答案 → 均值按 2 题")
    void macroAverageOverAnswerableOnly() {
      List<QueryJudgement> judgements = List.of(
          judgement("q1", Answerability.ANSWERABLE, List.of("c1"), hit("c1"), hit("c2")),
          judgement("q2", Answerability.ANSWERABLE, List.of("c9"), hit("c1"), hit("c2")),
          judgement("q3", Answerability.NO_ANSWER, List.of(), hit("c1")));
      EvaluationReport report = RetrievalMetrics.evaluate(2, judgements);
      assertEquals(3, report.totalQueries());
      assertEquals(2, report.evaluatedQueries());
      assertEquals(1, report.noAnswerQueries());
      assertEquals(1, report.excludedQueries());
      // q1: hit=1, recall=1, rr=1 ; q2: hit=0, recall=0, rr=0
      assertEquals(0.5, report.macroHitAtK(), DELTA);
      assertEquals(0.5, report.macroRecallAtK(), DELTA);
      assertEquals(0.5, report.macroMrrAtK(), DELTA);
    }
  }

  // ==================== 报告元信息 ====================

  @Test
  @DisplayName("报告标注 executionMode=fixture、scope=metric_validation 且带免责声明")
  void reportCarriesFixtureScopeAndDisclaimer() {
    EvaluationReport report = RetrievalMetrics.evaluate(
        3, List.of(judgement("q1", Answerability.ANSWERABLE, List.of("c1"), hit("c1"))));
    assertEquals("fixture", report.executionMode());
    assertEquals("metric_validation", report.scope());
    assertNotNull(report.disclaimer());
    assertTrue(report.disclaimer().contains("非真实"),
        "免责声明应明确这不是真实 RAG 质量基线");
    assertEquals(3, report.k());
  }

  // ==================== 无效输入拒绝 ====================

  @Nested
  @DisplayName("无效输入明确拒绝")
  class InvalidInputTests {

    @Test
    @DisplayName("K<=0 被拒绝")
    void nonPositiveKRejected() {
      List<QueryJudgement> ok = List.of(
          judgement("q1", Answerability.ANSWERABLE, List.of("c1"), hit("c1")));
      assertThrows(IllegalArgumentException.class, () -> RetrievalMetrics.evaluate(0, ok));
      assertThrows(IllegalArgumentException.class, () -> RetrievalMetrics.evaluate(-1, ok));
    }

    @Test
    @DisplayName("queryId 重复被拒绝")
    void duplicateQueryIdRejected() {
      List<QueryJudgement> dup = List.of(
          judgement("q1", Answerability.ANSWERABLE, List.of("c1"), hit("c1")),
          judgement("q1", Answerability.ANSWERABLE, List.of("c2"), hit("c2")));
      IllegalArgumentException ex = assertThrows(
          IllegalArgumentException.class, () -> RetrievalMetrics.evaluate(3, dup));
      assertTrue(ex.getMessage().contains("queryId 重复"));
    }

    @Test
    @DisplayName("检索结果含重复 chunkId 被拒绝")
    void duplicateResultChunkIdRejected() {
      List<QueryJudgement> dup = new ArrayList<>();
      dup.add(new QueryJudgement(
          new EvalQuery("q1", "question", Answerability.ANSWERABLE, List.of("c1")),
          List.of(hit("c1"), hit("c1"))));
      IllegalArgumentException ex = assertThrows(
          IllegalArgumentException.class, () -> RetrievalMetrics.evaluate(3, dup));
      assertTrue(ex.getMessage().contains("重复 chunkId"));
    }

    @Test
    @DisplayName("ANSWERABLE 但 gold 为空被拒绝")
    void answerableWithEmptyGoldRejected() {
      List<QueryJudgement> bad = List.of(
          judgement("q1", Answerability.ANSWERABLE, List.of(), hit("c1")));
      assertThrows(IllegalArgumentException.class, () -> RetrievalMetrics.evaluate(3, bad));
    }

    @Test
    @DisplayName("非可答题却携带 gold 被拒绝")
    void nonAnswerableWithGoldRejected() {
      List<QueryJudgement> bad = List.of(
          judgement("q1", Answerability.NO_ANSWER, List.of("c1"), hit("c1")));
      assertThrows(IllegalArgumentException.class, () -> RetrievalMetrics.evaluate(3, bad));
    }

    @Test
    @DisplayName("gold 含重复片段被拒绝")
    void duplicateGoldRejected() {
      List<QueryJudgement> bad = List.of(
          judgement("q1", Answerability.ANSWERABLE, List.of("c1", "c1"), hit("c1")));
      assertThrows(IllegalArgumentException.class, () -> RetrievalMetrics.evaluate(3, bad));
    }

    @Test
    @DisplayName("answerability 为 null 被拒绝")
    void nullAnswerabilityRejected() {
      List<QueryJudgement> bad = List.of(
          new QueryJudgement(new EvalQuery("q1", "question", null, List.of("c1")), List.of(hit("c1"))));
      assertThrows(IllegalArgumentException.class, () -> RetrievalMetrics.evaluate(3, bad));
    }
  }
}
