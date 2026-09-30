package interview.guide.modules.knowledgebase.model;

/**
 * 来源引用 DTO，用于 SSE 事件和消息持久化。
 * documentName 取自 KnowledgeBaseEntity.originalFilename（原始文件名，不可被用户修改）。
 */
public record SourceReference(
    Long kbId,
    String documentName,
    String contentSnippet,
    Double score
) {}
