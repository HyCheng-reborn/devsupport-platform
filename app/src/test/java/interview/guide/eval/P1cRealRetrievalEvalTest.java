package interview.guide.eval;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.Timeout;
import com.openai.credential.BearerTokenCredential;
import interview.guide.common.ai.ApiPathResolver;
import interview.guide.eval.P1cEvalRunOrchestrator.CleanupRowCounts;
import interview.guide.eval.P1cEvalRunOrchestrator.RunContext;
import interview.guide.eval.P1cMultiKMetrics.KAggregate;
import interview.guide.eval.P1cMultiKMetrics.PerKResult;
import interview.guide.eval.P1cRetrievalHandler.Hit;
import interview.guide.eval.P1cRetrievalHandler.Outcome;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * P1-C L1 真实向量检索评测入口（装配层）。
 *
 * <p>本类只做装配。契约逻辑都在纯组件里，各有离线单元测试：
 * {@link P1cIngestionVerifier}（严格入库核对）、{@link P1cRetrievalHandler}（检索状态与回退准入）、
 * {@link P1cMultiKMetrics}（多 K 宏/微指标）、{@link P1cEvalRunOrchestrator}（统一失败与清理）。
 *
 * <p>阶段顺序：Phase 0 环境与身份校验（无写副作用、无付费请求；工件哈希与已提交冻结清单逐项比对，
 * 且该校验先于读取凭据、构建客户端、连接数据库）→ Phase 1 入库 + 逐行核对
 * （此处标记写入起点）→ Phase 2 检索 20 题 → Phase 3/4 多 K 指标 + 整轮可用性判定 → 清理 → 报告。
 * 清理与报告写出由 orchestrator 统一保证（清理先于报告），各阶段不散写 finally；
 * 每个阶段边界都会落一次部分结果与请求观测快照，中途失败时报告仍带着到该阶段为止的内容。
 *
 * <p>整轮可用性：存在 {@code RETRIEVAL_FAILED} 或题数未全部请求完成时，Phase 3 之后抛实验级异常
 * （{@link P1cRoundAvailability}）。失败题的原始记录与按零计入的多 K 指标仍写进报告，
 * 但这一轮不会被当成"跑成功了的基线"。
 *
 * <p>前置条件：
 * <ul>
 *   <li>评测 Postgres 容器已启动：
 *       {@code docker compose -f docker-compose-eval.yml --env-file .env.eval up -d}</li>
 *   <li>系统属性 {@code eval.p1c.realApi=true}（严格布尔，缺失/空串/其他值一律跳过）</li>
 *   <li>Embedding Key：环境变量 {@code AI_BAILIAN_API_KEY}（推荐）或系统属性 {@code eval.embedding.apiKey}</li>
 *   <li>eval_runner 密码：环境变量 {@code EVAL_RUNNER_PASSWORD}（推荐）或系统属性 {@code eval.datasource.password}</li>
 *   <li>数据集绝对路径由 Gradle 注入系统属性 {@code eval.datasetDir}</li>
 * </ul>
 *
 * <p>运行命令：
 * <pre>
 * AI_BAILIAN_API_KEY=... EVAL_RUNNER_PASSWORD=... \
 *   ./gradlew :app:evalP1cReal -Peval.p1c.realApi=true
 * </pre>
 */
@Tag("real-eval")
public class P1cRealRetrievalEvalTest {

  private static final Logger log = LoggerFactory.getLogger(P1cRealRetrievalEvalTest.class);

  private static final int TOP_K = 10;
  private static final int FALLBACK_TOP_K = 30;
  // 预算硬上限：默认 50，不得擅自调高；仅当调用方显式 -Peval.budget.hardLimit=... 时才覆盖。
  private static final int BUDGET_HARD_LIMIT = Integer.getInteger("eval.budget.hardLimit", 50);
  private static final int EMBEDDING_BATCH_SIZE = Integer.getInteger("eval.embedding.batchSize", 10);
  // 数据集预期规模：默认对齐基线 devsupport-v0.1（28 chunk / 38 要点 / 20 题 / 16+4）。
  // 独立候选数据集通过系统属性覆盖，Phase 0 在任何网络调用前据此校验。
  private static final int EXPECTED_CHUNK_COUNT = Integer.getInteger("eval.expected.chunkCount", 28);
  private static final int EXPECTED_QUERY_COUNT = Integer.getInteger("eval.expected.queryCount", 20);
  private static final int EXPECTED_ANSWERABLE_COUNT =
      Integer.getInteger("eval.expected.answerableCount", 16);
  private static final int EXPECTED_NO_ANSWER_COUNT =
      Integer.getInteger("eval.expected.noAnswerCount", 4);
  private static final int EXPECTED_ANSWER_POINTS =
      Integer.getInteger("eval.expected.answerPoints", 38);
  private static final String EXPECTED_MARKER_UUID = "f47ac10b-58cc-4372-a567-0e0283c5d9e7";
  private static final String EXPECTED_MARKER_TYPE = "p1c-eval-isolated";
  private static final String KB_ID = P1cEvalResultValidator.EXPECTED_KB_ID;
  private static final String KB_FILTER = "kb_id in ['" + KB_ID + "']";
  private static final String REPORT_VERSION = "p1c-l1-v1.4";
  private static final Set<String> FROZEN_ARTIFACT_FILES = Set.of(
      "chunks.jsonl", "candidate-gold.json", "corpus-manifest.json", "chunk-manifest.json");

  @Test
  void realRetrievalEval() throws Throwable {
    assumeTrue("true".equals(System.getProperty("eval.p1c.realApi")),
        "P1-C 真实评测已禁用：需设置 -Peval.p1c.realApi=true");

    RunState st = new RunState();
    st.evalRunId = UUID.randomUUID().toString();
    st.startTime = Instant.now();
    st.budget = new P1cEvalCallBudget(BUDGET_HARD_LIMIT);
    st.httpCounter = new P1cEvalHttpCallCounter();

    P1cEvalRunOrchestrator orchestrator = new P1cEvalRunOrchestrator(
        st::cleanupByRunId,
        result -> writeReport(st, result));

    var result = orchestrator.execute(st::runPhases);
    log.info("P1-C 编排结束: failure={}, cleanup={}, reportWritten={}, reportError={}",
        result.failure(), result.cleanup().status(), result.reportWritten(), result.reportError());

    Throwable primary = orchestrator.primaryError();
    if (primary != null) {
      // 原始实验异常保持为主因；清理与报告写出的失败已作为 suppressed 挂在链上
      throw new AssertionError("P1-C 真实评测失败（报告已尽力写出，见 "
          + reportPath().toAbsolutePath() + "）", primary);
    }
  }

  // ══════════════════════════════ 装配主体 ══════════════════════════════

  /** 一次评测的可变状态 + Phase 0~4 装配步骤 + Phase 5 清理动作。 */
  private final class RunState {

    String evalRunId;
    Instant startTime;
    P1cEvalCallBudget budget;
    P1cEvalHttpCallCounter httpCounter;
    JdbcTemplate jdbc;
    PgVectorStore vectorStore;

    Path datasetDir;
    String chunksSha256;
    String candidateGoldSha256;
    String corpusManifestSha256;
    String chunkManifestSha256;
    List<ChunkRecord> chunks = List.of();
    CandidateGold candidateGold;
    final Set<String> frozenChunkIds = new HashSet<>();

    String evalUrl;
    String baseUrl;
    String modelName;
    int dimensions;

    P1cEvalReport.ConnectionIdentity connectionIdentity;
    P1cFrozenArtifactVerifier.Verification artifactFreeze;
    P1cEvalReport.IngestionVerification ingestion;
    Integer dbVectorDimension;
    String dbIndexDef;

    final List<P1cEvalReport.PerQueryResult> answerableResults = new ArrayList<>();
    final List<P1cEvalReport.PerQueryResult> noAnswerDiagnostics = new ArrayList<>();
    final List<Map<Integer, PerKResult>> answerablePerK = new ArrayList<>();
    final Map<String, Integer> stageCounts = new LinkedHashMap<>();
    Map<String, P1cEvalReport.KAggregateReport> aggregatesByK = Map.of();
    int failedRetrievalQueries;
    int zeroResultQueries;
    int fallbackQueries;
    int fallbackRawCandidates;
    P1cRoundAvailability roundAvailability;

    void runPhases(RunContext ctx) throws Exception {
      ctx.phase("PHASE0_ENV");
      phase0Environment();
      snapshot(ctx);

      ctx.phase("PHASE1_INGEST");
      // 从这一刻起可能已产生写副作用：orchestrator 必须尝试按 runId 清理
      ctx.startWrites();
      snapshot(ctx);
      phase1IngestAndVerify();
      snapshot(ctx);

      ctx.phase("PHASE2_RETRIEVAL");
      phase2Retrieve();
      snapshot(ctx);

      ctx.phase("PHASE3_METRICS");
      phase3Metrics();
      snapshot(ctx);
    }

    /**
     * 每个阶段边界都落一次部分结果与请求观测快照（后一次覆盖前一次）。
     * 入库核对失败时 {@link #ingestion} 已定值但检索还没跑，此时报告仍能拿到该阶段为止的结果，
     * 而不是一个空上下文。
     */
    private void snapshot(RunContext ctx) {
      Map<String, Object> partial = new LinkedHashMap<>();
      partial.put("phase", ctx.phase());
      partial.put("artifactFreeze", artifactFreeze);
      partial.put("ingestionVerification", ingestion);
      partial.put("answerableResults", List.copyOf(answerableResults));
      partial.put("noAnswerDiagnostics", List.copyOf(noAnswerDiagnostics));
      ctx.partialResults(partial);
      ctx.observe("outerOperations", new LinkedHashMap<>(stageCounts));
      ctx.observe("budgetAttempts", budget.getAttempts());
      ctx.observe("httpRequestsObserved", httpCounter.get());
      ctx.observe("failedRetrievalQueries", failedRetrievalQueries);
      ctx.observe("zeroResultQueries", zeroResultQueries);
      ctx.observe("fallbackQueries", fallbackQueries);
      ctx.observe("fallbackRawCandidates", fallbackRawCandidates);
    }

    // ───── Phase 0：环境与身份校验（无写操作、无付费请求）─────

    private void phase0Environment() throws IOException, NoSuchAlgorithmException {
      datasetDir = resolveDatasetDir();
      log.info("Phase 0: 数据集目录 {}", datasetDir);

      Path chunksPath = datasetDir.resolve("chunks.jsonl");
      Path candidateGoldPath = datasetDir.resolve("candidate-gold.json");
      Path corpusManifestPath = datasetDir.resolve("corpus-manifest.json");
      Path chunkManifestPath = datasetDir.resolve("chunk-manifest.json");
      for (Path p : List.of(chunksPath, candidateGoldPath, corpusManifestPath, chunkManifestPath)) {
        if (!Files.isRegularFile(p)) {
          throw new IllegalStateException("P1-B 冻结工件缺失: " + p.toAbsolutePath());
        }
      }

      // 冻结工件校验：与已提交清单里的批准哈希逐项比对。
      // 位置是硬约束——必须在读取凭据、构建 Embedding 客户端、连接数据库、任何写入之前完成，
      // 否则一份被改写而条数不变的工件会先被付费 Embedding 向量化再发现。
      artifactFreeze = P1cFrozenArtifactVerifier.verifyAgainstFreezeList(
          datasetDir, FROZEN_ARTIFACT_FILES);
      if (!artifactFreeze.passed()) {
        throw new IllegalStateException("P1-B 冻结工件校验失败（" + artifactFreeze.violations().size()
            + " 项违例，本轮不启动）:\n  - "
            + String.join("\n  - ", artifactFreeze.violations()));
      }
      log.info("Phase 0: 冻结工件逐项比对通过: {} 个工件, 口径={}",
          artifactFreeze.checks().size(), artifactFreeze.hashCaliber());

      chunksSha256 = sha256File(chunksPath);
      candidateGoldSha256 = sha256File(candidateGoldPath);
      corpusManifestSha256 = sha256File(corpusManifestPath);
      chunkManifestSha256 = sha256File(chunkManifestPath);

      chunks = readChunks(chunksPath);
      for (ChunkRecord chunk : chunks) {
        if (!frozenChunkIds.add(chunk.chunkId())) {
          throw new IllegalStateException("chunks.jsonl 存在重复 chunkId: " + chunk.chunkId());
        }
      }

      candidateGold = readCandidateGold(candidateGoldPath);
      int answerable = 0;
      int noAnswer = 0;
      int points = 0;
      List<String> goldSupportingIds = new ArrayList<>();
      for (CandidateGoldEntry entry : candidateGold.entries()) {
        if ("ANSWERABLE".equals(entry.answerability())) {
          answerable++;
          List<P1cAnswerPointMetrics.AnswerPoint> aps = extractAnswerPoints(entry);
          points += aps.size();
          for (P1cAnswerPointMetrics.AnswerPoint ap : aps) {
            goldSupportingIds.addAll(ap.supportingChunkIds());
          }
        } else if ("NO_ANSWER".equals(entry.answerability())) {
          noAnswer++;
        }
      }

      // 数据集预检：数量契约 + 金标 ID 闭环。必须在读取凭据、构建 Embedding 客户端、连接数据库
      // 之前完成——金标指向不存在的 chunk 时所有命中会恒为 0，绝不能带着它去发起付费检索。
      P1cDatasetValidator.Result preflight = P1cDatasetValidator.validate(
          Set.copyOf(frozenChunkIds), chunks.size(), candidateGold.entries().size(),
          answerable, noAnswer, points, goldSupportingIds,
          EXPECTED_CHUNK_COUNT, EXPECTED_QUERY_COUNT, EXPECTED_ANSWERABLE_COUNT,
          EXPECTED_NO_ANSWER_COUNT, EXPECTED_ANSWER_POINTS);
      if (!preflight.passed()) {
        throw new IllegalStateException("数据集预检失败（先于任何凭据/网络调用）:\n  - "
            + String.join("\n  - ", preflight.violations()));
      }

      String apiKey = requireCredential("eval.embedding.apiKey", "AI_BAILIAN_API_KEY",
          "Embedding API Key");
      String dbPassword = requireCredential("eval.datasource.password", "EVAL_RUNNER_PASSWORD",
          "评测数据库 eval_runner 密码");
      baseUrl = System.getProperty("eval.embedding.baseUrl",
          "https://dashscope.aliyuncs.com/compatible-mode/v1");
      modelName = System.getProperty("eval.embedding.model", "text-embedding-v3");
      dimensions = Integer.parseInt(System.getProperty("eval.embedding.dimensions", "1024"));
      evalUrl = System.getProperty("eval.datasource.url",
          "jdbc:postgresql://127.0.0.1:5433/interview_guide_eval");

      EmbeddingModel embeddingModel =
          buildEmbeddingModel(apiKey, baseUrl, modelName, dimensions, httpCounter);

      DriverManagerDataSource ds = new DriverManagerDataSource();
      ds.setUrl(evalUrl);
      ds.setUsername(System.getProperty("eval.datasource.username", "eval_runner"));
      ds.setPassword(dbPassword);
      jdbc = new JdbcTemplate(ds);

      vectorStore = PgVectorStore.builder(jdbc, embeddingModel)
          .dimensions(dimensions)
          .distanceType(PgDistanceType.COSINE_DISTANCE)
          .indexType(PgIndexType.HNSW)
          .initializeSchema(false)
          .build();

      verifyIdentity(jdbc, evalUrl);

      dbVectorDimension = jdbc.queryForObject(
          "SELECT atttypmod FROM pg_attribute JOIN pg_class ON attrelid = oid "
          + "WHERE relname = 'vector_store' AND attname = 'embedding'", Integer.class);
      if (dbVectorDimension == null || dbVectorDimension != dimensions) {
        throw new IllegalStateException(
            "向量维度不一致: 数据库=" + dbVectorDimension + ", 配置=" + dimensions);
      }
      List<String> indexDefs = jdbc.queryForList(
          "SELECT indexdef FROM pg_indexes WHERE tablename = 'vector_store'", String.class);
      dbIndexDef = String.join(" | ", indexDefs);
      boolean hnswCosine = indexDefs.stream().anyMatch(d ->
          d.toLowerCase().contains("using hnsw") && d.toLowerCase().contains("vector_cosine_ops"));
      if (!hnswCosine) {
        throw new IllegalStateException("vector_store 缺少 HNSW + vector_cosine_ops 索引: " + dbIndexDef);
      }
      connectionIdentity = buildConnectionIdentity(jdbc, evalUrl);
      log.info("Phase 0 完成: dbVectorDimension={}, indexDef={}", dbVectorDimension, dbIndexDef);
    }

    // ───── Phase 1：入库 + 严格逐行核对 ─────

    private void phase1IngestAndVerify() {
      List<P1cExpectedChunk> expected = new ArrayList<>();
      List<Document> documents = new ArrayList<>();
      for (ChunkRecord chunk : chunks) {
        expected.add(P1cExpectedChunk.from(
            chunk.chunkId(), chunk.docId(), chunk.seq(), chunk.text()));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("eval_chunk_id", chunk.chunkId());
        metadata.put("eval_run_id", evalRunId);
        metadata.put("kb_id", KB_ID);
        metadata.put("doc_id", chunk.docId());
        metadata.put("chunk_index", chunk.seq());
        documents.add(new Document(chunk.text(), metadata));
      }

      for (List<Document> batch : partition(documents, EMBEDDING_BATCH_SIZE)) {
        addBatchWithOneRetry(batch);
      }

      // 全表读回（不加 runId 过滤，才能发现外来行）；kb_id 原始 JSON 用于判定存储类型
      List<P1cIngestionVerifier.StoredRow> rows = jdbc.query(
              "SELECT content, metadata->>'eval_chunk_id' AS eval_chunk_id, "
              + "metadata->>'doc_id' AS doc_id, metadata->>'chunk_index' AS chunk_index, "
              + "metadata->>'eval_run_id' AS eval_run_id, metadata->>'kb_id' AS kb_id, "
              + "CAST(metadata->'kb_id' AS TEXT) AS kb_id_raw "
              + "FROM vector_store ORDER BY id",
              (rs, rowNum) -> new P1cIngestionVerifier.StoredRow(
                  rs.getString("content"),
                  rs.getString("eval_chunk_id"),
                  rs.getString("doc_id"),
                  rs.getString("chunk_index"),
                  rs.getString("eval_run_id"),
                  rs.getString("kb_id"),
                  rs.getString("kb_id_raw")));

      List<String> violations = P1cIngestionVerifier.verify(expected, rows, evalRunId, KB_ID);
      Integer firstDim = jdbc.queryForObject(
          "SELECT vector_dims(embedding) FROM vector_store WHERE metadata->>'eval_run_id' = ? "
          + "ORDER BY id LIMIT 1", Integer.class, evalRunId);
      int runRows = (int) rows.stream().filter(r -> evalRunId.equals(r.evalRunId())).count();
      int distinctIds = (int) rows.stream()
          .map(P1cIngestionVerifier.StoredRow::evalChunkId)
          .filter(Objects::nonNull).distinct().count();

      ingestion = new P1cEvalReport.IngestionVerification(
          expected.size(), rows.size(), runRows, distinctIds, runRows - distinctIds,
          firstDim, violations, violations.isEmpty() ? "PASS" : "FAIL");

      if (!violations.isEmpty()) {
        throw new IllegalStateException("严格入库核对失败（" + violations.size() + " 项违例）:\n  - "
            + String.join("\n  - ", violations));
      }
      log.info("Phase 1 完成: 28 行逐字段核对通过, firstEmbeddingDimension={}", firstDim);
    }

    private void addBatchWithOneRetry(List<Document> batch) {
      acquireBudget("ingestionAttempts");
      try {
        vectorStore.add(batch);
        budget.recordSuccess();
        return;
      } catch (Exception first) {
        budget.recordFailure();
        stageCounts.merge("ingestionFailures", 1, Integer::sum);
        log.warn("入库批次失败，重试 1 次: {}", first.getMessage());
      }

      acquireBudget("ingestionAttempts");
      stageCounts.merge("ingestionRetries", 1, Integer::sum);
      try {
        vectorStore.add(batch);
        budget.recordSuccess();
      } catch (Exception second) {
        budget.recordFailure();
        stageCounts.merge("ingestionFailures", 1, Integer::sum);
        throw new IllegalStateException(
            "入库批次重试后仍失败（整体失败，进入清理）: " + second.getMessage(), second);
      }
    }

    private void acquireBudget(String attemptKey) {
      try {
        budget.tryAcquire();
      } catch (IllegalStateException exhausted) {
        // 未递增、未发出请求：预算耗尽不是普通失败，整轮指标会失真
        throw new IllegalStateException(
            "外层操作预算耗尽，评测中止（本次未发起，零 HTTP）: " + exhausted.getMessage(), exhausted);
      }
      stageCounts.merge(attemptKey, 1, Integer::sum);
    }

    // ───── Phase 2：检索（20 题，全部走 handler 状态契约）─────

    private void phase2Retrieve() {
      P1cRetrievalHandler handler = new P1cRetrievalHandler(
          budget, new PgSearch(), evalRunId, KB_ID, Set.copyOf(frozenChunkIds),
          TOP_K, FALLBACK_TOP_K);

      for (CandidateGoldEntry entry : candidateGold.entries()) {
        Outcome outcome = handler.retrieve(entry.question());

        if (outcome.status() == P1cRetrievalHandler.Status.BUDGET_EXCEEDED) {
          throw new IllegalStateException(
              "检索预算耗尽，评测中止（不产出被清零的指标）: queryId=" + entry.queryId()
              + ", " + outcome.fallbackError());
        }
        if (outcome.fallbackAttempted()) {
          fallbackQueries++;
        }
        fallbackRawCandidates += outcome.fallbackRawCandidateCount();
        if (outcome.failed()) {
          failedRetrievalQueries++;
          log.error("检索失败（该题按零计入宏平均，状态独立记为 RETRIEVAL_FAILED，"
              + "不与正常零命中合并）: queryId={}, mainError={}, fallbackError={}",
              entry.queryId(), outcome.mainError(), outcome.fallbackError());
        } else if (outcome.status() == P1cRetrievalHandler.Status.OK_ZERO_RESULT) {
          zeroResultQueries++;
        }

        List<P1cEvalReport.RetrievedDoc> docs = new ArrayList<>();
        List<String> rankedIds = new ArrayList<>();
        for (int i = 0; i < outcome.hits().size(); i++) {
          Hit hit = outcome.hits().get(i);
          rankedIds.add(hit.evalChunkId());
          docs.add(new P1cEvalReport.RetrievedDoc(
              i + 1, hit.evalChunkId(), hit.score(), hit.kbId(), hit.evalRunId()));
        }

        boolean isAnswerable = "ANSWERABLE".equals(entry.answerability());
        Map<Integer, PerKResult> perK = P1cMultiKMetrics.perQuery(
            extractAnswerPoints(entry), rankedIds, outcome.failed());

        P1cEvalReport.PerQueryResult row = new P1cEvalReport.PerQueryResult(
            entry.queryId(), entry.question(), entry.answerability(), isAnswerable,
            outcome.status().name(), outcome.fallbackAttempted(),
            outcome.mainError(), outcome.fallbackError(), docs.size(), List.copyOf(docs),
            P1cEvalReport.reportPerK(perK));

        if (isAnswerable) {
          answerableResults.add(row);
          if (!perK.isEmpty()) {
            answerablePerK.add(perK);
          }
        } else {
          // NO_ANSWER 只进诊断区：原始检索结果照常保存，不参与任何分母
          noAnswerDiagnostics.add(row);
        }
      }
      log.info("Phase 2 完成: {} 题（RETRIEVAL_FAILED {} / OK_ZERO_RESULT {} / 触发回退 {}）",
          candidateGold.entries().size(), failedRetrievalQueries, zeroResultQueries, fallbackQueries);
    }

    /** 真实检索函数：主检索带 kb_id 过滤，回退无过滤且候选 topK×3。 */
    private final class PgSearch implements P1cRetrievalHandler.SearchFunction {
      @Override
      public List<Hit> search(String query, boolean filtered, int topK) {
        stageCounts.merge(filtered ? "queryAttempts" : "fallbackAttempts", 1, Integer::sum);
        var builder = SearchRequest.builder()
            .query(query)
            .topK(topK)
            .similarityThresholdAll();
        if (filtered) {
          builder = builder.filterExpression(KB_FILTER);
        }
        List<Hit> hits = new ArrayList<>();
        for (Document doc : vectorStore.similaritySearch(builder.build())) {
          Map<String, Object> md = doc.getMetadata();
          Object kb = md.get("kb_id");
          hits.add(new Hit(
              asStringOrNull(md.get("eval_chunk_id")),
              doc.getScore(),
              asStringOrNull(md.get("eval_run_id")),
              asStringOrNull(kb),
              kb instanceof String));
        }
        return hits;
      }
    }

    // ───── Phase 3/4：多 K 指标聚合 ─────

    private void phase3Metrics() {
      Map<Integer, KAggregate> aggregated = P1cMultiKMetrics.aggregate(answerablePerK);
      for (KAggregate a : aggregated.values()) {
        if (a.totalAnswerPoints() != EXPECTED_ANSWER_POINTS) {
          throw new IllegalStateException("多 K 微平均分母应为 " + EXPECTED_ANSWER_POINTS
              + " 个答案要点: 实际 " + a.totalAnswerPoints());
        }
      }
      aggregatesByK = P1cEvalReport.reportAggregates(aggregated, answerablePerK.size());
      roundAvailability = P1cRoundAvailability.evaluate(EXPECTED_QUERY_COUNT,
          answerableResults.size() + noAnswerDiagnostics.size(), failedRetrievalQueries,
          zeroResultQueries, fallbackQueries);
      log.info("Phase 3/4 完成: 宏平均分母={} 道可答题, 微平均分母={} 个答案要点, 整轮可用性={}",
          answerablePerK.size(), EXPECTED_ANSWER_POINTS, roundAvailability.status());

      // 实验级判定：单题按零口径不能顺手变成"整轮成功"。
      // 这里抛出后 orchestrator 仍会先清理再写报告，报告里保留失败题记录与已算出的多 K 指标。
      if (!roundAvailability.usable()) {
        throw new IllegalStateException("P1-C 本轮不可用，指标不得作为正式基线: "
            + String.join("; ", roundAvailability.reasons()));
      }
    }

    // ───── Phase 5：由 orchestrator 调用（按 runId 清理并回读残留）─────

    private CleanupRowCounts cleanupByRunId() {
      if (jdbc == null || evalRunId == null) {
        throw new IllegalStateException("无法清理：数据源或 runId 未就绪");
      }
      int deleted = jdbc.update(
          "DELETE FROM vector_store WHERE metadata->>'eval_run_id' = ?", evalRunId);
      int remaining = jdbc.queryForObject(
          "SELECT COUNT(*) FROM vector_store WHERE metadata->>'eval_run_id' = ?",
          Integer.class, evalRunId);
      log.info("清理: 删除 {} 行, 残留 {} 行, runId={}", deleted, remaining, evalRunId);
      return new CleanupRowCounts(deleted, remaining);
    }
  }

  // ══════════════════════════════ 报告写出 ══════════════════════════════

  private void writeReport(RunState st, P1cEvalRunOrchestrator.RunResult result) throws Exception {
    DateTimeFormatter fmt = DateTimeFormatter.ISO_INSTANT;
    int operations = st.budget == null ? 0 : st.budget.getAttempts();
    int httpObserved = st.httpCounter == null ? 0 : st.httpCounter.get();

    P1cEvalReport.CleanupStatus cleanup = new P1cEvalReport.CleanupStatus(
        result.cleanup().status().name(), result.cleanup().attempted(),
        result.cleanup().deletedRows(), result.cleanup().remainingRows(),
        result.cleanup().error(),
        "清理先于报告写入执行；CLEAN_FAILED / CLEAN_PARTIAL 不可能被记为 CLEANED");

    P1cEvalReport.FailureInfo failure = result.failure() == null ? null
        : new P1cEvalReport.FailureInfo(result.failure().phase(),
            result.failure().exceptionClass(), result.failure().message(),
            "原始异常未被清理或报告写出的失败覆盖；后两者以 suppressed 挂在异常链上");

    Map<String, Integer> outer = new LinkedHashMap<>();
    if (st.budget != null) {
      outer.putAll(st.stageCounts);
      outer.put("totalAttempts", st.budget.getAttempts());
      outer.put("totalSuccesses", st.budget.getSuccesses());
      outer.put("totalFailures", st.budget.getFailures());
      outer.put("hardLimit", st.budget.getHardLimit());
    }

    P1cRoundAvailability availability = st.roundAvailability != null ? st.roundAvailability
        : P1cRoundAvailability.evaluate(EXPECTED_QUERY_COUNT,
            st.answerableResults.size() + st.noAnswerDiagnostics.size(),
            st.failedRetrievalQueries, st.zeroResultQueries, st.fallbackQueries);

    P1cEvalReport report = new P1cEvalReport(
        REPORT_VERSION, st.evalRunId,
        fmt.format(st.startTime.atOffset(ZoneOffset.UTC)),
        fmt.format(Instant.now().atOffset(ZoneOffset.UTC)),
        "real-embedding", "p1c-l1-vector-retrieval",
        "L1 向量检索组件基线，非完整系统基线：不含查询改写、动态 topK/阈值；"
        + "回退是评测自有的单次受预算约束回退，不声称与生产 fallback 等价。",
        st.connectionIdentity,
        new P1cEvalReport.EmbeddingConfig("dashscope", st.modelName, st.dimensions, st.baseUrl,
            "系统属性（密钥只从环境变量/系统属性读取，绝不写入报告）"),
        new P1cEvalReport.VectorStoreConfig("COSINE_DISTANCE", "HNSW",
            st.dbVectorDimension == null ? -1 : st.dbVectorDimension, false,
            "docker/postgres/eval-init.sql; indexDef=" + st.dbIndexDef),
        new P1cEvalReport.CallGuard(outer,
            new P1cEvalReport.HttpObservation(httpObserved,
                "OkHttp interceptor (SpringAiOpenAiHttpClient.Builder.interceptor)",
                "外层操作数=" + operations + "，HTTP 观测数=" + httpObserved
                + "；两者可能不等（发送前失败为 0 HTTP、SDK 内部可能拆分请求）"),
            "before-send",
            operations <= BUDGET_HARD_LIMIT ? "WITHIN_LIMIT" : "EXCEEDED",
            "预算门控的是外层 Embedding 操作次数，不是 HTTP 请求硬上限"),
        new P1cEvalReport.QueryCounts(EXPECTED_QUERY_COUNT,
            st.answerableResults.size() + st.noAnswerDiagnostics.size(),
            st.answerableResults.size(), st.noAnswerDiagnostics.size(),
            st.noAnswerDiagnostics.size(), st.answerablePerK.size(),
            st.failedRetrievalQueries, st.zeroResultQueries, st.fallbackQueries,
            st.fallbackRawCandidates),
        new P1cEvalReport.DataHashes(st.chunksSha256, st.candidateGoldSha256,
            st.corpusManifestSha256, st.chunkManifestSha256, "SHA-256",
            "换行归一化(CRLF/CR→LF) → UTF-8 字节 → SHA-256 小写 hex；"
            + "写入侧取自 chunks.jsonl 解码文本，核对侧取自 vector_store.content 列，同一函数",
            "整文件原始字节 SHA-256（随 checkout 行尾策略而变，仅作参考记录；"
            + "批准值比对见 artifactFreeze，那里用行尾归一化哈希）"),
        st.artifactFreeze,
        st.ingestion,
        new P1cEvalReport.EvalConfig(TOP_K, 0.0, false, KB_ID,
            P1cMultiKMetrics.DEFAULT_KS, FALLBACK_TOP_K,
            "主检索异常时每题至多 1 次回退：无过滤 topK=30 → 对原始候选逐条归属判定。"
            + "只有本次 runId 且 chunkId 落在冻结 28 集合、kb_id 值与存储类型都合规的候选才计入；"
            + "其余候选（其他 runId、缺失 runId、本次 runId 但 ID/kb_id 越界、null 候选）一律中止整轮，"
            + "不做静默过滤。回退候选集为空仍判 RETRIEVAL_FAILED"),
        availability,
        st.aggregatesByK,
        List.copyOf(st.answerableResults),
        List.copyOf(st.noAnswerDiagnostics),
        cleanup,
        failure);

    Path path = reportPath();
    Files.createDirectories(path.getParent());
    ObjectMapper mapper = JsonMapper.builder().build();
    Files.writeString(path,
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
    log.info("报告已写入: {}", path.toAbsolutePath());
  }

  private static Path reportPath() {
    return Path.of("build/eval/p1c-l1-report.json");
  }

  // ══════════════════════════════ 可离线测试的静态入口 ══════════════════════════════

  /** 从 Gradle 系统属性 {@code eval.datasetDir} 读取绝对路径；未传入则失败。 */
  static Path resolveDatasetDir() {
    String dir = System.getProperty("eval.datasetDir");
    if (dir == null || dir.isBlank()) {
      throw new IllegalStateException(
          "必须通过系统属性 eval.datasetDir 指定 P1-B 数据集绝对路径（Gradle 自动注入）");
    }
    Path p = Path.of(dir);
    if (!Files.isDirectory(p)) {
      throw new IllegalStateException("数据集目录不存在: " + p.toAbsolutePath());
    }
    return p;
  }

  /**
   * 解析凭据：系统属性（非空）优先，回退到环境变量；两者都缺失则抛异常。
   * 严禁在异常消息或日志中打印真实值。
   */
  static String requireCredential(String sysPropKey, String envKey, String humanName) {
    String fromSysProp = System.getProperty(sysPropKey);
    if (fromSysProp != null && !fromSysProp.isBlank()) {
      return fromSysProp;
    }
    String fromEnv = System.getenv(envKey);
    if (fromEnv != null && !fromEnv.isBlank()) {
      return fromEnv;
    }
    throw new IllegalStateException(
        humanName + " 未提供：请通过环境变量 " + envKey + "（推荐）或系统属性 -P"
        + sysPropKey + "=... 传入；拒绝使用默认值");
  }

  static List<ChunkRecord> readChunks(Path path) throws IOException {
    List<ChunkRecord> chunks = new ArrayList<>();
    ObjectMapper mapper = JsonMapper.builder().build();
    try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        Map<String, Object> map = mapper.readValue(line, Map.class);
        chunks.add(new ChunkRecord(
            (String) map.get("chunkId"),
            (String) map.get("docId"),
            ((Number) map.get("seq")).intValue(),
            (String) map.get("text")));
      }
    }
    return chunks;
  }

  static CandidateGold readCandidateGold(Path path) throws IOException {
    ObjectMapper mapper = JsonMapper.builder().build();
    Map<String, Object> root = mapper.readValue(path.toFile(), Map.class);
    List<Map<String, Object>> entries = (List<Map<String, Object>>) root.get("entries");
    List<CandidateGoldEntry> result = new ArrayList<>();
    for (Map<String, Object> entry : entries) {
      result.add(new CandidateGoldEntry(
          (String) entry.get("queryId"),
          (String) entry.get("question"),
          (String) entry.get("answerability"),
          entry.get("candidateGold")));
    }
    return new CandidateGold(result);
  }

  static List<P1cAnswerPointMetrics.AnswerPoint> extractAnswerPoints(CandidateGoldEntry entry) {
    List<P1cAnswerPointMetrics.AnswerPoint> points = new ArrayList<>();
    Map<String, Object> gold = (Map<String, Object>) entry.candidateGold();
    if (gold == null) {
      return points;
    }
    List<Map<String, Object>> answerPoints = (List<Map<String, Object>>) gold.get("answerPoints");
    if (answerPoints == null) {
      return points;
    }
    for (Map<String, Object> point : answerPoints) {
      int index = ((Number) point.get("answerPointIndex")).intValue();
      points.add(new P1cAnswerPointMetrics.AnswerPoint(
          index, (List<String>) point.get("supportingChunkIds")));
    }
    return points;
  }

  static String sha256File(Path path) throws IOException, NoSuchAlgorithmException {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
  }

  static <T> List<List<T>> partition(List<T> list, int size) {
    List<List<T>> partitions = new ArrayList<>();
    for (int i = 0; i < list.size(); i += size) {
      partitions.add(list.subList(i, Math.min(i + size, list.size())));
    }
    return partitions;
  }

  // ══════════════════════════════ 身份核验与客户端装配 ══════════════════════════════

  private void verifyIdentity(JdbcTemplate jdbc, String configuredUrl) {
    String currentUser = jdbc.queryForObject("SELECT current_user", String.class);
    if (!"eval_runner".equals(currentUser)) {
      throw new IllegalStateException(
          "评测数据库用户不匹配: 期望 eval_runner, 实际 " + currentUser);
    }

    String markerType;
    String markerUuid;
    try {
      markerType = jdbc.queryForObject(
          "SELECT marker_value FROM eval_instance_identity WHERE marker_key = 'instance_type'",
          String.class);
      markerUuid = jdbc.queryForObject(
          "SELECT marker_value FROM eval_instance_identity WHERE marker_key = 'instance_uuid'",
          String.class);
    } catch (Exception e) {
      throw new IllegalStateException("评测实例标记表查询失败: " + e.getMessage()
          + "。请确认连接的是评测容器（docker compose -f docker-compose-eval.yml ps）。", e);
    }
    if (!EXPECTED_MARKER_TYPE.equals(markerType)) {
      throw new IllegalStateException(
          "评测实例类型标记不匹配: 期望 " + EXPECTED_MARKER_TYPE + ", 实际 " + markerType);
    }
    if (!EXPECTED_MARKER_UUID.equals(markerUuid)) {
      throw new IllegalStateException(
          "评测实例 UUID 标记不匹配: 期望 " + EXPECTED_MARKER_UUID + ", 实际 " + markerUuid);
    }

    if (Boolean.TRUE.equals(jdbc.queryForObject(
        "SELECT rolsuper FROM pg_roles WHERE rolname = 'eval_runner'", Boolean.class))) {
      throw new IllegalStateException("eval_runner 不应具有超级用户权限");
    }

    Integer existingRows = jdbc.queryForObject("SELECT COUNT(*) FROM vector_store", Integer.class);
    if (existingRows != null && existingRows > 0) {
      throw new IllegalStateException("评测 vector_store 表非空（" + existingRows + " 行），拒绝写入。"
          + "请先执行: docker compose -f docker-compose-eval.yml down -v 然后重新 up -d");
    }
    log.info("身份核验通过: configuredUrl={}, user={}, markerUuid={}",
        configuredUrl, currentUser, markerUuid);
  }

  private P1cEvalReport.ConnectionIdentity buildConnectionIdentity(
      JdbcTemplate jdbc, String configuredUrl) {
    String dbName = jdbc.queryForObject("SELECT current_database()", String.class);
    Integer pgPort = jdbc.queryForObject(
        "SELECT setting::int FROM pg_settings WHERE name = 'port'", Integer.class);
    String currentUser = jdbc.queryForObject("SELECT current_user", String.class);
    boolean isSuperuser = Boolean.TRUE.equals(jdbc.queryForObject(
        "SELECT rolsuper FROM pg_roles WHERE rolname = 'eval_runner'", Boolean.class));
    String markerType = jdbc.queryForObject(
        "SELECT marker_value FROM eval_instance_identity WHERE marker_key = 'instance_type'",
        String.class);
    String markerUuid = jdbc.queryForObject(
        "SELECT marker_value FROM eval_instance_identity WHERE marker_key = 'instance_uuid'",
        String.class);

    return new P1cEvalReport.ConnectionIdentity(
        configuredUrl, dbName, pgPort == null ? -1 : pgPort, currentUser, isSuperuser,
        markerType, markerUuid, true,
        "runtime-current_user + eval_instance_identity + pg_settings + pg_roles",
        "configuredUrl 来自系统属性，其余来自数据库查询；固定 UUID 是仓库常量，"
        + "不能单独证明物理容器身份，运行前仍须人工比对 URL 与 Compose 目标。");
  }

  private EmbeddingModel buildEmbeddingModel(String apiKey, String baseUrl, String modelName,
      int dimensions, P1cEvalHttpCallCounter httpCounter) {
    Timeout timeout = Timeout.builder()
        .connect(Duration.ofMillis(10_000))
        .read(Duration.ofMillis(300_000))
        .build();

    var evalHttpClient = SpringAiOpenAiHttpClient.builder()
        .timeout(timeout)
        .interceptor(httpCounter.toInterceptor())
        .build();

    ClientOptions options = ClientOptions.Companion.builder()
        .apiKey(apiKey)
        .credential(BearerTokenCredential.create(apiKey))
        .baseUrl(ApiPathResolver.resolveVersionedBaseUrl(baseUrl))
        .timeout(timeout)
        .httpClient(evalHttpClient)
        .maxRetries(0)
        .build();

    OpenAIClient client = new OpenAIClientImpl(options);

    return OpenAiEmbeddingModel.builder()
        .openAiClient(client)
        .options(OpenAiEmbeddingOptions.builder()
            .model(modelName)
            .dimensions(dimensions)
            .build())
        .metadataMode(MetadataMode.EMBED)
        .build();
  }

  private static String asStringOrNull(Object v) {
    return v == null ? null : v.toString();
  }

  record ChunkRecord(String chunkId, String docId, int seq, String text) {
  }

  record CandidateGold(List<CandidateGoldEntry> entries) {
  }

  record CandidateGoldEntry(String queryId, String question, String answerability,
      Object candidateGold) {
  }
}
