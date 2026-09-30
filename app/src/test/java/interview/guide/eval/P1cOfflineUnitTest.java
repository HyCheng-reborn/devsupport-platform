package interview.guide.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * P1-C 离线单元测试：用纯计算验证预算门控、APC/FC 指标、HTTP 计数器的正确性。
 *
 * <p>不需要数据库、Redis、API Key 或真实 EmbeddingModel。
 * 覆盖异常路径：预算耗尽不递增、空要点拒绝、空支持 chunk 拒绝。
 */
class P1cOfflineUnitTest {

  @Nested
  @DisplayName("P1cEvalCallBudget 外层操作预算门控")
  class BudgetTest {

    @Test
    @DisplayName("tryAcquire 在预算内正常递增并返回 attempt 编号")
    void tryAcquire_withinLimit_returnsAttemptNumber() {
      P1cEvalCallBudget budget = new P1cEvalCallBudget(3);

      assertThat(budget.tryAcquire()).isEqualTo(1);
      assertThat(budget.tryAcquire()).isEqualTo(2);
      assertThat(budget.tryAcquire()).isEqualTo(3);
      assertThat(budget.getAttempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("tryAcquire 达到上限后抛出异常且本次不递增")
    void tryAcquire_atLimit_throwsWithoutIncrement() {
      P1cEvalCallBudget budget = new P1cEvalCallBudget(2);

      budget.tryAcquire();
      budget.tryAcquire();
      assertThat(budget.getAttempts()).isEqualTo(2);

      assertThatThrownBy(budget::tryAcquire)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("预算耗尽")
          .hasMessageContaining("已完成 2 次")
          .hasMessageContaining("上限 2")
          .hasMessageContaining("本次未递增");

      assertThat(budget.getAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("recordSuccess / recordFailure 独立计数")
    void recordSuccessAndFailure_trackIndependently() {
      P1cEvalCallBudget budget = new P1cEvalCallBudget(10);
      budget.tryAcquire();
      budget.recordSuccess();
      budget.tryAcquire();
      budget.recordFailure();

      assertThat(budget.getAttempts()).isEqualTo(2);
      assertThat(budget.getSuccesses()).isEqualTo(1);
      assertThat(budget.getFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("snapshot 返回正确的 Map 视图")
    void snapshot_returnsCorrectMap() {
      P1cEvalCallBudget budget = new P1cEvalCallBudget(5);
      budget.tryAcquire();
      budget.recordSuccess();

      var snapshot = budget.snapshot();
      assertThat(snapshot).containsEntry("attempts", 1)
          .containsEntry("successes", 1)
          .containsEntry("failures", 0)
          .containsEntry("hardLimit", 5);
    }

    @Test
    @DisplayName("预算为 0 时首次 tryAcquire 即拒绝")
    void tryAcquire_zeroLimit_throwsImmediately() {
      P1cEvalCallBudget budget = new P1cEvalCallBudget(0);

      assertThatThrownBy(budget::tryAcquire)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("已完成 0 次");

      assertThat(budget.getAttempts()).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("P1cAnswerPointMetrics APC/FC 指标计算")
  class ApcFcTest {

    @Test
    @DisplayName("全部要点覆盖：APC=1.0, FC=true")
    void compute_allCovered_apc1FcTrue() {
      var points = List.of(
          new P1cAnswerPointMetrics.AnswerPoint(1, List.of("chunk-a")),
          new P1cAnswerPointMetrics.AnswerPoint(2, List.of("chunk-b")));
      var retrieved = List.of("chunk-a", "chunk-b", "chunk-c");

      var result = P1cAnswerPointMetrics.compute(points, retrieved);

      assertThat(result.totalPoints()).isEqualTo(2);
      assertThat(result.coveredPoints()).isEqualTo(2);
      assertThat(result.apc()).isEqualTo(1.0);
      assertThat(result.fullCoverage()).isTrue();
    }

    @Test
    @DisplayName("部分要点覆盖：APC=0.5, FC=false")
    void compute_partialCoverage_apc05FcFalse() {
      var points = List.of(
          new P1cAnswerPointMetrics.AnswerPoint(1, List.of("chunk-a")),
          new P1cAnswerPointMetrics.AnswerPoint(2, List.of("chunk-b")));
      var retrieved = List.of("chunk-a", "chunk-x");

      var result = P1cAnswerPointMetrics.compute(points, retrieved);

      assertThat(result.totalPoints()).isEqualTo(2);
      assertThat(result.coveredPoints()).isEqualTo(1);
      assertThat(result.apc()).isEqualTo(0.5);
      assertThat(result.fullCoverage()).isFalse();
    }

    @Test
    @DisplayName("无要点覆盖：APC=0.0, FC=false")
    void compute_noneCovered_apc0FcFalse() {
      var points = List.of(
          new P1cAnswerPointMetrics.AnswerPoint(1, List.of("chunk-a")));
      var retrieved = List.of("chunk-x", "chunk-y");

      var result = P1cAnswerPointMetrics.compute(points, retrieved);

      assertThat(result.coveredPoints()).isEqualTo(0);
      assertThat(result.apc()).isEqualTo(0.0);
      assertThat(result.fullCoverage()).isFalse();
    }

    @Test
    @DisplayName("多支持 chunk 的要点：任一命中即覆盖")
    void compute_multiSupportChunks_anyHitCounts() {
      var points = List.of(
          new P1cAnswerPointMetrics.AnswerPoint(1, List.of("chunk-a", "chunk-b")));
      var retrieved = List.of("chunk-b");

      var result = P1cAnswerPointMetrics.compute(points, retrieved);

      assertThat(result.coveredPoints()).isEqualTo(1);
      assertThat(result.apc()).isEqualTo(1.0);
      assertThat(result.fullCoverage()).isTrue();
    }

    @Test
    @DisplayName("空检索结果：APC=0.0, FC=false")
    void compute_emptyRetrieved_apc0FcFalse() {
      var points = List.of(
          new P1cAnswerPointMetrics.AnswerPoint(1, List.of("chunk-a")));
      List<String> retrieved = List.of();

      var result = P1cAnswerPointMetrics.compute(points, retrieved);

      assertThat(result.coveredPoints()).isEqualTo(0);
      assertThat(result.apc()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("空要点列表抛出 IllegalArgumentException")
    void compute_emptyPoints_throws() {
      assertThatThrownBy(() ->
          P1cAnswerPointMetrics.compute(List.of(), List.of("chunk-a")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("答案要点列表不能为空");
    }

    @Test
    @DisplayName("null 要点列表抛出 IllegalArgumentException")
    void compute_nullPoints_throws() {
      assertThatThrownBy(() ->
          P1cAnswerPointMetrics.compute(null, List.of("chunk-a")))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("要点支持 chunk 为空抛出 IllegalArgumentException")
    void compute_emptySupportingChunks_throws() {
      var points = List.of(
          new P1cAnswerPointMetrics.AnswerPoint(1, List.of()));

      assertThatThrownBy(() ->
          P1cAnswerPointMetrics.compute(points, List.of("chunk-a")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("支持 chunk 列表不能为空");
    }
  }

  @Nested
  @DisplayName("P1cEvalHttpCallCounter HTTP 请求观测")
  class HttpCounterTest {

    @Test
    @DisplayName("初始值为 0，incrementAndGet 递增")
    void counter_startsAtZero_increments() {
      P1cEvalHttpCallCounter counter = new P1cEvalHttpCallCounter();

      assertThat(counter.get()).isEqualTo(0);
      assertThat(counter.incrementAndGet()).isEqualTo(1);
      assertThat(counter.incrementAndGet()).isEqualTo(2);
      assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("toInterceptor 返回非 null 拦截器")
    void toInterceptor_returnsNonNull() {
      P1cEvalHttpCallCounter counter = new P1cEvalHttpCallCounter();
      assertThat(counter.toInterceptor()).isNotNull();
    }
  }

  @Nested
  @DisplayName("P1cEvalReport 报告 record 构造")
  class ReportRecordTest {

    @Test
    @DisplayName("报告 record 可正确构造并读取字段")
    void report_canConstructAndReadFields() {
      var identity = new P1cEvalReport.ConnectionIdentity(
          "jdbc:postgresql://localhost:5433/eval", "interview_guide_eval",
          5433, "eval_runner", false,
          "p1c-eval-isolated", "f47ac10b-58cc-4372-a567-0e0283c5d9e7", true,
          "runtime", "note");
      var embedding = new P1cEvalReport.EmbeddingConfig(
          "dashscope", "text-embedding-v3", 1024, "https://example.com", "system-property");
      var vs = new P1cEvalReport.VectorStoreConfig(
          "COSINE_DISTANCE", "HNSW", 1024, false, "eval-init.sql");
      var guard = new P1cEvalReport.CallGuard(
          java.util.Map.of("attempts", 3, "successes", 3, "failures", 0, "hardLimit", 50),
          new P1cEvalReport.HttpObservation(3, "interceptor", "note"),
          "before-send", "WITHIN_LIMIT");
      var counts = new P1cEvalReport.QueryCounts(20, 20, 16, 4, 4, 16, 0);
      var hashes = new P1cEvalReport.DataHashes("a", "b", "c", "d", "sha256");
      var metrics = new P1cEvalReport.MacroMetrics(0.8, 0.6, 0.7, 0.5);

      var report = new P1cEvalReport(
          "v1", "run-1", "2026-09-29T00:00:00Z", "2026-09-29T00:01:00Z",
          "real-embedding", "p1c-l1",
          identity, embedding, vs, guard, counts, hashes,
          metrics, 30, 20, List.of(), null, null);

      assertThat(report.evalRunId()).isEqualTo("run-1");
      assertThat(report.connectionIdentity().markerVerified()).isTrue();
      assertThat(report.callGuard().status()).isEqualTo("WITHIN_LIMIT");
      assertThat(report.macroMetrics().macroHitAtK()).isEqualTo(0.8);
      assertThat(report.totalAnswerPoints()).isEqualTo(30);
    }
  }
}
