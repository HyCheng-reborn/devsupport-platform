package interview.guide.modules.caselibrary.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.mapper.CaseLibraryMapper;
import interview.guide.modules.caselibrary.model.CaseAuditLogEntity;
import interview.guide.modules.caselibrary.model.CaseDTO;
import interview.guide.modules.caselibrary.model.CaseEntity;
import interview.guide.modules.caselibrary.model.CaseRequests.CaseUpdateRequest;
import interview.guide.modules.caselibrary.model.CaseStatus;
import interview.guide.modules.caselibrary.repository.CaseAuditLogRepository;
import interview.guide.modules.caselibrary.repository.CaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 案例审核服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseReviewService {

  /** 向量化批量大小，与知识库向量化保持一致（DashScope API 限制 batch <= 10） */
  private static final int MAX_BATCH_SIZE = 10;

  private final CaseRepository caseRepository;
  private final CaseAuditLogRepository auditLogRepository;
  private final CaseLibraryMapper caseLibraryMapper;
  private final LlmProviderRegistry llmProviderRegistry;
  private final VectorStore vectorStore;

  /**
   * 更新案例（仅 DRAFT 或 REJECTED 状态可编辑）
   *
   * @param caseId   案例ID
   * @param request  更新请求
   * @param operator 操作者（来自 X-Operator header，默认 anonymous）
   * @return 案例DTO
   */
  @Transactional
  public CaseDTO updateCase(Long caseId, CaseUpdateRequest request, String operator) {
    CaseEntity caseEntity = caseRepository.findById(caseId)
      .orElseThrow(() -> new BusinessException(ErrorCode.CASE_NOT_FOUND));

    CaseStatus currentStatus = caseEntity.getStatus();
    if (currentStatus != CaseStatus.DRAFT && currentStatus != CaseStatus.REJECTED) {
      throw new BusinessException(ErrorCode.CASE_NOT_EDITABLE,
        "当前状态 " + currentStatus + " 不可编辑");
    }

    // 更新字段
    if (request.title() != null) {
      caseEntity.setTitle(request.title());
    }
    if (request.problemDescription() != null) {
      caseEntity.setProblemDescription(request.problemDescription());
    }
    if (request.rootCause() != null) {
      caseEntity.setRootCause(request.rootCause());
    }
    if (request.resolutionSteps() != null) {
      caseEntity.setResolutionSteps(request.resolutionSteps());
    }
    if (request.resolutionResult() != null) {
      caseEntity.setResolutionResult(request.resolutionResult());
    }
    if (request.affectedVersions() != null) {
      caseEntity.setAffectedVersions(request.affectedVersions());
    }
    if (request.environment() != null) {
      caseEntity.setEnvironment(request.environment());
    }
    if (request.service() != null) {
      caseEntity.setService(request.service());
    }
    if (request.aiGeneratedContent() != null) {
      caseEntity.setAiGeneratedContent(request.aiGeneratedContent());
    }
    if (request.userConfirmedContent() != null) {
      caseEntity.setUserConfirmedContent(request.userConfirmedContent());
    }

    // 如果从 REJECTED 编辑，状态回到 DRAFT
    CaseStatus previousStatus = caseEntity.getStatus();
    if (previousStatus == CaseStatus.REJECTED) {
      caseEntity.setStatus(CaseStatus.DRAFT);
    }

    CaseEntity saved = caseRepository.save(caseEntity);

    // 写入审计日志
    CaseAuditLogEntity auditLog = CaseAuditLogEntity.builder()
      .caseId(caseId)
      .action("EDITED")
      .previousStatus(previousStatus.name())
      .newStatus(saved.getStatus().name())
      .operator(operator)
      .build();
    auditLogRepository.save(auditLog);

    return caseLibraryMapper.toDTO(saved);
  }

  /**
   * 提交审核（DRAFT → PENDING_REVIEW）
   *
   * @param caseId   案例ID
   * @param operator 操作者（来自 X-Operator header，默认 anonymous）
   * @return 案例DTO
   */
  @Transactional
  public CaseDTO submitForReview(Long caseId, String operator) {
    CaseEntity caseEntity = caseRepository.findById(caseId)
      .orElseThrow(() -> new BusinessException(ErrorCode.CASE_NOT_FOUND));

    if (caseEntity.getStatus() != CaseStatus.DRAFT) {
      throw new BusinessException(ErrorCode.CASE_INVALID_OPERATION,
        "只有 DRAFT 状态可以提交审核，当前状态: " + caseEntity.getStatus());
    }

    caseEntity.setStatus(CaseStatus.PENDING_REVIEW);
    CaseEntity saved = caseRepository.save(caseEntity);

    // 写入审计日志
    CaseAuditLogEntity auditLog = CaseAuditLogEntity.builder()
      .caseId(caseId)
      .action("SUBMITTED")
      .previousStatus(CaseStatus.DRAFT.name())
      .newStatus(CaseStatus.PENDING_REVIEW.name())
      .operator(operator)
      .build();
    auditLogRepository.save(auditLog);

    return caseLibraryMapper.toDTO(saved);
  }

  /**
   * 审核通过（PENDING_REVIEW → PUBLISHED）
   * <p>
   * 通过后同步向量化案例内容，写入 vector_store 供检索使用。
   *
   * @param caseId   案例ID
   * @param operator 操作者（来自 X-Operator header，默认 anonymous）
   * @return 案例DTO
   */
  @Transactional
  public CaseDTO approve(Long caseId, String operator) {
    CaseEntity caseEntity = caseRepository.findById(caseId)
      .orElseThrow(() -> new BusinessException(ErrorCode.CASE_NOT_FOUND));

    if (caseEntity.getStatus() != CaseStatus.PENDING_REVIEW) {
      throw new BusinessException(ErrorCode.CASE_INVALID_OPERATION,
        "只有 PENDING_REVIEW 状态可以审核通过，当前状态: " + caseEntity.getStatus());
    }

    caseEntity.setStatus(CaseStatus.PUBLISHED);
    caseEntity.setActive(true);
    CaseEntity saved = caseRepository.save(caseEntity);

    // 写入审计日志
    CaseAuditLogEntity auditLog = CaseAuditLogEntity.builder()
      .caseId(caseId)
      .action("APPROVED")
      .previousStatus(CaseStatus.PENDING_REVIEW.name())
      .newStatus(CaseStatus.PUBLISHED.name())
      .operator(operator)
      .remark("审核通过，案例已激活")
      .build();
    auditLogRepository.save(auditLog);

    // 向量化案例内容，写入 vector_store 供检索
    vectorizeCaseContent(saved);

    return caseLibraryMapper.toDTO(saved);
  }

  /**
   * 审核拒绝（PENDING_REVIEW → REJECTED）
   *
   * @param caseId   案例ID
   * @param remark   拒绝原因
   * @param operator 操作者（来自 X-Operator header，默认 anonymous）
   * @return 案例DTO
   */
  @Transactional
  public CaseDTO reject(Long caseId, String remark, String operator) {
    CaseEntity caseEntity = caseRepository.findById(caseId)
      .orElseThrow(() -> new BusinessException(ErrorCode.CASE_NOT_FOUND));

    if (caseEntity.getStatus() != CaseStatus.PENDING_REVIEW) {
      throw new BusinessException(ErrorCode.CASE_INVALID_OPERATION,
        "只有 PENDING_REVIEW 状态可以拒绝，当前状态: " + caseEntity.getStatus());
    }

    caseEntity.setStatus(CaseStatus.REJECTED);
    CaseEntity saved = caseRepository.save(caseEntity);

    // 写入审计日志
    CaseAuditLogEntity auditLog = CaseAuditLogEntity.builder()
      .caseId(caseId)
      .action("REJECTED")
      .previousStatus(CaseStatus.PENDING_REVIEW.name())
      .newStatus(CaseStatus.REJECTED.name())
      .operator(operator)
      .remark(remark)
      .build();
    auditLogRepository.save(auditLog);

    return caseLibraryMapper.toDTO(saved);
  }

  // ========== 私有方法 ==========

  /**
   * 将案例内容向量化并存储到 vector_store。
   * <p>
   * 拼接 problemDescription + rootCause + resolutionSteps + resolutionResult（跳过 null），
   * 使用 TokenTextSplitter 分块后通过 EmbeddingModel 生成向量写入数据库。
   * metadata 包含 source_type=CASE、case_id、case_id_long、service、environment。
   */
  private void vectorizeCaseContent(CaseEntity caseEntity) {
    String content = concatenateCaseContent(caseEntity);
    if (content.isBlank()) {
      log.warn("案例内容为空，跳过向量化: caseId={}", caseEntity.getId());
      return;
    }

    try {
      log.info("开始向量化案例: caseId={}, contentLength={}", caseEntity.getId(), content.length());

      TextSplitter textSplitter = TokenTextSplitter.builder().build();
      List<Document> chunks = textSplitter.apply(List.of(new Document(content)));

      // 为每个 chunk 添加案例 metadata
      for (Document chunk : chunks) {
        Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
        metadata.put("source_type", "CASE");
        metadata.put("case_id", caseEntity.getId().toString());
        metadata.put("case_id_long", caseEntity.getId());
        if (caseEntity.getService() != null) {
          metadata.put("service", caseEntity.getService());
        }
        if (caseEntity.getEnvironment() != null) {
          metadata.put("environment", caseEntity.getEnvironment());
        }
        chunk.getMetadata().putAll(metadata);
      }

      // 分批向量化并存储（DashScope API 限制 batch size <= 10）
      int totalChunks = chunks.size();
      int batchCount = (totalChunks + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE;
      for (int i = 0; i < batchCount; i++) {
        int start = i * MAX_BATCH_SIZE;
        int end = Math.min(start + MAX_BATCH_SIZE, totalChunks);
        List<Document> batch = chunks.subList(start, end);
        vectorStore.add(batch);
      }

      log.info("案例向量化完成: caseId={}, chunks={}, batches={}",
        caseEntity.getId(), totalChunks, batchCount);
    } catch (Exception e) {
      log.error("案例向量化失败: caseId={}, error={}", caseEntity.getId(), e.getMessage(), e);
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
        "案例向量化失败: " + e.getMessage());
    }
  }

  /**
   * 拼接案例内容：problemDescription + rootCause + resolutionSteps + resolutionResult，跳过 null。
   */
  private String concatenateCaseContent(CaseEntity caseEntity) {
    StringBuilder sb = new StringBuilder();
    appendSection(sb, "问题描述", caseEntity.getProblemDescription());
    appendSection(sb, "根本原因", caseEntity.getRootCause());
    appendSection(sb, "解决步骤", caseEntity.getResolutionSteps());
    appendSection(sb, "解决结果", caseEntity.getResolutionResult());
    return sb.toString().trim();
  }

  private void appendSection(StringBuilder sb, String heading, String content) {
    if (content != null && !content.isBlank()) {
      if (!sb.isEmpty()) {
        sb.append("\n\n");
      }
      sb.append("## ").append(heading).append("\n\n").append(content);
    }
  }
}
