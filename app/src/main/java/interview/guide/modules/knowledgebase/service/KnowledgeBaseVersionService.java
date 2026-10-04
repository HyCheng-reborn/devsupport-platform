package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识库版本生命周期服务（阶段 2）。
 * 负责"新版本替换旧版本"与"手动停用"：先在 DB 置 active=false（检索范围立即排除），
 * 再物理删除旧版本向量（真正退出召回）。双保险保证旧版本不再进入检索。
 *
 * <p>向量删除走 {@link KnowledgeBaseVectorService#deleteByKnowledgeBaseId}（内部用 TransactionalExecutor），
 * 不放在业务事务内，符合"外部/向量写在事务外"的约束。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseVersionService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBasePersistenceService persistenceService;
    private final KnowledgeBaseVectorService vectorService;

    /**
     * 新版本落库后，停用并清理同一 documentKey 的旧启用版本。
     *
     * @param documentKey 逻辑文档标识
     * @param keepId      新激活版本的知识库 ID
     */
    public void retireSupersededVersions(String documentKey, Long keepId) {
        if (documentKey == null || documentKey.isBlank()) {
            return;
        }
        List<KnowledgeBaseEntity> superseded = knowledgeBaseRepository
            .findByDocumentKeyOrderByVersionNoDesc(documentKey).stream()
            .filter(kb -> !kb.getId().equals(keepId))
            .filter(kb -> !Boolean.FALSE.equals(kb.getActive()))
            .toList();

        // 1) 先停用（检索范围立即排除旧版本）
        persistenceService.retireOtherVersions(documentKey, keepId);

        // 2) 再物理删除旧版本向量（真正退出向量召回）
        for (KnowledgeBaseEntity old : superseded) {
            vectorService.deleteByKnowledgeBaseId(old.getId());
        }

        log.info("旧版本已停用并清理向量: documentKey={}, keepId={}, retiredIds={}",
            documentKey, keepId, superseded.stream().map(KnowledgeBaseEntity::getId).toList());
    }

    /**
     * 手动停用单个知识库：置 active=false 并删除其向量。
     */
    public void retire(Long kbId) {
        persistenceService.retire(kbId);
        vectorService.deleteByKnowledgeBaseId(kbId);
    }
}
