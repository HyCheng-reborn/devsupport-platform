package interview.guide.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

  @Nested
  @DisplayName("P1cEvalCallBudget 并发原子性")
  class BudgetConcurrencyTest {

    @Test
    @DisplayName("多线程争用：成功次数不超过 hardLimit，达到后一致抛异常")
    void tryAcquire_concurrent_staysWithinLimit() throws Exception {
      final int hardLimit = 20;
      final int threadCount = 64;
      P1cEvalCallBudget budget = new P1cEvalCallBudget(hardLimit);

      AtomicInteger successCount = new AtomicInteger();
      AtomicInteger rejectCount = new AtomicInteger();
      CountDownLatch start = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(threadCount);
      try {
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
          futures.add(pool.submit(() -> {
            try {
              start.await();
              budget.tryAcquire();
              successCount.incrementAndGet();
            } catch (IllegalStateException expected) {
              rejectCount.incrementAndGet();
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
            }
          }));
        }
        start.countDown();
        for (var f : futures) {
          f.get(5, TimeUnit.SECONDS);
        }
      } finally {
        pool.shutdownNow();
      }

      assertThat(successCount.get()).isEqualTo(hardLimit);
      assertThat(rejectCount.get()).isEqualTo(threadCount - hardLimit);
      assertThat(budget.getAttempts()).isEqualTo(hardLimit);
    }

    @Test
    @DisplayName("并发递增返回唯一编号 1..hardLimit")
    void tryAcquire_concurrent_returnsUniqueAttemptNumbers() throws Exception {
      final int hardLimit = 50;
      final int threadCount = 200;
      P1cEvalCallBudget budget = new P1cEvalCallBudget(hardLimit);

      java.util.Set<Integer> returned = java.util.concurrent.ConcurrentHashMap
          .newKeySet();
      CountDownLatch start = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(16);
      try {
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
          futures.add(pool.submit(() -> {
            try {
              start.await();
              returned.add(budget.tryAcquire());
            } catch (IllegalStateException expected) {
              // rejected
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
            }
          }));
        }
        start.countDown();
        for (var f : futures) {
          f.get(5, TimeUnit.SECONDS);
        }
      } finally {
        pool.shutdownNow();
      }

      assertThat(returned).hasSize(hardLimit);
      for (int n = 1; n <= hardLimit; n++) {
        assertThat(returned).contains(n);
      }
    }
  }

  @Nested
  @DisplayName("P1cEvalResultValidator 检索结果隔离核对")
  class ResultValidatorTest {

    private P1cEvalResultValidator.HitMetadata hit(int rank, String runId, String kb, String chunk) {
      return new P1cEvalResultValidator.HitMetadata(rank, runId, kb, chunk);
    }

    @Test
    @DisplayName("合法命中：全部通过校验")
    void validate_allPass_returnsList() {
      var hits = List.of(
          hit(1, "run-x", "900001", "chunk-a"),
          hit(2, "run-x", "900001", "chunk-b"));

      var result = P1cEvalResultValidator.validate(hits, "run-x");

      assertThat(result).hasSize(2);
      assertThat(P1cEvalResultValidator.extractEvalChunkIds(result))
          .containsExactly("chunk-a", "chunk-b");
    }

    @Test
    @DisplayName("空命中列表通过（NO_ANSWER 或空检索）")
    void validate_emptyList_passes() {
      assertThatCode(() ->
          P1cEvalResultValidator.validate(List.of(), "run-x")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("缺失 eval_run_id 抛隔离失败")
    void validate_missingRunId_throws() {
      var hits = List.of(hit(1, null, "900001", "chunk-a"));
      assertThatThrownBy(() -> P1cEvalResultValidator.validate(hits, "run-x"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("rank=1")
          .hasMessageContaining("缺失 eval_run_id");
    }

    @Test
    @DisplayName("eval_run_id 与期望 runId 不一致抛隔离失败")
    void validate_runIdMismatch_throws() {
      var hits = List.of(hit(1, "other-run", "900001", "chunk-a"));
      assertThatThrownBy(() -> P1cEvalResultValidator.validate(hits, "run-x"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("other-run")
          .hasMessageContaining("隔离失败");
    }

    @Test
    @DisplayName("kb_id 缺失或不等于 900001 都抛隔离失败")
    void validate_kbIdBad_throws() {
      var missing = List.of(hit(1, "run-x", null, "chunk-a"));
      assertThatThrownBy(() -> P1cEvalResultValidator.validate(missing, "run-x"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("缺失 kb_id");

      var wrong = List.of(hit(1, "run-x", "12345", "chunk-a"));
      assertThatThrownBy(() -> P1cEvalResultValidator.validate(wrong, "run-x"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("kb_id=12345")
          .hasMessageContaining("900001");
    }

    @Test
    @DisplayName("缺失 eval_chunk_id 抛隔离失败")
    void validate_missingChunkId_throws() {
      var hits = List.of(hit(1, "run-x", "900001", "  "));
      assertThatThrownBy(() -> P1cEvalResultValidator.validate(hits, "run-x"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("缺失 eval_chunk_id");
    }
  }

  @Nested
  @DisplayName("P1cRealRetrievalEvalTest 数据集路径解析（离线）")
  class DatasetPathTest {

    @Test
    @DisplayName("未指定 eval.datasetDir 时抛 IllegalStateException")
    void resolveDatasetDir_missingProp_throws() {
      String prev = System.getProperty("eval.datasetDir");
      try {
        System.clearProperty("eval.datasetDir");
        assertThatThrownBy(P1cRealRetrievalEvalTest::resolveDatasetDir)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("eval.datasetDir");
      } finally {
        if (prev != null) {
          System.setProperty("eval.datasetDir", prev);
        }
      }
    }

    @Test
    @DisplayName("指定不存在的目录抛 IllegalStateException")
    void resolveDatasetDir_missingDir_throws(@TempDir Path tmp) {
      String prev = System.getProperty("eval.datasetDir");
      try {
        System.setProperty("eval.datasetDir", tmp.resolve("nope").toString());
        assertThatThrownBy(P1cRealRetrievalEvalTest::resolveDatasetDir)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("数据集目录不存在");
      } finally {
        if (prev != null) {
          System.setProperty("eval.datasetDir", prev);
        } else {
          System.clearProperty("eval.datasetDir");
        }
      }
    }

    @Test
    @DisplayName("读取仓库内已提交的 P1-B 工件：28 行 chunks + 20 条 entries")
    void committedArtifacts_haveExpectedCounts() throws Exception {
      Path datasetDir = locateRepoDatasetDir();
      Path chunks = datasetDir.resolve("chunks.jsonl");
      Path gold = datasetDir.resolve("candidate-gold.json");
      assertThat(chunks).exists();
      assertThat(gold).exists();

      int chunkLines = 0;
      try (BufferedReader r = Files.newBufferedReader(chunks, StandardCharsets.UTF_8)) {
        String line;
        while ((line = r.readLine()) != null) {
          if (!line.isBlank()) {
            chunkLines++;
          }
        }
      }
      assertThat(chunkLines).as("chunks.jsonl 非空行数").isEqualTo(28);

      tools.jackson.databind.ObjectMapper mapper =
          tools.jackson.databind.json.JsonMapper.builder().build();
      var root = mapper.readTree(gold.toFile());
      var entries = root.get("entries");
      assertThat(entries.isArray()).isTrue();
      assertThat(entries.size()).as("candidate-gold.json entries 数量").isEqualTo(20);
    }

    /**
     * 从 {@code user.dir}（{@code :app:test} 的 workingDir）出发定位仓库内数据集；
     * 若上层 Gradle 已注入 {@code eval.datasetDir}，优先使用它。
     */
    private Path locateRepoDatasetDir() {
      String prop = System.getProperty("eval.datasetDir");
      if (prop != null && !prop.isBlank() && Files.isDirectory(Path.of(prop))) {
        return Path.of(prop);
      }
      Path fromApp = Path.of("..").resolve("eval/datasets/devsupport-v0.1").normalize();
      if (Files.isDirectory(fromApp)) {
        return fromApp;
      }
      Path fromRoot = Path.of("eval/datasets/devsupport-v0.1").normalize();
      if (Files.isDirectory(fromRoot)) {
        return fromRoot;
      }
      throw new IllegalStateException("无法定位 P1-B 数据集目录（user.dir="
          + System.getProperty("user.dir") + "）");
    }
  }

  @Nested
  @DisplayName("P1cRealRetrievalEvalTest 凭据优先级（离线）")
  class CredentialTest {

    @Test
    @DisplayName("sysProp 非空即返回，不看环境变量")
    void requireCredential_sysPropWins() {
      String prev = System.getProperty("eval.embedding.apiKey");
      try {
        System.setProperty("eval.embedding.apiKey", "sk-from-sysprop");
        String got = P1cRealRetrievalEvalTest.requireCredential(
            "eval.embedding.apiKey", "AI_BAILIAN_API_KEY", "Embedding API Key");
        assertThat(got).isEqualTo("sk-from-sysprop");
      } finally {
        if (prev != null) {
          System.setProperty("eval.embedding.apiKey", prev);
        } else {
          System.clearProperty("eval.embedding.apiKey");
        }
      }
    }

    @Test
    @DisplayName("sysProp 空串时不视为已提供，回退环境变量（若无则失败）")
    void requireCredential_blankFallsBack() {
      String prev = System.getProperty("eval.embedding.apiKey");
      try {
        System.setProperty("eval.embedding.apiKey", "");
        // 本机没有 AI_BAILIAN_API_KEY 时抛异常；有的话返回环境变量值——两者都是合法结果
        if (System.getenv("AI_BAILIAN_API_KEY") == null) {
          assertThatThrownBy(() -> P1cRealRetrievalEvalTest.requireCredential(
              "eval.embedding.apiKey", "AI_BAILIAN_API_KEY", "Embedding API Key"))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("Embedding API Key")
              .hasMessageContaining("AI_BAILIAN_API_KEY")
              // 消息不应泄漏真实值
              .hasMessageNotContaining("sk-");
        }
      } finally {
        if (prev != null) {
          System.setProperty("eval.embedding.apiKey", prev);
        } else {
          System.clearProperty("eval.embedding.apiKey");
        }
      }
    }

    @Test
    @DisplayName("sysProp 与 env 都缺失时在 HTTP 客户端构造前抛出")
    void requireCredential_allMissing_throwsBeforeHttp() {
      String prevKey = System.getProperty("eval.embedding.apiKey");
      String prevPw = System.getProperty("eval.datasource.password");
      try {
        // 用一个几乎不可能存在的环境变量名，保证失败路径
        System.clearProperty("eval.embedding.apiKey");
        assertThatThrownBy(() -> P1cRealRetrievalEvalTest.requireCredential(
            "eval.embedding.apiKey", "P1C_TEST_DEFINITELY_NOT_SET_XYZ", "Embedding API Key"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("拒绝使用默认值");
        assertThatThrownBy(() -> P1cRealRetrievalEvalTest.requireCredential(
            "eval.datasource.password", "P1C_TEST_DEFINITELY_NOT_SET_XYZ", "评测数据库密码"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("评测数据库密码");
      } finally {
        if (prevKey != null) {
          System.setProperty("eval.embedding.apiKey", prevKey);
        }
        if (prevPw != null) {
          System.setProperty("eval.datasource.password", prevPw);
        }
      }
    }
  }
}
