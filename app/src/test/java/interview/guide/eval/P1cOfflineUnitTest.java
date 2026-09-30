package interview.guide.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import interview.guide.eval.P1cRetrievalHandler.Hit;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * P1-C 离线单元测试：用纯计算与假组件验证 v1.4 数据与失败契约。
 *
 * <p>不需要数据库、Redis、API Key 或真实 EmbeddingModel。覆盖的是会改变实验结论的路径：
 * 严格入库核对（文本/来源错位、缺失/重复/额外行、kb_id 存储类型）、
 * 检索状态机（零命中≠失败、回退预算约束、非冻结 ID 中止）、
 * 多 K 宏/微分母、统一失败与清理顺序（清理失败不覆盖原始异常、报告失败仍已清理）。
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
  @DisplayName("P1cEvalReport 报告 record 构造与序列化")
  class ReportRecordTest {

    @Test
    @DisplayName("多 K 报告可构造并按 k=N 键序列化")
    void report_multiKFields_serializeWithKKeys() {
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
          Map.of("queryAttempts", 20, "fallbackAttempts", 1, "totalAttempts", 23, "hardLimit", 50),
          new P1cEvalReport.HttpObservation(23, "interceptor", "note"),
          "before-send", "WITHIN_LIMIT", "外层操作≠HTTP 上限");
      var counts = new P1cEvalReport.QueryCounts(20, 20, 16, 4, 4, 16, 1, 2, 1);
      var hashes = new P1cEvalReport.DataHashes(
          "a", "b", "c", "d", "SHA-256", "normalize→UTF-8→sha256", "runtime");
      var ingestion = new P1cEvalReport.IngestionVerification(
          28, 28, 28, 28, 0, 1024, List.of(), "PASS");
      var evalConfig = new P1cEvalReport.EvalConfig(
          10, 0.0, false, "900001", P1cMultiKMetrics.DEFAULT_KS, 30, "回退契约");
      var perK = Map.of("k=1", new P1cEvalReport.PerKMetrics(1.0, 1.0, 0.5, false, 1, 2));
      var agg = Map.of("k=1", new P1cEvalReport.KAggregateReport(
          1, 16, 0.5, 0.4, 0.3, 0.2, 12, 38, 12 / 38.0, "macro≠micro"));
      var query = new P1cEvalReport.PerQueryResult(
          "q01", "怎么限流", "ANSWERABLE", true, "OK_WITH_HITS", false, null, null, 1,
          List.of(new P1cEvalReport.RetrievedDoc(1, "chunk-a", 0.91, "900001", "run-1")), perK);
      var diagnostic = new P1cEvalReport.PerQueryResult(
          "q17", "无关问题", "NO_ANSWER", false, "OK_ZERO_RESULT", false, null, null, 0,
          List.of(), Map.of());

      var report = new P1cEvalReport(
          "p1c-l1-v1.4", "run-1", "2026-09-29T00:00:00Z", "2026-09-29T00:01:00Z",
          "real-embedding", "p1c-l1-vector-retrieval", "disclaimer",
          identity, embedding, vs, guard, counts, hashes, ingestion, evalConfig, agg,
          List.of(query), List.of(diagnostic),
          new P1cEvalReport.CleanupStatus("CLEANED", true, 28, 0, null, "note"),
          new P1cEvalReport.FailureInfo("PHASE2_RETRIEVAL", "IllegalStateException", "boom", "note"));

      assertThat(report.callGuard().outerOperations()).containsEntry("fallbackAttempts", 1);
      assertThat(report.queryCounts().failedRetrievalQueries()).isEqualTo(1);
      assertThat(report.queryCounts().zeroResultQueries()).isEqualTo(2);
      assertThat(report.ingestionVerification().status()).isEqualTo("PASS");
      assertThat(report.macroMetricsByK().get("k=1").totalAnswerPoints()).isEqualTo(38);
      assertThat(report.answerableResults()).singleElement()
          .satisfies(q -> assertThat(q.retrievalStatus()).isEqualTo("OK_WITH_HITS"));
      assertThat(report.noAnswerDiagnostics()).singleElement()
          .satisfies(q -> assertThat(q.includedInMacroAverage()).isFalse());

      // 与装配层一致：pretty printer 序列化；断言时忽略缩进差异
      String json = tools.jackson.databind.json.JsonMapper.builder().build()
          .writerWithDefaultPrettyPrinter().writeValueAsString(report).replaceAll("\\s+", "");
      assertThat(json).contains("\"k=1\"", "\"retrievalStatus\":\"OK_WITH_HITS\"",
          "\"cleanupStatus\"").doesNotContain("sk-");
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

  @Nested
  @DisplayName("P1cIngestionVerifier 严格入库核对（假数据库行）")
  class IngestionVerifierTest {

    private static final String RUN = "run-1";
    private static final String KB = "900001";

    private P1cExpectedChunk expected(String id, String docId, int seq, String text) {
      return P1cExpectedChunk.from(id, docId, seq, text);
    }

    /** 与期望一致的行；kb_id 原始 JSON 带引号 = 字符串型。 */
    private P1cIngestionVerifier.StoredRow row(String id, String docId, int seq, String text) {
      return new P1cIngestionVerifier.StoredRow(text, id, docId, String.valueOf(seq), RUN, KB,
          "\"" + KB + "\"");
    }

    private List<String> verifyOne(P1cIngestionVerifier.StoredRow actual) {
      return P1cIngestionVerifier.verify(
          List.of(expected("chunk-a", "doc-1", 3, "原文")), List.of(actual), RUN, KB);
    }

    @Test
    @DisplayName("完全一致：零违例")
    void verify_allAligned_noViolations() {
      assertThat(verifyOne(row("chunk-a", "doc-1", 3, "原文"))).isEmpty();
    }

    @Test
    @DisplayName("文本错位：内容差一个字符即拒绝（SHA-256 比对）")
    void verify_misalignedText_rejected() {
      assertThat(verifyOne(row("chunk-a", "doc-1", 3, "原文X")))
          .anyMatch(v -> v.contains("文本错位") && v.contains("chunk-a"));
    }

    @Test
    @DisplayName("字节口径：CRLF 与 LF 归一化后哈希相同")
    void verify_crlfAndLfSameHash() {
      String withCr = "第一行\r\n第二行\r第三行";
      String withLf = "第一行\n第二行\n第三行";
      assertThat(P1cExpectedChunk.sha256NormalizedUtf8(withCr))
          .isEqualTo(P1cExpectedChunk.sha256NormalizedUtf8(withLf));
      // 归一化不掩盖可见字符漂移
      assertThat(P1cExpectedChunk.sha256NormalizedUtf8(withLf))
          .isNotEqualTo(P1cExpectedChunk.sha256NormalizedUtf8(withLf + " "));
    }

    @Test
    @DisplayName("来源错位：doc_id 与 chunk_index 各自单独拒绝")
    void verify_sourceMisaligned_rejected() {
      assertThat(verifyOne(row("chunk-a", "doc-OTHER", 3, "原文")))
          .anyMatch(v -> v.contains("来源错位(doc_id)"));
      assertThat(verifyOne(row("chunk-a", "doc-1", 99, "原文")))
          .anyMatch(v -> v.contains("来源错位(chunk_index)"));
    }

    @Test
    @DisplayName("kb_id 数字型（JSON 不带引号）判为非字符串型")
    void verify_numericKbId_rejected() {
      P1cIngestionVerifier.StoredRow numeric = new P1cIngestionVerifier.StoredRow(
          "原文", "chunk-a", "doc-1", "3", RUN, KB, KB);
      assertThat(P1cIngestionVerifier.verify(
          List.of(expected("chunk-a", "doc-1", 3, "原文")), List.of(numeric), RUN, KB))
          .anyMatch(v -> v.contains("kb_id 非字符串型"));
    }

    @Test
    @DisplayName("缺失、重复、额外 eval_chunk_id 全部拒绝")
    void verify_missingDuplicateExtraIds_rejected() {
      List<P1cExpectedChunk> expected = List.of(
          expected("chunk-a", "doc-1", 0, "A"),
          expected("chunk-b", "doc-1", 1, "B"));

      // 缺 chunk-b、chunk-a 重复、多出 chunk-zzz
      List<P1cIngestionVerifier.StoredRow> actual = List.of(
          row("chunk-a", "doc-1", 0, "A"),
          row("chunk-a", "doc-1", 0, "A"),
          row("chunk-zzz", "doc-1", 2, "Z"));

      List<String> violations = P1cIngestionVerifier.verify(expected, actual, RUN, KB);
      assertThat(violations).anyMatch(v -> v.contains("缺失行: eval_chunk_id=chunk-b"));
      assertThat(violations).anyMatch(v -> v.contains("重复 eval_chunk_id=chunk-a"));
      assertThat(violations).anyMatch(v -> v.contains("额外 eval_chunk_id（不在冻结集）: chunk-zzz"));
      assertThat(violations).anyMatch(v -> v.contains("行数"));
    }

    @Test
    @DisplayName("外来 runId 的行按额外行拒绝，不参与逐行对照")
    void verify_foreignRunIdRows_rejected() {
      List<String> violations = P1cIngestionVerifier.verify(
          List.of(expected("chunk-a", "doc-1", 0, "A")),
          List.of(row("chunk-a", "doc-1", 0, "A"),
              new P1cIngestionVerifier.StoredRow("A", "chunk-x", "doc-1", "0",
                  "other-run", KB, "\"" + KB + "\"")),
          RUN, KB);

      assertThat(violations).anyMatch(v -> v.contains("eval_run_id 越界: other-run"));
      assertThat(violations).anyMatch(v -> v.contains("不属于本次 runId"));
    }

    @Test
    @DisplayName("全表行数与期望不符时单独立违例")
    void verify_rowCountMismatch_rejected() {
      assertThat(P1cIngestionVerifier.verify(
          List.of(expected("chunk-a", "doc-1", 0, "A"), expected("chunk-b", "doc-1", 1, "B")),
          List.of(row("chunk-a", "doc-1", 0, "A")), RUN, KB))
          .anyMatch(v -> v.contains("缺失行: eval_chunk_id=chunk-b"));
    }
  }

  @Nested
  @DisplayName("P1cRetrievalHandler 检索状态与回退准入（假检索）")
  class RetrievalHandlerTest {

    private static final String RUN = "run-1";
    private static final String KB = "900001";
    private static final Set<String> FROZEN = Set.of("chunk-a", "chunk-b");

    private Hit hit(String chunkId) {
      return new Hit(chunkId, 0.9, RUN, KB, true);
    }

    /** 可编程假检索：记录调用序列，按脚本抛异常或返回结果。 */
    private static final class FakeSearch implements P1cRetrievalHandler.SearchFunction {
      final List<String> calls = new ArrayList<>();
      Exception mainError;
      Exception fallbackError;
      List<Hit> mainResult = List.of();
      List<Hit> fallbackResult = List.of();

      @Override
      public List<Hit> search(String query, boolean filtered, int topK) throws Exception {
        calls.add((filtered ? "main:" : "fallback:") + topK);
        if (filtered && mainError != null) {
          throw mainError;
        }
        if (!filtered && fallbackError != null) {
          throw fallbackError;
        }
        return filtered ? mainResult : fallbackResult;
      }
    }

    private P1cRetrievalHandler handler(P1cEvalCallBudget budget, FakeSearch search) {
      return new P1cRetrievalHandler(budget, search, RUN, KB, FROZEN, 10, 30);
    }

    @Test
    @DisplayName("构造期拒绝不符合 topK×3 的回退候选数")
    void constructor_rejectsWrongFallbackTopK() {
      assertThatThrownBy(() -> new P1cRetrievalHandler(
          new P1cEvalCallBudget(10), (q, f, k) -> List.of(), RUN, KB, FROZEN, 10, 20))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("mainTopK×3 = 30")
          .hasMessageContaining("实际 20");
    }

    @Test
    @DisplayName("主检索有命中：OK_WITH_HITS，不发起回退")
    void retrieve_mainHits_ok() {
      FakeSearch search = new FakeSearch();
      search.mainResult = List.of(hit("chunk-a"));
      P1cEvalCallBudget budget = new P1cEvalCallBudget(50);

      var outcome = handler(budget, search).retrieve("q");

      assertThat(outcome.status()).isEqualTo(P1cRetrievalHandler.Status.OK_WITH_HITS);
      assertThat(outcome.fallbackAttempted()).isFalse();
      assertThat(search.calls).containsExactly("main:10");
      assertThat(budget.getAttempts()).isEqualTo(1);
      assertThat(budget.getSuccesses()).isEqualTo(1);
    }

    @Test
    @DisplayName("主检索正常返回但零命中：OK_ZERO_RESULT，不回退、不算失败")
    void retrieve_zeroHits_isNotFailure() {
      FakeSearch search = new FakeSearch();
      search.mainResult = List.of();

      var outcome = handler(new P1cEvalCallBudget(50), search).retrieve("q");

      assertThat(outcome.status()).isEqualTo(P1cRetrievalHandler.Status.OK_ZERO_RESULT);
      assertThat(outcome.failed()).isFalse();
      assertThat(search.calls).containsExactly("main:10");
    }

    @Test
    @DisplayName("主检索异常→回退 topK=30 无过滤并本地隔离过滤")
    void retrieve_mainThrows_fallbackFiltersForeignRows() {
      FakeSearch search = new FakeSearch();
      search.mainError = new IllegalStateException("SQL 超时");
      search.fallbackResult = List.of(
          hit("chunk-a"),
          new Hit("chunk-b", 0.8, "other-run", KB, true),
          new Hit("chunk-b", 0.8, RUN, "12345", true));

      var outcome = handler(new P1cEvalCallBudget(50), search).retrieve("q");

      assertThat(outcome.status()).isEqualTo(P1cRetrievalHandler.Status.OK_WITH_HITS);
      assertThat(outcome.fallbackAttempted()).isTrue();
      assertThat(search.calls).containsExactly("main:10", "fallback:30");
      assertThat(outcome.hits()).extracting(Hit::evalChunkId).containsExactly("chunk-a");
    }

    @Test
    @DisplayName("回退过滤后为空：判 RETRIEVAL_FAILED，绝不降级成正常零命中")
    void retrieve_fallbackEmptyAfterFilter_isFailure() {
      FakeSearch search = new FakeSearch();
      search.mainError = new IllegalStateException("SQL 超时");
      search.fallbackResult = List.of(new Hit("chunk-a", 0.8, "other-run", KB, true));

      var outcome = handler(new P1cEvalCallBudget(50), search).retrieve("q");

      assertThat(outcome.status()).isEqualTo(P1cRetrievalHandler.Status.RETRIEVAL_FAILED);
      assertThat(outcome.hits()).isEmpty();
      assertThat(outcome.fallbackError()).contains("本地过滤后无命中");
    }

    @Test
    @DisplayName("主+回退都异常：RETRIEVAL_FAILED 且两个错误都记录")
    void retrieve_bothFail_recordsBothErrors() {
      FakeSearch search = new FakeSearch();
      search.mainError = new IllegalStateException("主链路挂了");
      search.fallbackError = new IllegalStateException("回退也挂了");

      var outcome = handler(new P1cEvalCallBudget(50), search).retrieve("q");

      assertThat(outcome.status()).isEqualTo(P1cRetrievalHandler.Status.RETRIEVAL_FAILED);
      assertThat(outcome.mainError()).contains("主链路挂了");
      assertThat(outcome.fallbackError()).contains("回退也挂了");
    }

    @Test
    @DisplayName("预算只剩 1 次：主检索用掉后回退不发起，零额外 HTTP")
    void retrieve_fallbackOverBudget_notIssued() {
      FakeSearch search = new FakeSearch();
      search.mainError = new IllegalStateException("SQL 超时");
      P1cEvalCallBudget budget = new P1cEvalCallBudget(1);

      var outcome = handler(budget, search).retrieve("q");

      assertThat(outcome.status()).isEqualTo(P1cRetrievalHandler.Status.RETRIEVAL_FAILED);
      assertThat(search.calls).containsExactly("main:10");
      assertThat(budget.getAttempts()).isEqualTo(1);
      assertThat(outcome.fallbackError()).contains("回退未发起（预算耗尽）");
    }

    @Test
    @DisplayName("预算为 0：BUDGET_EXCEEDED，未发起检索、未递增 attempt")
    void retrieve_budgetExhausted_noAttemptNoHttp() {
      FakeSearch search = new FakeSearch();
      P1cEvalCallBudget budget = new P1cEvalCallBudget(0);

      var outcome = handler(budget, search).retrieve("q");

      assertThat(outcome.status()).isEqualTo(P1cRetrievalHandler.Status.BUDGET_EXCEEDED);
      assertThat(search.calls).isEmpty();
      assertThat(budget.getAttempts()).isEqualTo(0);
      assertThat(budget.getFailures()).isEqualTo(0);
    }

    @Test
    @DisplayName("命中非冻结 chunkId：直接中止整轮，不吞成单题零分")
    void retrieve_nonFrozenChunkId_abortsRun() {
      FakeSearch search = new FakeSearch();
      search.mainResult = List.of(hit("chunk-ghost"));

      assertThatThrownBy(() -> handler(new P1cEvalCallBudget(50), search).retrieve("q"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("非冻结 eval_chunk_id")
          .hasMessageContaining("chunk-ghost");
    }

    @Test
    @DisplayName("命中 runId 越界或 kb_id 非字符串：中止整轮")
    void retrieve_isolationBreak_abortsRun() {
      FakeSearch foreignRun = new FakeSearch();
      foreignRun.mainResult = List.of(new Hit("chunk-a", 0.9, "other-run", KB, true));
      assertThatThrownBy(() -> handler(new P1cEvalCallBudget(50), foreignRun).retrieve("q"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("隔离失败");

      FakeSearch numericKb = new FakeSearch();
      numericKb.mainResult = List.of(new Hit("chunk-a", 0.9, RUN, KB, false));
      assertThatThrownBy(() -> handler(new P1cEvalCallBudget(50), numericKb).retrieve("q"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("kb_id 非字符串型");
    }

    @Test
    @DisplayName("每题至多 1 次回退：回退异常不再二次回退")
    void retrieve_fallbackFailsOnce_onlyOneFallback() {
      FakeSearch search = new FakeSearch();
      search.mainError = new IllegalStateException("主链路挂了");
      search.fallbackError = new IllegalStateException("回退挂了");

      handler(new P1cEvalCallBudget(50), search).retrieve("q");

      assertThat(search.calls).hasSize(2);
      assertThat(search.calls).containsExactly("main:10", "fallback:30");
    }
  }

  @Nested
  @DisplayName("P1cMultiKMetrics 多 K 截断与宏/微分母")
  class MultiKMetricsTest {

    private P1cAnswerPointMetrics.AnswerPoint point(int idx, String... chunks) {
      return new P1cAnswerPointMetrics.AnswerPoint(idx, List.of(chunks));
    }

    @Test
    @DisplayName("离线截断：K 越大 Hit/MRR/APC 单调不减")
    void perQuery_truncationMonotonic() {
      var points = List.of(point(1, "chunk-a"), point(2, "chunk-b"));
      var ranked = List.of("chunk-x", "chunk-y", "chunk-a", "chunk-b", "chunk-z");

      var perK = P1cMultiKMetrics.perQuery(points, ranked, false);

      assertThat(perK.keySet()).containsExactlyInAnyOrder(1, 3, 5, 10);
      assertThat(perK.get(1).hitAtK()).isZero();
      assertThat(perK.get(3).hitAtK()).isEqualTo(1.0);
      assertThat(perK.get(3).mrrAtK()).isEqualTo(1.0 / 3);
      assertThat(perK.get(3).apcAtK()).isEqualTo(0.5);
      assertThat(perK.get(3).fullCoverageAtK()).isFalse();
      assertThat(perK.get(5).apcAtK()).isEqualTo(1.0);
      assertThat(perK.get(5).fullCoverageAtK()).isTrue();
      assertThat(perK.get(10).coveredPoints()).isEqualTo(2);
    }

    @Test
    @DisplayName("NO_ANSWER（空要点）不产生任何 K 的指标")
    void perQuery_noAnswerPoints_returnsEmpty() {
      assertThat(P1cMultiKMetrics.perQuery(List.of(), List.of("chunk-a"), false)).isEmpty();
    }

    @Test
    @DisplayName("检索失败题：指标全零但仍计入宏平均分母（状态由调用方独立记录）")
    void aggregate_failedQueryCountsAsZeroInDenominator() {
      var ok = P1cMultiKMetrics.perQuery(
          List.of(point(1, "chunk-a")), List.of("chunk-a"), false);
      var failed = P1cMultiKMetrics.perQuery(
          List.of(point(2, "chunk-b")), List.of(), true);

      var agg = P1cMultiKMetrics.aggregate(List.of(ok, failed));

      P1cMultiKMetrics.KAggregate atK1 = agg.get(1);
      assertThat(atK1.macroHitAtK()).isEqualTo(0.5);
      assertThat(atK1.macroApcAtK()).isEqualTo(0.5);
      assertThat(failed.get(1).hitAtK()).isZero();
      assertThat(failed.get(1).totalPoints()).isEqualTo(1);
    }

    @Test
    @DisplayName("微平均 coveredPoints/38 与宏平均 APC 名称与分母都不同")
    void aggregate_microDenominatorIsPoints_notQueries() {
      // q1: 3 个要点覆盖 1 个；q2: 1 个要点覆盖 1 个
      var q1 = P1cMultiKMetrics.perQuery(
          List.of(point(1, "chunk-a"), point(2, "nope"), point(3, "nope2")),
          List.of("chunk-a"), false);
      var q2 = P1cMultiKMetrics.perQuery(List.of(point(1, "chunk-b")), List.of("chunk-b"), false);

      var agg = P1cMultiKMetrics.aggregate(List.of(q1, q2)).get(1);

      // macro：先算每题再平均 → (1/3 + 1) / 2 = 0.6667
      assertThat(agg.macroApcAtK()).isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(1e-9));
      // micro：要点直接相加 → 2/4 = 0.5，分母是要点总数不是题数
      assertThat(agg.coveredPointsAtK()).isEqualTo(2);
      assertThat(agg.totalAnswerPoints()).isEqualTo(4);
      assertThat(agg.microCoverageAtK()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("无题可聚合时返回空 Map（不因全失败题而除零）")
    void aggregate_empty_returnsEmpty() {
      assertThat(P1cMultiKMetrics.aggregate(List.of())).isEmpty();
    }
  }

  @Nested
  @DisplayName("P1cEvalRunOrchestrator 统一失败与清理流程")
  class OrchestratorTest {

    private static final class Recorder {
      final List<String> order = new ArrayList<>();
      int deletedRows = 28;
      int remainingRows = 0;
      RuntimeException cleanupError;
      RuntimeException reportError;
      P1cEvalRunOrchestrator.RunResult seenByWriter;

      P1cEvalRunOrchestrator.CleanupAction cleanup() {
        return () -> {
          order.add("cleanup");
          if (cleanupError != null) {
            throw cleanupError;
          }
          return new P1cEvalRunOrchestrator.CleanupRowCounts(deletedRows, remainingRows);
        };
      }

      P1cEvalRunOrchestrator.ReportWriter writer() {
        return result -> {
          order.add("report");
          seenByWriter = result;
          if (reportError != null) {
            throw reportError;
          }
        };
      }
    }

    @Test
    @DisplayName("主体异常：先清理再写报告，报告携带失败阶段")
    void execute_bodyFails_cleanupThenReport() {
      Recorder rec = new Recorder();
      var orchestrator = new P1cEvalRunOrchestrator(rec.cleanup(), rec.writer());
      RuntimeException boom = new RuntimeException("Phase 2 挂了");

      var result = orchestrator.execute(ctx -> {
        ctx.startWrites();
        ctx.phase("PHASE2_RETRIEVAL");
        throw boom;
      });

      assertThat(rec.order).containsExactly("cleanup", "report");
      assertThat(result.failure().phase()).isEqualTo("PHASE2_RETRIEVAL");
      assertThat(result.failure().message()).isEqualTo("Phase 2 挂了");
      assertThat(result.cleanup().status()).isEqualTo(P1cEvalRunOrchestrator.CleanupStatus.CLEANED);
      assertThat(result.reportWritten()).isTrue();
      assertThat(orchestrator.primaryError()).isSameAs(boom);
    }

    @Test
    @DisplayName("未进入写入阶段：不尝试清理，报告照常写出")
    void execute_noWrites_cleanupNotRequired() {
      Recorder rec = new Recorder();
      var orchestrator = new P1cEvalRunOrchestrator(rec.cleanup(), rec.writer());

      var result = orchestrator.execute(ctx -> ctx.phase("PHASE0_ENV"));

      assertThat(rec.order).containsExactly("report");
      assertThat(result.cleanup().status())
          .isEqualTo(P1cEvalRunOrchestrator.CleanupStatus.NOT_REQUIRED);
      assertThat(result.cleanup().attempted()).isFalse();
      assertThat(result.experimentSucceeded()).isTrue();
    }

    @Test
    @DisplayName("清理失败不覆盖原始实验异常，且状态不是 CLEANED")
    void execute_cleanupFails_doesNotOverridePrimary() {
      Recorder rec = new Recorder();
      rec.cleanupError = new RuntimeException("DELETE 权限不足");
      var orchestrator = new P1cEvalRunOrchestrator(rec.cleanup(), rec.writer());
      RuntimeException boom = new RuntimeException("检索阶段异常");

      var result = orchestrator.execute(ctx -> {
        ctx.startWrites();
        ctx.phase("PHASE2_RETRIEVAL");
        throw boom;
      });

      assertThat(result.cleanup().status())
          .isEqualTo(P1cEvalRunOrchestrator.CleanupStatus.CLEAN_FAILED);
      assertThat(result.cleanup().success()).isFalse();
      assertThat(result.failure().phase()).isEqualTo("PHASE2_RETRIEVAL");
      assertThat(result.failure().message()).isEqualTo("检索阶段异常");
      assertThat(orchestrator.primaryError()).isSameAs(boom);
      assertThat(boom.getSuppressed())
          .anyMatch(t -> t.getMessage().contains("DELETE 权限不足"));
      // 清理失败时报告仍然写出，且带上了清理状态
      assertThat(result.reportWritten()).isTrue();
      assertThat(rec.seenByWriter.cleanup().status())
          .isEqualTo(P1cEvalRunOrchestrator.CleanupStatus.CLEAN_FAILED);
    }

    @Test
    @DisplayName("清理后仍有残留：记为 CLEAN_PARTIAL，主体成功也判失败")
    void execute_cleanupPartial_isFailure() {
      Recorder rec = new Recorder();
      rec.remainingRows = 3;
      var orchestrator = new P1cEvalRunOrchestrator(rec.cleanup(), rec.writer());

      var result = orchestrator.execute(ctx -> ctx.startWrites());

      assertThat(result.cleanup().status())
          .isEqualTo(P1cEvalRunOrchestrator.CleanupStatus.CLEAN_PARTIAL);
      assertThat(result.failure().phase()).isEqualTo("CLEANUP");
      assertThat(result.experimentSucceeded()).isFalse();
      assertThat(orchestrator.primaryError()).isNotNull();
    }

    @Test
    @DisplayName("报告写入失败：清理已尝试过，失败信息不丢")
    void execute_reportFails_cleanupStillAttempted() {
      Recorder rec = new Recorder();
      rec.reportError = new RuntimeException("磁盘已满");
      var orchestrator = new P1cEvalRunOrchestrator(rec.cleanup(), rec.writer());

      var result = orchestrator.execute(ctx -> ctx.startWrites());

      assertThat(rec.order).containsExactly("cleanup", "report");
      assertThat(result.cleanup().status()).isEqualTo(P1cEvalRunOrchestrator.CleanupStatus.CLEANED);
      assertThat(result.reportWritten()).isFalse();
      assertThat(result.reportError()).contains("磁盘已满");
      // 报告没写出来就不能算成功交付
      assertThat(orchestrator.primaryError()).isNotNull();
    }

    @Test
    @DisplayName("主体异常叠加报告失败：原始异常仍是主因，报告失败挂 suppressed")
    void execute_bodyFailsAndReportFails_keepsPrimary() {
      Recorder rec = new Recorder();
      rec.reportError = new RuntimeException("磁盘已满");
      var orchestrator = new P1cEvalRunOrchestrator(rec.cleanup(), rec.writer());
      RuntimeException boom = new RuntimeException("入库失败");

      var result = orchestrator.execute(ctx -> {
        ctx.startWrites();
        ctx.phase("PHASE1_INGEST");
        throw boom;
      });

      assertThat(orchestrator.primaryError()).isSameAs(boom);
      assertThat(result.failure().message()).isEqualTo("入库失败");
      assertThat(boom.getSuppressed()).anyMatch(t -> t.getMessage().contains("磁盘已满"));
    }

    @Test
    @DisplayName("部分结果与请求观测在失败时也进入报告上下文")
    void execute_preservesPartialResultsAndObservations() {
      Recorder rec = new Recorder();
      var orchestrator = new P1cEvalRunOrchestrator(rec.cleanup(), rec.writer());

      var result = orchestrator.execute(ctx -> {
        ctx.startWrites();
        ctx.partialResults(List.of("q01", "q02"));
        ctx.observe("httpRequestsObserved", 7);
        ctx.phase("PHASE2_RETRIEVAL");
        throw new RuntimeException("中途失败");
      });

      assertThat(result.partialResults()).isEqualTo(List.of("q01", "q02"));
      assertThat(result.observations()).containsEntry("httpRequestsObserved", 7);
      assertThat(rec.seenByWriter.partialResults()).isEqualTo(List.of("q01", "q02"));
    }

    @Test
    @DisplayName("全部成功：CLEANED + 报告写出 + 无失败")
    void execute_allGood() {
      Recorder rec = new Recorder();
      var orchestrator = new P1cEvalRunOrchestrator(rec.cleanup(), rec.writer());

      var result = orchestrator.execute(ctx -> ctx.startWrites());

      assertThat(result.experimentSucceeded()).isTrue();
      assertThat(result.reportUsable()).isTrue();
      assertThat(orchestrator.primaryError()).isNull();
    }
  }
}
