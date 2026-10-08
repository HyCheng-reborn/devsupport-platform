package interview.guide.modules.caselibrary;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.caselibrary.model.CaseAuditLogEntity;
import interview.guide.modules.caselibrary.model.CaseDTO;
import interview.guide.modules.caselibrary.model.CaseStatus;
import interview.guide.modules.caselibrary.repository.CaseAuditLogRepository;
import interview.guide.modules.caselibrary.repository.CaseRepository;
import interview.guide.modules.caselibrary.service.CaseDraftService;
import interview.guide.modules.caselibrary.service.CaseLifecycleService;
import interview.guide.modules.caselibrary.service.CaseReviewService;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import interview.guide.modules.caselibrary.model.CaseRequests.CaseUpdateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 案例库集成测试
 * 使用 Testcontainers 启动隔离的 PostgreSQL + Redis，验证端到端案例生命周期
 */
@DisplayName("案例库集成测试（Testcontainers 隔离）")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"demo", "test"})
@Import(CaseLibraryIntegrationTest.DeterministicEmbeddingConfig.class)
@DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)
class CaseLibraryIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
      DockerImageName.parse("pgvector/pgvector:pg16"))
      .withDatabaseName("case_library_test")
      .withUsername("test")
      .withPassword("test");

  @Container
  static final GenericContainer<?> redis = new GenericContainer<>(
      DockerImageName.parse("redis:7-alpine"))
      .withExposedPorts(6379);

  @DynamicPropertySource
  static void props(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    registry.add("spring.jpa.properties.hibernate.dialect",
        () -> "org.hibernate.dialect.PostgreSQLDialect");
    registry.add("spring.redis.redisson.config", () ->
        "singleServerConfig:\n  address: \"redis://" +
            redis.getHost() + ":" + redis.getMappedPort(6379) + "\"\n  database: 0");
  }

  @MockitoBean
  LlmProviderRegistry llmProviderRegistry;

  @Autowired CaseDraftService draftService;
  @Autowired CaseReviewService reviewService;
  @Autowired CaseLifecycleService lifecycleService;
  @Autowired CaseRepository caseRepository;
  @Autowired CaseAuditLogRepository auditLogRepository;
  @Autowired RagChatSessionRepository sessionRepository;
  @Autowired RagChatMessageRepository messageRepository;
  @Autowired VectorStore vectorStore;
  @Autowired JdbcTemplate jdbcTemplate;

  // ─────────── 确定性 Embedding 配置 ───────────

  static float[] deterministicVector() {
    float[] v = new float[1024];
    v[1023] = 1f;
    return v;
  }

  @TestConfiguration
  static class DeterministicEmbeddingConfig {
    @Bean
    @Primary
    EmbeddingModel deterministicEmbeddingModel() {
      return new EmbeddingModel() {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
          List<Embedding> results = new ArrayList<>();
          List<String> instructions = request.getInstructions();
          for (int i = 0; i < instructions.size(); i++) {
            results.add(new Embedding(deterministicVector(), i));
          }
          return new EmbeddingResponse(results);
        }

        @Override
        public float[] embed(Document document) {
          return deterministicVector().clone();
        }

        @Override
        public int dimensions() {
          return 1024;
        }
      };
    }
  }

  // ─────────── 辅助方法 ───────────

  private RagChatSessionEntity createSession(String title) {
    RagChatSessionEntity session = new RagChatSessionEntity();
    session.setTitle(title);
    session.setStatus(RagChatSessionEntity.SessionStatus.ACTIVE);
    return sessionRepository.save(session);
  }

  private RagChatMessageEntity createMessage(RagChatSessionEntity session,
                                              RagChatMessageEntity.MessageType type,
                                              String content, int order) {
    RagChatMessageEntity message = new RagChatMessageEntity();
    message.setSession(session);
    message.setType(type);
    message.setContent(content);
    message.setMessageOrder(order);
    return messageRepository.save(message);
  }

  /**
   * 创建草稿并通过 updateCase 填充可检索内容字段（problemDescription 等），
   * 因为 createDraft 只设置 aiGeneratedContent，而向量化拼接的是另外 4 个字段。
   */
  private CaseDTO createDraftWithContent(String uniqueContent) {
    RagChatSessionEntity session = createSession("检索测试-" + uniqueContent);
    RagChatMessageEntity msg = createMessage(session, RagChatMessageEntity.MessageType.ASSISTANT,
        uniqueContent, 0);

    CaseDTO draft = draftService.createDraft(session.getId(), msg.getId());

    CaseUpdateRequest updateReq = new CaseUpdateRequest(
        "案例标题-" + uniqueContent,
        "问题描述：" + uniqueContent,
        "根因分析：" + uniqueContent,
        "解决步骤：" + uniqueContent,
        "解决结果：" + uniqueContent,
        null, null, null, null, null
    );
    return reviewService.updateCase(draft.id(), updateReq, "tester");
  }

  private CaseDTO createApproveCase(String uniqueContent) {
    CaseDTO draft = createDraftWithContent(uniqueContent);
    reviewService.submitForReview(draft.id(), "tester");
    return reviewService.approve(draft.id(), "tester");
  }

  private int vectorCountForCase(Long caseId) {
    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM vector_store " +
            "WHERE metadata->>'source_type' = 'CASE' " +
            "AND metadata->>'case_id_long' IS NOT NULL " +
            "AND (metadata->>'case_id_long')::bigint = ?",
        Integer.class, caseId);
    return count == null ? 0 : count;
  }

  private List<Document> searchCaseVectors(String query) {
    SearchRequest request = SearchRequest.builder()
        .query(query)
        .topK(10)
        .filterExpression("source_type == 'CASE'")
        .build();
    return vectorStore.similaritySearch(request);
  }

  // ─────────── 端到端案例生命周期 ───────────

  @Nested
  @DisplayName("端到端案例生命周期")
  class FullLifecycle {

    @Test
    @DisplayName("创建会话和消息 → 生成草稿 → 提交 → 批准 → 废弃")
    void fullLifecycle() {
      // 1. 创建会话和 ASSISTANT 消息
      RagChatSessionEntity session = createSession("集成测试会话");
      RagChatMessageEntity userMsg = createMessage(session, RagChatMessageEntity.MessageType.USER,
          "Spring Boot 启动报错怎么办？", 0);
      RagChatMessageEntity assistantMsg = createMessage(session, RagChatMessageEntity.MessageType.ASSISTANT,
          "常见原因：1. 端口被占用 2. Bean 循环依赖 3. 配置文件格式错误。请检查具体错误日志。", 1);

      // 2. 从消息创建草稿 → DRAFT
      CaseDTO draft = draftService.createDraft(session.getId(), assistantMsg.getId());
      assertThat(draft.id()).isNotNull();
      assertThat(draft.status()).isEqualTo(CaseStatus.DRAFT);
      assertThat(draft.active()).isFalse();
      assertThat(draft.sourceSessionId()).isEqualTo(session.getId());
      assertThat(draft.sourceMessageId()).isEqualTo(assistantMsg.getId());

      // 3. 提交审核 → PENDING_REVIEW
      CaseDTO pending = reviewService.submitForReview(draft.id(), "tester");
      assertThat(pending.status()).isEqualTo(CaseStatus.PENDING_REVIEW);

      // 4. 审核通过 → PUBLISHED + active=true
      CaseDTO published = reviewService.approve(draft.id(), "tester");
      assertThat(published.status()).isEqualTo(CaseStatus.PUBLISHED);
      assertThat(published.active()).isTrue();

      // 5. 验证审计日志：CREATED, SUBMITTED, APPROVED
      List<CaseAuditLogEntity> auditLogs = auditLogRepository
          .findByCaseIdOrderByCreatedAtDesc(draft.id());
      assertThat(auditLogs).hasSize(3);
      assertThat(auditLogs).extracting(CaseAuditLogEntity::getAction)
          .containsExactly("APPROVED", "SUBMITTED", "CREATED");

      // 6. 废弃 → DEPRECATED + active=false
      CaseDTO deprecated = lifecycleService.deprecate(draft.id(), "tester");
      assertThat(deprecated.status()).isEqualTo(CaseStatus.DEPRECATED);
      assertThat(deprecated.active()).isFalse();

      // 7. 验证审计日志：DEPRECATED
      List<CaseAuditLogEntity> allLogs = auditLogRepository
          .findByCaseIdOrderByCreatedAtDesc(draft.id());
      assertThat(allLogs).hasSize(4);
      assertThat(allLogs).extracting(CaseAuditLogEntity::getAction)
          .containsExactly("DEPRECATED", "APPROVED", "SUBMITTED", "CREATED");
    }

    @Test
    @DisplayName("拒绝后编辑再重新提交流程")
    void rejectEditResubmitLifecycle() {
      // 创建草稿
      RagChatSessionEntity session = createSession("拒绝重投测试会话");
      RagChatMessageEntity msg = createMessage(session, RagChatMessageEntity.MessageType.ASSISTANT,
          "Redis 连接超时的排查步骤", 0);

      CaseDTO draft = draftService.createDraft(session.getId(), msg.getId());
      assertThat(draft.status()).isEqualTo(CaseStatus.DRAFT);

      // 提交 → 拒绝
      reviewService.submitForReview(draft.id(), "tester");
      CaseDTO rejected = reviewService.reject(draft.id(), "内容不够详细，请补充更多细节", "tester");
      assertThat(rejected.status()).isEqualTo(CaseStatus.REJECTED);

      // 编辑 REJECTED 案例 → 状态回退为 DRAFT
      var updateReq = new interview.guide.modules.caselibrary.model.CaseRequests.CaseUpdateRequest(
          "Redis 连接超时排查指南",
          "生产环境 Redis 连接频繁超时",
          "网络抖动 + 连接池配置不当",
          "1. 检查网络 2. 调整连接池 3. 增加重试",
          "问题解决",
          "Spring Boot 3.x",
          "production",
          "redis-service",
          null,
          "用户确认的内容"
      );
      CaseDTO updated = reviewService.updateCase(draft.id(), updateReq, "tester");
      assertThat(updated.status()).isEqualTo(CaseStatus.DRAFT);
      assertThat(updated.title()).isEqualTo("Redis 连接超时排查指南");
      assertThat(updated.service()).isEqualTo("redis-service");

      // 重新提交
      CaseDTO resubmitted = reviewService.submitForReview(draft.id(), "tester");
      assertThat(resubmitted.status()).isEqualTo(CaseStatus.PENDING_REVIEW);

      // 验证审计日志
      List<CaseAuditLogEntity> logs = auditLogRepository
          .findByCaseIdOrderByCreatedAtDesc(draft.id());
      assertThat(logs).extracting(CaseAuditLogEntity::getAction)
          .containsExactly("SUBMITTED", "EDITED", "REJECTED", "SUBMITTED", "CREATED");
    }
  }

  // ─────────── 草稿内容提取 ───────────

  @Nested
  @DisplayName("草稿内容提取")
  class DraftContent {

    @Test
    @DisplayName("草稿的 aiGeneratedContent 包含消息内容")
    void draft_aiContentContainsMessageContent() {
      String expectedContent = "这是一段详细的 AI 回答，包含排查步骤和解决方案。";
      RagChatSessionEntity session = createSession("内容提取测试会话");
      RagChatMessageEntity msg = createMessage(session, RagChatMessageEntity.MessageType.ASSISTANT,
          expectedContent, 0);

      CaseDTO draft = draftService.createDraft(session.getId(), msg.getId());

      assertThat(draft.aiGeneratedContent()).isEqualTo(expectedContent);
    }

    @Test
    @DisplayName("草稿的 sourceSessionId 和 sourceMessageId 正确")
    void draft_sourceFieldsCorrect() {
      RagChatSessionEntity session = createSession("来源字段测试会话");
      RagChatMessageEntity msg = createMessage(session, RagChatMessageEntity.MessageType.ASSISTANT,
          "测试消息内容", 0);

      CaseDTO draft = draftService.createDraft(session.getId(), msg.getId());

      assertThat(draft.sourceSessionId()).isEqualTo(session.getId());
      assertThat(draft.sourceMessageId()).isEqualTo(msg.getId());

      // 从数据库重新加载验证持久化正确
      var reloaded = caseRepository.findById(draft.id()).orElseThrow();
      assertThat(reloaded.getSourceSessionId()).isEqualTo(session.getId());
      assertThat(reloaded.getSourceMessageId()).isEqualTo(msg.getId());
    }
  }

  // ─────────── 列表与查询 ───────────

  @Nested
  @DisplayName("案例列表与查询")
  class ListAndQuery {

    @Test
    @DisplayName("按状态过滤案例列表")
    void listByStatus() {
      // 创建两个不同状态的案例
      RagChatSessionEntity session = createSession("列表测试会话");
      RagChatMessageEntity msg1 = createMessage(session, RagChatMessageEntity.MessageType.ASSISTANT,
          "内容1", 0);
      RagChatMessageEntity msg2 = createMessage(session, RagChatMessageEntity.MessageType.ASSISTANT,
          "内容2", 1);

      CaseDTO draft = draftService.createDraft(session.getId(), msg1.getId());
      CaseDTO draft2 = draftService.createDraft(session.getId(), msg2.getId());
      reviewService.submitForReview(draft2.id(), "tester");

      // 查询 DRAFT 状态
      List<CaseDTO> drafts = lifecycleService.listCases("DRAFT", null);
      assertThat(drafts).isNotEmpty();
      assertThat(drafts).allSatisfy(c -> assertThat(c.status()).isEqualTo(CaseStatus.DRAFT));

      // 查询 PENDING_REVIEW 状态
      List<CaseDTO> pending = lifecycleService.listCases("PENDING_REVIEW", null);
      assertThat(pending).isNotEmpty();
      assertThat(pending).allSatisfy(c -> assertThat(c.status()).isEqualTo(CaseStatus.PENDING_REVIEW));
    }

    @Test
    @DisplayName("获取单个案例详情")
    void getCaseDetail() {
      RagChatSessionEntity session = createSession("详情测试会话");
      RagChatMessageEntity msg = createMessage(session, RagChatMessageEntity.MessageType.ASSISTANT,
          "详细内容", 0);

      CaseDTO created = draftService.createDraft(session.getId(), msg.getId());
      CaseDTO detail = lifecycleService.getCase(created.id());

      assertThat(detail.id()).isEqualTo(created.id());
      assertThat(detail.title()).isNotBlank();
      assertThat(detail.status()).isEqualTo(CaseStatus.DRAFT);
    }
  }

  // ─────────── 案例检索集成 ───────────

  @Nested
  @DisplayName("案例检索集成")
  class CaseRetrieval {

    @Test
    @DisplayName("草稿案例不进入检索（未向量化）")
    void draftCase_notSearchable() {
      // 1. 创建带内容的草稿，不提交/批准
      String uniqueContent = "草稿检索测试_alpha_unique_7831";
      CaseDTO draft = createDraftWithContent(uniqueContent);
      assertThat(draft.status()).isEqualTo(CaseStatus.DRAFT);

      // 2. 向量表中不应有该案例的向量
      assertThat(vectorCountForCase(draft.id()))
          .as("草稿案例不应有向量数据")
          .isZero();

      // 3. 通过 similaritySearch 也搜不到
      List<Document> results = searchCaseVectors(uniqueContent);
      assertThat(results)
          .as("草稿案例不应被检索命中")
          .noneMatch(doc ->
              String.valueOf(doc.getMetadata().get("case_id_long"))
                  .equals(String.valueOf(draft.id())));
    }

    @Test
    @DisplayName("批准后可通过检索链路命中案例")
    void approvedCase_searchable() {
      // 1. 创建 → 提交 → 批准，触发向量化
      String uniqueContent = "批准检索测试_bravo_unique_4529";
      CaseDTO published = createApproveCase(uniqueContent);
      assertThat(published.status()).isEqualTo(CaseStatus.PUBLISHED);
      assertThat(published.active()).isTrue();

      // 2. 验证向量已写入
      assertThat(vectorCountForCase(published.id()))
          .as("批准后案例应有向量数据")
          .isGreaterThan(0);

      // 3. 通过 similaritySearch 检索，应命中该案例
      List<Document> results = searchCaseVectors(uniqueContent);
      assertThat(results)
          .as("批准后案例应被检索命中")
          .isNotEmpty();
      assertThat(results).anyMatch(doc -> {
        Object caseIdLong = doc.getMetadata().get("case_id_long");
        return caseIdLong != null &&
            String.valueOf(caseIdLong).equals(String.valueOf(published.id()));
      });

      // 4. 验证 metadata 中 source_type=CASE
      Document matchedDoc = results.stream()
          .filter(doc -> String.valueOf(doc.getMetadata().get("case_id_long"))
              .equals(String.valueOf(published.id())))
          .findFirst().orElseThrow();
      assertThat(matchedDoc.getMetadata().get("source_type")).isEqualTo("CASE");
    }

    @Test
    @DisplayName("废弃后案例退出检索（向量已删除）")
    void deprecatedCase_notSearchable() {
      // 1. 创建并批准（向量存在）
      String uniqueContent = "废弃检索测试_charlie_unique_6193";
      CaseDTO published = createApproveCase(uniqueContent);
      assertThat(vectorCountForCase(published.id()))
          .as("批准后应有向量")
          .isGreaterThan(0);

      // 2. 废弃 → DEPRECATED，触发向量删除
      CaseDTO deprecated = lifecycleService.deprecate(published.id(), "tester");
      assertThat(deprecated.status()).isEqualTo(CaseStatus.DEPRECATED);
      assertThat(deprecated.active()).isFalse();

      // 3. 验证向量已删除
      assertThat(vectorCountForCase(deprecated.id()))
          .as("废弃后向量应被删除")
          .isZero();

      // 4. similaritySearch 不应命中
      List<Document> results = searchCaseVectors(uniqueContent);
      assertThat(results)
          .as("废弃案例不应被检索命中")
          .noneMatch(doc ->
              String.valueOf(doc.getMetadata().get("case_id_long"))
                  .equals(String.valueOf(deprecated.id())));
    }
  }
}
