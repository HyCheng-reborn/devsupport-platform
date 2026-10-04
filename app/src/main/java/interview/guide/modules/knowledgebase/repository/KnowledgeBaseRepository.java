package interview.guide.modules.knowledgebase.repository;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.QuestionGenStatus;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 知识库Repository
 */
@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, Long> {

    /**
     * 根据文件哈希查找知识库（用于去重）
     */
    Optional<KnowledgeBaseEntity> findByFileHash(String fileHash);

    /**
     * 锁定知识库行，用于串行化同一知识库的题目生成状态迁移。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT k FROM KnowledgeBaseEntity k WHERE k.id = :id")
    Optional<KnowledgeBaseEntity> findByIdForUpdate(@Param("id") Long id);

    /**
     * 检查文件哈希是否存在
     */
    boolean existsByFileHash(String fileHash);

    /**
     * 按上传时间倒序查找所有知识库
     */
    List<KnowledgeBaseEntity> findAllByOrderByUploadedAtDesc();

    /**
     * 获取所有不同的分类
     */
    @Query("SELECT DISTINCT k.category FROM KnowledgeBaseEntity k WHERE k.category IS NOT NULL ORDER BY k.category")
    List<String> findAllCategories();

    /**
     * 根据分类查找知识库
     */
    List<KnowledgeBaseEntity> findByCategoryOrderByUploadedAtDesc(String category);

    /**
     * 查找未分类的知识库
     */
    List<KnowledgeBaseEntity> findByCategoryIsNullOrderByUploadedAtDesc();

    /**
     * 按名称或文件名模糊搜索（不区分大小写）
     */
    @Query("SELECT k FROM KnowledgeBaseEntity k WHERE LOWER(k.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(k.originalFilename) LIKE LOWER(CONCAT('%', :keyword, '%')) ORDER BY k.uploadedAt DESC")
    List<KnowledgeBaseEntity> searchByKeyword(@Param("keyword") String keyword);

    /**
     * 按文件大小排序
     */
    List<KnowledgeBaseEntity> findAllByOrderByFileSizeDesc();

    /**
     * 按访问次数排序
     */
    List<KnowledgeBaseEntity> findAllByOrderByAccessCountDesc();

    /**
     * 按提问次数排序
     */
    List<KnowledgeBaseEntity> findAllByOrderByQuestionCountDesc();

    // ==================== 批量更新 ====================

    /**
     * 批量增加知识库提问计数
     * @param ids 知识库ID列表
     * @return 更新的行数
     */
    @Modifying
    @Query("UPDATE KnowledgeBaseEntity k SET k.questionCount = k.questionCount + 1 WHERE k.id IN :ids")
    int incrementQuestionCountBatch(@Param("ids") List<Long> ids);

    // ==================== 统计查询 ====================

    /**
     * 统计总提问次数
     */
    @Query("SELECT COALESCE(SUM(k.questionCount), 0) FROM KnowledgeBaseEntity k")
    long sumQuestionCount();

    /**
     * 统计总访问次数
     */
    @Query("SELECT COALESCE(SUM(k.accessCount), 0) FROM KnowledgeBaseEntity k")
    long sumAccessCount();

    /**
     * 按向量化状态统计数量
     */
    long countByVectorStatus(VectorStatus vectorStatus);

    /**
     * 按向量化状态查找知识库（按上传时间倒序）
     */
    List<KnowledgeBaseEntity> findByVectorStatusOrderByUploadedAtDesc(VectorStatus vectorStatus);

    @Query("SELECT k FROM KnowledgeBaseEntity k "
        + "WHERE k.questionGenStatus = :status "
        + "AND (k.questionGenUpdatedAt IS NULL OR k.questionGenUpdatedAt < :threshold)")
    List<KnowledgeBaseEntity> findStaleQuestionGenerationTasks(
        @Param("status") QuestionGenStatus status,
        @Param("threshold") LocalDateTime threshold);

    // ==================== 服务/环境标签查询 ====================

    /**
     * 根据服务标签查找知识库（按上传时间倒序）
     */
    List<KnowledgeBaseEntity> findByServiceOrderByUploadedAtDesc(String service);

    /**
     * 根据环境标签查找知识库（按上传时间倒序）
     */
    List<KnowledgeBaseEntity> findByEnvironmentOrderByUploadedAtDesc(String environment);

    /**
     * 根据服务标签和环境标签查找知识库（按上传时间倒序）
     */
    List<KnowledgeBaseEntity> findByServiceAndEnvironmentOrderByUploadedAtDesc(String service, String environment);

    // ==================== 阶段 2：版本分组与生命周期 ====================

    /**
     * 同一逻辑文档（documentKey）的所有版本，按版本号降序（首个为最新版本）。
     */
    List<KnowledgeBaseEntity> findByDocumentKeyOrderByVersionNoDesc(String documentKey);

    /**
     * 同一逻辑文档中仍处于启用状态的版本（按版本号降序）。
     */
    List<KnowledgeBaseEntity> findByDocumentKeyAndActiveTrueOrderByVersionNoDesc(String documentKey);

    /**
     * 获取所有不同的服务标签（非空，按字母排序）
     */
    @Query("SELECT DISTINCT k.service FROM KnowledgeBaseEntity k WHERE k.service IS NOT NULL ORDER BY k.service")
    List<String> findAllServices();

    /**
     * 获取所有不同的环境标签（非空，按字母排序）
     */
    @Query("SELECT DISTINCT k.environment FROM KnowledgeBaseEntity k WHERE k.environment IS NOT NULL ORDER BY k.environment")
    List<String> findAllEnvironments();
}
