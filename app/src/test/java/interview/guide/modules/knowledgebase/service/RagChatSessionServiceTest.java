package interview.guide.modules.knowledgebase.service;

import interview.guide.infrastructure.mapper.KnowledgeBaseMapper;
import interview.guide.infrastructure.mapper.RagChatMapper;
import interview.guide.modules.knowledgebase.model.MessageStatus;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * RagChatSessionService 单元测试 — 聚焦 completeStreamMessage 的状态与来源持久化
 */
@DisplayName("RAG 聊天会话服务测试")
@ExtendWith(MockitoExtension.class)
class RagChatSessionServiceTest {

  @Mock
  private RagChatSessionRepository sessionRepository;
  @Mock
  private RagChatMessageRepository messageRepository;
  @Mock
  private KnowledgeBaseRepository knowledgeBaseRepository;
  @Mock
  private KnowledgeBaseQueryService queryService;
  @Mock
  private RagChatMapper ragChatMapper;
  @Mock
  private KnowledgeBaseMapper knowledgeBaseMapper;
  @Mock
  private KnowledgeBaseQueryProperties queryProperties;

  @InjectMocks
  private RagChatSessionService ragChatSessionService;

  // ========== 辅助方法 ==========

  private RagChatMessageEntity createMessage() {
    RagChatMessageEntity msg = new RagChatMessageEntity();
    msg.setId(100L);
    msg.setContent("");
    msg.setCompleted(false);
    msg.setType(RagChatMessageEntity.MessageType.ASSISTANT);
    msg.setMessageOrder(1);
    return msg;
  }

  // ========== completeStreamMessage 测试 ==========

  @Test
  @DisplayName("completeStreamMessage_正常完成_状态为COMPLETED")
  void completeStreamMessage_completed_statusIsCOMPLETED() {
    RagChatMessageEntity msg = createMessage();
    when(messageRepository.findById(100L)).thenReturn(Optional.of(msg));
    when(messageRepository.save(any(RagChatMessageEntity.class))).thenReturn(msg);

    String sourcesJson = "[{\"kbId\":1,\"documentName\":\"test.pdf\"}]";
    ragChatSessionService.completeStreamMessage(100L, "回答内容", MessageStatus.COMPLETED, sourcesJson);

    assertThat(msg.getContent()).isEqualTo("回答内容");
    assertThat(msg.getCompleted()).isTrue();
    assertThat(msg.getStatus()).isEqualTo(MessageStatus.COMPLETED);
    assertThat(msg.getSourcesJson()).isEqualTo(sourcesJson);
    verify(messageRepository).save(msg);
  }

  @Test
  @DisplayName("completeStreamMessage_模型失败_状态为MODEL_FAILED")
  void completeStreamMessage_modelFailed_statusIsMODEL_FAILED() {
    RagChatMessageEntity msg = createMessage();
    when(messageRepository.findById(100L)).thenReturn(Optional.of(msg));
    when(messageRepository.save(any(RagChatMessageEntity.class))).thenReturn(msg);

    ragChatSessionService.completeStreamMessage(100L, "错误信息", MessageStatus.MODEL_FAILED, null);

    assertThat(msg.getStatus()).isEqualTo(MessageStatus.MODEL_FAILED);
    assertThat(msg.getCompleted()).isTrue();
    assertThat(msg.getContent()).isEqualTo("错误信息");
    verify(messageRepository).save(msg);
  }

  @Test
  @DisplayName("completeStreamMessage_客户端断开_状态为CLIENT_DISCONNECTED")
  void completeStreamMessage_clientDisconnected_statusIsCLIENT_DISCONNECTED() {
    RagChatMessageEntity msg = createMessage();
    when(messageRepository.findById(100L)).thenReturn(Optional.of(msg));
    when(messageRepository.save(any(RagChatMessageEntity.class))).thenReturn(msg);

    ragChatSessionService.completeStreamMessage(100L, "部分内容", MessageStatus.CLIENT_DISCONNECTED, "[]");

    assertThat(msg.getStatus()).isEqualTo(MessageStatus.CLIENT_DISCONNECTED);
    assertThat(msg.getCompleted()).isTrue();
    assertThat(msg.getSourcesJson()).isEqualTo("[]");
    verify(messageRepository).save(msg);
  }

  @Test
  @DisplayName("completeStreamMessage_零命中_状态为NO_RESULTS")
  void completeStreamMessage_noResults_statusIsNO_RESULTS() {
    RagChatMessageEntity msg = createMessage();
    when(messageRepository.findById(100L)).thenReturn(Optional.of(msg));
    when(messageRepository.save(any(RagChatMessageEntity.class))).thenReturn(msg);

    ragChatSessionService.completeStreamMessage(100L, "未找到相关信息", MessageStatus.NO_RESULTS, "[]");

    assertThat(msg.getStatus()).isEqualTo(MessageStatus.NO_RESULTS);
    assertThat(msg.getCompleted()).isTrue();
    assertThat(msg.getSourcesJson()).isEqualTo("[]");
    verify(messageRepository).save(msg);
  }
}
