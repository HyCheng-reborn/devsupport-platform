package interview.guide.modules.knowledgebase.service;

import interview.guide.infrastructure.mapper.KnowledgeBaseMapper;
import interview.guide.infrastructure.mapper.RagChatMapper;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.MessageStatus;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity;
import interview.guide.modules.knowledgebase.model.SourceReference;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import interview.guide.modules.caselibrary.repository.CaseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
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
  private CaseRepository caseRepository;
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

  /**
   * 构建带 kb_id metadata 和 score 的 Document
   */
  private Document createDoc(String text, Long kbId, Double score) {
    Map<String, Object> metadata = new HashMap<>();
    if (kbId != null) {
      metadata.put("kb_id", kbId);
    }
    Document doc = new Document(text, metadata);
    if (score != null) {
      setScore(doc, score);
    }
    return doc;
  }

  /**
   * 通过反射设置 Document 的 score（Spring AI 2.0.0 未暴露 setScore 方法）
   */
  private static void setScore(Document doc, Double score) {
    try {
      Field scoreField = Document.class.getDeclaredField("score");
      scoreField.setAccessible(true);
      scoreField.set(doc, score);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException("Failed to set Document score via reflection", e);
    }
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

  // ========== buildSourceReferences 来源提取测试 ==========

  @Nested
  @DisplayName("buildSourceReferences 来源提取测试")
  class BuildSourceReferencesTests {

    @Test
    @DisplayName("buildSourceReferences_从Document提取来源_保持顺序与截断")
    void extractsSourceReferencesFromDocuments() {
      List<Document> docs = List.of(
          createDoc("这是一段来自简历的文本内容，用于测试来源引用功能", 10L, 0.92),
          createDoc("A".repeat(300), 20L, 0.75) // 长文本，测试截断
      );

      KnowledgeBaseEntity kb10 = new KnowledgeBaseEntity();
      kb10.setId(10L);
      kb10.setOriginalFilename("张三_简历.pdf");

      KnowledgeBaseEntity kb20 = new KnowledgeBaseEntity();
      kb20.setId(20L);
      kb20.setOriginalFilename("李四_简历.docx");

      when(knowledgeBaseRepository.findAllById(anySet())).thenReturn(List.of(kb10, kb20));

      List<SourceReference> refs = ragChatSessionService.buildSourceReferences(docs);

      assertThat(refs).hasSize(2);

      // 第一个来源
      assertThat(refs.get(0).kbId()).isEqualTo(10L);
      assertThat(refs.get(0).documentName()).isEqualTo("张三_简历.pdf");
      assertThat(refs.get(0).contentSnippet()).isEqualTo("这是一段来自简历的文本内容，用于测试来源引用功能");
      assertThat(refs.get(0).score()).isEqualTo(0.92);

      // 第二个来源 — 验证截断
      assertThat(refs.get(1).kbId()).isEqualTo(20L);
      assertThat(refs.get(1).documentName()).isEqualTo("李四_简历.docx");
      assertThat(refs.get(1).contentSnippet()).hasSize(203); // 200 + "..."
      assertThat(refs.get(1).contentSnippet()).endsWith("...");
      assertThat(refs.get(1).score()).isEqualTo(0.75);
    }

    @Test
    @DisplayName("buildSourceReferences_未知kb回退未知文档且来源不丢")
    void unknownKb_fallsBackToUnknownName() {
      List<Document> docs = List.of(createDoc("一段内容", 99L, 0.5));
      when(knowledgeBaseRepository.findAllById(anySet())).thenReturn(List.of());

      List<SourceReference> refs = ragChatSessionService.buildSourceReferences(docs);

      assertThat(refs).hasSize(1);
      assertThat(refs.get(0).kbId()).isEqualTo(99L);
      assertThat(refs.get(0).documentName()).isEqualTo("未知文档");
    }

    @Test
    @DisplayName("buildSourceReferences_透传 service/environment 标签；KB 存在但标签为 NULL 时来源标签也为 NULL")
    void carriesTagsAndNullTags() {
      List<Document> docs = List.of(
          createDoc("有标签的内容", 10L, 0.9),
          createDoc("无标签的内容", 20L, 0.8)
      );

      KnowledgeBaseEntity tagged = new KnowledgeBaseEntity();
      tagged.setId(10L);
      tagged.setOriginalFilename("支付网关说明.md");
      tagged.setService("支付网关");
      tagged.setEnvironment("生产");

      KnowledgeBaseEntity untagged = new KnowledgeBaseEntity();
      untagged.setId(20L);
      untagged.setOriginalFilename("空白文档.md");
      // service/environment 保持默认 null → 命中 KB 但标签为 NULL

      when(knowledgeBaseRepository.findAllById(anySet())).thenReturn(List.of(tagged, untagged));

      List<SourceReference> refs = ragChatSessionService.buildSourceReferences(docs);

      assertThat(refs).hasSize(2);
      // 顺序保持：第一条带标签
      assertThat(refs.get(0).documentName()).isEqualTo("支付网关说明.md");
      assertThat(refs.get(0).service()).isEqualTo("支付网关");
      assertThat(refs.get(0).environment()).isEqualTo("生产");
      // 第二条命中 KB 但标签 NULL → 来源标签为 null（前端渲染为“无标签”）
      assertThat(refs.get(1).documentName()).isEqualTo("空白文档.md");
      assertThat(refs.get(1).service()).isNull();
      assertThat(refs.get(1).environment()).isNull();
    }

    @Test
    @DisplayName("buildSourceReferences_包含版本字段 versionLabel/versionNo/documentKey")
    void includesVersionFieldsFromKnowledgeBase() {
      List<Document> docs = List.of(createDoc("版本相关内容", 10L, 0.88));

      KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
      kb.setId(10L);
      kb.setOriginalFilename("支付网关_v2.1.pdf");
      kb.setVersionLabel("v2.1");
      kb.setVersionNo(3);
      kb.setDocumentKey("abc123");

      when(knowledgeBaseRepository.findAllById(anySet())).thenReturn(List.of(kb));

      List<SourceReference> refs = ragChatSessionService.buildSourceReferences(docs);

      assertThat(refs).hasSize(1);
      assertThat(refs.get(0).versionLabel()).isEqualTo("v2.1");
      assertThat(refs.get(0).versionNo()).isEqualTo(3);
      assertThat(refs.get(0).documentKey()).isEqualTo("abc123");
    }

    @Test
    @DisplayName("buildSourceReferences_KB不存在时版本字段为null")
    void versionFieldsNullWhenKbMissing() {
      List<Document> docs = List.of(createDoc("内容", 99L, 0.5));
      when(knowledgeBaseRepository.findAllById(anySet())).thenReturn(List.of());

      List<SourceReference> refs = ragChatSessionService.buildSourceReferences(docs);

      assertThat(refs).hasSize(1);
      assertThat(refs.get(0).versionLabel()).isNull();
      assertThat(refs.get(0).versionNo()).isNull();
      assertThat(refs.get(0).documentKey()).isNull();
    }

    @Test
    @DisplayName("buildSourceReferences_空列表返回空")
    void emptyDocs_returnsEmpty() {
      assertThat(ragChatSessionService.buildSourceReferences(List.of())).isEmpty();
    }

    @Test
    @DisplayName("buildSourceReferences_null列表返回空")
    void nullDocs_returnsEmpty() {
      assertThat(ragChatSessionService.buildSourceReferences(null)).isEmpty();
    }
  }

  // ========== sectionTitle 提取测试 ==========

  @Nested
  @DisplayName("buildSourceReferences sectionTitle 提取测试")
  class SectionTitleExtractionTests {

    @Test
    @DisplayName("sectionTitle_文档内容以# Title开头_提取Title")
    void h1Heading_extractsTitle() {
      List<Document> docs = List.of(createDoc("# 支付网关配置\n\n后端端口是 8080。", 10L, 0.9));

      KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
      kb.setId(10L);
      kb.setOriginalFilename("支付网关.md");
      when(knowledgeBaseRepository.findAllById(anySet())).thenReturn(List.of(kb));

      List<SourceReference> refs = ragChatSessionService.buildSourceReferences(docs);

      assertThat(refs).hasSize(1);
      assertThat(refs.get(0).sectionTitle()).isEqualTo("支付网关配置");
    }

    @Test
    @DisplayName("sectionTitle_文档内容以## Section开头_提取Section")
    void h2Heading_extractsSection() {
      List<Document> docs = List.of(createDoc("## 部署步骤\n\n1. 拉取镜像\n2. 启动容器", 10L, 0.85));

      KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
      kb.setId(10L);
      kb.setOriginalFilename("部署手册.md");
      when(knowledgeBaseRepository.findAllById(anySet())).thenReturn(List.of(kb));

      List<SourceReference> refs = ragChatSessionService.buildSourceReferences(docs);

      assertThat(refs).hasSize(1);
      assertThat(refs.get(0).sectionTitle()).isEqualTo("部署步骤");
    }

    @Test
    @DisplayName("sectionTitle_文档内容以### SubSection开头_提取SubSection")
    void h3Heading_extractsSubSection() {
      List<Document> docs = List.of(createDoc("### 子章节\n\n详细内容如下", 10L, 0.88));

      KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
      kb.setId(10L);
      kb.setOriginalFilename("技术文档.md");
      when(knowledgeBaseRepository.findAllById(anySet())).thenReturn(List.of(kb));

      List<SourceReference> refs = ragChatSessionService.buildSourceReferences(docs);

      assertThat(refs).hasSize(1);
      assertThat(refs.get(0).sectionTitle()).isEqualTo("子章节");
    }

    @Test
    @DisplayName("sectionTitle_文档内容无标题_返回null")
    void noHeading_returnsNull() {
      List<Document> docs = List.of(createDoc("这是一段没有标题的普通文本内容。", 10L, 0.8));

      KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
      kb.setId(10L);
      kb.setOriginalFilename("说明文档.md");
      when(knowledgeBaseRepository.findAllById(anySet())).thenReturn(List.of(kb));

      List<SourceReference> refs = ragChatSessionService.buildSourceReferences(docs);

      assertThat(refs).hasSize(1);
      assertThat(refs.get(0).sectionTitle()).isNull();
    }
  }
}
