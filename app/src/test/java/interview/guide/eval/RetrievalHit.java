package interview.guide.eval;

/**
 * 单条检索命中。
 *
 * <p>所在列表的顺序即排名（rank 从 1 开始）。{@code score} 可为 {@code null}；
 * 排名类指标（Hit/Recall/MRR）不依赖 score。
 *
 * <p>{@code kbId} 表示命中片段的来源知识库，与查询标注中的 gold 片段标签
 * （{@code relevantChunkIds}）是不同维度：命中判定只按 {@code chunkId}，
 * 不能因为来源知识库相同就算片段命中。
 */
public record RetrievalHit(String chunkId, String kbId, Double score) {
}
