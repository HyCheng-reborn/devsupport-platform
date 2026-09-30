package interview.guide.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P1-C L1 真实向量检索评测报告（v1.4 数据与失败契约）。
 *
 * <p>与 P1-A 的 {@code EvaluationReport}（fixture 模式）完全独立，不改其契约。
 *
 * <p>多 K 口径：每题只检索一次 topK=10，离线截断为 K∈{1,3,5,10}，
 * 每个 K 各自给出宏平均（macro，先算每题再平均，分母=可答题数）与
 * 微平均覆盖（micro，{@code coveredPointsAtK / totalAnswerPoints}，
 * 分母=全部可答题答案要点数，当前冻结集为 38）。两者名称与分母同时写出，不混用。
 *
 * <p>检索状态：{@code OK_WITH_HITS} / {@code OK_ZERO_RESULT} / {@code RETRIEVAL_FAILED}
 * 三者互斥，失败题的指标按零计入宏平均但状态独立记录，不与"正常零命中"合并。
 *
 * <p>两个"结论层"字段与指标本身分开写出，避免读宏平均的人把流程故障当成检索质量：
 * <ul>
 *   <li>{@code artifactFreeze}：四份 P1-B 工件的批准哈希与实测哈希逐项对照（只看数量抓不到
 *       等条数的内容漂移）。</li>
 *   <li>{@code roundAvailability}：本轮是否可作为正式基线。存在 {@code RETRIEVAL_FAILED}
 *       或题数未全部请求完成时为 {@code NOT_USABLE}，此时宏平均仍然照口径计算并保存，
 *       但它的含义是"请求故障下的按零累加"，不是检索质量。</li>
 * </ul>
 *
 * <p>报告由 {@link P1cEvalRunOrchestrator} 在清理之后写出，因此即使清理失败或实验主体抛异常，
 * {@code cleanupStatus} / {@code failureInfo} 也已定值；{@code reportWritten} 本身不写进 JSON
 * （写出这一动作不可能由被写出的内容自证），由编排返回值与 {@code primaryError()} 携带。
 */
public record P1cEvalReport(
    String reportVersion,
    String evalRunId,
    String startTime,
    String endTime,
    String executionMode,
    String scope,
    String disclaimer,

    ConnectionIdentity connectionIdentity,
    EmbeddingConfig embedding,
    VectorStoreConfig vectorStore,
    CallGuard callGuard,
    QueryCounts queryCounts,
    DataHashes dataHashes,
    P1cFrozenArtifactVerifier.Verification artifactFreeze,
    IngestionVerification ingestionVerification,

    EvalConfig evalConfig,
    P1cRoundAvailability roundAvailability,
    Map<String, KAggregateReport> macroMetricsByK,
    List<PerQueryResult> answerableResults,
    List<PerQueryResult> noAnswerDiagnostics,

    CleanupStatus cleanupStatus,
    FailureInfo failureInfo) {

  public record ConnectionIdentity(
      String configuredUrl,
      String database,
      int internalPort,
      String user,
      boolean superuser,
      String markerType,
      String markerUuid,
      boolean markerVerified,
      String source,
      String note) {
  }

  public record EmbeddingConfig(
      String providerId,
      String modelName,
      int configuredDimensions,
      String baseUrl,
      String source) {
  }

  public record VectorStoreConfig(
      String distanceType,
      String indexType,
      int vectorDimension,
      boolean initializeSchema,
      String schemaCreatedBy) {
  }

  public record EvalConfig(
      int topK,
      double minScore,
      boolean queryRewrite,
      String evalKbId,
      List<Integer> reportingKValues,
      int fallbackTopK,
      String fallbackNote) {
  }

  /**
   * 外层操作预算 + HTTP 观测。
   *
   * <p>{@code outerOperations} 的键按阶段拆分（入库/查询/回退），
   * 回退次数必须显式出现在报告里；{@code httpObservation} 是独立计数，两者可能不等。
   */
  public record CallGuard(
      Map<String, Integer> outerOperations,
      HttpObservation httpObservation,
      String budgetGatePosition,
      String status,
      String note) {
  }

  public record HttpObservation(
      int observedHttpRequests,
      String source,
      String note) {
  }

  public record QueryCounts(
      int totalQueries,
      int retrievedQueries,
      int answerableMetricQueries,
      int noAnswerDiagnosticQueries,
      int metricsExcludedNoAnswerQueries,
      int metricsDenominator,
      int failedRetrievalQueries,
      int zeroResultQueries,
      int queriesWithFallbackAttempted,
      int fallbackRawCandidatesObserved) {
  }

  public record DataHashes(
      String chunksJsonlSha256,
      String candidateGoldSha256,
      String corpusManifestSha256,
      String chunkManifestSha256,
      String contentHashAlgorithm,
      String contentHashByteContract,
      String source) {
  }

  /** 严格入库核对结果：{@code violations} 为空即 PASS，非空时逐条列出全部违例。 */
  public record IngestionVerification(
      int expectedChunks,
      int totalTableRows,
      int actualRunIdRows,
      int uniqueEvalChunkIds,
      int duplicateEvalChunkIds,
      Integer firstEmbeddingDimension,
      List<String> violations,
      String status) {
  }

  /** 单 K 聚合：宏平均四项 + 微平均覆盖（分子/分母都写出）。 */
  public record KAggregateReport(
      int k,
      int participatingQueries,
      double macroHitAtK,
      double macroMrrAtK,
      double macroApcAtK,
      double macroFullCoverageAtK,
      int coveredPointsAtK,
      int totalAnswerPoints,
      double microCoverageAtK,
      String microDenominatorNote) {
  }

  /** 单题各 K 指标。 */
  public record PerKMetrics(
      double hitAtK,
      double mrrAtK,
      double apcAtK,
      boolean fullCoverageAtK,
      int coveredPoints,
      int totalPoints) {
  }

  /**
   * 单题结果。NO_ANSWER 题只出现在 {@code noAnswerDiagnostics}：
   * 原始检索结果照常保存，{@code metricsByK} 为空、不计入任何分母。
   */
  public record PerQueryResult(
      String queryId,
      String question,
      String answerability,
      boolean includedInMacroAverage,
      String retrievalStatus,
      boolean fallbackAttempted,
      String mainError,
      String fallbackError,
      int retrievedCount,
      List<RetrievedDoc> retrievedDocs,
      Map<String, PerKMetrics> metricsByK) {
  }

  public record RetrievedDoc(
      int rank,
      String evalChunkId,
      Double score,
      String kbId,
      String evalRunId) {
  }

  public record CleanupStatus(
      String status,
      boolean attempted,
      int deletedRows,
      int remainingRows,
      String error,
      String note) {
  }

  public record FailureInfo(
      String failedPhase,
      String exceptionClass,
      String message,
      String note) {
  }

  /** 报告键统一用 {@code k=N}，与 K 值一一对应，避免 Map 键被当成数组下标。 */
  public static String kKey(int k) {
    return "k=" + k;
  }

  public static Map<String, PerKMetrics> reportPerK(Map<Integer, P1cMultiKMetrics.PerKResult> perK) {
    Map<String, PerKMetrics> out = new LinkedHashMap<>();
    perK.forEach((k, r) -> out.put(kKey(k), new PerKMetrics(
        r.hitAtK(), r.mrrAtK(), r.apcAtK(), r.fullCoverageAtK(),
        r.coveredPoints(), r.totalPoints())));
    return out;
  }

  public static Map<String, KAggregateReport> reportAggregates(
      Map<Integer, P1cMultiKMetrics.KAggregate> aggregates, int participatingQueries) {
    Map<String, KAggregateReport> out = new LinkedHashMap<>();
    aggregates.forEach((k, a) -> out.put(kKey(k), new KAggregateReport(
        a.k(), participatingQueries, a.macroHitAtK(), a.macroMrrAtK(),
        a.macroApcAtK(), a.macroFullCoverageAtK(),
        a.coveredPointsAtK(), a.totalAnswerPoints(), a.microCoverageAtK(),
        "macro*=宏平均（分母=" + participatingQueries + " 道可答题）；"
        + "micro=coveredPointsAtK/" + a.totalAnswerPoints() + "（全部可答题答案要点总数，跨题累加）")));
    return out;
  }
}
