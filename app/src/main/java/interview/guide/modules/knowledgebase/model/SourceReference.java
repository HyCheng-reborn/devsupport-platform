package interview.guide.modules.knowledgebase.model;

/**
 * 来源引用 DTO，用于 SSE 事件和消息持久化。
 * documentName 取自 KnowledgeBaseEntity.originalFilename（原始文件名，不可被用户修改）。
 * service/environment 为提问时刻的快照，后续 KB 标签变更不影响已持久化的来源。
 * sourceType 区分来源类型："KB"（知识库文档）或 "CASE"（已发布案例）。
 * caseId / caseTitle 仅在 sourceType="CASE" 时有值，用于前端展示案例来源。
 * chunkId 为 vector_store 中 Document 的 UUID，用于回归评测的期望证据追溯。
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
    String sectionTitle,
    String sourceType,
    Long caseId,
    String caseTitle,
    String chunkId
) {
  /** 向后兼容构造器：默认 sourceType="KB"，caseId/caseTitle/chunkId 为 null */
  public SourceReference(Long kbId, String documentName, String contentSnippet, Double score,
                         String service, String environment, String versionLabel, Integer versionNo,
                         String documentKey, String sectionTitle) {
    this(kbId, documentName, contentSnippet, score, service, environment, versionLabel, versionNo,
      documentKey, sectionTitle, "KB", null, null, null);
  }
}
