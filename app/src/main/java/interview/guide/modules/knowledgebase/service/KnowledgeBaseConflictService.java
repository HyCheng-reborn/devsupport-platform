package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.knowledgebase.listener.VectorizeStreamProducer;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 知识库版本冲突解决服务。
 * 当同一 (documentKey, normalizedVersionLabel) 下出现多个 active 行时，
 * 上传阶段会将新行标记为 conflict=true, active=false, vectorStatus=CONFLICT。
 * 本服务提供两种解决策略：adopt（采纳冲突版本）和 abandon（放弃冲突版本）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseConflictService {

  private final KnowledgeBaseRepository repository;
  private final KnowledgeBaseVectorService vectorService;
  private final VectorizeStreamProducer vectorizeStreamProducer;
  private final KnowledgeBaseParseService parseService;
  private final TransactionalExecutor transactionalExecutor;

  /**
   * 采纳冲突版本：停用当前 active 版本，激活冲突版本并发送向量化任务。
   *
   * <p>流程：
   * <ol>
   *   <li>校验冲突记录存在且 conflict=true</li>
   *   <li>在同一事务中：停用当前 active 版本，激活冲突版本（conflict=false, active=true）</li>
   *   <li>事务外：删除旧版本的向量数据</li>
   *   <li>从 S3 重新解析内容并发送向量化任务</li>
   * </ol>
   */
  public void adoptVersion(Long conflictKbId) {
    // 1. 加载冲突记录
    KnowledgeBaseEntity conflictEntity = repository.findById(conflictKbId)
      .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));

    if (!Boolean.TRUE.equals(conflictEntity.getConflict())) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "该记录不是冲突状态");
    }

    String documentKey = conflictEntity.getDocumentKey();
    log.info("开始采纳冲突版本: kbId={}, documentKey={}", conflictKbId, documentKey);

    // 2. 查找同 documentKey 的当前 active 版本
    List<KnowledgeBaseEntity> currentActive = repository
      .findByDocumentKeyAndActiveTrueOrderByVersionNoDesc(documentKey).stream()
      .filter(e -> !e.getId().equals(conflictKbId))
      .toList();

    // 3. 在事务中：停用旧版本 + 激活冲突版本
    transactionalExecutor.run(() -> {
      for (KnowledgeBaseEntity active : currentActive) {
        active.setActive(false);
        repository.save(active);
      }
      conflictEntity.setActive(true);
      conflictEntity.setConflict(false);
      conflictEntity.setVectorStatus(VectorStatus.PENDING);
      repository.save(conflictEntity);
    });

    // 4. 事务外：删除旧版本的向量
    for (KnowledgeBaseEntity old : currentActive) {
      vectorService.deleteByKnowledgeBaseId(old.getId());
    }

    // 5. 从 S3 重新解析内容并发送向量化任务
    String content = parseService.downloadAndParseContent(
      conflictEntity.getStorageKey(), conflictEntity.getOriginalFilename());
    if (content == null || content.trim().isEmpty()) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法从存储中重新解析文件内容");
    }
    vectorizeStreamProducer.sendVectorizeTask(conflictKbId, content);

    log.info("冲突版本已采纳: kbId={}, documentKey={}, 已停用旧版本数={}",
      conflictKbId, documentKey, currentActive.size());
  }

  /**
   * 放弃冲突版本：清除冲突标记，保持 active=false，不影响当前 active 版本。
   *
   * <p>流程：
   * <ol>
   *   <li>校验冲突记录存在且 conflict=true</li>
   *   <li>设置 conflict=false, vectorStatus=FAILED（表示不再向量化）</li>
   *   <li>不触碰当前 active 版本，不删除 S3 文件（可恢复，非破坏性）</li>
   * </ol>
   */
  @Transactional(rollbackFor = Exception.class)
  public void abandonVersion(Long conflictKbId) {
    KnowledgeBaseEntity conflictEntity = repository.findById(conflictKbId)
      .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));

    if (!Boolean.TRUE.equals(conflictEntity.getConflict())) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "该记录不是冲突状态");
    }

    conflictEntity.setConflict(false);
    conflictEntity.setVectorStatus(VectorStatus.FAILED);
    repository.save(conflictEntity);

    log.info("冲突版本已放弃: kbId={}, documentKey={}", conflictKbId, conflictEntity.getDocumentKey());
  }
}
