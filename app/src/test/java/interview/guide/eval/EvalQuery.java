package interview.guide.eval;

import java.util.List;

/**
 * 查询标注。
 *
 * <p>{@code relevantChunkIds} 是"相关片段 gold 标签"，按 chunkId 与检索结果匹配；
 * 它与来源知识库(kbId)是不同概念。{@code answerability} 决定该查询是否参与宏平均。
 */
public record EvalQuery(
    String queryId,
    String question,
    Answerability answerability,
    List<String> relevantChunkIds) {
}
