package interview.guide.modules.evalregression.model;

/**
 * top-K 召回快照条目。
 *
 * @param evidenceId 召回文档的证据 ID（vector_store 文档 ID）
 * @param content    召回文档内容
 * @param score      相似度分数
 */
public record TopKSnapshotEntry(
  String evidenceId,
  String content,
  Double score
) {}
