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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 案例驱动回归评测端到端集成测试（Testcontainers 隔离，零付费）。
 * <p>
 * 使用 pgvector/pgvector:pg16 + Redis 容器，Flyway 应用 V20261010 建回归三表，
 * Embedding 为<b>确定性 @Primary bean</b>（与 {@code CaseLibraryIntegrationTest} /
 * {@code KnowledgeBaseUploadPipelineIntegrationTest} 完全相同的实现：所有向量恒等，
 * 仅第 1024 维为 1），不调用任何真实模型。
 *
 * <p><b>确定性召回策略（关键）</b>：确定性 bean 非语义模型，所有向量恒等，向量相似度无法区分
 * 文本， ties 之间的排序不可依赖。生产检索路径
 * {@code KnowledgeBaseQueryService.retrieveAndMerge(query, List.of(), caseContextFilter, topK, 0.0)}
 * 的 KB 分支在 kbIds 为空时<b>不带过滤</b>地检索整个向量库并返回至多 topK 条。因此本测试统一
 * 采用<b>足够大的 topK（{@link #LARGE_TOPK}）</b>，使 KB 分支返回库内<b>全部</b>文档，
 * 从而保证目标案例自身证据 ID 必然出现在 {@code retrievedEvidenceIds} 中；断言只检查
 * "目标证据 ID ∈ 召回集合"，不依赖精确排名位次，避免因 ties 排序产生的脆弱性。
 * 每个 fixture 案例内容都很短（单个 chunk），且要点取自单行文本，确保逐字包含判定稳定成立。
 *
 * <p><b>service 隔离</b>：由于恒定向量无法在合并检索层做语义区分，跨 service 隔离在
 * <b>案例作用域检索原语</b> {@link KnowledgeBaseVectorService#searchCaseVectors} 层验证
 * （基于 metadata 精确过滤，确定性可复现），即回归运行时 caseDocs 分支实际依赖的隔离机制。
 */
@DisplayName("案例回归评测集成测试（Testcontainers 隔离）")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"demo", "test"})
@Import(CaseRegressionIntegrationTest.DeterministicEmbeddingConfig.class)
@DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)
class CaseRegressionIntegrationTest {

  /** 足够大的 topK，使无过滤的 KB 检索分支返回库内全部文档，保证目标证据确定性命中 */
  private static final int LARGE_TOPK = 200;

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
      DockerImageName.parse("pgvector/pgvector:pg16"))
      .withDatabaseName("case_regression_test")
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
  @Autowired JdbcTemplate jdbcTemplate;

  /**
   * 每个测试前清理回归相关表数据，避免跨测试数据泄漏。
   */
  @BeforeEach
  void cleanRegressionTables() {
    jdbcTemplate.execute("DELETE FROM case_regression_results");
    jdbcTemplate.execute("DELETE FROM case_regression_runs");
    jdbcTemplate.execute("DELETE FROM case_regression_items");
    jdbcTemplate.execute("DELETE FROM case_audit_logs");
    jdbcTemplate.execute("DELETE FROM cases");
    jdbcTemplate.execute("DELETE FROM rag_chat_messages");
    jdbcTemplate.execute("DELETE FROM rag_chat_sessions");
    jdbcTemplate.execute("DELETE FROM knowledge_bases");
    try {
      jdbcTemplate.execute("DELETE FROM vector_store");
    } catch (Exception ignored) {
      // vector_store 表可能尚未创建
    }
  }

  // ─────────── 确定性 Embedding 配置（与既有集成测试同一实现）───────────

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

  private RagChatMessageEntity createMessage(RagChatSessionEntity session, String content) {
    RagChatMessageEntity message = new RagChatMessageEntity();
    message.setSession(session);
    message.setType(RagChatMessageEntity.MessageType.ASSISTANT);
    message.setContent(content);
    message.setMessageOrder(0);
    return messageRepository.save(message);
  }

  /**
   * 创建 → 填充可检索内容（含 service/environment 标签）→ 提交 → 审核发布，
   * 发布路径会触发向量化并调用 upsertOnPublish 生成回归项。
   * <p>解决步骤 / 结果均为单行分号分隔文本，使派生要点为原文连续子串，逐字包含判定稳定。
   */
  private CaseDTO createApprovedCase(String token, String service, String environment) {
    return createApprovedCase(token, service, environment, null);
  }

  /**
   * 创建 → 填充可检索内容（含 service/environment 标签）→ 提交 → 审核发布，
   * 发布路径会触发向量化并调用 upsertOnPublish 生成回归项。
   * 支持指定 sourceChunkIds，用于模拟带来源 KB chunk 的案例。
   */
  private CaseDTO createApprovedCase(String token, String service, String environment,
                                      List<String> sourceChunkIds) {
    RagChatSessionEntity session = createSession("回归测试会话-" + token);
    // 设置消息的 sourceChunkIds（模拟控制器 KB-only 过滤后的结果）
    RagChatMessageEntity message = new RagChatMessageEntity();
    message.setSession(session);
    message.setType(RagChatMessageEntity.MessageType.ASSISTANT);
    message.setContent("回归测试消息-" + token);
    message.setMessageOrder(0);
    if (sourceChunkIds != null && !sourceChunkIds.isEmpty()) {
      message.setSourceChunkIds(jsonCodec.toJson(sourceChunkIds));
    }
    RagChatMessageEntity msg = messageRepository.save(message);
    CaseDTO draft = draftService.createDraft(session.getId(), msg.getId());

    CaseUpdateRequest updateReq = new CaseUpdateRequest(
        "网关超时排查 " + token,
        "生产网关频繁超时 " + token,
        "连接池耗尽导致请求堆积 " + token,
        "重启网关服务;扩容连接池上限 " + token,
        "服务恢复正常水位 " + token,
        null, environment, service, null, null);
    reviewService.updateCase(draft.id(), updateReq, "tester");
    reviewService.submitForReview(draft.id(), "tester");
    return reviewService.approve(draft.id(), "tester");
  }

  private KnowledgeBaseEntity createKnowledgeBase(String name) {
    KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
    kb.setFileHash(UUID.randomUUID().toString());
    kb.setName(name);
    kb.setOriginalFilename(name + ".txt");
    return knowledgeBaseRepository.save(kb);
  }

  /** 读取某案例回归项的期望证据 ID 列表（由 approve 时向量化的 chunk ID 绑定） */
  private List<String> expectedEvidenceOf(Long caseId) {
    CaseRegressionItemEntity item = itemRepository.findByCaseId(caseId).orElseThrow();
    return jsonCodec.toStringList(item.getExpectedEvidence());
  }

  // ═══════════════ 端到端：发布 → 回归通过 ═══════════════

  @Nested
  @DisplayName("发布案例后回归运行命中自身证据")
  class PublishThenRegressionPasses {

    @Test
    @DisplayName("上传 KB → 审核发布案例（带来源 KB chunk）→ runRegression：回归项通过且 KB 证据被召回")
    void approvedCase_regressionPasses() {
      // 1. 上传并向量化一个知识库文档，获得 KB chunk IDs
      KnowledgeBaseEntity kb = createKnowledgeBase("回归测试知识库");
      String token = "reg_pass_alpha_7001";
      String kbContent = "网关超时排查手册：重启网关服务;扩容连接池上限 " + token + ";服务恢复正常水位 " + token;
      vectorService.vectorizeAndStore(kb.getId(), kbContent);

      // 获取 KB chunk IDs（promoteVectorJob 后 kb_id 为最终值）
      List<String> kbChunkIds = jdbcTemplate.queryForList(
          "SELECT id FROM vector_store WHERE metadata->>'kb_id' = ?",
          String.class, kb.getId().toString());

      // 2. 审核发布案例（带来源 KB chunk IDs，模拟真实来源会话流程）
      CaseDTO published = createApprovedCase(token, "gateway", "prod", kbChunkIds);
      assertThat(published.status()).isEqualTo(CaseStatus.PUBLISHED);

      // 3. 回归项已生成，active=true，evidenceSource=SOURCE
      CaseRegressionItemEntity item = itemRepository.findByCaseId(published.id()).orElseThrow();
      assertThat(item.getActive()).isTrue();
      assertThat(item.getEvidenceSource()).isEqualTo("SOURCE");
      List<String> expectedEvidence = jsonCodec.toStringList(item.getExpectedEvidence());
      assertThat(expectedEvidence).as("发布应绑定来源 KB chunk ID").isNotEmpty();

      // 4. 运行回归（大 topK 保证确定性召回全部文档）
      RegressionRunDetailDTO detail = runService.runRegression(LARGE_TOPK);
      assertThat(detail.status()).isEqualTo(RegressionRunStatus.COMPLETED.name());

      // 5. 该案例对应逐项结果通过，且 KB 证据 ID ∈ retrievedEvidenceIds
      RegressionResultDTO result = detail.results().stream()
          .filter(r -> r.itemId().equals(item.getId()))
          .findFirst().orElseThrow();
      assertThat(result.passed()).as("SOURCE 证据案例应通过").isTrue();
      assertThat(result.retrievedEvidenceIds())
          .as("来源 KB chunk 应出现在召回集合中")
          .containsAnyElementsOf(expectedEvidence);
      assertThat(result.missingKeyPoints()).isEmpty();
    }
  }

  // ═══════════════ 废弃 → 回归项停用并排除 ═══════════════

  @Nested
  @DisplayName("废弃案例后回归项停用并被排除")
  class DeprecateExcludesItem {

    @Test
    @DisplayName("废弃案例 → 回归项 active=false → 再次 runRegression 不含该项")
    void deprecatedCase_itemDeactivated_andExcluded() {
      // 保留一个始终激活的案例 A，废弃案例 B，验证 B 被排除而 A 仍参与
      String tokenA = "reg_keep_bravo_7002";
      String tokenB = "reg_deprecate_charlie_7003";
      CaseDTO caseA = createApprovedCase(tokenA, "gateway", "prod");
      CaseDTO caseB = createApprovedCase(tokenB, "gateway", "prod");

      CaseRegressionItemEntity itemA = itemRepository.findByCaseId(caseA.id()).orElseThrow();
      CaseRegressionItemEntity itemB = itemRepository.findByCaseId(caseB.id()).orElseThrow();
      assertThat(itemA.getActive()).isTrue();
      assertThat(itemB.getActive()).isTrue();

      // 废弃案例 B → deactivateOnDeprecate 将其回归项置为非激活
      CaseDTO deprecated = lifecycleService.deprecate(caseB.id(), "tester");
      assertThat(deprecated.status()).isEqualTo(CaseStatus.DEPRECATED);

      CaseRegressionItemEntity reloadedB = itemRepository.findByCaseId(caseB.id()).orElseThrow();
      assertThat(reloadedB.getActive()).as("废弃后回归项应停用").isFalse();

      // 再次运行回归：只包含激活项 A，不含已停用的 B
      RegressionRunDetailDTO detail = runService.runRegression(LARGE_TOPK);
      assertThat(detail.results())
          .extracting(RegressionResultDTO::itemId)
          .contains(itemA.getId())
          .doesNotContain(itemB.getId());
      assertThat(detail.totalItems()).isEqualTo(1);
    }
  }

  // ═══════════════ service 上下文隔离 ═══════════════

  @Nested
  @DisplayName("案例作用域检索按 service 隔离")
  class ServiceScopeIsolation {

    @Test
    @DisplayName("不同 service 的案例：跨 service 检索不召回其证据（作用域检索原语层）")
    void crossService_notRecalled() {
      String tokenA = "reg_iso_delta_7004";
      String tokenB = "reg_iso_echo_7005";
      CaseDTO caseA = createApprovedCase(tokenA, "service-alpha", "prod");
      CaseDTO caseB = createApprovedCase(tokenB, "service-beta", "prod");

      List<String> evidenceA = expectedEvidenceOf(caseA.id());
      List<String> evidenceB = expectedEvidenceOf(caseB.id());
      assertThat(evidenceA).isNotEmpty();
      assertThat(evidenceB).isNotEmpty();

      // 以 service-alpha 上下文检索：召回 A 自身证据，不召回 B 的证据
      Map<String, String> filterAlpha = Map.of("service", "service-alpha");
      List<Document> docsAlpha = vectorService.searchCaseVectors(
          "网关超时排查 " + tokenA, filterAlpha, List.of(), LARGE_TOPK, 0.0);
      List<String> idsAlpha = docsAlpha.stream().map(Document::getId).toList();
      assertThat(idsAlpha)
          .as("service-alpha 上下文应召回 A 自身证据")
          .containsAnyElementsOf(evidenceA);
      assertThat(idsAlpha)
          .as("service-alpha 上下文不应召回 service-beta 案例 B 的证据")
          .doesNotContainAnyElementsOf(evidenceB);

      // 以 service-beta 上下文检索：召回 B 自身证据，不召回 A 的证据
      Map<String, String> filterBeta = Map.of("service", "service-beta");
      List<Document> docsBeta = vectorService.searchCaseVectors(
          "网关超时排查 " + tokenB, filterBeta, List.of(), LARGE_TOPK, 0.0);
      List<String> idsBeta = docsBeta.stream().map(Document::getId).toList();
      assertThat(idsBeta)
          .as("service-beta 上下文应召回 B 自身证据")
          .containsAnyElementsOf(evidenceB);
      assertThat(idsBeta)
          .as("service-beta 上下文不应召回 service-alpha 案例 A 的证据")
          .doesNotContainAnyElementsOf(evidenceA);
    }
  }

  // ═══════════════ 运行报告落库完整性 ═══════════════

  @Nested
  @DisplayName("运行报告完整落库")
  class RunReportPersistence {

    @Test
    @DisplayName("case_regression_runs 一行汇总 + case_regression_results 逐项行，字段一致")
    void runAndResultsPersisted() {
      // 创建 KB 并获得 chunk IDs，使案例有 SOURCE 证据
      KnowledgeBaseEntity kb = createKnowledgeBase("报告测试知识库");
      String token = "reg_report_foxtrot_7006";
      String kbContent = "网关排查：重启网关服务;扩容连接池上限 " + token + ";服务恢复正常水位 " + token;
      vectorService.vectorizeAndStore(kb.getId(), kbContent);
      List<String> kbChunkIds = jdbcTemplate.queryForList(
          "SELECT id FROM vector_store WHERE metadata->>'kb_id' = ?",
          String.class, kb.getId().toString());

      CaseDTO published = createApprovedCase(token, "gateway", "prod", kbChunkIds);
      CaseRegressionItemEntity item = itemRepository.findByCaseId(published.id()).orElseThrow();
      assertThat(item.getEvidenceSource()).isEqualTo("SOURCE");

      RegressionRunDetailDTO detail = runService.runRegression(LARGE_TOPK);
      Long runId = detail.id();

      // 1. case_regression_runs：该 runId 恰好一行，汇总字段与 DTO 一致
      Map<String, Object> runRow = jdbcTemplate.queryForMap(
          "SELECT total_items, passed, failed, skipped, status, trigger_source, embedding_model, "
              + "started_at, finished_at FROM case_regression_runs WHERE id = ?", runId);
      assertThat(((Number) runRow.get("total_items")).intValue()).isEqualTo(detail.totalItems());
      assertThat(((Number) runRow.get("passed")).intValue()).isEqualTo(detail.passed());
      assertThat(((Number) runRow.get("failed")).intValue()).isEqualTo(detail.failed());
      assertThat(((Number) runRow.get("skipped")).intValue()).isEqualTo(detail.skipped());
      assertThat(runRow.get("status")).isEqualTo(RegressionRunStatus.COMPLETED.name());
      assertThat(runRow.get("trigger_source")).isEqualTo("MANUAL");
      assertThat(runRow.get("embedding_model")).isEqualTo(detail.embeddingModel());
      assertThat(runRow.get("started_at")).isNotNull();
      assertThat(runRow.get("finished_at")).isNotNull();
      // total = passed + failed + skipped
      assertThat(detail.totalItems())
          .isEqualTo(detail.passed() + detail.failed() + detail.skipped());

      // 2. case_regression_results：逐项行数与 total 一致，且该案例项落库
      Integer resultCount = jdbcTemplate.queryForObject(
          "SELECT COUNT(*) FROM case_regression_results WHERE run_id = ?", Integer.class, runId);
      assertThat(resultCount).isEqualTo(detail.totalItems());

      Map<String, Object> resultRow = jdbcTemplate.queryForMap(
          "SELECT item_id, passed, retrieved_evidence_ids, matched_key_points, missing_key_points, "
              + "topk_snapshot, failure_reason FROM case_regression_results WHERE run_id = ? AND item_id = ?",
          runId, item.getId());
      assertThat(((Number) resultRow.get("item_id")).longValue()).isEqualTo(item.getId());
      assertThat((Boolean) resultRow.get("passed")).isTrue();
      // retrieved_evidence_ids JSON 可反序列化且包含自身证据
      List<String> persistedRetrieved = jsonCodec.toStringList((String) resultRow.get("retrieved_evidence_ids"));
      assertThat(persistedRetrieved)
          .containsAnyElementsOf(jsonCodec.toStringList(item.getExpectedEvidence()));
      assertThat(jsonCodec.toStringList((String) resultRow.get("missing_key_points"))).isEmpty();
      assertThat(jsonCodec.toSnapshotList((String) resultRow.get("topk_snapshot"))).isNotEmpty();
      assertThat(resultRow.get("failure_reason")).isNull();
    }
  }
}
