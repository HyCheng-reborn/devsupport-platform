package interview.guide.eval;

import java.util.List;

/**
 * 一条评测样本：查询标注 + 其按排名排列的固定检索结果。
 *
 * <p>{@code rankedResults} 允许为空或少于 K 条；顺序即排名。
 */
public record QueryJudgement(EvalQuery query, List<RetrievalHit> rankedResults) {
}
