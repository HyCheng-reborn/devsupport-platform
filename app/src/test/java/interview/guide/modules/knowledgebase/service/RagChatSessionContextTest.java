package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.mapper.KnowledgeBaseMapper;
import interview.guide.infrastructure.mapper.RagChatMapper;
import interview.guide.modules.knowledgebase.model.ContextKbItem;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.RagChatDTO.CreateSessionRequest;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionDTO;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RagChatSessionService.createSession 上下文解析测试
 * 覆盖 service/environment 解析、与显式 kbIds 合并、空上下文错误等场景
 */
@DisplayName("RAG 聊天会话创建上下文测试")
@ExtendWith(MockitoExtension.class)
class RagChatSessionContextTest {

  @Mock private RagChatSessionRepository sessionRepository;
  @Mock private RagChatMessageRepository messageRepository;
  @Mock private KnowledgeBaseRepository knowledgeBaseRepository;
  @Mock private KnowledgeBaseQueryService queryService;
  @Mock private KnowledgeBaseListService listService;
  @Mock private RagChatMapper ragChatMapper;
  @Mock private KnowledgeBaseMapper knowledgeBaseMapper;
  @Mock private KnowledgeBaseQueryProperties queryProperties;

  private RagChatSessionService ragChatSessionService;

  @org.junit.jupiter.api.BeforeEach
  void setUp() {
    ragChatSessionService = new RagChatSessionService(
        sessionRepository, messageRepository, knowledgeBaseRepository,
        queryService, listService, ragChatMapper, knowledgeBaseMapper, queryProperties);
  }

  private static KnowledgeBaseEntity buildKb(Long id, String name) {
    KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
    kb.setId(id);
    kb.setName(name);
    kb.setOriginalFilename(name + ".md");
    kb.setUploadedAt(LocalDateTime.now());
    kb.setVectorStatus(VectorStatus.COMPLETED);
    kb.setChunkCount(1);
    return kb;
  }

  private static RagChatSessionEntity buildSession(Long id, Set<KnowledgeBaseEntity> kbs) {
    RagChatSessionEntity session = new RagChatSessionEntity();
    session.setId(id);
    session.setTitle("测试会话");
    session.setKnowledgeBases(kbs);
    session.setCreatedAt(LocalDateTime.now());
    session.setUpdatedAt(LocalDateTime.now());
    session.setMessageCount(0);
    return session;
  }

  @Nested
  @DisplayName("service/environment 解析为 KB IDs")
  class ContextResolution {

    @Test
    @DisplayName("仅 service 解析到匹配的 KB IDs")
    void shouldResolveKbIdsFromService() {
      // given
      List<ContextKbItem> contextItems = List.of(
          new ContextKbItem(1L, "支付文档A", "payment", "生产"),
          new ContextKbItem(2L, "支付文档B", "payment", "测试"));
      when(listService.resolveContext("payment", null)).thenReturn(contextItems);

      KnowledgeBaseEntity kb1 = buildKb(1L, "支付文档A");
      KnowledgeBaseEntity kb2 = buildKb(2L, "支付文档B");
      when(knowledgeBaseRepository.findAllById(anySet())).thenReturn(List.of(kb1, kb2));

      RagChatSessionEntity savedSession = buildSession(100L, new HashSet<>(List.of(kb1, kb2)));
      when(sessionRepository.save(any(RagChatSessionEntity.class))).thenReturn(savedSession);
      when(ragChatMapper.toSessionDTO(any())).thenReturn(new SessionDTO(100L, "测试会话", List.of(1L, 2L), LocalDateTime.now()));

      CreateSessionRequest request = new CreateSessionRequest(null, null, "payment", null);

      // when
      SessionDTO result = ragChatSessionService.createSession(request);

      // then
      assertThat(result).isNotNull();
      assertThat(result.id()).isEqualTo(100L);
      verify(listService).resolveContext("payment", null);
    }

    @Test
    @DisplayName("service + environment 组合解析")
    void shouldResolveKbIdsFromServiceAndEnvironment() {
      // given
      List<ContextKbItem> contextItems = List.of(
          new ContextKbItem(1L, "支付生产文档", "payment", "生产"));
      when(listService.resolveContext("payment", "生产")).thenReturn(contextItems);

      KnowledgeBaseEntity kb1 = buildKb(1L, "支付生产文档");
      when(knowledgeBaseRepository.findAllById(anySet())).thenReturn(List.of(kb1));

      RagChatSessionEntity savedSession = buildSession(100L, new HashSet<>(List.of(kb1)));
      when(sessionRepository.save(any(RagChatSessionEntity.class))).thenReturn(savedSession);
      when(ragChatMapper.toSessionDTO(any())).thenReturn(new SessionDTO(100L, "测试会话", List.of(1L), LocalDateTime.now()));

      CreateSessionRequest request = new CreateSessionRequest(null, null, "payment", "生产");

      // when
      SessionDTO result = ragChatSessionService.createSession(request);

      // then
      assertThat(result).isNotNull();
      verify(listService).resolveContext("payment", "生产");
    }
  }

  @Nested
  @DisplayName("与显式 kbIds 合并（并集）")
  class UnionWithExplicitIds {

    @Test
    @DisplayName("显式 kbIds + service 上下文取并集")
    void shouldUnionExplicitIdsWithContextResolvedIds() {
      // given
      List<ContextKbItem> contextItems = List.of(
          new ContextKbItem(2L, "支付文档B", "payment", "生产"));
      when(listService.resolveContext("payment", null)).thenReturn(contextItems);

      KnowledgeBaseEntity kb1 = buildKb(1L, "显式文档");
      KnowledgeBaseEntity kb2 = buildKb(2L, "支付文档B");
      when(knowledgeBaseRepository.findAllById(anySet())).thenReturn(List.of(kb1, kb2));

      RagChatSessionEntity savedSession = buildSession(100L, new HashSet<>(List.of(kb1, kb2)));
      when(sessionRepository.save(any(RagChatSessionEntity.class))).thenReturn(savedSession);
      when(ragChatMapper.toSessionDTO(any())).thenReturn(new SessionDTO(100L, "测试会话", List.of(1L, 2L), LocalDateTime.now()));

      // 显式指定 kbId=1，同时 service=payment 解析到 kbId=2
      CreateSessionRequest request = new CreateSessionRequest(List.of(1L), null, "payment", null);

      // when
      SessionDTO result = ragChatSessionService.createSession(request);

      // then
      assertThat(result).isNotNull();
      // 验证 findAllById 被调用时传入了两个 ID（并集）
      verify(knowledgeBaseRepository).findAllById(Set.of(1L, 2L));
    }

    @Test
    @DisplayName("显式 kbIds 与上下文解析有重叠时去重")
    void shouldDeduplicateWhenOverlap() {
      // given
      List<ContextKbItem> contextItems = List.of(
          new ContextKbItem(1L, "支付文档A", "payment", "生产"));
      when(listService.resolveContext("payment", null)).thenReturn(contextItems);

      KnowledgeBaseEntity kb1 = buildKb(1L, "支付文档A");
      when(knowledgeBaseRepository.findAllById(anySet())).thenReturn(List.of(kb1));

      RagChatSessionEntity savedSession = buildSession(100L, new HashSet<>(List.of(kb1)));
      when(sessionRepository.save(any(RagChatSessionEntity.class))).thenReturn(savedSession);
      when(ragChatMapper.toSessionDTO(any())).thenReturn(new SessionDTO(100L, "测试会话", List.of(1L), LocalDateTime.now()));

      // 显式指定 kbId=1，上下文也解析到 kbId=1
      CreateSessionRequest request = new CreateSessionRequest(List.of(1L), null, "payment", null);

      // when
      SessionDTO result = ragChatSessionService.createSession(request);

      // then
      assertThat(result).isNotNull();
      // 验证去重后只有一个 ID
      verify(knowledgeBaseRepository).findAllById(Set.of(1L));
    }
  }

  @Nested
  @DisplayName("空上下文错误处理")
  class EmptyContextError {

    @Test
    @DisplayName("无 kbIds 且无 service/environment 时抛出异常")
    void shouldThrowWhenNoKbIdsAndNoContext() {
      // given
      CreateSessionRequest request = new CreateSessionRequest(null, null, null, null);

      // when & then
      assertThatThrownBy(() -> ragChatSessionService.createSession(request))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("至少需要一个知识库");
    }

    @Test
    @DisplayName("空 kbIds 列表且无上下文时抛出异常")
    void shouldThrowWhenEmptyKbIdsAndNoContext() {
      // given
      CreateSessionRequest request = new CreateSessionRequest(List.of(), null, null, null);

      // when & then
      assertThatThrownBy(() -> ragChatSessionService.createSession(request))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("至少需要一个知识库");
    }

    @Test
    @DisplayName("service/environment 解析为空且无显式 kbIds 时抛出异常")
    void shouldThrowWhenContextResolvesEmptyAndNoExplicitIds() {
      // given
      when(listService.resolveContext("nonexistent", null)).thenReturn(List.of());

      CreateSessionRequest request = new CreateSessionRequest(null, null, "nonexistent", null);

      // when & then
      assertThatThrownBy(() -> ragChatSessionService.createSession(request))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("至少需要一个知识库");
    }
  }

  @Nested
  @DisplayName("向后兼容")
  class BackwardCompatibility {

    @Test
    @DisplayName("仅显式 kbIds（无 service/environment）正常工作")
    void shouldWorkWithExplicitIdsOnly() {
      // given
      KnowledgeBaseEntity kb1 = buildKb(1L, "文档A");
      KnowledgeBaseEntity kb2 = buildKb(2L, "文档B");
      when(knowledgeBaseRepository.findAllById(Set.of(1L, 2L))).thenReturn(List.of(kb1, kb2));

      RagChatSessionEntity savedSession = buildSession(100L, new HashSet<>(List.of(kb1, kb2)));
      when(sessionRepository.save(any(RagChatSessionEntity.class))).thenReturn(savedSession);
      when(ragChatMapper.toSessionDTO(any())).thenReturn(new SessionDTO(100L, "测试会话", List.of(1L, 2L), LocalDateTime.now()));

      CreateSessionRequest request = new CreateSessionRequest(List.of(1L, 2L), "我的会话", null, null);

      // when
      SessionDTO result = ragChatSessionService.createSession(request);

      // then
      assertThat(result).isNotNull();
      assertThat(result.id()).isEqualTo(100L);
      // 不应调用上下文解析（因为没有提供 service/environment）
      verify(listService, never()).resolveContext(any(), any());
    }
  }
}
