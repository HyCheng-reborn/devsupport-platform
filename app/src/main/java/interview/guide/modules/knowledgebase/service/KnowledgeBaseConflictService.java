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
   * 采纳冲突版本（异步状态机）。
   *
   * <p>流程：
   * <ol>
   *   <li>校验冲突记录存在且 conflict=true</li>
   *   <li>幂等判断：已 ADOPTING 直接返回；已 ABANDONED 抛异常</li>
   *   <li>设置 vectorStatus=ADOPTING（不触碰旧 active 版本）</li>
   *   <li>从 S3 下载文件并解析内容</li>
   *   <li>发送向量化任务到 Redis Stream（携带 adoptMode 标志）</li>
   *   <li>S3 或 Redis 失败时回滚 vectorStatus=CONFLICT</li>
   * </ol>
   *
   * <p>向量化完成后，由 {@link interview.guide.modules.knowledgebase.listener.VectorizeStreamConsumer}
   * 负责将旧版本置为 active=false，并将本版本提升为 active=true, conflict=false。
   */
  public void adoptVersion(Long conflictKbId) {
    // 1. 加载冲突记录
    KnowledgeBaseEntity conflictEntity = repository.findById(conflictKbId)
      .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));

    if (!Boolean.TRUE.equals(conflictEntity.getConflict())) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "该记录不是冲突状态");
    }

    // 2. 幂等判断
    VectorStatus currentStatus = conflictEntity.getVectorStatus();
    if (currentStatus == VectorStatus.ADOPTING) {
      log.info("冲突版本已在采纳中，跳过: kbId={}", conflictKbId);
      return;
    }
    if (currentStatus == VectorStatus.ABANDONED) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "该冲突版本已被放弃，无法采纳");
    }

    String documentKey = conflictEntity.getDocumentKey();
    log.info("开始采纳冲突版本: kbId={}, documentKey={}", conflictKbId, documentKey);

    // 3. 设置 vectorStatus=ADOPTING（不触碰旧 active 版本）
    transactionalExecutor.run(() -> {
      conflictEntity.setVectorStatus(VectorStatus.ADOPTING);
      conflictEntity.setVectorError(null);
      repository.save(conflictEntity);
    });

    // 4. 从 S3 下载文件并解析内容
    String content;
    try {
      content = parseService.downloadAndParseContent(
        conflictEntity.getStorageKey(), conflictEntity.getOriginalFilename());
      if (content == null || content.trim().isEmpty()) {
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法从存储中重新解析文件内容");
      }
    } catch (Exception e) {
      log.error("采纳冲突版本时 S3 下载失败，回滚状态: kbId={}", conflictKbId, e);
      transactionalExecutor.run(() -> {
        conflictEntity.setVectorStatus(VectorStatus.CONFLICT);
        conflictEntity.setVectorError(truncateError("S3 下载失败: " + e.getMessage()));
        repository.save(conflictEntity);
      });
      return;
    }

    // 5. 发送向量化任务到 Redis Stream（携带 adoptMode 标志）
    try {
      vectorizeStreamProducer.sendVectorizeTask(conflictKbId, content, true);
    } catch (Exception e) {
      log.error("采纳冲突版本时 Redis 发送失败，回滚状态: kbId={}", conflictKbId, e);
      transactionalExecutor.run(() -> {
        conflictEntity.setVectorStatus(VectorStatus.CONFLICT);
        conflictEntity.setVectorError(truncateError("Redis 发送失败: " + e.getMessage()));
        repository.save(conflictEntity);
      });
      return;
    }

    log.info("冲突版本采纳任务已发送: kbId={}, documentKey={}", conflictKbId, documentKey);
  }

  private static String truncateError(String error) {
    if (error == null) {
      return null;
    }
    return error.length() > 500 ? error.substring(0, 500) : error;
  }

  /**
   * 放弃冲突版本：设置 ABANDONED 状态，不影响当前 active 版本。
   *
   * <p>流程：
   * <ol>
   *   <li>校验冲突记录存在</li>
   *   <li>幂等判断：已 ABANDONED 直接返回；ADOPTING 中抛异常</li>
   *   <li>设置 active=false, conflict=false, vectorStatus=ABANDONED</li>
   *   <li>不触碰当前 active 版本，不删除 S3 文件（可恢复，非破坏性）</li>
   * </ol>
   */
  @Transactional(rollbackFor = Exception.class)
  public void abandonVersion(Long conflictKbId) {
    KnowledgeBaseEntity conflictEntity = repository.findById(conflictKbId)
      .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));

    // 幂等判断
    VectorStatus currentStatus = conflictEntity.getVectorStatus();
    if (currentStatus == VectorStatus.ABANDONED) {
      log.info("冲突版本已放弃，跳过: kbId={}", conflictKbId);
      return;
    }
    if (currentStatus == VectorStatus.ADOPTING) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "该冲突版本正在采纳中，无法放弃");
    }

    conflictEntity.setActive(false);
    conflictEntity.setConflict(false);
    conflictEntity.setVectorStatus(VectorStatus.ABANDONED);
    repository.save(conflictEntity);

    log.info("冲突版本已放弃: kbId={}, documentKey={}", conflictKbId, conflictEntity.getDocumentKey());
  }
}
