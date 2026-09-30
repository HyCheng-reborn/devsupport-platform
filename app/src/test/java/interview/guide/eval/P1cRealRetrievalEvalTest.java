package interview.guide.eval;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.Timeout;
import com.openai.credential.BearerTokenCredential;
import interview.guide.common.ai.ApiPathResolver;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * P1-C L1 真实向量检索评测。
 *
 * <p>前置条件：
 * <ul>
 *   <li>评测 Postgres 容器已启动（{@code docker compose -f docker-compose-eval.yml up -d}）</li>
 *   <li>系统属性 {@code eval.p1c.realApi=true}（严格布尔，缺失/空串/其他值一律禁用）</li>
 *   <li>Embedding API Key 已通过 {@code eval.embedding.apiKey} 或环境变量 {@code AI_BAILIAN_API_KEY} 传入</li>
 * </ul>
 *
 * <p>运行命令：
 * <pre>
 * ./gradlew :app:evalP1cReal -Peval.p1c.realApi=true -Peval.embedding.apiKey=xxx
 * </pre>
 */
@Tag("real-eval")
public class P1cRealRetrievalEvalTest {

  private static final Logger log = LoggerFactory.getLogger(P1cRealRetrievalEvalTest.class);

  private static final int TOP_K = 10;
  private static final int BUDGET_HARD_LIMIT = 50;
  private static final int EMBEDDING_BATCH_SIZE = 10;
  private static final String EXPECTED_MARKER_UUID = "f47ac10b-58cc-4372-a567-0e0283c5d9e7";
  private static final String EXPECTED_MARKER_TYPE = "p1c-eval-isolated";
  private static final String EVAL_RUN_ID = UUID.randomUUID().toString();

  private static final Path DATASET_DIR = Path.of("eval/datasets/devsupport-v0.1");

  @Test
  void realRetrievalEval() throws Exception {
    // ═══ 付费开关 ═══
    String raw = System.getProperty("eval.p1c.realApi");
    assumeTrue("true".equals(raw),
        "P1-C 真实评测已禁用：需设置 -Deval.p1c.realApi=true");

    Instant startTime = Instant.now();
    P1cEvalCallBudget budget = new P1cEvalCallBudget(BUDGET_HARD_LIMIT);
    P1cEvalHttpCallCounter httpCounter = new P1cEvalHttpCallCounter();

    // ═══ Phase 0: 环境校验 ═══
    log.info("Phase 0: 环境校验开始");

    // 0.1 连接评测数据库
    DriverManagerDataSource evalDs = new DriverManagerDataSource();
    String evalUrl = System.getProperty("eval.datasource.url",
        "jdbc:postgresql://localhost:5433/interview_guide_eval");
    String evalUser = System.getProperty("eval.datasource.username", "eval_runner");
    String evalPassword = System.getProperty("eval.datasource.password", "eval_runner_2026");
    evalDs.setUrl(evalUrl);
    evalDs.setUsername(evalUser);
    evalDs.setPassword(evalPassword);
    JdbcTemplate evalJdbc = new JdbcTemplate(evalDs);

    // 0.2 身份核验（4 层）
    verifyIdentity(evalJdbc, evalUrl);

    // 0.3 读取 P1-B 工件并计算 SHA-256
    Path chunksPath = DATASET_DIR.resolve("chunks.jsonl");
    Path candidateGoldPath = DATASET_DIR.resolve("candidate-gold.json");
    Path corpusManifestPath = DATASET_DIR.resolve("corpus-manifest.json");
    Path chunkManifestPath = DATASET_DIR.resolve("chunk-manifest.json");

    String chunksSha256 = sha256File(chunksPath);
    String candidateGoldSha256 = sha256File(candidateGoldPath);
    String corpusManifestSha256 = sha256File(corpusManifestPath);
    String chunkManifestSha256 = sha256File(chunkManifestPath);

    log.info("P1-B 工件 SHA-256: chunks={}, candidateGold={}, corpusManifest={}, chunkManifest={}",
        chunksSha256, candidateGoldSha256, corpusManifestSha256, chunkManifestSha256);

    // 0.4 读取 chunks.jsonl
    List<ChunkRecord> chunks = readChunks(chunksPath);
    if (chunks.size() != 28) {
      throw new IllegalStateException("chunks.jsonl 行数不为 28: " + chunks.size());
    }

    // 0.5 读取 candidate-gold.json
    CandidateGold candidateGold = readCandidateGold(candidateGoldPath);
    if (candidateGold.entries().size() != 20) {
      throw new IllegalStateException("candidate-gold.json entries 不为 20: "
          + candidateGold.entries().size());
    }

    // 0.6 构建 EmbeddingModel
    String apiKey = System.getProperty("eval.embedding.apiKey",
        System.getenv().getOrDefault("AI_BAILIAN_API_KEY", ""));
    if (apiKey.isBlank()) {
      throw new IllegalStateException("Embedding API Key 未配置");
    }
    String baseUrl = System.getProperty("eval.embedding.baseUrl",
        "https://dashscope.aliyuncs.com/compatible-mode/v1");
    String modelName = System.getProperty("eval.embedding.model", "text-embedding-v3");
    int dimensions = Integer.parseInt(
        System.getProperty("eval.embedding.dimensions", "1024"));

    EmbeddingModel embeddingModel = buildEmbeddingModel(
        apiKey, baseUrl, modelName, dimensions, httpCounter);

    // 0.7 构建 PgVectorStore（initializeSchema=false）
    PgVectorStore vectorStore = PgVectorStore.builder(evalJdbc, embeddingModel)
        .dimensions(dimensions)
        .distanceType(PgDistanceType.COSINE_DISTANCE)
        .indexType(PgIndexType.HNSW)
        .initializeSchema(false)
        .build();

    // 0.8 读取数据库维度并校验
    Integer vectorDim = evalJdbc.queryForObject(
        "SELECT atttypmod FROM pg_attribute "
        + "JOIN pg_class ON attrelid = oid "
        + "WHERE relname = 'vector_store' AND attname = 'embedding'",
        Integer.class);
    if (vectorDim == null || vectorDim != dimensions) {
      throw new IllegalStateException(
          "向量维度不一致: 数据库=" + vectorDim + ", 配置=" + dimensions);
    }

    log.info("Phase 0: 环境校验完成");

    // ═══ Phase 1: 入库 ═══
    log.info("Phase 1: 入库开始");
    try {
      List<Document> documents = new ArrayList<>();
      for (ChunkRecord chunk : chunks) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("eval_chunk_id", chunk.chunkId());
        metadata.put("eval_run_id", EVAL_RUN_ID);
        metadata.put("doc_id", chunk.docId());
        metadata.put("chunk_index", chunk.seq());
        documents.add(new Document(chunk.text(), metadata));
      }

      // 分批入库
      List<List<Document>> batches = partition(documents, EMBEDDING_BATCH_SIZE);
      for (List<Document> batch : batches) {
        budget.tryAcquire();
        try {
          vectorStore.add(batch);
          budget.recordSuccess();
        } catch (Exception e) {
          budget.recordFailure();
          throw e;
        }
      }

      // 入库后核对
      verifyIngestion(evalJdbc, chunks);

      log.info("Phase 1: 入库完成，{} 个 chunk", chunks.size());
    } catch (Exception e) {
      log.error("Phase 1 失败: {}", e.getMessage());
      cleanup(evalJdbc);
      throw e;
    }

    // ═══ Phase 2: 检索 ═══
    log.info("Phase 2: 检索开始");
    List<P1cEvalReport.PerQueryResult> perQueryResults = new ArrayList<>();
    int answerableCount = 0;
    int noAnswerCount = 0;
    double sumHit = 0.0;
    double sumMrr = 0.0;
    double sumApc = 0.0;
    double sumFc = 0.0;
    int totalAnswerPoints = 0;
    int coveredAnswerPoints = 0;

    try {
      for (CandidateGoldEntry entry : candidateGold.entries()) {
        boolean isAnswerable = "ANSWERABLE".equals(entry.answerability());

        budget.tryAcquire();
        List<Document> results;
        try {
          results = vectorStore.similaritySearch(
              SearchRequest.builder()
                  .query(entry.question())
                  .topK(TOP_K)
                  .build());
          budget.recordSuccess();
        } catch (Exception e) {
          budget.recordFailure();
          log.error("检索失败: queryId={}, error={}", entry.queryId(), e.getMessage());
          results = List.of();
        }

        // 构建检索结果列表
        List<P1cEvalReport.RetrievedDoc> retrievedDocs = new ArrayList<>();
        List<String> topKChunkIds = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
          Document doc = results.get(i);
          String evalChunkId = (String) doc.getMetadata().get("eval_chunk_id");
          retrievedDocs.add(new P1cEvalReport.RetrievedDoc(
              i + 1, doc.getId(), evalChunkId, doc.getScore(), null));
          topKChunkIds.add(evalChunkId);
        }

        // 计算指标
        Double hitAtK = null;
        Double mrrAtK = null;
        Integer totalPoints = null;
        Integer coveredPts = null;
        Double apcAtK = null;
        Boolean fullCoverage = null;

        if (isAnswerable) {
          answerableCount++;

          // Hit@K and MRR@K
          List<String> goldChunkIds = extractGoldChunkIds(entry);
          hitAtK = 0.0;
          mrrAtK = 0.0;
          for (int i = 0; i < topKChunkIds.size(); i++) {
            if (goldChunkIds.contains(topKChunkIds.get(i))) {
              hitAtK = 1.0;
              mrrAtK = 1.0 / (i + 1);
              break;
            }
          }

          // APC@K and FC@K
          List<P1cAnswerPointMetrics.AnswerPoint> answerPoints = extractAnswerPoints(entry);
          P1cAnswerPointMetrics.PointCoverageResult pvr =
              P1cAnswerPointMetrics.compute(answerPoints, topKChunkIds);
          totalPoints = pvr.totalPoints();
          coveredPts = pvr.coveredPoints();
          apcAtK = pvr.apc();
          fullCoverage = pvr.fullCoverage();

          sumHit += hitAtK;
          sumMrr += mrrAtK;
          sumApc += apcAtK;
          sumFc += fullCoverage ? 1.0 : 0.0;
          totalAnswerPoints += totalPoints;
          coveredAnswerPoints += coveredPts;
        } else {
          noAnswerCount++;
        }

        perQueryResults.add(new P1cEvalReport.PerQueryResult(
            entry.queryId(), entry.question(), entry.answerability(),
            isAnswerable, TOP_K, retrievedDocs,
            hitAtK, mrrAtK, totalPoints, coveredPts, apcAtK, fullCoverage));
      }

      log.info("Phase 2: 检索完成");
    } catch (Exception e) {
      log.error("Phase 2 失败: {}", e.getMessage());
      cleanup(evalJdbc);
      throw e;
    }

    // ═══ Phase 3: 计算宏平均 ═══
    Double macroHit = answerableCount == 0 ? null : sumHit / answerableCount;
    Double macroMrr = answerableCount == 0 ? null : sumMrr / answerableCount;
    Double macroApc = answerableCount == 0 ? null : sumApc / answerableCount;
    Double macroFc = answerableCount == 0 ? null : sumFc / answerableCount;

    // ═══ Phase 4: 生成报告 ═══
    Instant endTime = Instant.now();
    DateTimeFormatter fmt = DateTimeFormatter.ISO_INSTANT;

    P1cEvalReport report = new P1cEvalReport(
        "p1c-l1-v1.4",
        EVAL_RUN_ID,
        fmt.format(startTime.atOffset(ZoneOffset.UTC)),
        fmt.format(endTime.atOffset(ZoneOffset.UTC)),
        "real-embedding",
        "p1c-l1-vector-retrieval",
        buildConnectionIdentity(evalJdbc, evalUrl),
        new P1cEvalReport.EmbeddingConfig(
            "dashscope", modelName, dimensions, baseUrl, "eval-system-property"),
        new P1cEvalReport.VectorStoreConfig(
            "COSINE_DISTANCE", "HNSW", vectorDim, false, "docker/postgres/eval-init.sql"),
        new P1cEvalReport.CallGuard(
            budget.snapshot(),
            new P1cEvalReport.HttpObservation(
                httpCounter.get(),
                "OkHttp interceptor (SpringAiOpenAiHttpClient.Builder.interceptor)",
                "外层操作数与 HTTP 请求数可能不等（发送前失败、SDK 内部拆分等）。"
                + "两者不等时此字段记录差异。"),
            "before-send",
            budget.getAttempts() <= BUDGET_HARD_LIMIT ? "WITHIN_LIMIT" : "EXCEEDED"),
        new P1cEvalReport.QueryCounts(
            20, 20, answerableCount, noAnswerCount, noAnswerCount, answerableCount, 0),
        new P1cEvalReport.DataHashes(
            chunksSha256, candidateGoldSha256, corpusManifestSha256, chunkManifestSha256,
            "sha256-of-p1b-frozen-artifacts"),
        new P1cEvalReport.MacroMetrics(macroHit, macroMrr, macroApc, macroFc),
        totalAnswerPoints,
        coveredAnswerPoints,
        perQueryResults,
        null,
        null);

    // 写入报告
    Path reportPath = Path.of("build/eval/p1c-l1-report.json");
    Files.createDirectories(reportPath.getParent());
    ObjectMapper mapper = JsonMapper.builder().build();
    String reportJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
    Files.writeString(reportPath, reportJson, StandardCharsets.UTF_8);
    log.info("报告已写入: {}", reportPath.toAbsolutePath());

    // ═══ Phase 5: 清理 ═══
    cleanup(evalJdbc);
  }

  private void verifyIdentity(JdbcTemplate jdbc, String configuredUrl) {
    // Layer 1: current_user
    String currentUser = jdbc.queryForObject("SELECT current_user", String.class);
    if (!"eval_runner".equals(currentUser)) {
      throw new IllegalStateException(
          "评测数据库用户不匹配: 期望 eval_runner, 实际 " + currentUser);
    }

    // Layer 2: marker table
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
      throw new IllegalStateException(
          "评测实例标记表查询失败: " + e.getMessage()
          + "。请确认连接的是评测容器（核对 docker compose -f docker-compose-eval.yml ps）。", e);
    }

    if (!EXPECTED_MARKER_TYPE.equals(markerType)) {
      throw new IllegalStateException(
          "评测实例类型标记不匹配: 期望 " + EXPECTED_MARKER_TYPE + ", 实际 " + markerType);
    }
    if (!EXPECTED_MARKER_UUID.equals(markerUuid)) {
      throw new IllegalStateException(
          "评测实例 UUID 标记不匹配: 期望 " + EXPECTED_MARKER_UUID + ", 实际 " + markerUuid);
    }

    // Layer 3: auxiliary info
    String dbName = jdbc.queryForObject("SELECT current_database()", String.class);
    Integer pgPort = jdbc.queryForObject(
        "SELECT setting::int FROM pg_settings WHERE name = 'port'", Integer.class);
    boolean isSuperuser = jdbc.queryForObject(
        "SELECT rolsuper FROM pg_roles WHERE rolname = 'eval_runner'", Boolean.class);

    if (isSuperuser) {
      throw new IllegalStateException("eval_runner 不应具有超级用户权限");
    }

    log.info("身份核验通过: db={}, port={}, user={}, markerUuid={}",
        dbName, pgPort, currentUser, markerUuid);

    // Layer 4: vector_store empty
    int existingRows = jdbc.queryForObject(
        "SELECT COUNT(*) FROM vector_store", Integer.class);
    if (existingRows > 0) {
      throw new IllegalStateException(
          "评测 vector_store 表非空（" + existingRows + " 行），拒绝写入。"
          + "请先执行: docker compose -f docker-compose-eval.yml down -v 然后重新 up -d");
    }
  }

  private P1cEvalReport.ConnectionIdentity buildConnectionIdentity(
      JdbcTemplate jdbc, String configuredUrl) {
    String dbName = jdbc.queryForObject("SELECT current_database()", String.class);
    Integer pgPort = jdbc.queryForObject(
        "SELECT setting::int FROM pg_settings WHERE name = 'port'", Integer.class);
    String currentUser = jdbc.queryForObject("SELECT current_user", String.class);
    boolean isSuperuser = jdbc.queryForObject(
        "SELECT rolsuper FROM pg_roles WHERE rolname = 'eval_runner'", Boolean.class);
    String markerType = jdbc.queryForObject(
        "SELECT marker_value FROM eval_instance_identity WHERE marker_key = 'instance_type'",
        String.class);
    String markerUuid = jdbc.queryForObject(
        "SELECT marker_value FROM eval_instance_identity WHERE marker_key = 'instance_uuid'",
        String.class);

    return new P1cEvalReport.ConnectionIdentity(
        configuredUrl, dbName, pgPort, currentUser, isSuperuser,
        markerType, markerUuid, true,
        "runtime-current_user + eval_instance_identity + pg_settings + pg_roles",
        "configuredUrl 来自系统属性，其余来自数据库查询。两者分别记录，供人工比对。");
  }

  private void verifyIngestion(JdbcTemplate jdbc, List<ChunkRecord> chunks) {
    // 全表行数 = 28
    int totalRows = jdbc.queryForObject("SELECT COUNT(*) FROM vector_store", Integer.class);
    if (totalRows != 28) {
      throw new IllegalStateException("vector_store 全表行数不为 28: " + totalRows);
    }

    // 本次 runId 行数 = 28
    int runRows = jdbc.queryForObject(
        "SELECT COUNT(*) FROM vector_store WHERE metadata->>'eval_run_id' = ?",
        Integer.class, EVAL_RUN_ID);
    if (runRows != 28) {
      throw new IllegalStateException("本次 runId 行数不为 28: " + runRows);
    }

    // 唯一 eval_chunk_id 数量 = 28
    int uniqueIds = jdbc.queryForObject(
        "SELECT COUNT(DISTINCT metadata->>'eval_chunk_id') FROM vector_store "
        + "WHERE metadata->>'eval_run_id' = ?",
        Integer.class, EVAL_RUN_ID);
    if (uniqueIds != 28) {
      throw new IllegalStateException("唯一 eval_chunk_id 数量不为 28: " + uniqueIds);
    }

    // 无重复 eval_chunk_id
    List<Map<String, Object>> duplicates = jdbc.queryForList(
        "SELECT metadata->>'eval_chunk_id' AS chunk_id, COUNT(*) AS cnt "
        + "FROM vector_store WHERE metadata->>'eval_run_id' = ? "
        + "GROUP BY metadata->>'eval_chunk_id' HAVING COUNT(*) > 1",
        EVAL_RUN_ID);
    if (!duplicates.isEmpty()) {
      throw new IllegalStateException("存在重复 eval_chunk_id: " + duplicates);
    }

    log.info("入库核对通过: totalRows=28, runRows=28, uniqueIds=28, duplicates=0");
  }

  private void cleanup(JdbcTemplate jdbc) {
    try {
      int deleted = jdbc.update(
          "DELETE FROM vector_store WHERE metadata->>'eval_run_id' = ?", EVAL_RUN_ID);
      log.info("评测清理完成: 删除 {} 行, runId={}", deleted, EVAL_RUN_ID);

      int remaining = jdbc.queryForObject(
          "SELECT COUNT(*) FROM vector_store WHERE metadata->>'eval_run_id' = ?",
          Integer.class, EVAL_RUN_ID);
      if (remaining != 0) {
        log.error("清理后仍有残留: {} 行, runId={}", remaining, EVAL_RUN_ID);
      }
    } catch (Exception e) {
      log.error("评测清理失败, runId={}: {}", EVAL_RUN_ID, e.getMessage());
    }
  }

  private EmbeddingModel buildEmbeddingModel(String apiKey, String baseUrl, String modelName,
      int dimensions, P1cEvalHttpCallCounter httpCounter) {
    Timeout timeout = Timeout.builder()
        .connect(Duration.ofMillis(10_000))
        .read(Duration.ofMillis(300_000))
        .build();

    var evalHttpClient = org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient
        .builder()
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

    OpenAIClient openAiClient = new OpenAIClientImpl(options);

    return OpenAiEmbeddingModel.builder()
        .openAiClient(openAiClient)
        .options(OpenAiEmbeddingOptions.builder()
            .model(modelName)
            .dimensions(dimensions)
            .build())
        .metadataMode(MetadataMode.EMBED)
        .build();
  }

  private List<ChunkRecord> readChunks(Path path) throws IOException {
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

  private CandidateGold readCandidateGold(Path path) throws IOException {
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

  private List<String> extractGoldChunkIds(CandidateGoldEntry entry) {
    List<String> ids = new ArrayList<>();
    Map<String, Object> candidateGold = (Map<String, Object>) entry.candidateGold();
    if (candidateGold == null) {
      return ids;
    }
    List<Map<String, Object>> answerPoints =
        (List<Map<String, Object>>) candidateGold.get("answerPoints");
    if (answerPoints == null) {
      return ids;
    }
    for (Map<String, Object> point : answerPoints) {
      List<String> supportingChunkIds = (List<String>) point.get("supportingChunkIds");
      if (supportingChunkIds != null) {
        ids.addAll(supportingChunkIds);
      }
    }
    return ids.stream().distinct().toList();
  }

  private List<P1cAnswerPointMetrics.AnswerPoint> extractAnswerPoints(
      CandidateGoldEntry entry) {
    List<P1cAnswerPointMetrics.AnswerPoint> points = new ArrayList<>();
    Map<String, Object> candidateGold = (Map<String, Object>) entry.candidateGold();
    if (candidateGold == null) {
      return points;
    }
    List<Map<String, Object>> answerPoints =
        (List<Map<String, Object>>) candidateGold.get("answerPoints");
    if (answerPoints == null) {
      return points;
    }
    for (Map<String, Object> point : answerPoints) {
      int index = ((Number) point.get("answerPointIndex")).intValue();
      List<String> supportingChunkIds = (List<String>) point.get("supportingChunkIds");
      points.add(new P1cAnswerPointMetrics.AnswerPoint(index, supportingChunkIds));
    }
    return points;
  }

  private String sha256File(Path path) throws IOException, NoSuchAlgorithmException {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] bytes = Files.readAllBytes(path);
    byte[] hash = digest.digest(bytes);
    return HexFormat.of().formatHex(hash);
  }

  private <T> List<List<T>> partition(List<T> list, int size) {
    List<List<T>> partitions = new ArrayList<>();
    for (int i = 0; i < list.size(); i += size) {
      partitions.add(list.subList(i, Math.min(i + size, list.size())));
    }
    return partitions;
  }

  private record ChunkRecord(String chunkId, String docId, int seq, String text) {
  }

  private record CandidateGold(List<CandidateGoldEntry> entries) {
  }

  private record CandidateGoldEntry(String queryId, String question, String answerability,
      Object candidateGold) {
  }
}
