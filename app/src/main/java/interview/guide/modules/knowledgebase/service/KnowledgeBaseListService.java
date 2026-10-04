package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.mapper.KnowledgeBaseMapper;
import interview.guide.modules.knowledgebase.model.ContextKbItem;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseStatsDTO;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity.MessageType;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 知识库查询服务
 * 负责知识库列表和详情的查询
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseListService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final RagChatMessageRepository ragChatMessageRepository;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final FileStorageService fileStorageService;

    /**
     * 获取知识库列表（支持状态过滤、排序和 service/environment 筛选）
     * 
     * @param vectorStatus 向量化状态，null 表示不过滤
     * @param sortBy 排序字段，null 或 "time" 表示按时间排序
     * @param service 服务标签，null 或空表示不过滤
     * @param environment 环境标签，null 或空表示不过滤
     * @return 知识库列表
     */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBases(VectorStatus vectorStatus, String sortBy,
                                                              String service, String environment) {
        return listKnowledgeBases(vectorStatus, sortBy, service, environment, null, null, null);
    }

    /**
     * 获取知识库列表（支持 service/environment/project/docType/version 筛选）
     *
     * @param vectorStatus 向量化状态，null 表示不过滤
     * @param sortBy 排序字段，null 或 "time" 表示按时间排序
     * @param service 服务标签，null 或空表示不过滤
     * @param environment 环境标签，null 或空表示不过滤
     * @param project 项目标签，null 或空表示不过滤
     * @param docType 文档类型，null 或空表示不过滤
     * @param version 版本标签，null 或空表示不过滤
     * @return 知识库列表
     */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBases(VectorStatus vectorStatus, String sortBy,
                                                              String service, String environment,
                                                              String project, String docType,
                                                              String version) {
        List<KnowledgeBaseEntity> entities;
        
        boolean hasService = service != null && !service.isBlank();
        boolean hasEnvironment = environment != null && !environment.isBlank();
        
        if (vectorStatus != null) {
            // 状态过滤优先，然后在内存中按 service/environment 筛选
            entities = knowledgeBaseRepository.findByVectorStatusOrderByUploadedAtDesc(vectorStatus);
            if (hasService || hasEnvironment) {
                entities = filterByServiceEnvironment(entities, service, environment);
            }
        } else if (hasService && hasEnvironment) {
            entities = knowledgeBaseRepository.findByServiceAndEnvironmentOrderByUploadedAtDesc(
                service.trim(), environment.trim());
        } else if (hasService) {
            entities = knowledgeBaseRepository.findByServiceOrderByUploadedAtDesc(service.trim());
        } else if (hasEnvironment) {
            entities = knowledgeBaseRepository.findByEnvironmentOrderByUploadedAtDesc(environment.trim());
        } else {
            entities = knowledgeBaseRepository.findAllByOrderByUploadedAtDesc();
        }

        // 内存中按 project/docType/version 筛选
        entities = filterByProjectDocTypeVersion(entities, project, docType, version);
        
        // 如果指定了排序字段，在内存中排序
        if (sortBy != null && !sortBy.isBlank() && !sortBy.equalsIgnoreCase("time")) {
            entities = sortEntities(entities, sortBy);
        }

        return markVersionConflicts(entities, knowledgeBaseMapper.toListItemDTOList(entities));
    }

    /**
     * 计算并标记版本冲突（阶段 2）：同一 documentKey 下存在 ≥2 个启用版本且内容(fileHash)不同。
     * 对应路线图“当同一主题存在不同版本且内容冲突时，文档中心应标识冲突”。
     */
    private List<KnowledgeBaseListItemDTO> markVersionConflicts(
            List<KnowledgeBaseEntity> entities, List<KnowledgeBaseListItemDTO> dtos) {
        Map<String, Set<String>> hashesByDoc = new HashMap<>();
        for (KnowledgeBaseEntity e : entities) {
            if (e.getDocumentKey() == null || Boolean.FALSE.equals(e.getActive())) {
                continue;
            }
            hashesByDoc.computeIfAbsent(e.getDocumentKey(), k -> new HashSet<>())
                .add(e.getFileHash());
        }
        Set<String> conflictDocs = new HashSet<>();
        hashesByDoc.forEach((doc, hashes) -> {
            if (hashes.size() >= 2) {
                conflictDocs.add(doc);
            }
        });
        if (conflictDocs.isEmpty()) {
            return dtos;
        }
        return dtos.stream()
            .map(d -> d.documentKey() != null && conflictDocs.contains(d.documentKey())
                ? d.withVersionConflict(true) : d)
            .toList();
    }

    /**
     * 在内存中按 service/environment 过滤实体列表
     */
    private List<KnowledgeBaseEntity> filterByServiceEnvironment(List<KnowledgeBaseEntity> entities,
                                                                   String service, String environment) {
        boolean hasService = service != null && !service.isBlank();
        boolean hasEnvironment = environment != null && !environment.isBlank();
        String svc = hasService ? service.trim() : null;
        String env = hasEnvironment ? environment.trim() : null;
        
        return entities.stream()
            .filter(e -> {
                if (hasService && !svc.equals(e.getService())) return false;
                if (hasEnvironment && !env.equals(e.getEnvironment())) return false;
                return true;
            })
            .toList();
    }

    /**
     * 在内存中按 project/docType/version 过滤实体列表
     */
    private List<KnowledgeBaseEntity> filterByProjectDocTypeVersion(List<KnowledgeBaseEntity> entities,
                                                                     String project, String docType,
                                                                     String version) {
        String proj = (project != null && !project.isBlank()) ? project.trim() : null;
        String doc = (docType != null && !docType.isBlank()) ? docType.trim() : null;
        String ver = (version != null && !version.isBlank()) ? version.trim() : null;
        if (proj == null && doc == null && ver == null) {
            return entities;
        }
        return entities.stream()
            .filter(e -> {
                if (proj != null && !proj.equalsIgnoreCase(e.getProject())) return false;
                if (doc != null && !doc.equalsIgnoreCase(e.getDocType())) return false;
                if (ver != null && !ver.equalsIgnoreCase(e.getVersionLabel())) return false;
                return true;
            })
            .toList();
    }

    /**
     * 获取所有知识库列表（保持向后兼容）
     */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBases() {
        return listKnowledgeBases(null, null, null, null, null, null, null);
    }

    /**
     * 按向量化状态获取知识库列表（保持向后兼容）
     */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBasesByStatus(VectorStatus vectorStatus) {
        return listKnowledgeBases(vectorStatus, null, null, null, null, null, null);
    }

    /**
     * 根据ID获取知识库详情
     */
    public Optional<KnowledgeBaseListItemDTO> getKnowledgeBase(Long id) {
        return knowledgeBaseRepository.findById(id)
            .map(knowledgeBaseMapper::toListItemDTO);
    }

    /**
     * 根据ID获取知识库实体（用于删除等操作）
     */
    public Optional<KnowledgeBaseEntity> getKnowledgeBaseEntity(Long id) {
        return knowledgeBaseRepository.findById(id);
    }

    /**
     * 根据ID列表获取知识库名称列表
     */
    public List<String> getKnowledgeBaseNames(List<Long> ids) {
        return ids.stream()
            .map(id -> knowledgeBaseRepository.findById(id)
                .map(KnowledgeBaseEntity::getName)
                .orElse("未知知识库"))
            .toList();
    }

    // ========== 分类管理 ==========

    /**
     * 获取所有分类
     */
    public List<String> getAllCategories() {
        return knowledgeBaseRepository.findAllCategories();
    }

    /**
     * 获取所有服务标签
     */
    public List<String> getAllServices() {
        return knowledgeBaseRepository.findAllServices();
    }

    /**
     * 获取所有环境标签
     */
    public List<String> getAllEnvironments() {
        return knowledgeBaseRepository.findAllEnvironments();
    }

    /**
     * 根据分类获取知识库列表
     */
    public List<KnowledgeBaseListItemDTO> listByCategory(String category) {
        List<KnowledgeBaseEntity> entities;
        if (category == null || category.isBlank()) {
            entities = knowledgeBaseRepository.findByCategoryIsNullOrderByUploadedAtDesc();
        } else {
            entities = knowledgeBaseRepository.findByCategoryOrderByUploadedAtDesc(category);
        }
        return knowledgeBaseMapper.toListItemDTOList(entities);
    }

    /**
     * 更新知识库分类
     */
    @Transactional
    public void updateCategory(Long id, String category) {
        KnowledgeBaseEntity entity = knowledgeBaseRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));
        entity.setCategory(category != null && !category.isBlank() ? category : null);
        knowledgeBaseRepository.save(entity);
        log.info("更新知识库分类: id={}, category={}", id, category);
    }

    /**
     * 更新知识库服务/环境标签
     */
    @Transactional
    public void updateLabels(Long id, String service, String environment) {
        // 长度验证
        if (service != null && service.trim().length() > 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "服务名称不能超过100个字符");
        }
        if (environment != null && environment.trim().length() > 50) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "环境名称不能超过50个字符");
        }

        KnowledgeBaseEntity entity = knowledgeBaseRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));
        entity.setService(service != null && !service.isBlank() ? service.trim() : null);
        entity.setEnvironment(environment != null && !environment.isBlank() ? environment.trim() : null);
        knowledgeBaseRepository.save(entity);
        log.info("更新知识库标签: id={}, service={}, environment={}", id, service, environment);
    }

    // ========== 搜索功能 ==========

    /**
     * 按关键词搜索知识库
     */
    public List<KnowledgeBaseListItemDTO> search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return listKnowledgeBases();
        }
        return knowledgeBaseMapper.toListItemDTOList(
            knowledgeBaseRepository.searchByKeyword(keyword.trim())
        );
    }

    // ========== 排序功能 ==========

    /**
     * 按指定字段排序获取知识库列表（保持向后兼容）
     */
    public List<KnowledgeBaseListItemDTO> listSorted(String sortBy) {
        return listKnowledgeBases(null, sortBy, null, null, null, null, null);
    }

    /**
     * 在内存中对实体列表排序
     */
    private List<KnowledgeBaseEntity> sortEntities(List<KnowledgeBaseEntity> entities, String sortBy) {
        return switch (sortBy.toLowerCase()) {
            case "size" -> entities.stream()
                .sorted((a, b) -> Long.compare(b.getFileSize(), a.getFileSize()))
                .toList();
            case "access" -> entities.stream()
                .sorted((a, b) -> Integer.compare(b.getAccessCount(), a.getAccessCount()))
                .toList();
            case "question" -> entities.stream()
                .sorted((a, b) -> Integer.compare(b.getQuestionCount(), a.getQuestionCount()))
                .toList();
            default -> entities; // time 已经在数据库层面排序了
        };
    }

    // ========== 统计功能 ==========

    /**
     * 获取知识库统计信息
     * 总提问次数从用户消息数统计，确保多知识库提问只算一次
     */
    public KnowledgeBaseStatsDTO getStatistics() {
        return new KnowledgeBaseStatsDTO(
            knowledgeBaseRepository.count(),
            ragChatMessageRepository.countByType(MessageType.USER),  // 真正的提问次数
            knowledgeBaseRepository.sumAccessCount(),
            knowledgeBaseRepository.countByVectorStatus(VectorStatus.COMPLETED),
            knowledgeBaseRepository.countByVectorStatus(VectorStatus.PROCESSING)
        );
    }

    // ========== 下载功能 ==========

    /**
     * 下载知识库文件
     */
    public byte[] downloadFile(Long id) {
        KnowledgeBaseEntity entity = knowledgeBaseRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));

        String storageKey = entity.getStorageKey();
        if (storageKey == null || storageKey.isBlank()) {
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件存储信息不存在");
        }

        log.info("下载知识库文件: id={}, filename={}", id, entity.getOriginalFilename());
        return fileStorageService.downloadFile(storageKey);
    }

    /**
     * 根据 service/environment 上下文解析匹配的知识库列表。
     * <ul>
     *   <li>两者均为空 -> 返回所有知识库</li>
     *   <li>仅 service -> 按 service 过滤</li>
     *   <li>仅 environment -> 按 environment 过滤</li>
     *   <li>两者均有 -> AND 过滤</li>
     * </ul>
     *
     * @param service 服务标签，可为 null 或空
     * @param environment 环境标签，可为 null 或空
     * @return 匹配的知识库摘要列表
     */
    public List<ContextKbItem> resolveContext(String service, String environment) {
        return resolveContext(service, environment, null);
    }

    /**
     * 解析检索范围（阶段 2）：仅返回处于启用状态、匹配 service/environment/project 的知识库。
     * 停用或被新版本取代的资料（active=false）不进入解析结果，因此不会进入后续检索。
     */
    public List<ContextKbItem> resolveContext(String service, String environment, String project) {
        boolean hasService = service != null && !service.isBlank();
        boolean hasEnvironment = environment != null && !environment.isBlank();

        List<KnowledgeBaseEntity> entities;
        if (hasService && hasEnvironment) {
            entities = knowledgeBaseRepository.findByServiceAndEnvironmentOrderByUploadedAtDesc(
                service.trim(), environment.trim());
        } else if (hasService) {
            entities = knowledgeBaseRepository.findByServiceOrderByUploadedAtDesc(service.trim());
        } else if (hasEnvironment) {
            entities = knowledgeBaseRepository.findByEnvironmentOrderByUploadedAtDesc(environment.trim());
        } else {
            entities = knowledgeBaseRepository.findAllByOrderByUploadedAtDesc();
        }

        String projectFilter = project != null && !project.isBlank() ? project.trim() : null;
        return entities.stream()
            .filter(e -> !Boolean.FALSE.equals(e.getActive()))
            .filter(e -> projectFilter == null || projectFilter.equalsIgnoreCase(e.getProject()))
            .map(e -> new ContextKbItem(e.getId(), e.getName(), e.getService(), e.getEnvironment(),
                e.getProject(), e.getVersionNo(), e.getDocumentKey(),
                !Boolean.FALSE.equals(e.getActive())))
            .toList();
    }

    /**
     * 获取知识库文件信息（用于下载）
     */
    public KnowledgeBaseEntity getEntityForDownload(Long id) {
        return knowledgeBaseRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));
    }
}
