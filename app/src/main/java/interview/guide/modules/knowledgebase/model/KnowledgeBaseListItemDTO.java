package interview.guide.modules.knowledgebase.model;

import java.time.LocalDateTime;

/**
 * 知识库列表项DTO
 * 使用MapStruct进行转换，见KnowledgeBaseMapper
 */
public record KnowledgeBaseListItemDTO(
    Long id,
    String name,
    String category,
    String service,
    String environment,
    String project,
    String docType,
    String source,
    String versionLabel,
    String documentKey,
    Integer versionNo,
    Boolean active,
    String originalFilename,
    Long fileSize,
    String contentType,
    LocalDateTime uploadedAt,
    LocalDateTime lastAccessedAt,
    Integer accessCount,
    Integer questionCount,
    VectorStatus vectorStatus,
    String vectorError,
    Integer chunkCount,
    QuestionGenStatus questionGenStatus,
    String questionGenError,
    Boolean versionConflict
) {
    /**
     * 返回带版本冲突标记的副本（版本冲突由服务层按 documentKey 下启用版本的 distinct fileHash 数计算）。
     */
    public KnowledgeBaseListItemDTO withVersionConflict(boolean conflict) {
        return new KnowledgeBaseListItemDTO(
            id, name, category, service, environment, project, docType, source,
            versionLabel, documentKey, versionNo, active, originalFilename, fileSize,
            contentType, uploadedAt, lastAccessedAt, accessCount, questionCount,
            vectorStatus, vectorError, chunkCount, questionGenStatus, questionGenError, conflict);
    }
}

