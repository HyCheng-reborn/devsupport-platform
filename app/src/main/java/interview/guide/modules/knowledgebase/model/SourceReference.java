package interview.guide.modules.knowledgebase.model;

/**
 * 来源引用 DTO，用于 SSE 事件和消息持久化。
 * documentName 取自 KnowledgeBaseEntity.originalFilename（原始文件名，不可被用户修改）。
 * service/environment 为提问时刻的快照，后续 KB 标签变更不影响已持久化的来源。
 */
public record SourceReference(
    Long kbId,
    String documentName,
    String contentSnippet,
    Double score,
    String service,
    String environment,
    String versionLabel,
    Integer versionNo,
    String documentKey,
    String sectionTitle
) {}
