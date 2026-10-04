package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.modules.knowledgebase.listener.VectorizeStreamProducer;
import interview.guide.modules.knowledgebase.model.KbUploadMetadata;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.dao.DataIntegrityViolationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 知识库上传服务
 * 处理知识库上传、解析的业务逻辑
 * 向量化改为异步处理，通过 Redis Stream 实现
 *
 * <p>阶段 2：贯通 project/docType/source/versionLabel 元数据；按 documentKey 组织版本，
 * 新内容上传成为新版本并停用/清理旧版本向量（旧版本退出检索）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseUploadService {

    private final KnowledgeBaseParseService parseService;
    private final KnowledgeBasePersistenceService persistenceService;
    private final FileStorageService storageService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final FileValidationService fileValidationService;
    private final FileHashService fileHashService;
    private final VectorizeStreamProducer vectorizeStreamProducer;
    private final KnowledgeBaseVersionService versionService;

    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB

    /**
     * 上传知识库文件（阶段 2：含项目/文档类型/来源/适用版本元数据与版本分组）。
     *
     * @param documentKeyParam 传入已有逻辑文档标识则登记为其新版本；为空则按稳定身份派生
     */
    public Map<String, Object> uploadKnowledgeBase(MultipartFile file, String name, String category,
                                                     String service, String environment,
                                                     String project, String docType, String source,
                                                     String versionLabel, String documentKeyParam) {
        // 0. 元数据长度校验（在 S3/fileHash/Redis 调用之前）
        checkLength("service", service, 100);
        checkLength("environment", environment, 50);
        checkLength("project", project, 100);
        checkLength("docType", docType, 50);
        checkLength("source", source, 50);
        checkLength("versionLabel", versionLabel, 50);

        // 1. 验证文件
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "知识库");

        String fileName = file.getOriginalFilename();
        log.info("收到知识库上传请求: {}, 大小: {} bytes, project: {}, service: {}, environment: {}, docType: {}",
            fileName, file.getSize(), project, service, environment, docType);

        // 2. 验证文件类型
        String contentType = parseService.detectContentType(file);
        validateContentType(contentType, fileName);

        // 3. 检查知识库是否已存在（内容级去重：相同 fileHash 不产生新行/新向量）
        String fileHash = fileHashService.calculateHash(file);
        Optional<KnowledgeBaseEntity> existingKb = knowledgeBaseRepository.findByFileHash(fileHash);
        if (existingKb.isPresent()) {
            log.info("检测到重复知识库: hash={}", fileHash);
            return persistenceService.handleDuplicateKnowledgeBase(existingKb.get(), fileHash);
        }

        // 4. 解析知识库文本（用于向量化）
        String content = parseService.parseContent(file);
        if (content == null || content.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法从文件中提取文本内容，请确保文件格式正确");
        }

        // 5. 保存文件到 RustFS
        String fileKey = storageService.uploadKnowledgeBase(file);
        String fileUrl = storageService.getFileUrl(fileKey);
        log.info("知识库已存储到RustFS: {}", fileKey);

        // 6. 计算逻辑文档标识与版本号（版本分组）
        KbUploadMetadata meta = new KbUploadMetadata(service, environment, project, docType, source, versionLabel);
        String documentKey = (documentKeyParam != null && !documentKeyParam.isBlank())
            ? documentKeyParam.trim()
            : deriveDocumentKey(meta, name, fileName);
        int versionNo = nextVersionNo(documentKey);

        // 6.5 计算归一化版本标签并检测冲突
        String normalizedVersionLabel = (versionLabel != null && !versionLabel.isBlank())
            ? versionLabel.trim().toLowerCase() : null;

        boolean hasVersionConflict = false;
        if (normalizedVersionLabel != null && documentKey != null) {
            List<KnowledgeBaseEntity> existingActive = knowledgeBaseRepository
                .findByDocumentKeyAndNormalizedVersionLabelAndActiveTrue(documentKey, normalizedVersionLabel);
            hasVersionConflict = existingActive.stream()
                .anyMatch(e -> !e.getFileHash().equals(fileHash));
        }

        // 7. 保存知识库元数据到数据库
        KnowledgeBaseEntity savedKb;
        try {
            savedKb = persistenceService.saveKnowledgeBase(
                file, name, category, meta, documentKey, versionNo, fileKey, fileUrl, fileHash,
                normalizedVersionLabel, hasVersionConflict);
        } catch (DataIntegrityViolationException dive) {
            // 并发安全网：partial unique index 被违反，说明另一个事务已插入同 (documentKey, normalizedVersionLabel)
            log.warn("并发冲突检测到唯一索引违反: documentKey={}, normalizedVersionLabel={}", documentKey, normalizedVersionLabel, dive);
            // 将当前上传保存为冲突候选：active=false, conflict=true, vectorStatus=CONFLICT
            // 这样它不满足唯一索引 WHERE 子句 (active=TRUE AND conflict=FALSE)，可以插入
            KnowledgeBaseEntity conflictCandidate;
            try {
                conflictCandidate = persistenceService.saveKnowledgeBase(
                    file, name, category, meta, documentKey, versionNo, fileKey, fileUrl, fileHash,
                    normalizedVersionLabel, true);
            } catch (Exception innerEx) {
                // DB 保存彻底失败，清理 S3 对象
                log.error("冲突候选保存失败，清理 S3: fileKey={}", fileKey, innerEx);
                try {
                    storageService.deleteKnowledgeBase(fileKey);
                } catch (Exception cleanupEx) {
                    log.warn("S3 清理失败（可后续手动清理）: fileKey={}", fileKey, cleanupEx);
                }
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "版本冲突记录保存失败");
            }
            return buildConflictResult(conflictCandidate, content.length());
        }

        // 8. 如果检测到版本冲突，返回冲突结果（已在保存时标记）
        if (hasVersionConflict) {
            log.info("检测到版本冲突，已标记: kbId={}, documentKey={}, normalizedVersionLabel={}",
                savedKb.getId(), documentKey, normalizedVersionLabel);
            return buildConflictResult(savedKb, content.length());
        }

        // 9. 停用并清理同 documentKey 的旧版本（旧版本退出检索）
        versionService.retireSupersededVersions(documentKey, savedKb.getId());

        // 10. 发送向量化任务到 Redis Stream（异步处理）
        vectorizeStreamProducer.sendVectorizeTask(savedKb.getId(), content);

        log.info("知识库上传完成，向量化任务已入队: {}, kbId={}, documentKey={}, versionNo={}",
            fileName, savedKb.getId(), documentKey, versionNo);

        // 11. 返回结果（状态 PENDING，前端可轮询获取最新状态）
        Map<String, Object> kb = new HashMap<>();
        kb.put("id", savedKb.getId());
        kb.put("name", savedKb.getName());
        kb.put("category", savedKb.getCategory() != null ? savedKb.getCategory() : "");
        kb.put("service", savedKb.getService() != null ? savedKb.getService() : "");
        kb.put("environment", savedKb.getEnvironment() != null ? savedKb.getEnvironment() : "");
        kb.put("project", savedKb.getProject() != null ? savedKb.getProject() : "");
        kb.put("docType", savedKb.getDocType() != null ? savedKb.getDocType() : "");
        kb.put("source", savedKb.getSource() != null ? savedKb.getSource() : "");
        kb.put("versionLabel", savedKb.getVersionLabel() != null ? savedKb.getVersionLabel() : "");
        kb.put("documentKey", savedKb.getDocumentKey() != null ? savedKb.getDocumentKey() : "");
        kb.put("versionNo", savedKb.getVersionNo());
        kb.put("active", true);
        kb.put("fileSize", savedKb.getFileSize());
        kb.put("contentLength", content.length());
        kb.put("vectorStatus", VectorStatus.PENDING.name());

        return Map.of(
            "knowledgeBase", kb,
            "storage", Map.of(
                "fileKey", fileKey,
                "fileUrl", fileUrl
            ),
            "duplicate", false,
            "conflict", false
        );
    }

    /**
     * 构建版本冲突的返回结果。
     */
    private Map<String, Object> buildConflictResult(KnowledgeBaseEntity kb, int contentLength) {
        Map<String, Object> kbMap = new HashMap<>();
        kbMap.put("id", kb.getId());
        kbMap.put("name", kb.getName());
        kbMap.put("category", kb.getCategory() != null ? kb.getCategory() : "");
        kbMap.put("service", kb.getService() != null ? kb.getService() : "");
        kbMap.put("environment", kb.getEnvironment() != null ? kb.getEnvironment() : "");
        kbMap.put("project", kb.getProject() != null ? kb.getProject() : "");
        kbMap.put("docType", kb.getDocType() != null ? kb.getDocType() : "");
        kbMap.put("source", kb.getSource() != null ? kb.getSource() : "");
        kbMap.put("versionLabel", kb.getVersionLabel() != null ? kb.getVersionLabel() : "");
        kbMap.put("documentKey", kb.getDocumentKey() != null ? kb.getDocumentKey() : "");
        kbMap.put("versionNo", kb.getVersionNo());
        kbMap.put("active", false);
        kbMap.put("fileSize", kb.getFileSize());
        kbMap.put("contentLength", contentLength);
        kbMap.put("vectorStatus", VectorStatus.CONFLICT.name());

        return Map.of(
            "knowledgeBase", kbMap,
            "storage", Map.of(
                "fileKey", kb.getStorageKey() != null ? kb.getStorageKey() : "",
                "fileUrl", kb.getStorageUrl() != null ? kb.getStorageUrl() : ""
            ),
            "duplicate", false,
            "conflict", true
        );
    }

    /**
     * 重新向量化知识库（手动重试）。从 RustFS 重新下载文件并发送向量化任务。
     */
    public void revectorize(Long kbId) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));

        // 拒绝 ABANDONED 状态
        if (kb.getVectorStatus() == VectorStatus.ABANDONED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "已放弃的知识库无法重新向量化");
        }

        log.info("开始重新向量化知识库: kbId={}, name={}", kbId, kb.getName());

        // 1. 下载文件并解析内容
        String content = parseService.downloadAndParseContent(kb.getStorageKey(), kb.getOriginalFilename());
        if (content == null || content.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法从文件中提取文本内容");
        }

        // 2. 更新状态为 PENDING（通过单独的 Service 保证事务生效）
        persistenceService.updateVectorStatusToPending(kbId);

        // 3. 发送向量化任务到 Stream
        vectorizeStreamProducer.sendVectorizeTask(kbId, content);

        log.info("重新向量化任务已发送: kbId={}", kbId);
    }

    /**
     * 停用知识库（手动）：置 active=false 并删除其向量，使其退出检索。
     */
    public void retire(Long kbId) {
        versionService.retire(kbId);
    }

    // ========== 私有方法 ==========

    private void validateContentType(String contentType, String fileName) {
        fileValidationService.validateContentType(
            contentType,
            fileName,
            fileValidationService::isKnowledgeBaseMimeType,
            fileValidationService::isMarkdownExtension,
            "不支持的文件类型: " + contentType + "，支持的类型：PDF、DOCX、DOC、TXT、MD等"
        );
    }

    private void checkLength(String field, String value, int max) {
        if (value != null && value.trim().length() > max) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + " 长度不能超过 " + max);
        }
    }

    /** 按稳定身份 project|service|environment|docType|baseName 派生 SHA-256 逻辑文档标识。 */
    private String deriveDocumentKey(KbUploadMetadata meta, String name, String fileName) {
        String baseName = (name != null && !name.trim().isEmpty())
            ? name.trim()
            : stripExtension(fileName);
        String identity = String.join("|",
            normalize(meta.project()), normalize(meta.service()), normalize(meta.environment()),
            normalize(meta.docType()), normalize(baseName));
        return sha256Hex(identity);
    }

    private int nextVersionNo(String documentKey) {
        if (documentKey == null || documentKey.isBlank()) {
            return 1;
        }
        List<KnowledgeBaseEntity> existing =
            knowledgeBaseRepository.findByDocumentKeyOrderByVersionNoDesc(documentKey);
        if (existing.isEmpty()) {
            return 1;
        }
        Integer max = existing.get(0).getVersionNo();
        return (max == null ? 1 : max) + 1;
    }

    private static String stripExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "";
        }
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
    }

    private static String normalize(String v) {
        return v == null ? "" : v.trim().toLowerCase();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "计算文档标识失败");
        }
    }
}
