package interview.guide.modules.caselibrary.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.mapper.CaseLibraryMapper;
import interview.guide.modules.caselibrary.model.CaseAuditLogEntity;
import interview.guide.modules.caselibrary.model.CaseDTO;
import interview.guide.modules.caselibrary.model.CaseEntity;
import interview.guide.modules.caselibrary.model.CaseRequests.CaseUpdateRequest;
import interview.guide.modules.caselibrary.model.CaseStatus;
import interview.guide.modules.caselibrary.repository.CaseAuditLogRepository;
import interview.guide.modules.caselibrary.repository.CaseRepository;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 案例库服务单元测试
 * 覆盖 CaseDraftService、CaseReviewService、CaseLifecycleService
 */
@DisplayName("案例库服务单元测试")
@ExtendWith(MockitoExtension.class)
class CaseLibraryServiceTest {

  @Mock private CaseRepository caseRepository;
  @Mock private CaseAuditLogRepository auditLogRepository;
  @Mock private RagChatSessionRepository sessionRepository;
  @Mock private RagChatMessageRepository messageRepository;
  @Mock private CaseLibraryMapper caseLibraryMapper;
  @Mock private LlmProviderRegistry llmProviderRegistry;
  @Mock private org.springframework.ai.vectorstore.VectorStore vectorStore;
  @Mock private VectorRepository vectorRepository;
  @Mock private TransactionalExecutor transactionalExecutor;

  private CaseDraftService draftService;
  private CaseReviewService reviewService;
  private CaseLifecycleService lifecycleService;

  @BeforeEach
  void setUp() {
    draftService = new CaseDraftService(
        caseRepository, auditLogRepository, sessionRepository, messageRepository, caseLibraryMapper);
    reviewService = new CaseReviewService(caseRepository, auditLogRepository, caseLibraryMapper, llmProviderRegistry, vectorStore);
    // transactionalExecutor mock: 直接执行传入的 Runnable/Supplier
    lenient().when(transactionalExecutor.call(any())).thenAnswer(inv -> {
      java.util.function.Supplier<?> supplier = inv.getArgument(0);
      return supplier.get();
    });
    lenient().doAnswer(inv -> {
      Runnable action = inv.getArgument(0);
      action.run();
      return null;
    }).when(transactionalExecutor).runRequiresNew(any());
    lifecycleService = new CaseLifecycleService(caseRepository, auditLogRepository, caseLibraryMapper, vectorRepository, transactionalExecutor);
  }

  // ─────────── 辅助方法 ───────────

  private static RagChatSessionEntity buildSession(Long id) {
    RagChatSessionEntity session = new RagChatSessionEntity();
    session.setId(id);
    session.setTitle("测试会话");
    session.setStatus(RagChatSessionEntity.SessionStatus.ACTIVE);
    return session;
  }

  private static RagChatMessageEntity buildMessage(Long id, RagChatMessageEntity.MessageType type, String content) {
    RagChatMessageEntity message = new RagChatMessageEntity();
    message.setId(id);
    message.setType(type);
    message.setContent(content);
    message.setMessageOrder(0);
    return message;
  }

  private static CaseEntity buildCase(Long id, CaseStatus status) {
    return CaseEntity.builder()
        .id(id)
        .title("测试案例")
        .status(status)
        .active(status == CaseStatus.PUBLISHED)
        .versionNo(1)
        .createdAt(LocalDateTime.now())
        .updatedAt(LocalDateTime.now())
        .build();
  }

  private static CaseDTO buildCaseDTO(Long id, CaseStatus status) {
    return CaseDTO.builder()
        .id(id)
        .title("测试案例")
        .status(status)
        .active(status == CaseStatus.PUBLISHED)
        .versionNo(1)
        .build();
  }

  // ─────────── 草稿生成 ───────────

  @Nested
  @DisplayName("草稿生成")
  class DraftCreation {

    @Test
    @DisplayName("从有效会话消息创建草稿")
    void createDraft_withValidSessionAndMessage_shouldCreateDraft() {
      RagChatSessionEntity session = buildSession(1L);
      RagChatMessageEntity message = buildMessage(2L, RagChatMessageEntity.MessageType.ASSISTANT, "AI 回答内容");
      CaseEntity savedCase = buildCase(10L, CaseStatus.DRAFT);
      savedCase.setAiGeneratedContent("AI 回答内容");
      savedCase.setSourceSessionId(1L);
      savedCase.setSourceMessageId(2L);
      CaseDTO expectedDTO = buildCaseDTO(10L, CaseStatus.DRAFT);

      when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
      when(messageRepository.findById(2L)).thenReturn(Optional.of(message));
      when(caseRepository.save(any(CaseEntity.class))).thenReturn(savedCase);
      when(caseLibraryMapper.toDTO(savedCase)).thenReturn(expectedDTO);

      CaseDTO result = draftService.createDraft(1L, 2L);

      assertThat(result).isNotNull();
      assertThat(result.status()).isEqualTo(CaseStatus.DRAFT);
      assertThat(result.id()).isEqualTo(10L);

      ArgumentCaptor<CaseEntity> captor = ArgumentCaptor.forClass(CaseEntity.class);
      verify(caseRepository).save(captor.capture());
      CaseEntity captured = captor.getValue();
      assertThat(captured.getAiGeneratedContent()).isEqualTo("AI 回答内容");
      assertThat(captured.getSourceSessionId()).isEqualTo(1L);
      assertThat(captured.getSourceMessageId()).isEqualTo(2L);
      assertThat(captured.getStatus()).isEqualTo(CaseStatus.DRAFT);
      assertThat(captured.getActive()).isFalse();
    }

    @Test
    @DisplayName("会话不存在时抛异常")
    void createDraft_withNonExistentSession_shouldThrow() {
      when(sessionRepository.findById(999L)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> draftService.createDraft(999L, 1L))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("会话不存在");

      verify(caseRepository, never()).save(any());
    }

    @Test
    @DisplayName("消息不存在时抛异常")
    void createDraft_withNonExistentMessage_shouldThrow() {
      RagChatSessionEntity session = buildSession(1L);
      when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
      when(messageRepository.findById(999L)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> draftService.createDraft(1L, 999L))
          .isInstanceOf(BusinessException.class);

      verify(caseRepository, never()).save(any());
    }

    @Test
    @DisplayName("消息不是 ASSISTANT 类型时抛异常")
    void createDraft_withUserMessage_shouldThrow() {
      RagChatSessionEntity session = buildSession(1L);
      RagChatMessageEntity userMessage = buildMessage(2L, RagChatMessageEntity.MessageType.USER, "用户问题");
      when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
      when(messageRepository.findById(2L)).thenReturn(Optional.of(userMessage));

      assertThatThrownBy(() -> draftService.createDraft(1L, 2L))
          .isInstanceOf(BusinessException.class);

      verify(caseRepository, never()).save(any());
    }

    @Test
    @DisplayName("草稿创建后写入 CREATED 审计日志")
    void createDraft_shouldWriteAuditLog() {
      RagChatSessionEntity session = buildSession(1L);
      RagChatMessageEntity message = buildMessage(2L, RagChatMessageEntity.MessageType.ASSISTANT, "AI 回答");
      CaseEntity savedCase = buildCase(10L, CaseStatus.DRAFT);
      CaseDTO dto = buildCaseDTO(10L, CaseStatus.DRAFT);

      when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
      when(messageRepository.findById(2L)).thenReturn(Optional.of(message));
      when(caseRepository.save(any(CaseEntity.class))).thenReturn(savedCase);
      when(caseLibraryMapper.toDTO(savedCase)).thenReturn(dto);

      draftService.createDraft(1L, 2L);

      ArgumentCaptor<CaseAuditLogEntity> logCaptor = ArgumentCaptor.forClass(CaseAuditLogEntity.class);
      verify(auditLogRepository).save(logCaptor.capture());
      CaseAuditLogEntity log = logCaptor.getValue();
      assertThat(log.getCaseId()).isEqualTo(10L);
      assertThat(log.getAction()).isEqualTo("CREATED");
      assertThat(log.getNewStatus()).isEqualTo(CaseStatus.DRAFT.name());
      assertThat(log.getRemark()).contains("1").contains("2");
    }
  }

  // ─────────── 状态机转换 ───────────

  @Nested
  @DisplayName("状态机转换")
  class StateMachine {

    @Test
    @DisplayName("DRAFT → PENDING_REVIEW 合法")
    void submit_draft_shouldSucceed() {
      CaseEntity caseEntity = buildCase(1L, CaseStatus.DRAFT);
      CaseEntity savedEntity = buildCase(1L, CaseStatus.PENDING_REVIEW);
      CaseDTO expectedDTO = buildCaseDTO(1L, CaseStatus.PENDING_REVIEW);

      when(caseRepository.findById(1L)).thenReturn(Optional.of(caseEntity));
      when(caseRepository.save(any(CaseEntity.class))).thenReturn(savedEntity);
      when(caseLibraryMapper.toDTO(savedEntity)).thenReturn(expectedDTO);

      CaseDTO result = reviewService.submitForReview(1L, "tester");

      assertThat(result.status()).isEqualTo(CaseStatus.PENDING_REVIEW);

      ArgumentCaptor<CaseAuditLogEntity> logCaptor = ArgumentCaptor.forClass(CaseAuditLogEntity.class);
      verify(auditLogRepository).save(logCaptor.capture());
      assertThat(logCaptor.getValue().getAction()).isEqualTo("SUBMITTED");
    }

    @Test
    @DisplayName("PENDING_REVIEW → PUBLISHED 合法 + active=true")
    void approve_shouldPublishAndSetActive() {
      CaseEntity caseEntity = buildCase(1L, CaseStatus.PENDING_REVIEW);
      CaseEntity savedEntity = buildCase(1L, CaseStatus.PUBLISHED);
      savedEntity.setActive(true);
      CaseDTO expectedDTO = buildCaseDTO(1L, CaseStatus.PUBLISHED);
      expectedDTO = CaseDTO.builder()
          .id(1L).title("测试案例").status(CaseStatus.PUBLISHED).active(true).versionNo(1).build();

      when(caseRepository.findById(1L)).thenReturn(Optional.of(caseEntity));
      when(caseRepository.save(any(CaseEntity.class))).thenReturn(savedEntity);
      when(caseLibraryMapper.toDTO(savedEntity)).thenReturn(expectedDTO);

      CaseDTO result = reviewService.approve(1L, "tester");

      assertThat(result.status()).isEqualTo(CaseStatus.PUBLISHED);
      assertThat(result.active()).isTrue();

      ArgumentCaptor<CaseEntity> entityCaptor = ArgumentCaptor.forClass(CaseEntity.class);
      verify(caseRepository).save(entityCaptor.capture());
      assertThat(entityCaptor.getValue().getActive()).isTrue();

      ArgumentCaptor<CaseAuditLogEntity> logCaptor = ArgumentCaptor.forClass(CaseAuditLogEntity.class);
      verify(auditLogRepository).save(logCaptor.capture());
      assertThat(logCaptor.getValue().getAction()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("PENDING_REVIEW → REJECTED 合法")
    void reject_shouldSucceed() {
      CaseEntity caseEntity = buildCase(1L, CaseStatus.PENDING_REVIEW);
      CaseEntity savedEntity = buildCase(1L, CaseStatus.REJECTED);
      CaseDTO expectedDTO = buildCaseDTO(1L, CaseStatus.REJECTED);

      when(caseRepository.findById(1L)).thenReturn(Optional.of(caseEntity));
      when(caseRepository.save(any(CaseEntity.class))).thenReturn(savedEntity);
      when(caseLibraryMapper.toDTO(savedEntity)).thenReturn(expectedDTO);

      CaseDTO result = reviewService.reject(1L, "内容不够详细", "tester");

      assertThat(result.status()).isEqualTo(CaseStatus.REJECTED);

      ArgumentCaptor<CaseAuditLogEntity> logCaptor = ArgumentCaptor.forClass(CaseAuditLogEntity.class);
      verify(auditLogRepository).save(logCaptor.capture());
      CaseAuditLogEntity log = logCaptor.getValue();
      assertThat(log.getAction()).isEqualTo("REJECTED");
      assertThat(log.getRemark()).isEqualTo("内容不够详细");
    }

    @Test
    @DisplayName("REJECTED 编辑后回退为 DRAFT")
    void update_rejectedCase_shouldResetToDraft() {
      CaseEntity caseEntity = buildCase(1L, CaseStatus.REJECTED);
      CaseEntity savedEntity = buildCase(1L, CaseStatus.DRAFT);
      savedEntity.setTitle("更新后的标题");
      CaseDTO expectedDTO = buildCaseDTO(1L, CaseStatus.DRAFT);

      when(caseRepository.findById(1L)).thenReturn(Optional.of(caseEntity));
      when(caseRepository.save(any(CaseEntity.class))).thenReturn(savedEntity);
      when(caseLibraryMapper.toDTO(savedEntity)).thenReturn(expectedDTO);

      CaseUpdateRequest request = new CaseUpdateRequest(
          "更新后的标题", null, null, null, null, null, null, null, null, null);
      CaseDTO result = reviewService.updateCase(1L, request, "tester");

      assertThat(result.status()).isEqualTo(CaseStatus.DRAFT);

      ArgumentCaptor<CaseAuditLogEntity> logCaptor = ArgumentCaptor.forClass(CaseAuditLogEntity.class);
      verify(auditLogRepository).save(logCaptor.capture());
      CaseAuditLogEntity log = logCaptor.getValue();
      assertThat(log.getAction()).isEqualTo("EDITED");
      assertThat(log.getPreviousStatus()).isEqualTo(CaseStatus.REJECTED.name());
      assertThat(log.getNewStatus()).isEqualTo(CaseStatus.DRAFT.name());
    }

    @Test
    @DisplayName("PUBLISHED → DEPRECATED + active=false")
    void deprecate_shouldSetInactive() {
      CaseEntity caseEntity = buildCase(1L, CaseStatus.PUBLISHED);
      caseEntity.setActive(true);
      CaseEntity savedEntity = buildCase(1L, CaseStatus.DEPRECATED);
      savedEntity.setActive(false);
      CaseDTO expectedDTO = CaseDTO.builder()
          .id(1L).title("测试案例").status(CaseStatus.DEPRECATED).active(false).versionNo(1).build();

      when(caseRepository.findById(1L)).thenReturn(Optional.of(caseEntity));
      when(caseRepository.save(any(CaseEntity.class))).thenReturn(savedEntity);
      when(caseLibraryMapper.toDTO(savedEntity)).thenReturn(expectedDTO);

      CaseDTO result = lifecycleService.deprecate(1L, "tester");

      assertThat(result.status()).isEqualTo(CaseStatus.DEPRECATED);
      assertThat(result.active()).isFalse();

      ArgumentCaptor<CaseEntity> entityCaptor = ArgumentCaptor.forClass(CaseEntity.class);
      verify(caseRepository).save(entityCaptor.capture());
      assertThat(entityCaptor.getValue().getActive()).isFalse();

      ArgumentCaptor<CaseAuditLogEntity> logCaptor = ArgumentCaptor.forClass(CaseAuditLogEntity.class);
      verify(auditLogRepository).save(logCaptor.capture());
      assertThat(logCaptor.getValue().getAction()).isEqualTo("DEPRECATED");
    }

    @Test
    @DisplayName("DRAFT → PUBLISHED 非法转换抛异常")
    void approve_draft_shouldThrow() {
      CaseEntity caseEntity = buildCase(1L, CaseStatus.DRAFT);
      when(caseRepository.findById(1L)).thenReturn(Optional.of(caseEntity));

      assertThatThrownBy(() -> reviewService.approve(1L, "tester"))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("只有 PENDING_REVIEW 状态可以审核通过");

      verify(caseRepository, never()).save(any());
      verify(auditLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("DEPRECATED → 任何状态 非法（deprecate 非 PUBLISHED 抛异常）")
    void deprecate_fromDeprecated_shouldThrow() {
      CaseEntity caseEntity = buildCase(1L, CaseStatus.DEPRECATED);
      caseEntity.setActive(false);
      when(caseRepository.findById(1L)).thenReturn(Optional.of(caseEntity));

      assertThatThrownBy(() -> lifecycleService.deprecate(1L, "tester"))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("只有 PUBLISHED 状态可以废弃");

      verify(caseRepository, never()).save(any());
      verify(auditLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("非 DRAFT 状态提交审核抛异常")
    void submit_nonDraft_shouldThrow() {
      CaseEntity caseEntity = buildCase(1L, CaseStatus.PUBLISHED);
      when(caseRepository.findById(1L)).thenReturn(Optional.of(caseEntity));

      assertThatThrownBy(() -> reviewService.submitForReview(1L, "tester"))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("只有 DRAFT 状态可以提交审核");

      verify(caseRepository, never()).save(any());
    }

    @Test
    @DisplayName("非 PENDING_REVIEW 状态拒绝抛异常")
    void reject_nonPendingReview_shouldThrow() {
      CaseEntity caseEntity = buildCase(1L, CaseStatus.DRAFT);
      when(caseRepository.findById(1L)).thenReturn(Optional.of(caseEntity));

      assertThatThrownBy(() -> reviewService.reject(1L, "原因", "tester"))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("只有 PENDING_REVIEW 状态可以拒绝");

      verify(caseRepository, never()).save(any());
    }

    @Test
    @DisplayName("PUBLISHED 状态编辑抛异常")
    void update_publishedCase_shouldThrow() {
      CaseEntity caseEntity = buildCase(1L, CaseStatus.PUBLISHED);
      when(caseRepository.findById(1L)).thenReturn(Optional.of(caseEntity));

      CaseUpdateRequest request = new CaseUpdateRequest(
          "新标题", null, null, null, null, null, null, null, null, null);

      assertThatThrownBy(() -> reviewService.updateCase(1L, request, "tester"))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("不可编辑");

      verify(caseRepository, never()).save(any());
    }

    @Test
    @DisplayName("案例不存在时 submitForReview 抛异常")
    void submit_nonExistentCase_shouldThrow() {
      when(caseRepository.findById(999L)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> reviewService.submitForReview(999L, "tester"))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("案例不存在");
    }
  }

  // ─────────── 案例列表与详情 ───────────

  @Nested
  @DisplayName("案例列表与详情")
  class ListAndGet {

    @Test
    @DisplayName("按状态过滤")
    void listByStatus() {
      CaseEntity draftCase = buildCase(1L, CaseStatus.DRAFT);
      List<CaseEntity> entities = List.of(draftCase);
      List<CaseDTO> dtos = List.of(buildCaseDTO(1L, CaseStatus.DRAFT));

      when(caseRepository.findByStatus(CaseStatus.DRAFT)).thenReturn(entities);
      when(caseLibraryMapper.toDTOList(entities)).thenReturn(dtos);

      List<CaseDTO> result = lifecycleService.listCases("DRAFT", null);

      assertThat(result).hasSize(1);
      assertThat(result.get(0).status()).isEqualTo(CaseStatus.DRAFT);
      verify(caseRepository).findByStatus(CaseStatus.DRAFT);
    }

    @Test
    @DisplayName("按服务过滤")
    void listByService() {
      CaseEntity case1 = buildCase(1L, CaseStatus.PUBLISHED);
      case1.setService("payment-gateway");
      List<CaseEntity> entities = List.of(case1);
      List<CaseDTO> dtos = List.of(CaseDTO.builder()
          .id(1L).title("测试案例").status(CaseStatus.PUBLISHED).service("payment-gateway").versionNo(1).build());

      when(caseRepository.findByService("payment-gateway")).thenReturn(entities);
      when(caseLibraryMapper.toDTOList(entities)).thenReturn(dtos);

      List<CaseDTO> result = lifecycleService.listCases(null, "payment-gateway");

      assertThat(result).hasSize(1);
      verify(caseRepository).findByService("payment-gateway");
    }

    @Test
    @DisplayName("按状态和服务同时过滤")
    void listByStatusAndService() {
      CaseEntity case1 = buildCase(1L, CaseStatus.PUBLISHED);
      case1.setService("payment-gateway");
      List<CaseEntity> entities = List.of(case1);
      List<CaseDTO> dtos = List.of(CaseDTO.builder()
          .id(1L).title("测试案例").status(CaseStatus.PUBLISHED)
          .service("payment-gateway").versionNo(1).build());

      when(caseRepository.findByStatus(CaseStatus.PUBLISHED)).thenReturn(entities);
      when(caseLibraryMapper.toDTOList(entities)).thenReturn(dtos);

      List<CaseDTO> result = lifecycleService.listCases("PUBLISHED", "payment-gateway");

      assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("无过滤条件时返回全部")
    void listAll() {
      CaseEntity case1 = buildCase(1L, CaseStatus.DRAFT);
      CaseEntity case2 = buildCase(2L, CaseStatus.PUBLISHED);
      List<CaseEntity> entities = List.of(case1, case2);
      List<CaseDTO> dtos = List.of(
          buildCaseDTO(1L, CaseStatus.DRAFT), buildCaseDTO(2L, CaseStatus.PUBLISHED));

      when(caseRepository.findAll()).thenReturn(entities);
      when(caseLibraryMapper.toDTOList(entities)).thenReturn(dtos);

      List<CaseDTO> result = lifecycleService.listCases(null, null);

      assertThat(result).hasSize(2);
      verify(caseRepository).findAll();
    }

    @Test
    @DisplayName("案例不存在时抛异常")
    void getCase_notFound_shouldThrow() {
      when(caseRepository.findById(999L)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> lifecycleService.getCase(999L))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("案例不存在");
    }

    @Test
    @DisplayName("案例存在时返回详情")
    void getCase_found() {
      CaseEntity caseEntity = buildCase(1L, CaseStatus.PUBLISHED);
      CaseDTO expectedDTO = buildCaseDTO(1L, CaseStatus.PUBLISHED);

      when(caseRepository.findById(1L)).thenReturn(Optional.of(caseEntity));
      when(caseLibraryMapper.toDTO(caseEntity)).thenReturn(expectedDTO);

      CaseDTO result = lifecycleService.getCase(1L);

      assertThat(result).isNotNull();
      assertThat(result.id()).isEqualTo(1L);
    }
  }
}
