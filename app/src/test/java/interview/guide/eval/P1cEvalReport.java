package interview.guide.eval;

import java.util.List;
import java.util.Map;

/**
 * P1-C L1 真实向量检索评测报告。
 *
 * <p>与 P1-A 的 {@link EvaluationReport}（fixture 模式）不同，本报告来自真实 Embedding + 检索。
 */
public record P1cEvalReport(
    String reportVersion,
    String evalRunId,
    String startTime,
    String endTime,
    String executionMode,
    String scope,

    ConnectionIdentity connectionIdentity,
    EmbeddingConfig embedding,
    VectorStoreConfig vectorStore,
    CallGuard callGuard,
    QueryCounts queryCounts,
    DataHashes dataHashes,

    MacroMetrics macroMetrics,
    int totalAnswerPoints,
    int coveredAnswerPoints,

    List<PerQueryResult> perQuery,
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

  public record CallGuard(
      Map<String, Integer> outerOperations,
      HttpObservation httpObservation,
      String budgetGatePosition,
      String status) {
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
      int failedRetrievalQueries) {
  }

  public record DataHashes(
      String chunksJsonlSha256,
      String candidateGoldSha256,
      String corpusManifestSha256,
      String chunkManifestSha256,
      String source) {
  }

  public record MacroMetrics(
      Double macroHitAtK,
      Double macroMrrAtK,
      Double macroApcAtK,
      Double macroFullCoverageAtK) {
  }

  public record PerQueryResult(
      String queryId,
      String question,
      String answerability,
      boolean includedInMacroAverage,
      int topK,
      List<RetrievedDoc> retrievedDocs,
      Double hitAtK,
      Double mrrAtK,
      Integer totalPoints,
      Integer coveredPoints,
      Double apcAtK,
      Boolean fullCoverage) {
  }

  public record RetrievedDoc(
      int rank,
      String chunkId,
      String evalChunkId,
      Double score,
      String kbId) {
  }

  public record CleanupStatus(
      boolean cleanupAttempted,
      int deletedRows,
      int remainingRows,
      String note) {
  }

  public record FailureInfo(
      String phase,
      String message,
      String exceptionClass) {
  }
}
