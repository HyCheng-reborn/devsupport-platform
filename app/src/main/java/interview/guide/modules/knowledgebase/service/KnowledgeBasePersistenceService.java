package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.KbUploadMetadata;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 知识库持久化服务
 * 处理所有需要事务的数据库操作
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBasePersistenceService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;

    /**
     * 处理重复知识库（更新访问计数）
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> handleDuplicateKnowledgeBase(KnowledgeBaseEntity kb, String fileHash) {
        log.info("检测到重复知识库，返回已有记录: kbId={}", kb.getId());
        
        // 更新访问计数（在事务中）
        kb.incrementAccessCount();
        knowledgeBaseRepository.save(kb);
        
        // 重复知识库的向量数据应该已经存在，不需要重新向量化
        return Map.of(
            "knowledgeBase", Map.of(
                "id", kb.getId(),
                "name", kb.getName(),
                "fileSize", kb.getFileSize(),
                "contentLength", 0,  // 不再存储content，所以长度为0
                "service", kb.getService() != null ? kb.getService() : "",
                "environment", kb.getEnvironment() != null ? kb.getEnvironment() : "",
                "accessCount", kb.getAccessCount()
            ),
            "storage", Map.of(
                "fileKey", kb.getStorageKey() != null ? kb.getStorageKey() : "",
                "fileUrl", kb.getStorageUrl() != null ? kb.getStorageUrl() : ""
            ),
            "duplicate", true
        );
    }

    /**
     * 保存新知识库元数据到数据库（阶段 2：含 project/docType/source/version 元数据与版本分组）
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseEntity saveKnowledgeBase(MultipartFile file, String name, String category,
                                                  KbUploadMetadata meta, String documentKey, int versionNo,
                                                  String storageKey, String storageUrl, String fileHash,
                                                  String normalizedVersionLabel) {
        return saveKnowledgeBase(file, name, category, meta, documentKey, versionNo, storageKey, storageUrl, fileHash, normalizedVersionLabel, false);
    }

    /**
     * 保存新知识库元数据到数据库（支持冲突标记）
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseEntity saveKnowledgeBase(MultipartFile file, String name, String category,
                                                  KbUploadMetadata meta, String documentKey, int versionNo,
                                                  String storageKey, String storageUrl, String fileHash,
                                                  String normalizedVersionLabel, boolean isConflict) {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setFileHash(fileHash);
        kb.setName(name != null && !name.trim().isEmpty() ? name : extractNameFromFilename(file.getOriginalFilename()));
        kb.setCategory(trimToNull(category));
        kb.setService(meta != null ? trimToNull(meta.service()) : null);
        kb.setEnvironment(meta != null ? trimToNull(meta.environment()) : null);
        kb.setProject(meta != null ? trimToNull(meta.project()) : null);
        kb.setDocType(meta != null ? trimToNull(meta.docType()) : null);
        kb.setSource(meta != null ? trimToNull(meta.source()) : null);
        kb.setVersionLabel(meta != null ? trimToNull(meta.versionLabel()) : null);
        kb.setNormalizedVersionLabel(normalizedVersionLabel);
        kb.setDocumentKey(trimToNull(documentKey));
        kb.setVersionNo(versionNo);
        // 如果是冲突版本，设置 active=false 和 conflict=true，避免触发唯一索引
        kb.setActive(!isConflict);
        kb.setConflict(isConflict);
        if (isConflict) {
            kb.setVectorStatus(VectorStatus.CONFLICT);
        }
        kb.setOriginalFilename(file.getOriginalFilename());
        kb.setFileSize(file.getSize());
        kb.setContentType(file.getContentType());
        kb.setStorageKey(storageKey);
        kb.setStorageUrl(storageUrl);

        KnowledgeBaseEntity saved = knowledgeBaseRepository.save(kb);
        log.info("知识库已保存: id={}, name={}, documentKey={}, versionNo={}, hash={}, conflict={}",
            saved.getId(), saved.getName(), saved.getDocumentKey(), versionNo, fileHash, isConflict);
        return saved;
    }

    /**
     * 标记知识库为版本冲突状态（active=false, conflict=true, vectorStatus=CONFLICT）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void markAsConflict(Long kbId) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));
        kb.setActive(false);
        kb.setConflict(true);
        kb.setVectorStatus(VectorStatus.CONFLICT);
        knowledgeBaseRepository.save(kb);
        log.info("知识库已标记为冲突: kbId={}, documentKey={}", kbId, kb.getDocumentKey());
    }

    /**
     * 停用同一逻辑文档中除 keepId 外的其它启用版本（仅 DB 事务；向量删除由调用方在事务外执行）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void retireOtherVersions(String documentKey, Long keepId) {
        if (documentKey == null || documentKey.isBlank()) {
            return;
        }
        List<KnowledgeBaseEntity> others = knowledgeBaseRepository
            .findByDocumentKeyOrderByVersionNoDesc(documentKey).stream()
            .filter(kb -> !kb.getId().equals(keepId))
            .filter(kb -> !Boolean.FALSE.equals(kb.getActive()))
            .toList();
        for (KnowledgeBaseEntity kb : others) {
            kb.setActive(false);
        }
        knowledgeBaseRepository.saveAll(others);
        log.info("已停用同文档旧版本: documentKey={}, keepId={}, retired={}", documentKey, keepId, others.size());
    }

    /**
     * 停用单个知识库（置 active=false）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void retire(Long kbId) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));
        kb.setActive(false);
        knowledgeBaseRepository.save(kb);
        log.info("知识库已停用: kbId={}", kbId);
    }

    private static String trimToNull(String v) {
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    /**
     * 更新知识库向量化状态为 PENDING
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateVectorStatusToPending(Long kbId) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));
        
        kb.setVectorStatus(VectorStatus.PENDING);
        kb.setVectorError(null);
        knowledgeBaseRepository.save(kb);
        
        log.info("知识库向量化状态已更新为 PENDING: kbId={}", kbId);
    }

    /**
     * 从文件名提取知识库名称（去除扩展名）
     */
    private String extractNameFromFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "未命名知识库";
        }
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            return filename.substring(0, lastDot);
        }
        return filename;
    }
}

