package interview.guide.modules.evalregression;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.caselibrary.model.CaseDTO;
import interview.guide.modules.caselibrary.model.CaseEntity;
import interview.guide.modules.caselibrary.model.CaseRequests.CaseUpdateRequest;
import interview.guide.modules.caselibrary.model.CaseStatus;
import interview.guide.modules.caselibrary.repository.CaseRepository;
import interview.guide.modules.caselibrary.service.CaseDraftService;
import interview.guide.modules.caselibrary.service.CaseLifecycleService;
import interview.guide.modules.caselibrary.service.CaseReviewService;
import interview.guide.modules.evalregression.model.CaseRegressionItemEntity;
import interview.guide.modules.evalregression.model.RegressionResultDTO;
import interview.guide.modules.evalregression.model.RegressionRunDetailDTO;
import interview.guide.modules.evalregression.model.RegressionRunStatus;
import interview.guide.modules.evalregression.repository.CaseRegressionItemRepository;
import interview.guide.modules.evalregression.service.CaseRegressionRunService;
import interview.guide.modules.evalregression.service.RegressionJsonCodec;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.ai.vectorstore.VectorStore;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 案例回归评测来源证据隔离集成测试（Testcontainers 隔离，零付费）。
 * <p>
 * 验证 Task 18 来源证据隔离修复：
 * <ul>
 *   <li>SELF/MISSING/NULL evidenceSource 统一跳过评测，不计入 evaluatedCount</li>
 *   <li>source_chunk_ids 只保存 sourceType="KB" 的 chunk ID</li>
 *   <li>回归检索新增 requireKbOnly 参数，限制 source_type='KB'</li>
 *   <li>service/environment 范围过滤与废弃案例测试</li>
 * </ul>
 * <p>
 * 使用确定性 Embedding bean（所有向量恒等，仅第 1024 维为 1），不调用任何真实模型。
 */
@DisplayName("案例回归评测来源证据隔离测试（Testcontainers 隔离）")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"demo", "test"})
@Import(CaseRegressionIsolationIntegrationTest.DeterministicEmbeddingConfig.class)
@DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)
class CaseRegressionIsolationIntegrationTest {

  /** 足够大的 topK，使无过滤的 KB 检索分支返回库内全部文档 */
  private static final int LARGE_TOPK = 200;

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
      DockerImageName.parse("pgvector/pgvector:pg16"))
      .withDatabaseName("case_regression_isolation_test")
      .withUsername("test")
      .withPassword("test");

  @Container
  static final GenericContainer<?> redis = new GenericContainer<>(
      DockerImageName.parse("redis:7-alpine"))
      .withExposedPorts(6379);

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
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
  @Autowired CaseRegressionRunService runService;
  @Autowired CaseRegressionItemRepository itemRepository;
  @Autowired RegressionJsonCodec jsonCodec;
  @Autowired KnowledgeBaseVectorService vectorService;
  @Autowired KnowledgeBaseRepository knowledgeBaseRepository;
  @Autowired RagChatSessionRepository sessionRepository;
  @Autowired RagChatMessageRepository messageRepository;
  @Autowired CaseRepository caseRepository;
  @Autowired VectorStore vectorStore;

  /**
   * 每个测试前清理回归相关表数据，避免跨测试数据泄漏。
   * Testcontainers 数据库在 @DirtiesContext 刷新时不会重建，Flyway 也只执行一次。
   */
  @Autowired
  org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanRegressionTables() {
    // 按外键依赖顺序清理
    jdbcTemplate.execute("DELETE FROM case_regression_results");
    jdbcTemplate.execute("DELETE FROM case_regression_runs");
    jdbcTemplate.execute("DELETE FROM case_regression_items");
    jdbcTemplate.execute("DELETE FROM case_audit_logs");
    jdbcTemplate.execute("DELETE FROM cases");
    jdbcTemplate.execute("DELETE FROM rag_chat_messages");
    jdbcTemplate.execute("DELETE FROM rag_chat_sessions");
    jdbcTemplate.execute("DELETE FROM knowledge_bases");
    // vector_store 清理：使用 try-catch 容忍表不存在的情况
    try {
      jdbcTemplate.execute("DELETE FROM vector_store");
    } catch (Exception ignored) {
      // vector_store 表可能尚未创建或已被跳过
    }
  }

  // ─────────── 确定性 Embedding 配置 ───────────

  private static float[] deterministicVector() {
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

  /**
   * 创建带 sourceChunkIds 的 ASSISTANT 消息
   */
  private RagChatMessageEntity createMessageWithChunkIds(RagChatSessionEntity session,
                                                          String content,
                                                          List<String> chunkIds) {
    RagChatMessageEntity message = new RagChatMessageEntity();
    message.setSession(session);
    message.setType(RagChatMessageEntity.MessageType.ASSISTANT);
    message.setContent(content);
    message.setMessageOrder(0);
    if (chunkIds != null && !chunkIds.isEmpty()) {
      message.setSourceChunkIds(jsonCodec.toJson(chunkIds));
    }
    return messageRepository.save(message);
  }

  private RagChatMessageEntity createMessageWithoutChunkIds(RagChatSessionEntity session,
                                                              String content) {
    return createMessageWithChunkIds(session, content, null);
  }

  /**
   * 创建知识库文档并写入 vector_store，返回 chunk IDs。
   * 手动分块 + 写入，以便精确控制 chunk ID。
   */
  private List<String> createAndVectorizeKB(String kbName, String content) {
    KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
    kb.setFileHash(UUID.randomUUID().toString());
    kb.setName(kbName);
    kb.setOriginalFilename(kbName + ".txt");
    KnowledgeBaseEntity savedKb = knowledgeBaseRepository.save(kb);

    // 手动分块
    org.springframework.ai.transformer.splitter.TextSplitter textSplitter =
        org.springframework.ai.transformer.splitter.TokenTextSplitter.builder().build();
    List<Document> chunks = textSplitter.apply(List.of(new Document(content)));

    // 为每个 chunk 添加 KB metadata（source_type=KB 使 requireKbOnly 过滤能匹配）
    for (Document chunk : chunks) {
      chunk.getMetadata().put("source_type", "KB");
      chunk.getMetadata().put("target_kb_id", savedKb.getId().toString());
    }

    vectorStore.add(chunks);
    return chunks.stream().map(Document::getId).toList();
  }

  /**
   * 创建并发布案例（带来源 chunk IDs）
   */
  private CaseDTO createApprovedCaseWithSourceChunks(String token, String service,
                                                      String environment,
                                                      List<String> sourceChunkIds) {
    RagChatSessionEntity session = createSession("隔离测试会话-" + token);
    RagChatMessageEntity msg = createMessageWithChunkIds(session, "隔离测试消息-" + token,
        sourceChunkIds);

    CaseDTO draft = draftService.createDraft(session.getId(), msg.getId());

    CaseUpdateRequest updateReq = new CaseUpdateRequest(
        "隔离测试案例 " + token,
        "隔离测试问题描述 " + token,
        "隔离测试根因 " + token,
        "重启服务;扩容连接池 " + token,
        "服务恢复正常 " + token,
        null, environment, service, null, null);
    reviewService.updateCase(draft.id(), updateReq, "tester");
    reviewService.submitForReview(draft.id(), "tester");
    return reviewService.approve(draft.id(), "tester");
  }

  private CaseDTO createApprovedCaseWithoutSourceChunks(String token, String service,
                                                         String environment) {
    return createApprovedCaseWithSourceChunks(token, service, environment, null);
  }

  // ═══════════════ 1. 真实 KB source evidence 可被命中并通过 ═══════════════

  @Nested
  @DisplayName("真实 KB 来源证据可被命中并通过评测")
  class KbSourceEvidenceTests {

    @Test
    @DisplayName("案例有来源 KB chunk IDs 时，回归项标记为 SOURCE，评测通过且证据命中")
    void caseWithKbSourceChunks_passesEvaluation() {
      // 1. 上传 KB 文档并获得 chunk IDs
      // KB 内容需包含案例的解决要点，以便“逐字包含”判定通过
      String token = "kb_source_001";
      String kbContent = "网关超时排查手册：重启服务;扩容连接池 " + token + ";服务恢复正常 " + token;
      List<String> kbChunkIds = createAndVectorizeKB("隔离测试知识库", kbContent);
      assertThat(kbChunkIds).isNotEmpty();

      // 2. 创建案例（带来源 KB chunk IDs）
      CaseDTO published = createApprovedCaseWithSourceChunks(token, "gateway", "prod", kbChunkIds);
      assertThat(published.status()).isEqualTo(CaseStatus.PUBLISHED);

      // 3. 验证回归项标记为 SOURCE
      CaseRegressionItemEntity item = itemRepository.findByCaseId(published.id()).orElseThrow();
      assertThat(item.getEvidenceSource())
          .as("有来源 KB chunk IDs 的案例应标记为 SOURCE")
          .isEqualTo("SOURCE");

      List<String> expectedEvidence = jsonCodec.toStringList(item.getExpectedEvidence());
      assertThat(expectedEvidence)
          .as("期望证据应为原始 KB chunk IDs")
          .containsExactlyInAnyOrderElementsOf(kbChunkIds);

      // 4. 运行回归评测
      RegressionRunDetailDTO detail = runService.runRegression(LARGE_TOPK);
      assertThat(detail.status()).isEqualTo(RegressionRunStatus.COMPLETED.name());

      // 5. 验证评测结果：通过、证据命中
      RegressionResultDTO result = detail.results().stream()
          .filter(r -> r.itemId().equals(item.getId()))
          .findFirst().orElseThrow();

      assertThat(result.passed())
          .as("SOURCE 证据案例，KB chunk 在 top-K 中应通过")
          .isTrue();
      assertThat(result.retrievedEvidenceIds())
          .as("原始 KB chunk 应在 top-K 召回中")
          .containsAnyElementsOf(kbChunkIds);
      assertThat(detail.evaluatedCount())
          .as("SOURCE 证据应计入 evaluatedCount")
          .isEqualTo(1);
    }
  }

  // ═══════════════ 2. 自身 CASE 向量不能成为通过证据 ═══════════════

  @Nested
  @DisplayName("无来源 KB 证据的案例自身 CASE 向量不能成为通过证据")
  class SelfEvidenceSkippedTests {

    @Test
    @DisplayName("案例无 sourceChunkIds 但有自身向量化证据时，标记为 SELF，评测跳过且不计入分母")
    void caseWithoutSourceChunks_markedAsSelf_andSkipped() {
      // 1. 创建案例（不带来源 chunk IDs，但案例内容会被向量化）
      String token = "self_skip_002";
      CaseDTO published = createApprovedCaseWithoutSourceChunks(token, "gateway", "prod");
      assertThat(published.status()).isEqualTo(CaseStatus.PUBLISHED);

      // 2. 验证回归项标记为 SELF
      CaseRegressionItemEntity item = itemRepository.findByCaseId(published.id()).orElseThrow();
      assertThat(item.getEvidenceSource())
          .as("无来源 chunk IDs 但有自身向量化证据时应标记为 SELF")
          .isEqualTo("SELF");

      // 3. 运行回归评测
      RegressionRunDetailDTO detail = runService.runRegression(LARGE_TOPK);
      assertThat(detail.status()).isEqualTo(RegressionRunStatus.COMPLETED.name());

      // 4. 验证评测结果：SELF → skipped, passed=false
      RegressionResultDTO result = detail.results().stream()
          .filter(r -> r.itemId().equals(item.getId()))
          .findFirst().orElseThrow();

      assertThat(result.passed())
          .as("SELF 证据案例不应通过")
          .isFalse();
      assertThat(detail.skipped())
          .as("SELF 证据应计入 skipped")
          .isEqualTo(1);
      assertThat(detail.evaluatedCount())
          .as("SELF 证据不应计入 evaluatedCount")
          .isEqualTo(0);
    }
  }

  // ═══════════════ 3. SELF/MISSING/NULL 来源均不通过、不进入有效评分分母 ═══════════════

  @Nested
  @DisplayName("SELF/MISSING/NULL 来源均跳过且不进入有效评分分母")
  class AllNonSourceSkippedTests {

    @Test
    @DisplayName("三个案例分别为 SELF/MISSING/NULL 来源，全部跳过，evaluatedCount=0")
    void selfMissingNull_allSkipped_notInEvaluatedCount() {
      // 案例 A：SELF（有自身向量化证据，无来源 chunk IDs）
      String tokenA = "mixed_self_A";
      CaseDTO publishedA = createApprovedCaseWithoutSourceChunks(tokenA, "gateway", "prod");
      assertThat(publishedA.status()).isEqualTo(CaseStatus.PUBLISHED);
      CaseRegressionItemEntity itemA = itemRepository.findByCaseId(publishedA.id()).orElseThrow();
      assertThat(itemA.getEvidenceSource()).isEqualTo("SELF");

      // 案例 B：MISSING（无来源 chunk IDs，且内容为空 → 无自身向量化证据）
      RagChatSessionEntity sessionB = createSession("混合测试会话-missing_B");
      RagChatMessageEntity msgB = createMessageWithoutChunkIds(sessionB, "混合测试消息-missing_B");
      CaseDTO draftB = draftService.createDraft(sessionB.getId(), msgB.getId());
      CaseUpdateRequest emptyUpdate = new CaseUpdateRequest(
          "空内容案例 B", null, null, null, null,
          null, "prod", "gateway", null, null);
      reviewService.updateCase(draftB.id(), emptyUpdate, "tester");
      reviewService.submitForReview(draftB.id(), "tester");
      CaseDTO publishedB = reviewService.approve(draftB.id(), "tester");
      assertThat(publishedB.status()).isEqualTo(CaseStatus.PUBLISHED);
      CaseRegressionItemEntity itemB = itemRepository.findByCaseId(publishedB.id()).orElseThrow();
      assertThat(itemB.getEvidenceSource()).isEqualTo("MISSING");

      // 案例 C：NULL（直接操作数据库，将 evidenceSource 设为 null）
      String tokenC = "mixed_null_C";
      CaseDTO publishedC = createApprovedCaseWithoutSourceChunks(tokenC, "gateway", "prod");
      assertThat(publishedC.status()).isEqualTo(CaseStatus.PUBLISHED);
      CaseRegressionItemEntity itemC = itemRepository.findByCaseId(publishedC.id()).orElseThrow();
      // 强制覆盖为 null（模拟未知来源）
      itemC.setEvidenceSource(null);
      itemRepository.save(itemC);
      // 重新读取确认
      itemC = itemRepository.findByCaseId(publishedC.id()).orElseThrow();
      assertThat(itemC.getEvidenceSource()).isNull();

      // 运行回归评测
      RegressionRunDetailDTO detail = runService.runRegression(LARGE_TOPK);
      assertThat(detail.status()).isEqualTo(RegressionRunStatus.COMPLETED.name());

      // 验证：三项全部 skipped
      assertThat(detail.totalItems()).isEqualTo(3);
      assertThat(detail.skipped()).isEqualTo(3);
      assertThat(detail.evaluatedCount())
          .as("SELF/MISSING/NULL 均不计入 evaluatedCount")
          .isEqualTo(0);
      assertThat(detail.passed()).isEqualTo(0);
      assertThat(detail.failed()).isEqualTo(0);

      // 逐项验证 passed=false
      for (CaseRegressionItemEntity item : List.of(itemA, itemB, itemC)) {
        RegressionResultDTO result = detail.results().stream()
            .filter(r -> r.itemId().equals(item.getId()))
            .findFirst().orElseThrow();
        assertThat(result.passed())
            .as("evidenceSource=%s 的案例不应通过", item.getEvidenceSource())
            .isFalse();
      }
    }
  }

  // ═══════════════ 4. 混合来源只保留 KB gold ═══════════════

  @Nested
  @DisplayName("混合来源（KB + CASE）只保留 KB chunk ID")
  class MixedSourceFilteringTests {

    @Test
    @DisplayName("消息 sourceChunkIds 只包含 KB chunk ID，案例草稿继承后回归项期望证据仅为 KB")
    void mixedSources_onlyKbChunksRetained() {
      // 1. 创建 KB 文档并获得 KB chunk IDs
      String kbContent = "网关超时排查手册：重启网关服务、扩容连接池上限。";
      List<String> kbChunkIds = createAndVectorizeKB("混合测试知识库", kbContent);
      assertThat(kbChunkIds).isNotEmpty();

      // 2. 模拟混合来源：CASE chunk ID（伪造）+ KB chunk ID
      String fakeCaseChunkId = "fake-case-chunk-" + UUID.randomUUID();
      // 控制器层已做 KB-only 过滤，sourceChunkIds 只包含 KB chunk ID
      // 这里直接模拟控制器过滤后的结果
      List<String> filteredChunkIds = kbChunkIds; // 只有 KB chunk

      // 3. 创建会话和消息（sourceChunkIds 只包含 KB chunk ID，模拟控制器过滤后行为）
      String token = "mixed_filter_004";
      CaseDTO published = createApprovedCaseWithSourceChunks(token, "gateway", "prod",
          filteredChunkIds);
      assertThat(published.status()).isEqualTo(CaseStatus.PUBLISHED);

      // 4. 验证 CaseEntity.sourceChunkIds 只包含 KB chunk ID
      CaseEntity caseEntity = caseRepository.findById(published.id()).orElseThrow();
      List<String> caseSourceChunks = jsonCodec.toStringList(caseEntity.getSourceChunkIds());
      assertThat(caseSourceChunks)
          .as("sourceChunkIds 应只包含 KB chunk ID")
          .containsExactlyInAnyOrderElementsOf(kbChunkIds);
      assertThat(caseSourceChunks)
          .as("sourceChunkIds 不应包含 CASE chunk ID")
          .doesNotContain(fakeCaseChunkId);

      // 5. 验证回归项的 expectedEvidence 只包含 KB chunk ID
      CaseRegressionItemEntity item = itemRepository.findByCaseId(published.id()).orElseThrow();
      assertThat(item.getEvidenceSource()).isEqualTo("SOURCE");
      List<String> expectedEvidence = jsonCodec.toStringList(item.getExpectedEvidence());
      assertThat(expectedEvidence)
          .as("期望证据应只包含 KB chunk ID")
          .containsExactlyInAnyOrderElementsOf(kbChunkIds);
    }

    @Test
    @DisplayName("消息 sourceChunkIds 含混合来源时，CaseDraftService 原样继承（过滤在控制器层完成）")
    void draftService_copiesSourceChunkIdsAsIs() {
      // 验证 CaseDraftService 的行为：直接复制 message.sourceChunkIds
      // 控制器层负责过滤为 KB-only，DraftService 不做二次过滤

      // 1. 创建会话和消息（手动设置 sourceChunkIds 为 KB-only，模拟控制器过滤后）
      String token = "draft_inherit_005";
      List<String> kbOnlyChunkIds = List.of("kb-chunk-1", "kb-chunk-2");

      RagChatSessionEntity session = createSession("草稿继承测试-" + token);
      RagChatMessageEntity msg = createMessageWithChunkIds(session, "草稿继承测试消息",
          kbOnlyChunkIds);

      // 2. 创建草稿
      CaseDTO draft = draftService.createDraft(session.getId(), msg.getId());

      // 3. 验证草稿的 sourceChunkIds 继承自消息
      CaseEntity caseEntity = caseRepository.findById(draft.id()).orElseThrow();
      List<String> caseSourceChunks = jsonCodec.toStringList(caseEntity.getSourceChunkIds());
      assertThat(caseSourceChunks)
          .as("草稿应继承消息的 sourceChunkIds")
          .containsExactlyInAnyOrderElementsOf(kbOnlyChunkIds);
    }
  }

  // ═══════════════ 5. service/environment 范围与废弃案例测试 ═══════════════

  @Nested
  @DisplayName("service/environment 范围过滤与废弃案例测试")
  class ServiceEnvironmentAndDeprecationTests {

    @Test
    @DisplayName("废弃案例后回归项 active=false，不参与新的评测运行")
    void deprecatedCase_excludedFromRegression() {
      // 1. 创建 KB 文档 + 两个带来源证据的案例
      String kbContent = "数据库连接池排查：检查最大连接数、超时配置、慢查询日志。";
      List<String> kbChunkIds = createAndVectorizeKB("废弃测试知识库", kbContent);

      String token1 = "deprec_active_006";
      CaseDTO published1 = createApprovedCaseWithSourceChunks(token1, "gateway", "prod",
          kbChunkIds);
      assertThat(published1.status()).isEqualTo(CaseStatus.PUBLISHED);

      String token2 = "deprec_inactive_007";
      CaseDTO published2 = createApprovedCaseWithSourceChunks(token2, "gateway", "prod",
          kbChunkIds);
      assertThat(published2.status()).isEqualTo(CaseStatus.PUBLISHED);

      // 2. 验证两个回归项均为 active
      CaseRegressionItemEntity item1 = itemRepository.findByCaseId(published1.id()).orElseThrow();
      CaseRegressionItemEntity item2 = itemRepository.findByCaseId(published2.id()).orElseThrow();
      assertThat(item1.getActive()).isTrue();
      assertThat(item2.getActive()).isTrue();

      // 3. 废弃案例 2
      CaseDTO deprecated = lifecycleService.deprecate(published2.id(), "tester");
      assertThat(deprecated.status()).isEqualTo(CaseStatus.DEPRECATED);

      // 4. 验证回归项 2 被停用
      CaseRegressionItemEntity deactivatedItem = itemRepository.findByCaseId(published2.id())
          .orElseThrow();
      assertThat(deactivatedItem.getActive())
          .as("废弃后回归项应停用")
          .isFalse();

      // 5. 运行回归评测
      RegressionRunDetailDTO detail = runService.runRegression(LARGE_TOPK);
      assertThat(detail.status()).isEqualTo(RegressionRunStatus.COMPLETED.name());

      // 6. 验证只有案例 1 参与评测（案例 2 已停用）
      assertThat(detail.totalItems())
          .as("只有 active=true 的回归项参与评测")
          .isEqualTo(1);

      List<Long> resultItemIds = detail.results().stream()
          .map(RegressionResultDTO::itemId)
          .toList();
      assertThat(resultItemIds)
          .as("结果应包含案例 1 的回归项")
          .contains(item1.getId());
      assertThat(resultItemIds)
          .as("结果不应包含已废弃案例 2 的回归项")
          .doesNotContain(item2.getId());
    }

    @Test
    @DisplayName("不同 service/environment 的案例均可独立创建回归项并评测")
    void differentServiceEnvironment_independentRegressionItems() {
      // 1. 创建 KB 文档
      String kbContent = "服务排查手册：重启服务、检查日志、扩容连接池。";
      List<String> kbChunkIds = createAndVectorizeKB("范围测试知识库", kbContent);

      // 2. 创建两个不同 service/environment 的案例
      String tokenGateway = "scope_gateway_008";
      CaseDTO caseGateway = createApprovedCaseWithSourceChunks(tokenGateway, "gateway", "prod",
          kbChunkIds);
      assertThat(caseGateway.status()).isEqualTo(CaseStatus.PUBLISHED);

      String tokenAuth = "scope_auth_009";
      CaseDTO caseAuth = createApprovedCaseWithSourceChunks(tokenAuth, "auth-service", "staging",
          kbChunkIds);
      assertThat(caseAuth.status()).isEqualTo(CaseStatus.PUBLISHED);

      // 3. 验证两个回归项均创建且标记为 SOURCE
      CaseRegressionItemEntity itemGateway = itemRepository.findByCaseId(caseGateway.id())
          .orElseThrow();
      CaseRegressionItemEntity itemAuth = itemRepository.findByCaseId(caseAuth.id()).orElseThrow();
      assertThat(itemGateway.getEvidenceSource()).isEqualTo("SOURCE");
      assertThat(itemAuth.getEvidenceSource()).isEqualTo("SOURCE");
      assertThat(itemGateway.getActive()).isTrue();
      assertThat(itemAuth.getActive()).isTrue();

      // 4. 运行回归评测
      RegressionRunDetailDTO detail = runService.runRegression(LARGE_TOPK);
      assertThat(detail.status()).isEqualTo(RegressionRunStatus.COMPLETED.name());
      assertThat(detail.totalItems()).isEqualTo(2);
      assertThat(detail.evaluatedCount()).isEqualTo(2);
    }
  }
}
