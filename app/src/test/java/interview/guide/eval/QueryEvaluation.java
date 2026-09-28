package interview.guide.eval;

/**
 * 单题评测结果。
 *
 * <p>被排除的题（{@link Answerability#NO_ANSWER} / {@link Answerability#UNANNOTATED}）
 * 指标字段为 {@code null}，且 {@code includedInMacroAverage=false}，不参与宏平均。
 *
 * @param reciprocalRank 前 K 条中第一条相关片段排名的倒数（MRR@K 的单题分量），无命中为 0
 */
public record QueryEvaluation(
    String queryId,
    Answerability answerability,
    boolean includedInMacroAverage,
    Double hitAtK,
    Double recallAtK,
    Double reciprocalRank,
    int goldRelevantCount,
    int retrievedCount,
    int evaluatedTopK) {
}
