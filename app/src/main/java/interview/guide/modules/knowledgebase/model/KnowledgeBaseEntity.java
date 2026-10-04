package interview.guide.modules.knowledgebase.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 知识库实体
 */
@Entity
@Table(name = "knowledge_bases", indexes = {
    @Index(name = "idx_kb_hash", columnList = "fileHash", unique = true),
    @Index(name = "idx_kb_category", columnList = "category"),
    @Index(name = "idx_kb_service", columnList = "service"),
    @Index(name = "idx_kb_environment", columnList = "environment"),
    @Index(name = "idx_kb_project", columnList = "project"),
    @Index(name = "idx_kb_document_key", columnList = "documentKey"),
    @Index(name = "idx_kb_active", columnList = "active")
})
public class KnowledgeBaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 文件内容的SHA-256哈希值，用于去重
    @Column(nullable = false, unique = true, length = 64)
    private String fileHash;

    // 知识库名称（用户自定义或从文件名提取）
    @Column(nullable = false)
    private String name;

    // 分类/分组（如"Java面试"、"项目文档"等）
    @Column(length = 100)
    private String category;

    // 原始文件名
    @Column(nullable = false)
    private String originalFilename;
    
    // 文件大小（字节）
    private Long fileSize;
    
    // 文件类型
    private String contentType;
    
    // RustFS存储的文件Key
    @Column(length = 500)
    private String storageKey;
    
    // RustFS存储的文件URL
    @Column(length = 1000)
    private String storageUrl;
    
    // 上传时间
    @Column(nullable = false)
    private LocalDateTime uploadedAt;
    
    // 最后访问时间
    private LocalDateTime lastAccessedAt;
    
    // 访问次数
    private Integer accessCount = 0;
    
    // 问题数量（用户针对此知识库提问的次数）
    private Integer questionCount = 0;

    // 向量化状态（新上传时为 PENDING，异步处理完成后变为 COMPLETED）
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private VectorStatus vectorStatus = VectorStatus.PENDING;

    // 向量化错误信息（失败时记录）
    @Column(length = 500)
    private String vectorError;

    // 向量分块数量
    private Integer chunkCount = 0;

    // 问题生成状态
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private QuestionGenStatus questionGenStatus = QuestionGenStatus.NONE;

    // 问题生成错误信息（失败时记录）
    @Column(length = 500)
    private String questionGenError;

    // 问题生成任务ID（用于幂等判断，防止旧任务覆盖新任务）
    @Column(length = 36)
    private String questionGenTaskId;

    // 问题生成参数快照（不包含 Prompt、上下文或密钥）
    @Column(columnDefinition = "TEXT")
    private String questionGenConfig;

    // 问题生成结果摘要
    @Column(length = 500)
    private String questionGenMessage;

    private Integer questionGenSavedCount = 0;

    private Integer questionGenSkippedCount = 0;

    private LocalDateTime questionGenUpdatedAt;

    // 服务标签（如"支付网关"、"用户中心"）
    @Column(length = 100)
    private String service;

    // 环境标签（如"生产"、"预发"、"测试"）
    @Column(length = 50)
    private String environment;

    // 项目（DevSupport 资料归属的项目/系统，阶段 2 新增；可空表示未分类）
    @Column(length = 100)
    private String project;

    // 文档类型（如 runbook/部署手册/错误码/配置说明，阶段 2 新增）
    @Column(name = "doc_type", length = 50)
    private String docType;

    // 来源（如 wiki/仓库/工单，阶段 2 新增）
    @Column(length = 50)
    private String source;

    // 适用版本标签（用户可读，如 "v2.3"，阶段 2 新增）
    @Column(name = "version_label", length = 50)
    private String versionLabel;

    // 逻辑文档标识：同一 documentKey 的多行为同一文档的不同版本（阶段 2 版本分组）
    @Column(name = "document_key", length = 64)
    private String documentKey;

    // 版本号：同一 documentKey 内递增，越大越新
    @Column(name = "version_no", nullable = false)
    private Integer versionNo = 1;

    // 生命周期：true=启用可检索，false=已停用/被新版本取代（退出检索）
    @Column(nullable = false)
    private Boolean active = true;

    // 版本冲突标记：同一 (documentKey, normalizedVersionLabel) 下出现多个 active 行时置为 true
    @Column(nullable = false)
    private Boolean conflict = false;

    // 归一化版本标签（如 "v1.0"、"v2.0"），用于冲突分组
    @Column(name = "normalized_version_label", length = 50)
    private String normalizedVersionLabel;
    
    @PrePersist
    protected void onCreate() {
        uploadedAt = LocalDateTime.now();
        lastAccessedAt = LocalDateTime.now();
        accessCount = 1;
    }
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public String getFileHash() {
        return fileHash;
    }
    
    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getOriginalFilename() {
        return originalFilename;
    }
    
    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }
    
    public Long getFileSize() {
        return fileSize;
    }
    
    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }
    
    public String getContentType() {
        return contentType;
    }
    
    public void setContentType(String contentType) {
        this.contentType = contentType;
    }
    
    public String getStorageKey() {
        return storageKey;
    }
    
    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }
    
    public String getStorageUrl() {
        return storageUrl;
    }
    
    public void setStorageUrl(String storageUrl) {
        this.storageUrl = storageUrl;
    }
    
    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }
    
    public void setUploadedAt(LocalDateTime uploadedAt) {
        this.uploadedAt = uploadedAt;
    }
    
    public LocalDateTime getLastAccessedAt() {
        return lastAccessedAt;
    }
    
    public void setLastAccessedAt(LocalDateTime lastAccessedAt) {
        this.lastAccessedAt = lastAccessedAt;
    }
    
    public Integer getAccessCount() {
        return accessCount;
    }
    
    public void setAccessCount(Integer accessCount) {
        this.accessCount = accessCount;
    }
    
    public Integer getQuestionCount() {
        return questionCount;
    }
    
    public void setQuestionCount(Integer questionCount) {
        this.questionCount = questionCount;
    }
    
    public void incrementAccessCount() {
        this.accessCount++;
        this.lastAccessedAt = LocalDateTime.now();
    }
    
    public void incrementQuestionCount() {
        this.questionCount++;
        this.lastAccessedAt = LocalDateTime.now();
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public VectorStatus getVectorStatus() {
        return vectorStatus;
    }

    public void setVectorStatus(VectorStatus vectorStatus) {
        this.vectorStatus = vectorStatus;
    }

    public String getVectorError() {
        return vectorError;
    }

    public void setVectorError(String vectorError) {
        this.vectorError = vectorError;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
    }

    public QuestionGenStatus getQuestionGenStatus() {
        return questionGenStatus;
    }

    public void setQuestionGenStatus(QuestionGenStatus questionGenStatus) {
        this.questionGenStatus = questionGenStatus;
    }

    public String getQuestionGenError() {
        return questionGenError;
    }

    public void setQuestionGenError(String questionGenError) {
        this.questionGenError = questionGenError;
    }

    public String getQuestionGenTaskId() {
        return questionGenTaskId;
    }

    public void setQuestionGenTaskId(String questionGenTaskId) {
        this.questionGenTaskId = questionGenTaskId;
    }

    public String getQuestionGenConfig() {
        return questionGenConfig;
    }

    public void setQuestionGenConfig(String questionGenConfig) {
        this.questionGenConfig = questionGenConfig;
    }

    public String getQuestionGenMessage() {
        return questionGenMessage;
    }

    public void setQuestionGenMessage(String questionGenMessage) {
        this.questionGenMessage = questionGenMessage;
    }

    public Integer getQuestionGenSavedCount() {
        return questionGenSavedCount;
    }

    public void setQuestionGenSavedCount(Integer questionGenSavedCount) {
        this.questionGenSavedCount = questionGenSavedCount;
    }

    public Integer getQuestionGenSkippedCount() {
        return questionGenSkippedCount;
    }

    public void setQuestionGenSkippedCount(Integer questionGenSkippedCount) {
        this.questionGenSkippedCount = questionGenSkippedCount;
    }

    public LocalDateTime getQuestionGenUpdatedAt() {
        return questionGenUpdatedAt;
    }

    public void setQuestionGenUpdatedAt(LocalDateTime questionGenUpdatedAt) {
        this.questionGenUpdatedAt = questionGenUpdatedAt;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    public String getDocType() {
        return docType;
    }

    public void setDocType(String docType) {
        this.docType = docType;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getVersionLabel() {
        return versionLabel;
    }

    public void setVersionLabel(String versionLabel) {
        this.versionLabel = versionLabel;
    }

    public String getDocumentKey() {
        return documentKey;
    }

    public void setDocumentKey(String documentKey) {
        this.documentKey = documentKey;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public Boolean getConflict() {
        return conflict;
    }

    public void setConflict(Boolean conflict) {
        this.conflict = conflict;
    }

    public String getNormalizedVersionLabel() {
        return normalizedVersionLabel;
    }

    public void setNormalizedVersionLabel(String normalizedVersionLabel) {
        this.normalizedVersionLabel = normalizedVersionLabel;
    }
}
