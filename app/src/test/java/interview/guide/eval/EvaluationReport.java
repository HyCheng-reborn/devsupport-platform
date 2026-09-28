package interview.guide.eval;

import java.util.List;

/**
 * 离线检索评测报告（fixture 模式）。
 *
 * <p>{@code executionMode=fixture}、{@code scope=metric_validation}：本报告基于固定虚构夹具，
 * 仅用于验证指标计算逻辑的正确性，<b>不是真实 RAG 检索质量基线</b>。
 *
 * <p>宏平均字段（{@code macro*}）在无 ANSWERABLE 查询时为 {@code null}，不做除零、不回填满分。
 */
public record EvaluationReport(
    int k,
    String executionMode,
    String scope,
    String disclaimer,
    Double macroHitAtK,
    Double macroRecallAtK,
    Double macroMrrAtK,
    int totalQueries,
    int evaluatedQueries,
    int noAnswerQueries,
    int unannotatedQueries,
    int excludedQueries,
    List<QueryEvaluation> perQuery) {
}
