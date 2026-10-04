package interview.guide.modules.knowledgebase.model;

/**
 * 知识库向量化状态
 */
public enum VectorStatus {
    PENDING,     // 待处理
    PROCESSING,  // 处理中
    COMPLETED,   // 完成
    FAILED,      // 失败
    CONFLICT     // 版本冲突（同一 normalizedVersionLabel 下出现多个 active 行）
}
