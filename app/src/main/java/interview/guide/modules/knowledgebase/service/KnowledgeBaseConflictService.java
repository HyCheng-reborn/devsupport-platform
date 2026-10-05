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
   *   <li>原子 CAS：tryStartAdopt(id) 将 CONFLICT→ADOPTING（条件 UPDATE）</li>
   *   <li>抢占成功：从 S3 下载文件并解析内容</li>
   *   <li>发送向量化任务到 Redis Stream（携带 adoptMode 标志）</li>
   *   <li>S3 或 Redis 失败时回滚 vectorStatus=CONFLICT 并抛出 BusinessException</li>
   *   <li>抢占失败（affected=0）：根据当前状态判断幂等或抛异常</li>
   * </ol>
   *
   * <p>向量化完成后，由 {@link interview.guide.modules.knowledgebase.listener.VectorizeStreamConsumer}
   * 负责将旧版本置为 active=false，并将本版本提升为 active=true, conflict=false。
   */
  public void adoptVersion(Long conflictKbId) {
    // 1. 原子 CAS：CONFLICT → ADOPTING（独立事务）
    int affected = transactionalExecutor.call(() -> repository.tryStartAdopt(conflictKbId));

    if (affected == 1) {
      // 抢占成功，执行 S3 解析 + Redis 发送
      doAdoptParseAndSend(conflictKbId);
      return;
    }

    // 2. affected == 0：重新读取实体判断原因
    KnowledgeBaseEntity entity = repository.findById(conflictKbId)
      .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));

    VectorStatus status = entity.getVectorStatus();
    if (status == VectorStatus.ADOPTING) {
      log.info("冲突版本已在采纳中（幂等），跳过: kbId={}", conflictKbId);
      return;
    }
    if (status == VectorStatus.COMPLETED && !Boolean.TRUE.equals(entity.getConflict())) {
      log.info("冲突版本已完成采纳（幂等），跳过: kbId={}", conflictKbId);
      return;
    }
    // 已放弃的版本无法采纳
    if (status == VectorStatus.ABANDONED) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "版本已放弃，无法采纳");
    }
    // 其他非法状态
    throw new BusinessException(ErrorCode.BAD_REQUEST, "非法状态，无法采用");
  }

  /**
   * 执行 S3 解析 + Redis 发送。失败时回滚状态到 CONFLICT 并抛出 BusinessException。
   */
  private void doAdoptParseAndSend(Long conflictKbId) {
    KnowledgeBaseEntity entity = repository.findById(conflictKbId)
      .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));
    String documentKey = entity.getDocumentKey();
    log.info("开始采纳冲突版本: kbId={}, documentKey={}", conflictKbId, documentKey);

    // S3 下载并解析
    String content;
    try {
      content = parseService.downloadAndParseContent(
        entity.getStorageKey(), entity.getOriginalFilename());
      if (content == null || content.trim().isEmpty()) {
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法从存储中重新解析文件内容");
      }
    } catch (Exception e) {
      log.error("采纳冲突版本时 S3 下载失败: kbId={}", conflictKbId, e);
      recoverToConflict(entity, "S3 下载失败: " + e.getMessage());
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
        "S3 下载失败: " + e.getMessage());
    }

    // Redis Stream 发送
    try {
      vectorizeStreamProducer.sendVectorizeTask(conflictKbId, content, true);
    } catch (Exception e) {
      log.error("采纳冲突版本时 Redis 发送失败: kbId={}", conflictKbId, e);
      recoverToConflict(entity, "Redis 发送失败: " + e.getMessage());
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
        "Redis 发送失败: " + e.getMessage());
    }

    log.info("冲突版本采纳任务已发送: kbId={}, documentKey={}", conflictKbId, documentKey);
  }

  /**
   * 回滚状态到 CONFLICT 并设置 vectorError。使用 REQUIRES_NEW 确保独立提交，
   * 不受外层 BusinessException 传播影响。如果回滚本身失败，仅记录日志。
   */
  private void recoverToConflict(KnowledgeBaseEntity entity, String errorMessage) {
    try {
      transactionalExecutor.runRequiresNew(() -> {
        entity.setVectorStatus(VectorStatus.CONFLICT);
        entity.setVectorError(truncateError(errorMessage));
        repository.save(entity);
      });
    } catch (Exception recoveryEx) {
      log.error("回滚 CONFLICT 状态失败: kbId={}, 原始错误: {}",
        entity.getId(), errorMessage, recoveryEx);
    }
  }

  private static String truncateError(String error) {
    if (error == null) {
      return null;
    }
    return error.length() > 500 ? error.substring(0, 500) : error;
  }

  /**
   * 放弃冲突版本：原子 CAS + 状态回退判断。
   *
   * <p>流程：
   * <ol>
   *   <li>原子 CAS：tryStartAbandon(id) 将 CONFLICT→ABANDONED（条件 UPDATE，与 tryStartAdopt 互斥）</li>
   *   <li>抢占成功（affected=1）：直接返回</li>
   *   <li>抢占失败（affected=0）：重新读取实体判断原因</li>
   *   <li>vectorStatus=ABANDONED → 幂等返回；ADOPTING → 抛异常；COMPLETED+conflict=false → 已完成；其他 → 非法状态</li>
   * </ol>
   */
  public void abandonVersion(Long conflictKbId) {
    // 1. 原子 CAS：CONFLICT → ABANDONED（独立事务）
    int affected = transactionalExecutor.call(() -> repository.tryStartAbandon(conflictKbId));

    if (affected == 1) {
      log.info("冲突版本已放弃: kbId={}", conflictKbId);
      return;
    }

    // 2. affected == 0：重新读取实体判断原因
    KnowledgeBaseEntity entity = repository.findById(conflictKbId)
      .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));

    VectorStatus status = entity.getVectorStatus();
    if (status == VectorStatus.ABANDONED) {
      log.info("冲突版本已放弃（幂等），跳过: kbId={}", conflictKbId);
      return;
    }
    if (status == VectorStatus.ADOPTING) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "版本正在采用中，无法放弃");
    }
    if (status == VectorStatus.COMPLETED && !Boolean.TRUE.equals(entity.getConflict())) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "版本已完成采纳，无法放弃");
    }
    throw new BusinessException(ErrorCode.BAD_REQUEST, "非法状态，无法放弃");
  }
}
