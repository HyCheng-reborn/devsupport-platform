package interview.guide.modules.caselibrary.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.mapper.CaseLibraryMapper;
import interview.guide.modules.caselibrary.model.CaseAuditLogEntity;
import interview.guide.modules.caselibrary.model.CaseDTO;
import interview.guide.modules.caselibrary.model.CaseEntity;
import interview.guide.modules.caselibrary.model.CaseStatus;
import interview.guide.modules.caselibrary.repository.CaseAuditLogRepository;
import interview.guide.modules.caselibrary.repository.CaseRepository;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 案例草稿服务
 */
@Service
@RequiredArgsConstructor
public class CaseDraftService {

  private final CaseRepository caseRepository;
  private final CaseAuditLogRepository auditLogRepository;
  private final RagChatSessionRepository sessionRepository;
  private final RagChatMessageRepository messageRepository;
  private final CaseLibraryMapper caseLibraryMapper;

  /**
   * 从会话消息创建案例草稿
   *
   * @param sessionId 会话ID
   * @param messageId 消息ID
   * @return 案例DTO
   */
  @Transactional
  public CaseDTO createDraft(Long sessionId, Long messageId) {
    // 1. 校验会话存在
    RagChatSessionEntity session = sessionRepository.findById(sessionId)
      .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

    // 2. 校验消息存在且为 ASSISTANT 类型
    RagChatMessageEntity message = messageRepository.findById(messageId)
      .orElseThrow(() -> new BusinessException(ErrorCode.CASE_MESSAGE_NOT_FOUND));

    if (message.getType() != RagChatMessageEntity.MessageType.ASSISTANT) {
      throw new BusinessException(ErrorCode.CASE_MESSAGE_NOT_FOUND, "消息不是 ASSISTANT 类型");
    }

    // 3. 创建案例实体
    CaseEntity caseEntity = CaseEntity.builder()
      .title("从会话生成案例")
      .aiGeneratedContent(message.getContent())
      .userConfirmedContent("")
      .status(CaseStatus.DRAFT)
      .sourceSessionId(sessionId)
      .sourceMessageId(messageId)
      .sourceChunkIds(message.getSourceChunkIds())
      .active(false)
      .build();

    CaseEntity saved = caseRepository.save(caseEntity);

    // 4. 写入审计日志
    CaseAuditLogEntity auditLog = CaseAuditLogEntity.builder()
      .caseId(saved.getId())
      .action("CREATED")
      .newStatus(CaseStatus.DRAFT.name())
      .remark("从会话 " + sessionId + " 消息 " + messageId + " 创建")
      .build();
    auditLogRepository.save(auditLog);

    // 5. 返回 DTO
    return caseLibraryMapper.toDTO(saved);
  }
}
