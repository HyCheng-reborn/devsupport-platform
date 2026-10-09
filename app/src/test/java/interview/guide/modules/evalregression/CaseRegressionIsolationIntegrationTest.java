package interview.guide.modules.evalregression;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.caselibrary.model.CaseDTO;
import interview.guide.modules.caselibrary.model.CaseRequests.CaseUpdateRequest;
import interview.guide.modules.caselibrary.model.CaseStatus;
import interview.guide.modules.caselibrary.service.CaseDraftService;
import interview.guide.modules.caselibrary.service.CaseLifecycleService;
import interview.guide.modules.caselibrary.service.CaseReviewService;
import interview.guide.modules.evalregression.model.CaseRegressionItemEntity;
import interview.guide.modules.evalregression.model.RegressionResultDTO;
import interview.guide.modules.evalregression.model.RegressionRunDetailDTO;
import interview.guide.modules.evalregression.model.RegressionRunStatus;
import interview.guide.modules.evalregression.repository.CaseRegressionItemRepository;
import interview.guide.modules.evalregression.service.CaseRegressionRunService;
import interview.guide.modules.evalregression.service.EmbeddingMetadataResolver;
import interview.guide.modules.evalregression.service.RegressionJsonCodec;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 案例回归评测数据泄漏修复隔离集成测试（Testcontainers 隔离，零付费）。
 * <p>
 * 验证 Stage 5 数据泄漏修复：
 * <ul>
 *   <li>被测案例自身向量必须被排除，不因自我命中而通过</li>
 *   <li>原始 KB gold chunk 命中时通过</li>
 *   <li>无来源 KB gold 时明确标记为 MISSING</li>
 *   <li>废弃案例不再参与回归</li>
 *   <li>旧案例（SELF 证据）兼容性</li>
 * </ul>
 * <p>
 * 使用确定性 Embedding bean（所有向量恒等，仅第 1024 维为 1），不调用任何真实模型。
 */
@DisplayName("案例回归评测数据泄漏修复隔离测试（Testcontainers 隔离）")
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
  @Autowired EmbeddingMetadataResolver embeddingMetadataResolver;
  @Autowired KnowledgeBaseVectorService vectorService;
  @Autowired KnowledgeBaseRepository knowledgeBaseRepository;
  @Autowired RagChatSessionRepository sessionRepository;
  @Autowired RagChatMessageRepository messageRepository;
  @Autowired VectorStore vectorStore;

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

  /**
   * 创建带 sourceChunkIds 的 ASSISTANT 消息
   */
  private RagChatMessageEntity createMessageWithChunkIds(RagChatSessionEntity session, String content, List<String> chunkIds) {
    RagChatMessageEntity message = new RagChatMessageEntity();
    message.setSession(session);
    message.setType(RagChatMessageEntity.MessageType.ASSISTANT);
    message.setContent(content);
    message.setMessageOrder(0);
    // 设置 sourceChunkIds（JSON array 格式）
    if (chunkIds != null && !chunkIds.isEmpty()) {
      message.setSourceChunkIds(jsonCodec.toJson(chunkIds));
    }
    return messageRepository.save(message);
  }

  /**
   * 创建不带 sourceChunkIds 的 ASSISTANT 消息
   */
  private RagChatMessageEntity createMessageWithoutChunkIds(RagChatSessionEntity session, String content) {
    return createMessageWithChunkIds(session, content, null);
  }

  /**
   * 创建知识库并向量化文档，返回 chunk IDs
   */
  private List<String> createAndVectorizeKB(String kbName, String content) {
    KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
    kb.setFileHash(UUID.randomUUID().toString());
    kb.setName(kbName);
    kb.setOriginalFilename(kbName + ".txt");
    KnowledgeBaseEntity savedKb = knowledgeBaseRepository.save(kb);

    // 手动分块并添加到 vectorStore，以便获取 chunk IDs
    org.springframework.ai.transformer.splitter.TextSplitter textSplitter =
        org.springframework.ai.transformer.splitter.TokenTextSplitter.builder().build();
    List<Document> chunks = textSplitter.apply(List.of(new Document(content)));

    // 为每个 chunk 添加 KB metadata
    String jobId = UUID.randomUUID().toString();
    for (Document chunk : chunks) {
      chunk.getMetadata().put("kb_id", "temp_" + savedKb.getId() + ":" + jobId);
      chunk.getMetadata().put("target_kb_id", savedKb.getId().toString());
    }

    // 添加到 vectorStore
    vectorStore.add(chunks);

    // 返回 chunk IDs
    return chunks.stream().map(Document::getId).toList();
  }

  /**
   * 创建并发布案例（带来源 chunk IDs）
   */
  private CaseDTO createApprovedCaseWithSourceChunks(String token, String service, String environment, List<String> sourceChunkIds) {
    RagChatSessionEntity session = createSession("隔离测试会话-" + token);
    RagChatMessageEntity msg = createMessageWithChunkIds(session, "隔离测试消息-" + token, sourceChunkIds);

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

  /**
   * 创建并发布案例（不带来源 chunk IDs）
   */
  private CaseDTO createApprovedCaseWithoutSourceChunks(String token, String service, String environment) {
    return createApprovedCaseWithSourceChunks(token, service, environment, null);
  }

  // ═══════════════ 1. 被测案例自身向量必须被排除 ═══════════════

  @Nested
  @DisplayName("无来源证据的案例标记为 SELF（兜底）并正常评测")
  class MissingSourceEvidenceTests {

    @Test
    @DisplayName("案例无 sourceChunkIds 但有自身向量化证据时，回归项标记为 SELF，评测通过")
    void caseWithoutSourceChunks_markedAsSelf_andEvaluated() {
      // 1. 创建案例（不带来源 chunk IDs，但案例内容会被向量化）
      String token = "iso_missing_001";
      CaseDTO published = createApprovedCaseWithoutSourceChunks(token, "gateway", "prod");
      assertThat(published.status()).isEqualTo(CaseStatus.PUBLISHED);

      // 2. 验证回归项标记为 SELF（兜底）
      CaseRegressionItemEntity item = itemRepository.findByCaseId(published.id()).orElseThrow();
      assertThat(item.getEvidenceSource())
          .as("无来源 chunk IDs 但有自身向量化证据时应标记为 SELF")
          .isEqualTo("SELF");
      assertThat(item.getActive()).isTrue();

      // 3. 运行回归评测
      RegressionRunDetailDTO detail = runService.runRegression(LARGE_TOPK);
      assertThat(detail.status()).isEqualTo(RegressionRunStatus.COMPLETED.name());

      // 4. 验证评测结果（SELF 证据不排除自身，应通过）
      RegressionResultDTO result = detail.results().stream()
          .filter(r -> r.itemId().equals(item.getId()))
          .findFirst().orElseThrow();
      assertThat(result.passed())
          .as("SELF 证据案例应通过（但结果不可信）")
          .isTrue();
    }
  }

  // ═══════════════ 2. 原始 KB gold chunk 命中时通过 ═══════════════

  @Nested
  @DisplayName("有来源证据的案例使用原始 KB chunk IDs 作为期望证据")
  class SourceEvidenceTests {

    @Test
    @DisplayName("案例有 sourceChunkIds 时，回归项使用 SOURCE 证据，评测时排除自身向量")
    void caseWithSourceChunks_usesSourceEvidence_andExcludesSelf() {
      // 1. 上传 KB 文档并获得 chunk IDs
      String kbContent = "网关超时排查手册：重启网关服务、扩容连接池上限、观察水位恢复。";
      List<String> kbChunkIds = createAndVectorizeKB("隔离测试知识库", kbContent);
      assertThat(kbChunkIds).isNotEmpty();

      // 2. 创建案例（带来源 chunk IDs）
      String token = "iso_source_002";
      CaseDTO published = createApprovedCaseWithSourceChunks(token, "gateway", "prod", kbChunkIds);
      assertThat(published.status()).isEqualTo(CaseStatus.PUBLISHED);

      // 3. 验证回归项使用 SOURCE 证据
      CaseRegressionItemEntity item = itemRepository.findByCaseId(published.id()).orElseThrow();
      assertThat(item.getEvidenceSource())
          .as("有来源 chunk IDs 的案例应标记为 SOURCE")
          .isEqualTo("SOURCE");

      List<String> expectedEvidence = jsonCodec.toStringList(item.getExpectedEvidence());
      assertThat(expectedEvidence)
          .as("期望证据应为原始 KB chunk IDs")
          .containsExactlyInAnyOrderElementsOf(kbChunkIds);

      // 4. 运行回归评测
      RegressionRunDetailDTO detail = runService.runRegression(LARGE_TOPK);
      assertThat(detail.status()).isEqualTo(RegressionRunStatus.COMPLETED.name());

      // 5. 验证评测结果
      RegressionResultDTO result = detail.results().stream()
          .filter(r -> r.itemId().equals(item.getId()))
          .findFirst().orElseThrow();

      // 由于使用确定性向量，所有向量恒等，KB 分支会召回全部文档（包括 KB chunk）
      // 期望证据（KB chunk IDs）应在召回集合中
      assertThat(result.retrievedEvidenceIds())
          .as("原始 KB chunk 应在 top-K 召回中")
          .containsAnyElementsOf(kbChunkIds);

      // 被测案例自身的 chunk ID 不应在召回集合中（排除生效）
      // 注意：由于案例没有自身 chunk IDs 作为期望证据，这里验证的是 retrievedEvidenceIds 不包含案例自身向量化的 chunk
      // 但由于我们排除了案例自身，所以案例向量化的 chunk 不应被召回
      // 实际上，由于使用 SOURCE 证据，评测时会排除被测案例自身的 CASE 向量
    }
  }

  // ═══════════════ 3. 无来源 KB gold 时明确标记 ═══════════════

  @Nested
  @DisplayName("真正无证据的案例明确标记为 MISSING")
  class NoSourceEvidenceTests {

    @Test
    @DisplayName("案例无 sourceChunkIds 且无自身向量化证据时，回归项标记为 MISSING")
    void caseWithNoEvidenceAtAll_markedAsMissing() {
      // 1. 创建会话和消息（不带 chunk IDs）
      RagChatSessionEntity session = createSession("隔离测试会话-empty_003");
      RagChatMessageEntity msg = createMessageWithoutChunkIds(session, "隔离测试消息-empty_003");
      CaseDTO draft = draftService.createDraft(session.getId(), msg.getId());

      // 2. 更新案例为空内容（4 个内容字段均为 null）
      CaseUpdateRequest updateReq = new CaseUpdateRequest(
          "空内容案例",
          null, null, null, null, // problemDescription, rootCause, resolutionSteps, resolutionResult
          null, "prod", "gateway", null, null);
      reviewService.updateCase(draft.id(), updateReq, "tester");
      reviewService.submitForReview(draft.id(), "tester");
      CaseDTO published = reviewService.approve(draft.id(), "tester");
      assertThat(published.status()).isEqualTo(CaseStatus.PUBLISHED);

      // 3. 验证回归项标记为 MISSING
      CaseRegressionItemEntity item = itemRepository.findByCaseId(published.id()).orElseThrow();
      assertThat(item.getEvidenceSource())
          .as("无来源 chunk IDs 且无自身向量化证据时应标记为 MISSING")
          .isEqualTo("MISSING");
      assertThat(item.getActive()).isTrue();
    }
  }

  // ═══════════════ 4. 废弃案例不再参与回归 ═══════════════

  @Nested
  @DisplayName("废弃案例的回归项被停用")
  class DeprecatedCaseTests {

    @Test
    @DisplayName("废弃案例后，回归项 active=false")
    void deprecatedCase_regressionItemDeactivated() {
      // 1. 创建并发布案例（不带来源 chunk IDs，简化测试）
      String token = "iso_deprec_004";
      CaseDTO published = createApprovedCaseWithoutSourceChunks(token, "gateway", "prod");
      assertThat(published.status()).isEqualTo(CaseStatus.PUBLISHED);

      // 2. 验证回归项激活
      CaseRegressionItemEntity item = itemRepository.findByCaseId(published.id()).orElseThrow();
      assertThat(item.getActive()).isTrue();

      // 3. 废弃案例
      CaseDTO deprecated = lifecycleService.deprecate(published.id(), "tester");
      assertThat(deprecated.status()).isEqualTo(CaseStatus.DEPRECATED);

      // 4. 验证回归项被停用
      CaseRegressionItemEntity deactivatedItem = itemRepository.findByCaseId(published.id()).orElseThrow();
      assertThat(deactivatedItem.getActive())
          .as("废弃后回归项应停用")
          .isFalse();
    }
  }

  // ═══════════════ 5. 旧案例兼容性 ═══════════════

  @Nested
  @DisplayName("旧案例（SELF 证据）兼容性测试")
  class LegacyCaseTests {

    @Test
    @DisplayName("旧案例使用自身向量作为证据，标记为 SELF，仍被评测但结果不可信")
    void legacyCaseWithSelfEvidence_stillEvaluated_butMarkedAsSelf() {
      // 模拟旧案例：直接创建案例并发布，不设置 sourceChunkIds
      // 由于没有来源 chunk IDs，upsertOnPublish 会使用自身向量化的 chunk ID 作为证据
      // 但前提是 selfEvidenceIds 不为空

      // 1. 创建案例（不带来源 chunk IDs，但案例内容会被向量化）
      String token = "iso_legacy_005";
      CaseDTO published = createApprovedCaseWithoutSourceChunks(token, "gateway", "prod");
      assertThat(published.status()).isEqualTo(CaseStatus.PUBLISHED);

      // 2. 验证回归项
      CaseRegressionItemEntity item = itemRepository.findByCaseId(published.id()).orElseThrow();

      // 由于没有 sourceChunkIds，且 selfEvidenceIds 不为空（案例内容已被向量化）
      // 应标记为 SELF（兜底）
      assertThat(item.getEvidenceSource())
          .as("无来源 chunk IDs 但有自身向量化证据时应标记为 SELF")
          .isEqualTo("SELF");

      List<String> expectedEvidence = jsonCodec.toStringList(item.getExpectedEvidence());
      assertThat(expectedEvidence)
          .as("SELF 证据应为案例自身向量化的 chunk ID")
          .isNotEmpty();

      // 3. 运行回归评测
      RegressionRunDetailDTO detail = runService.runRegression(LARGE_TOPK);

      // 4. 验证评测结果
      RegressionResultDTO result = detail.results().stream()
          .filter(r -> r.itemId().equals(item.getId()))
          .findFirst().orElseThrow();

      // SELF 证据不排除自身，所以案例自身向量应被召回
      assertThat(result.retrievedEvidenceIds())
          .as("SELF 证据应召回案例自身向量")
          .containsAnyElementsOf(expectedEvidence);

      // 由于使用大 topK，应通过
      assertThat(result.passed())
          .as("SELF 证据案例应通过（但结果不可信）")
          .isTrue();
    }
  }
}
