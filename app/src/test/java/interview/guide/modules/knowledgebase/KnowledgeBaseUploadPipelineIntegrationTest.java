package interview.guide.modules.knowledgebase;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseConflictService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseListService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseUploadService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import interview.guide.modules.knowledgebase.listener.VectorizeStreamProducer;
import interview.guide.common.transaction.TransactionalExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 阶段 2 真实本地集成：上传 → RustFS/S3 对象 + PG 元数据 → Redis Stream 实际生产/消费 → 确定性 Embedding
 * 完成向量索引 → 按项目/环境/版本检索新版且不命中旧版 → 重试可观察。
 *
 * <p>隔离基础设施：Testcontainers 启动独立 pgvector / redis / MinIO(S3 兼容，RustFS 走同一 AWS SDK 路径) 容器，
 * 使用独立 bucket，绝不触碰现有 dev 数据卷/库。Embedding 为确定性 @Primary bean，零付费 API。
 */
@DisplayName("阶段 2 上传→S3→PG→Redis Stream→检索 真实集成（Testcontainers 隔离）")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(KnowledgeBaseUploadPipelineIntegrationTest.DeterministicEmbeddingConfig.class)
@DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)
class KnowledgeBaseUploadPipelineIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16"))
        .withDatabaseName("kb_pipeline_test")
        .withUsername("test")
        .withPassword("test");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);

    // 对象存储：使用与生产/dev 相同的 RustFS 镜像（本机通常已缓存，避免额外拉取）；S3Config 已 forcePathStyle
    @Container
    static final GenericContainer<?> rustfs = new GenericContainer<>(
            DockerImageName.parse("rustfs/rustfs:latest"))
        .withCommand("/data")
        .withEnv("RUSTFS_ACCESS_KEY", "rustfsadmin")
        .withEnv("RUSTFS_SECRET_KEY", "rustfsadmin")
        .withEnv("RUSTFS_CONSOLE_ENABLE", "false")
        .withExposedPorts(9000)
        .waitingFor(Wait.forListeningPort());

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
        // 隔离的 S3：独立容器 + 独立 bucket，不触碰现有 dev 数据
        registry.add("app.storage.endpoint", () ->
            "http://" + rustfs.getHost() + ":" + rustfs.getMappedPort(9000));
        registry.add("app.storage.access-key", () -> "rustfsadmin");
        registry.add("app.storage.secret-key", () -> "rustfsadmin");
        registry.add("app.storage.bucket", () -> "kb-lifecycle-test");
        registry.add("app.storage.region", () -> "us-east-1");
        registry.add("app.storage.auto-create-bucket", () -> "true");
    }

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

    @MockitoBean
    LlmProviderRegistry llmProviderRegistry;
    @Autowired KnowledgeBaseUploadService uploadService;
    @Autowired KnowledgeBaseRepository knowledgeBaseRepository;
    @Autowired KnowledgeBaseVectorService vectorService;
    @Autowired KnowledgeBaseListService listService;
    @Autowired KnowledgeBaseConflictService conflictService;
    @Autowired FileStorageService storageService;
    @Autowired RedisService redisService;
    @Autowired VectorizeStreamProducer vectorizeStreamProducer;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;
    @Autowired TransactionalExecutor transactionalExecutor;

    @SuppressWarnings("unchecked")
    private Long extractId(Map<String, Object> result) {
        return ((Number) ((Map<String, Object>) result.get("knowledgeBase")).get("id")).longValue();
    }

    @SuppressWarnings("unchecked")
    private boolean isConflict(Map<String, Object> result) {
        return Boolean.TRUE.equals(result.get("conflict"));
    }

    private KnowledgeBaseEntity awaitStatus(Long id, VectorStatus expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        KnowledgeBaseEntity kb = null;
        while (System.currentTimeMillis() < deadline) {
            kb = knowledgeBaseRepository.findById(id).orElseThrow();
            if (kb.getVectorStatus() == expected) {
                return kb;
            }
            Thread.sleep(300);
        }
        return kb; // 最后一次快照，交给断言失败以暴露实际状态
    }

    private int vectorRowCount(Long kbId) {
        Integer c = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM vector_store WHERE metadata->>'kb_id' = ?",
            Integer.class, String.valueOf(kbId));
        return c == null ? 0 : c;
    }

    private MockMultipartFile mdFile(String name, String content) {
        return new MockMultipartFile("file", name, "text/markdown", content.getBytes());
    }

    @Test
    @DisplayName("上传→S3 对象存在 + PG 元数据落库 + Redis Stream 异步索引 COMPLETED + 按项目/环境检索命中")
    void uploadPipelinePersistsAndIndexesAsynchronously() throws Exception {
        MockMultipartFile file = mdFile("pay-gw-v1.md", "支付网关 生产环境 端口配置 8080 超时排查 runbook");

        Map<String, Object> result = uploadService.uploadKnowledgeBase(
            file, "支付网关排查", null, "payment-gateway", "production",
            "billing", "runbook", "wiki", "v1", null);
        Long id = extractId(result);

        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(id).orElseThrow();
        // PG 元数据落库
        assertThat(kb.getProject()).isEqualTo("billing");
        assertThat(kb.getDocType()).isEqualTo("runbook");
        assertThat(kb.getEnvironment()).isEqualTo("production");
        assertThat(kb.getService()).isEqualTo("payment-gateway");
        assertThat(kb.getVersionNo()).isEqualTo(1);
        assertThat(kb.getActive()).isTrue();
        assertThat(kb.getDocumentKey()).isNotBlank();
        // S3 对象真实存在且可下载
        assertThat(kb.getStorageKey()).isNotBlank();
        assertThat(storageService.fileExists(kb.getStorageKey())).isTrue();
        assertThat(storageService.downloadFile(kb.getStorageKey())).isNotEmpty();

        // Redis Stream 实际生产→消费：异步状态最终变 COMPLETED（消费者线程驱动）
        KnowledgeBaseEntity done = awaitStatus(id, VectorStatus.COMPLETED, 30_000);
        assertThat(done.getVectorStatus()).as("异步消费者应完成向量化并置 COMPLETED").isEqualTo(VectorStatus.COMPLETED);
        assertThat(done.getVectorError()).isNull();
        assertThat(vectorRowCount(id)).as("真实 pgvector 应写入向量").isGreaterThan(0);

        // 按项目/环境解析（resolve-context 仅 active）→ 检索命中
        var scope = listService.resolveContext("payment-gateway", "production", "billing");
        assertThat(scope).extracting(item -> item.id()).contains(id);
        List<Document> hits = vectorService.similaritySearch("端口配置", List.of(id), 5, 0.0);
        assertThat(hits).isNotEmpty();
    }

    @Test
    @DisplayName("新版本替换后旧版本退出检索；重试幂等（重复上传相同内容不新增）")
    void newVersionSupersedesOldAndRetryIsIdempotent() throws Exception {
        MockMultipartFile v1 = mdFile("pay-timeout-v1.md", "支付网关 生产环境 端口配置 8080");
        Long id1 = extractId(uploadService.uploadKnowledgeBase(
            v1, "支付网关超时排查", null, "payment-gw", "prod", "billing", "runbook", "wiki", "v1", null));
        awaitStatus(id1, VectorStatus.COMPLETED, 30_000);

        // 相同内容重试 → 幂等去重：返回 duplicate=true，不新增行/不新增版本
        Map<String, Object> dup = uploadService.uploadKnowledgeBase(
            mdFile("pay-timeout-v1.md", "支付网关 生产环境 端口配置 8080"),
            "支付网关超时排查", null, "payment-gw", "prod", "billing", "runbook", "wiki", "v1", null);
        assertThat(dup.get("duplicate")).isEqualTo(true);

        // 新内容 → 新版本 v2，同 documentKey
        Long id2 = extractId(uploadService.uploadKnowledgeBase(
            mdFile("pay-timeout-v1.md", "支付网关 生产环境 端口配置 9090（调整 maximumPoolSize 10→20）"),
            "支付网关超时排查", null, "payment-gw", "prod", "billing", "runbook", "wiki", "v2", null));
        KnowledgeBaseEntity kb2 = awaitStatus(id2, VectorStatus.COMPLETED, 30_000);
        assertThat(kb2.getVersionNo()).isEqualTo(2);

        // 旧版本 v1：停用 + 向量删除 + 退出检索
        KnowledgeBaseEntity kb1 = knowledgeBaseRepository.findById(id1).orElseThrow();
        assertThat(kb1.getDocumentKey()).isEqualTo(kb2.getDocumentKey());
        assertThat(kb1.getActive()).as("v1 应被 v2 取代后停用").isFalse();
        assertThat(vectorRowCount(id1)).as("旧版本向量应被删除").isZero();

        // resolve-context 只返回 active 的 v2；检索传入 [v1,v2] 也只召回 v2
        var scope = listService.resolveContext("payment-gw", "prod", "billing");
        assertThat(scope).extracting(item -> item.id()).contains(id2).doesNotContain(id1);
        List<Document> hits = vectorService.similaritySearch("端口配置", List.of(id1, id2), 5, 0.0);
        assertThat(hits).allSatisfy(doc ->
            assertThat(String.valueOf(doc.getMetadata().get("kb_id"))).isEqualTo(String.valueOf(id2)));
    }

    @Test
    @DisplayName("同 documentKey + 同 normalizedVersionLabel + 不同 fileHash → 冲突标记")
    void sameKeySameLabelDifferentHash_producesConflict() throws Exception {
        // 1. 上传 v1，versionLabel="v1.0"
        MockMultipartFile v1 = mdFile("conflict-doc-v1.md", "支付网关 生产环境 端口配置 8080 超时排查");
        Long id1 = extractId(uploadService.uploadKnowledgeBase(
            v1, "冲突测试文档", null, "conflict-svc", "prod", "billing", "runbook", "wiki", "v1.0", null));
        KnowledgeBaseEntity kb1 = awaitStatus(id1, VectorStatus.COMPLETED, 30_000);
        assertThat(kb1.getActive()).isTrue();
        assertThat(kb1.getConflict()).isFalse();

        // 2. 上传 v2，相同 versionLabel="v1.0" 但不同内容 → 冲突
        MockMultipartFile v2 = mdFile("conflict-doc-v2.md", "支付网关 生产环境 端口配置 9090 完全不同的内容");
        Map<String, Object> result2 = uploadService.uploadKnowledgeBase(
            v2, "冲突测试文档", null, "conflict-svc", "prod", "billing", "runbook", "wiki", "v1.0", null);
        Long id2 = extractId(result2);

        // 3. 断言 v2 被标记为冲突
        assertThat(isConflict(result2)).as("上传结果应标记 conflict=true").isTrue();
        KnowledgeBaseEntity kb2 = knowledgeBaseRepository.findById(id2).orElseThrow();
        assertThat(kb2.getActive()).as("冲突版本应 active=false").isFalse();
        assertThat(kb2.getConflict()).as("冲突版本应 conflict=true").isTrue();
        assertThat(kb2.getVectorStatus()).as("冲突版本应 vectorStatus=CONFLICT").isEqualTo(VectorStatus.CONFLICT);

        // 4. 断言 v1 仍然正常
        KnowledgeBaseEntity refreshedKb1 = knowledgeBaseRepository.findById(id1).orElseThrow();
        assertThat(refreshedKb1.getActive()).isTrue();
        assertThat(refreshedKb1.getConflict()).isFalse();

        // 5. resolve-context 只包含 v1（不包含冲突的 v2）
        var scope = listService.resolveContext("conflict-svc", "prod", "billing");
        assertThat(scope).extracting(item -> item.id()).contains(id1).doesNotContain(id2);

        // 6. v2 没有向量行
        assertThat(vectorRowCount(id2)).as("冲突版本不应有向量").isZero();
    }

    @Test
    @DisplayName("采纳冲突版本：激活新版本并停用旧版本")
    void adoptConflictVersion_activatesNewAndRetiresOld() throws Exception {
        // 1. 设置冲突场景
        MockMultipartFile v1 = mdFile("adopt-doc-v1.md", "采纳测试 支付网关 生产环境 端口配置 8080 超时排查");
        Long id1 = extractId(uploadService.uploadKnowledgeBase(
            v1, "采纳测试文档", null, "adopt-svc", "prod", "billing", "runbook", "wiki", "v1.0", null));
        awaitStatus(id1, VectorStatus.COMPLETED, 30_000);

        MockMultipartFile v2 = mdFile("adopt-doc-v2.md", "支付网关 生产环境 端口配置 9090 不同内容");
        Long id2 = extractId(uploadService.uploadKnowledgeBase(
            v2, "采纳测试文档", null, "adopt-svc", "prod", "billing", "runbook", "wiki", "v1.0", null));

        KnowledgeBaseEntity kb2Before = knowledgeBaseRepository.findById(id2).orElseThrow();
        assertThat(kb2Before.getConflict()).isTrue();

        // 2. 采纳冲突版本
        conflictService.adoptVersion(id2);

        // 3. 等待异步向量化完成（消费者执行 promote 逻辑）
        KnowledgeBaseEntity kb2Done = awaitStatus(id2, VectorStatus.COMPLETED, 30_000);
        assertThat(kb2Done.getVectorStatus()).isEqualTo(VectorStatus.COMPLETED);
        assertThat(kb2Done.getActive()).as("冲突版本应被激活").isTrue();
        assertThat(kb2Done.getConflict()).as("冲突标记应被清除").isFalse();

        // 4. 断言 v1 被停用
        KnowledgeBaseEntity kb1After = knowledgeBaseRepository.findById(id1).orElseThrow();
        assertThat(kb1After.getActive()).as("旧版本应被停用").isFalse();

        // 5. 断言向量数据
        assertThat(vectorRowCount(id2)).as("采纳后应向量化").isGreaterThan(0);

        // 6. resolve-context 包含 v2 不包含 v1
        var scope = listService.resolveContext("adopt-svc", "prod", "billing");
        assertThat(scope).extracting(item -> item.id()).contains(id2).doesNotContain(id1);
    }

    @Test
    @DisplayName("放弃冲突版本：保持原 active 版本不受影响")
    void abandonConflictVersion_keepsActiveUntouched() throws Exception {
        // 1. 设置冲突场景
        MockMultipartFile v1 = mdFile("abandon-doc-v1.md", "放弃测试 支付网关 生产环境 端口配置 8080 连接池");
        Long id1 = extractId(uploadService.uploadKnowledgeBase(
            v1, "放弃测试文档", null, "abandon-svc", "prod", "billing", "runbook", "wiki", "v1.0", null));
        awaitStatus(id1, VectorStatus.COMPLETED, 30_000);

        MockMultipartFile v2 = mdFile("abandon-doc-v2.md", "放弃测试 支付网关 生产环境 端口配置 9090 新版本内容");
        Long id2 = extractId(uploadService.uploadKnowledgeBase(
            v2, "放弃测试文档", null, "abandon-svc", "prod", "billing", "runbook", "wiki", "v1.0", null));

        KnowledgeBaseEntity kb2Before = knowledgeBaseRepository.findById(id2).orElseThrow();
        assertThat(kb2Before.getConflict()).isTrue();

        // 2. 放弃冲突版本
        conflictService.abandonVersion(id2);

        // 3. 断言 v2 放弃语义：conflict=false, active=false, vectorStatus=ABANDONED
        KnowledgeBaseEntity kb2After = knowledgeBaseRepository.findById(id2).orElseThrow();
        assertThat(kb2After.getActive()).as("放弃的版本应保持 active=false").isFalse();
        assertThat(kb2After.getConflict()).as("冲突标记应被清除").isFalse();
        assertThat(kb2After.getVectorStatus())
            .as("放弃后 vectorStatus 应设为 ABANDONED")
            .isEqualTo(VectorStatus.ABANDONED);

        // 4. 断言 v1 不受影响
        KnowledgeBaseEntity kb1After = knowledgeBaseRepository.findById(id1).orElseThrow();
        assertThat(kb1After.getActive()).as("原 active 版本应不受影响").isTrue();
        assertThat(kb1After.getConflict()).isFalse();

        // 5. resolve-context 仍包含 v1
        var scope = listService.resolveContext("abandon-svc", "prod", "billing");
        assertThat(scope).extracting(item -> item.id()).contains(id1).doesNotContain(id2);
    }

    @Test
    @DisplayName("相同内容重复上传 → 幂等去重，不新增版本")
    void sameHash_idempotent_noNewVersion() throws Exception {
        MockMultipartFile file = mdFile("idempotent-doc.md", "幂等性测试 独特内容 abcdef123456 端口配置 8080");
        Long id1 = extractId(uploadService.uploadKnowledgeBase(
            file, "幂等测试文档", null, "idempotent-svc", "prod", "billing", "runbook", "wiki", "v1.0", null));
        awaitStatus(id1, VectorStatus.COMPLETED, 30_000);

        // 重复上传相同内容
        Map<String, Object> dup = uploadService.uploadKnowledgeBase(
            mdFile("idempotent-doc.md", "幂等性测试 独特内容 abcdef123456 端口配置 8080"),
            "幂等测试文档", null, "idempotent-svc", "prod", "billing", "runbook", "wiki", "v1.0", null);
        assertThat(dup.get("duplicate")).isEqualTo(true);

        // 确认没有新增行
        long count = knowledgeBaseRepository.findAllByOrderByUploadedAtDesc().stream()
            .filter(kb -> "idempotent-svc".equals(kb.getService()))
            .count();
        assertThat(count).as("重复上传不应新增行").isEqualTo(1);
    }

    @Test
    @DisplayName("不同 versionLabel + 不同内容 → 正常版本升级，非冲突")
    void differentLabelDifferentHash_normalUpgrade() throws Exception {
        // 1. 上传 v1.0
        MockMultipartFile v1 = mdFile("upgrade-doc-v1.md", "升级测试 支付网关 生产环境 端口配置 8080 线程池");
        Long id1 = extractId(uploadService.uploadKnowledgeBase(
            v1, "升级测试文档", null, "upgrade-svc", "prod", "billing", "runbook", "wiki", "v1.0", null));
        awaitStatus(id1, VectorStatus.COMPLETED, 30_000);

        // 2. 上传 v2.0（不同 label，不同内容）
        MockMultipartFile v2 = mdFile("upgrade-doc-v2.md", "支付网关 生产环境 端口配置 9090 新内容");
        Map<String, Object> result2 = uploadService.uploadKnowledgeBase(
            v2, "升级测试文档", null, "upgrade-svc", "prod", "billing", "runbook", "wiki", "v2.0", null);
        Long id2 = extractId(result2);

        // 3. 断言：正常升级，非冲突
        assertThat(isConflict(result2)).as("正常升级不应标记冲突").isFalse();
        KnowledgeBaseEntity kb2 = awaitStatus(id2, VectorStatus.COMPLETED, 30_000);
        assertThat(kb2.getActive()).as("新版本应 active=true").isTrue();
        assertThat(kb2.getConflict()).as("新版本应 conflict=false").isFalse();

        // 4. 旧版本被正常停用
        KnowledgeBaseEntity kb1 = knowledgeBaseRepository.findById(id1).orElseThrow();
        assertThat(kb1.getActive()).as("旧版本应被正常停用").isFalse();
        assertThat(kb1.getConflict()).isFalse();

        // 5. resolve-context 只包含 v2
        var scope = listService.resolveContext("upgrade-svc", "prod", "billing");
        assertThat(scope).extracting(item -> item.id()).contains(id2).doesNotContain(id1);
    }

    // ─────────── 辅助方法 ───────────

    /**
     * 创建冲突场景：上传 v1（active+COMPLETED），再上传同 versionLabel 不同内容的 v2（conflict）。
     * @return [id1, id2]
     */
    private Long[] createConflictScenario(String svcPrefix) throws Exception {
        MockMultipartFile v1 = mdFile(svcPrefix + "-v1.md",
            svcPrefix + " 支付网关 生产环境 端口配置 8080 超时排查 runbook");
        Long id1 = extractId(uploadService.uploadKnowledgeBase(
            v1, svcPrefix + "文档", null, svcPrefix, "prod", "billing",
            "runbook", "wiki", "v1.0", null));
        awaitStatus(id1, VectorStatus.COMPLETED, 30_000);

        MockMultipartFile v2 = mdFile(svcPrefix + "-v2.md",
            svcPrefix + " 支付网关 生产环境 端口配置 9090 完全不同内容");
        Long id2 = extractId(uploadService.uploadKnowledgeBase(
            v2, svcPrefix + "文档", null, svcPrefix, "prod", "billing",
            "runbook", "wiki", "v1.0", null));
        return new Long[] { id1, id2 };
    }

    // ─────────── 新增测试：并发 + 失败路径 + 状态机 ───────────

    @Test
    @DisplayName("并发上传同 documentKey + 同 versionLabel → 恰好一个 active + 一个 conflict")
    void concurrentUploadSameKeySameLabel_oneActiveOneConflict() throws Exception {
        // 预上传 v1 建立 documentKey
        MockMultipartFile v1 = mdFile("concurrent-base.md",
            "并发测试 支付网关 生产环境 端口配置 8080 基础版本");
        Long id1 = extractId(uploadService.uploadKnowledgeBase(
            v1, "并发测试文档", null, "concurrent-svc", "prod", "billing",
            "runbook", "wiki", "v1.0", null));
        awaitStatus(id1, VectorStatus.COMPLETED, 30_000);

        // 两个线程同时上传同 versionLabel 不同内容
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        var futureA = executor.submit(() -> {
            ready.countDown();
            try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            MockMultipartFile file = mdFile("concurrent-a.md",
                "并发测试 支付网关 生产环境 端口配置 AAA 线程A内容");
            return uploadService.uploadKnowledgeBase(
                file, "并发测试文档", null, "concurrent-svc", "prod", "billing",
                "runbook", "wiki", "v2.0", null);
        });
        var futureB = executor.submit(() -> {
            ready.countDown();
            try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            MockMultipartFile file = mdFile("concurrent-b.md",
                "并发测试 支付网关 生产环境 端口配置 BBB 线程B内容");
            return uploadService.uploadKnowledgeBase(
                file, "并发测试文档", null, "concurrent-svc", "prod", "billing",
                "runbook", "wiki", "v2.0", null);
        });

        ready.await(5, TimeUnit.SECONDS);
        go.countDown(); // 同时释放

        Map<String, Object> resultA = futureA.get(60, TimeUnit.SECONDS);
        Map<String, Object> resultB = futureB.get(60, TimeUnit.SECONDS);
        executor.shutdown();

        Long idA = extractId(resultA);
        Long idB = extractId(resultB);
        assertThat(idA).isNotEqualTo(idB);

        // 等待两个记录的异步处理完成
        awaitStatus(idA, VectorStatus.COMPLETED, 30_000);
        // idB 可能是 COMPLETED 或 CONFLICT，取决于是否触发并发冲突
        KnowledgeBaseEntity kbB = knowledgeBaseRepository.findById(idB).orElseThrow();

        // 查询同 documentKey 的所有记录（排除预置的 v1）
        String docKey = knowledgeBaseRepository.findById(idA).orElseThrow().getDocumentKey();
        List<KnowledgeBaseEntity> allForDoc = knowledgeBaseRepository
            .findByDocumentKeyOrderByVersionNoDesc(docKey);
        List<KnowledgeBaseEntity> concurrentRecords = allForDoc.stream()
            .filter(e -> e.getVersionNo() > 1) // 排除预置的 v1
            .toList();

        assertThat(concurrentRecords).hasSize(2);

        // 恰好一个 active=true, conflict=false
        long activeCount = concurrentRecords.stream()
            .filter(e -> Boolean.TRUE.equals(e.getActive()) && Boolean.FALSE.equals(e.getConflict()))
            .count();
        // 恰好一个 active=false, conflict=true, vectorStatus=CONFLICT
        long conflictCount = concurrentRecords.stream()
            .filter(e -> Boolean.FALSE.equals(e.getActive())
                && Boolean.TRUE.equals(e.getConflict())
                && e.getVectorStatus() == VectorStatus.CONFLICT)
            .count();

        assertThat(activeCount).as("应恰好一个 active 记录").isEqualTo(1);
        assertThat(conflictCount).as("应恰好一个 conflict 记录").isGreaterThanOrEqualTo(1);

        // 两条记录有不同的 fileHash/storageKey
        KnowledgeBaseEntity kbA = knowledgeBaseRepository.findById(idA).orElseThrow();
        assertThat(kbA.getFileHash()).isNotEqualTo(kbB.getFileHash());
        assertThat(kbA.getStorageKey()).isNotEqualTo(kbB.getStorageKey());

        // resolve-context 只返回 active 的
        var scope = listService.resolveContext("concurrent-svc", "prod", "billing");
        Long activeId = concurrentRecords.stream()
            .filter(e -> Boolean.TRUE.equals(e.getActive()))
            .findFirst().orElseThrow().getId();
        Long conflictId = concurrentRecords.stream()
            .filter(e -> Boolean.FALSE.equals(e.getActive()))
            .findFirst().orElseThrow().getId();
        assertThat(scope).extracting(item -> item.id())
            .contains(activeId).doesNotContain(conflictId);
    }

    @Test
    @DisplayName("采纳时 S3 下载失败 → 抛出 BusinessException，旧版本保持 active，冲突记录回退 CONFLICT")
    void adoptFailure_s3ParseError_throwsAndOldVersionStaysActive() throws Exception {
        Long[] ids = createConflictScenario("s3fail");
        Long id1 = ids[0], id2 = ids[1];

        // 篡改冲突记录的 storageKey 为不存在的 key，模拟 S3 下载失败
        KnowledgeBaseEntity kb2 = knowledgeBaseRepository.findById(id2).orElseThrow();
        assertThat(kb2.getConflict()).isTrue();
        kb2.setStorageKey("nonexistent-key-that-will-fail-on-download");
        knowledgeBaseRepository.save(kb2);

        // 执行采纳 → 现在抛出 BusinessException
        assertThatThrownBy(() -> conflictService.adoptVersion(id2))
            .isInstanceOf(interview.guide.common.exception.BusinessException.class)
            .hasMessageContaining("S3 下载失败");

        // 断言：冲突记录回退到 CONFLICT（不是 ADOPTING）
        KnowledgeBaseEntity kb2After = knowledgeBaseRepository.findById(id2).orElseThrow();
        assertThat(kb2After.getVectorStatus())
            .as("S3 失败后应回退到 CONFLICT")
            .isEqualTo(VectorStatus.CONFLICT);
        assertThat(kb2After.getVectorError()).contains("S3 下载失败");

        // 断言：旧版本仍然 active
        KnowledgeBaseEntity kb1After = knowledgeBaseRepository.findById(id1).orElseThrow();
        assertThat(kb1After.getActive()).as("旧版本应保持 active").isTrue();
        assertThat(kb1After.getVectorStatus()).isEqualTo(VectorStatus.COMPLETED);

        // 旧版本向量仍可检索
        assertThat(vectorRowCount(id1)).isGreaterThan(0);
    }

    @Test
    @DisplayName("采纳成功：记录 active ID 切换，旧版本向量删除，新版本 COMPLETED")
    void adoptSuccess_newVectorizedThenActiveSwitched() throws Exception {
        Long[] ids = createConflictScenario("adoptok");
        Long id1 = ids[0], id2 = ids[1];

        // 确认 id1 是 active 的
        KnowledgeBaseEntity kb1Before = knowledgeBaseRepository.findById(id1).orElseThrow();
        assertThat(kb1Before.getActive()).isTrue();
        assertThat(kb1Before.getVectorStatus()).isEqualTo(VectorStatus.COMPLETED);
        KnowledgeBaseEntity kb2Before = knowledgeBaseRepository.findById(id2).orElseThrow();
        assertThat(kb2Before.getConflict()).isTrue();
        assertThat(kb2Before.getActive()).isFalse();

        // 执行采纳
        conflictService.adoptVersion(id2);

        // 等待异步向量化完成（消费者执行 promote 逻辑）
        KnowledgeBaseEntity kb2Done = awaitStatus(id2, VectorStatus.COMPLETED, 30_000);
        assertThat(kb2Done.getVectorStatus()).isEqualTo(VectorStatus.COMPLETED);
        assertThat(kb2Done.getActive()).isTrue();
        assertThat(kb2Done.getConflict()).isFalse();

        // 旧版本被停用 + 向量删除
        KnowledgeBaseEntity kb1After = knowledgeBaseRepository.findById(id1).orElseThrow();
        assertThat(kb1After.getActive()).as("旧版本应被停用").isFalse();
        assertThat(vectorRowCount(id1)).as("旧版本向量应被删除").isZero();

        // 新版本有向量
        assertThat(vectorRowCount(id2)).isGreaterThan(0);

        // active ID 确实发生了切换：采纳前 id1 是 active，采纳后 id2 是 active
        assertThat(kb1Before.getActive()).as("采纳前 id1 是 active").isTrue();
        assertThat(kb2Before.getActive()).as("采纳前 id2 不是 active").isFalse();
        assertThat(kb2Done.getActive()).as("采纳后 id2 是 active").isTrue();
        assertThat(kb1After.getActive()).as("采纳后 id1 不是 active").isFalse();
    }

    @Test
    @DisplayName("重复采纳同一冲突版本 → 第二次幂等无效果（已完成状态）")
    void adoptIdempotent_duplicateClickNoEffect() throws Exception {
        Long[] ids = createConflictScenario("adoptdup");
        Long id2 = ids[1];

        // 第一次采纳
        conflictService.adoptVersion(id2);

        // 等待完成
        awaitStatus(id2, VectorStatus.COMPLETED, 30_000);

        // 第二次采纳：记录已完成（COMPLETED + conflict=false），幂等返回
        KnowledgeBaseEntity kb2AfterFirst = knowledgeBaseRepository.findById(id2).orElseThrow();
        assertThat(kb2AfterFirst.getConflict()).isFalse();
        assertThat(kb2AfterFirst.getActive()).isTrue();

        // 第二次调用不应抛出异常（幂等）
        conflictService.adoptVersion(id2);

        // 状态不变
        KnowledgeBaseEntity kb2AfterSecond = knowledgeBaseRepository.findById(id2).orElseThrow();
        assertThat(kb2AfterSecond.getActive()).isTrue();
        assertThat(kb2AfterSecond.getVectorStatus()).isEqualTo(VectorStatus.COMPLETED);
    }

    @Test
    @DisplayName("放弃后 conflict=false + active=false，当前 active 不受影响")
    void abandonClearsConflictAndActive_activeUntouched() throws Exception {
        Long[] ids = createConflictScenario("abnst");
        Long id1 = ids[0], id2 = ids[1];

        conflictService.abandonVersion(id2);

        KnowledgeBaseEntity kb2 = knowledgeBaseRepository.findById(id2).orElseThrow();
        assertThat(kb2.getConflict())
            .as("放弃后 conflict 应被清除")
            .isFalse();
        assertThat(kb2.getActive()).isFalse();
        // vectorStatus 设为 ABANDONED
        assertThat(kb2.getVectorStatus()).isEqualTo(VectorStatus.ABANDONED);

        // 当前 active 版本不受影响
        KnowledgeBaseEntity kb1 = knowledgeBaseRepository.findById(id1).orElseThrow();
        assertThat(kb1.getActive()).isTrue();
        assertThat(kb1.getVectorStatus()).isEqualTo(VectorStatus.COMPLETED);
    }

    @Test
    @DisplayName("重复放弃同一冲突版本 → 第二次幂等无效果")
    void abandonIdempotent() throws Exception {
        Long[] ids = createConflictScenario("abndup");
        Long id2 = ids[1];

        // 第一次放弃
        conflictService.abandonVersion(id2);
        KnowledgeBaseEntity kb2First = knowledgeBaseRepository.findById(id2).orElseThrow();
        assertThat(kb2First.getConflict()).isFalse();
        assertThat(kb2First.getActive()).isFalse();
        assertThat(kb2First.getVectorStatus()).isEqualTo(VectorStatus.ABANDONED);

        // 第二次放弃：幂等，不抛异常
        conflictService.abandonVersion(id2);

        // 状态不变
        KnowledgeBaseEntity kb2Second = knowledgeBaseRepository.findById(id2).orElseThrow();
        assertThat(kb2Second.getConflict()).isFalse();
        assertThat(kb2Second.getActive()).isFalse();
        assertThat(kb2Second.getVectorStatus()).isEqualTo(VectorStatus.ABANDONED);
    }

    @Test
    @DisplayName("已放弃的版本无法采纳 → 抛出 BusinessException（'版本已放弃'）")
    void abandonedCannotAdopt() throws Exception {
        Long[] ids = createConflictScenario("abnadopt");
        Long id1 = ids[0], id2 = ids[1];

        // 先放弃
        conflictService.abandonVersion(id2);
        KnowledgeBaseEntity kb2 = knowledgeBaseRepository.findById(id2).orElseThrow();
        assertThat(kb2.getConflict()).isFalse();
        assertThat(kb2.getActive()).isFalse();
        assertThat(kb2.getVectorStatus()).isEqualTo(VectorStatus.ABANDONED);

        // 尝试采纳已放弃的版本 → 抛 BusinessException
        assertThatThrownBy(() -> conflictService.adoptVersion(id2))
            .isInstanceOf(interview.guide.common.exception.BusinessException.class)
            .hasMessageContaining("已放弃");

        // 原 active 版本不受影响
        KnowledgeBaseEntity kb1 = knowledgeBaseRepository.findById(id1).orElseThrow();
        assertThat(kb1.getActive()).isTrue();
        assertThat(kb1.getVectorStatus()).isEqualTo(VectorStatus.COMPLETED);
    }

    @Test
    @DisplayName("已放弃的版本无法重新向量化 → 抛出 BusinessException")
    void abandonedCannotRevectorize() throws Exception {
        Long[] ids = createConflictScenario("abnrevec");
        Long id2 = ids[1];

        // 放弃
        conflictService.abandonVersion(id2);
        KnowledgeBaseEntity kb2 = knowledgeBaseRepository.findById(id2).orElseThrow();
        assertThat(kb2.getConflict()).isFalse();
        assertThat(kb2.getActive()).isFalse();
        assertThat(kb2.getVectorStatus()).isEqualTo(VectorStatus.ABANDONED);

        // 重新向量化应抛出 BusinessException
        assertThatThrownBy(() -> uploadService.revectorize(id2))
            .isInstanceOf(interview.guide.common.exception.BusinessException.class)
            .hasMessageContaining("已放弃");
    }

    @Test
    @DisplayName("并发采纳同一冲突记录 → 只有一个线程执行向量化，另一个幂等返回")
    void concurrentAdopt_sameConflictRecord_onlyOneSucceeds() throws Exception {
        Long[] ids = createConflictScenario("concadpt");
        Long id1 = ids[0], id2 = ids[1];

        // 确认冲突状态
        KnowledgeBaseEntity kb2Before = knowledgeBaseRepository.findById(id2).orElseThrow();
        assertThat(kb2Before.getConflict()).isTrue();
        assertThat(kb2Before.getVectorStatus()).isEqualTo(VectorStatus.CONFLICT);

        // 记录并发 adopt 前 Redis Stream 的消息数
        String streamKey = AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY;
        long streamSizeBefore = redisService.streamLen(streamKey);

        // 两个线程同时采纳
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        var futureA = executor.submit(() -> {
            ready.countDown();
            try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            conflictService.adoptVersion(id2);
        });
        var futureB = executor.submit(() -> {
            ready.countDown();
            try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            conflictService.adoptVersion(id2);
        });

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();

        // 两个线程都应正常完成（一个执行，一个幂等）
        futureA.get(60, TimeUnit.SECONDS);
        futureB.get(60, TimeUnit.SECONDS);
        executor.shutdown();

        // 断言：并发 adopt 只向 Redis Stream 投递了恰好 1 条消息
        long streamSizeAfter = redisService.streamLen(streamKey);
        long newMessages = streamSizeAfter - streamSizeBefore;
        assertThat(newMessages)
            .as("并发 adopt 应只向 Redis Stream 投递恰好 1 条消息，实际新增 %d 条", newMessages)
            .isEqualTo(1);

        // 等待异步向量化完成
        KnowledgeBaseEntity kb2Done = awaitStatus(id2, VectorStatus.COMPLETED, 30_000);
        assertThat(kb2Done.getVectorStatus()).isEqualTo(VectorStatus.COMPLETED);
        assertThat(kb2Done.getActive()).as("冲突版本应被激活").isTrue();
        assertThat(kb2Done.getConflict()).as("冲突标记应被清除").isFalse();

        // 旧版本被停用
        KnowledgeBaseEntity kb1After = knowledgeBaseRepository.findById(id1).orElseThrow();
        assertThat(kb1After.getActive()).as("旧版本应被停用").isFalse();

        // 新版本有向量
        assertThat(vectorRowCount(id2)).isGreaterThan(0);
    }

    @Test
    @DisplayName("并发 adopt vs abandon：恰好一个操作成功，状态一致")
    void concurrentAdoptAndAbandon_onlyOneWins() throws Exception {
        Long[] ids = createConflictScenario("conadoptabn");
        Long id1 = ids[0], id2 = ids[1];

        // 确认冲突状态
        KnowledgeBaseEntity kb2Before = knowledgeBaseRepository.findById(id2).orElseThrow();
        assertThat(kb2Before.getConflict()).isTrue();
        assertThat(kb2Before.getVectorStatus()).isEqualTo(VectorStatus.CONFLICT);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> adoptError = new AtomicReference<>();
        AtomicReference<Throwable> abandonError = new AtomicReference<>();

        // Thread 1: adopt
        Future<?> adoptFuture = executor.submit(() -> {
            ready.countDown();
            try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            try {
                conflictService.adoptVersion(id2);
            } catch (Throwable t) {
                adoptError.set(t);
            }
        });

        // Thread 2: abandon
        Future<?> abandonFuture = executor.submit(() -> {
            ready.countDown();
            try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            try {
                conflictService.abandonVersion(id2);
            } catch (Throwable t) {
                abandonError.set(t);
            }
        });

        ready.await(5, TimeUnit.SECONDS);
        go.countDown(); // 同时释放

        adoptFuture.get(60, TimeUnit.SECONDS);
        abandonFuture.get(60, TimeUnit.SECONDS);
        executor.shutdown();

        // 恰好一个操作成功：另一个应抛异常
        Throwable adoptEx = adoptError.get();
        Throwable abandonEx = abandonError.get();

        // 两种情况：
        // 1. adopt 成功（adoptEx==null），abandon 抛异常（abandonEx!=null）
        // 2. abandon 成功（abandonEx==null），adopt 抛异常（adoptEx!=null）
        boolean adoptWon = (adoptEx == null);
        boolean abandonWon = (abandonEx == null);

        // 不能两个都成功或都失败
        assertThat(adoptWon ^ abandonWon)
            .as("恰好一个操作成功（XOR）").isTrue();

        if (adoptWon) {
            // adopt 赢了：同步部分发 Redis Stream 消息，异步消费者完成向量化和 promote
            // 轮询等待终态 COMPLETED
            KnowledgeBaseEntity kb2Final = awaitStatus(id2, VectorStatus.COMPLETED, 30_000);
            assertThat(kb2Final.getVectorStatus())
                .as("adopt 赢时最终状态应为 COMPLETED")
                .isEqualTo(VectorStatus.COMPLETED);
            assertThat(kb2Final.getActive()).as("adopt 赢时新版本应被激活").isTrue();
            assertThat(kb2Final.getConflict()).as("冲突标记应被清除").isFalse();
            // abandon 应抛异常
            assertThat(abandonEx).isNotNull();
            // 旧版本应被停用
            KnowledgeBaseEntity kb1Final = knowledgeBaseRepository.findById(id1).orElseThrow();
            assertThat(kb1Final.getActive())
                .as("adopt 赢时旧版本应被停用").isFalse();
        } else {
            // abandon 赢了：最终状态应是 ABANDONED
            entityManager.clear();
            KnowledgeBaseEntity kb2Final = knowledgeBaseRepository.findById(id2).orElseThrow();
            KnowledgeBaseEntity kb1Final = knowledgeBaseRepository.findById(id1).orElseThrow();
            assertThat(kb2Final.getVectorStatus())
                .as("abandon 赢时最终状态应为 ABANDONED")
                .isEqualTo(VectorStatus.ABANDONED);
            assertThat(kb2Final.getActive()).isFalse();
            assertThat(kb2Final.getConflict()).isFalse();
            // adopt 应抛异常
            assertThat(adoptEx).isNotNull();
            // 旧版本应保持 active
            assertThat(kb1Final.getActive())
                .as("abandon 赢时旧版本应保持 active").isTrue();
        }
    }

    @Test
    @DisplayName("消费者领取前 abandon 先成功 → 原子领取必须失败，最终状态 ABANDONED")
    void consumerClaim_afterAbandon_mustFail() throws Exception {
        Long[] ids = createConflictScenario("claimabn");
        Long id1 = ids[0], id2 = ids[1];

        // 确认冲突状态
        KnowledgeBaseEntity kb2Before = knowledgeBaseRepository.findById(id2).orElseThrow();
        assertThat(kb2Before.getConflict()).isTrue();
        assertThat(kb2Before.getVectorStatus()).isEqualTo(VectorStatus.CONFLICT);

        // 使用真实业务操作：abandonVersion 原子 CAS CONFLICT→ABANDONED
        conflictService.abandonVersion(id2);

        entityManager.clear();
        KnowledgeBaseEntity kb2Abandoned = knowledgeBaseRepository.findById(id2).orElseThrow();
        assertThat(kb2Abandoned.getVectorStatus()).isEqualTo(VectorStatus.ABANDONED);

        // 消费者尝试原子领取 → 必须返回 0（ADOPTING/PENDING 条件不满足）
        int claimAffected = transactionalExecutor.call(
            () -> knowledgeBaseRepository.tryClaimForProcessing(id2));
        assertThat(claimAffected)
            .as("abandon 后原子领取应返回 0")
            .isEqualTo(0);

        // 最终状态断言
        entityManager.clear();
        KnowledgeBaseEntity kb2Final = knowledgeBaseRepository.findById(id2).orElseThrow();
        assertThat(kb2Final.getVectorStatus())
            .as("最终状态应为 ABANDONED")
            .isEqualTo(VectorStatus.ABANDONED);
        assertThat(kb2Final.getActive()).isFalse();
        assertThat(kb2Final.getConflict()).isFalse();

        // 旧版本仍然 active
        KnowledgeBaseEntity kb1Final = knowledgeBaseRepository.findById(id1).orElseThrow();
        assertThat(kb1Final.getActive())
            .as("旧版本应保持 active=true")
            .isTrue();
        assertThat(kb1Final.getVectorStatus()).isEqualTo(VectorStatus.COMPLETED);

        // 没有向量化工作被完成（ABANDONED 版本不应有向量）
        assertThat(vectorRowCount(id2))
            .as("ABANDONED 版本不应有向量数据")
            .isZero();
    }

    @Test
    @DisplayName("adopt 向量化重试：首次失败后重置 ADOPTING，重试成功后 COMPLETED + active")
    void adoptVectorizeRetry_successAfterFailure() throws Exception {
        Long[] ids = createConflictScenario("retryok");
        Long id1 = ids[0], id2 = ids[1];

        // 手动启动 adopt：CONFLICT → ADOPTING
        int adoptAffected = transactionalExecutor.call(
            () -> knowledgeBaseRepository.tryStartAdopt(id2));
        assertThat(adoptAffected).isEqualTo(1);

        // 模拟消费者领取：ADOPTING → PROCESSING
        int claimAffected = transactionalExecutor.call(
            () -> knowledgeBaseRepository.tryClaimForProcessing(id2));
        assertThat(claimAffected).isEqualTo(1);

        entityManager.clear();
        KnowledgeBaseEntity kb2Processing = knowledgeBaseRepository.findById(id2).orElseThrow();
        assertThat(kb2Processing.getVectorStatus()).isEqualTo(VectorStatus.PROCESSING);

        // 模拟向量化失败 → 消费者 retryMessage 重置 PROCESSING → ADOPTING
        vectorizeStreamProducer.retryMessageForAdopt(id2);

        entityManager.clear();
        KnowledgeBaseEntity kb2Retry = knowledgeBaseRepository.findById(id2).orElseThrow();
        assertThat(kb2Retry.getVectorStatus())
            .as("重试时应重置为 ADOPTING，使 tryClaimForProcessing 可重新领取")
            .isEqualTo(VectorStatus.ADOPTING);

        // 重试领取：ADOPTING → PROCESSING
        int retryClaim = transactionalExecutor.call(
            () -> knowledgeBaseRepository.tryClaimForProcessing(id2));
        assertThat(retryClaim).as("重试应能成功领取").isEqualTo(1);

        // 模拟重试成功：promote（停用旧版本 + 完成新版本）
        entityManager.clear();
        KnowledgeBaseEntity kb1 = knowledgeBaseRepository.findById(id1).orElseThrow();
        int promoteAffected = transactionalExecutor.call(() -> {
            kb1.setActive(false);
            knowledgeBaseRepository.save(kb1);
            return knowledgeBaseRepository.tryCompleteAdopt(id2);
        });
        assertThat(promoteAffected).isEqualTo(1);

        // 最终断言
        entityManager.clear();
        KnowledgeBaseEntity kb2Final = knowledgeBaseRepository.findById(id2).orElseThrow();
        assertThat(kb2Final.getVectorStatus()).isEqualTo(VectorStatus.COMPLETED);
        assertThat(kb2Final.getActive()).as("重试成功后新版本应被激活").isTrue();
        assertThat(kb2Final.getConflict()).isFalse();

        KnowledgeBaseEntity kb1Final = knowledgeBaseRepository.findById(id1).orElseThrow();
        assertThat(kb1Final.getActive()).as("旧版本应被停用").isFalse();
    }

    @Test
    @DisplayName("adopt 向量化最大重试次数：最终状态 CONFLICT（不卡在 PROCESSING），旧版本仍 active")
    void adoptVectorizeMaxRetries_exitsProcessing() throws Exception {
        Long[] ids = createConflictScenario("maxretry");
        Long id1 = ids[0], id2 = ids[1];

        // 手动启动 adopt：CONFLICT → ADOPTING
        int adoptAffected = transactionalExecutor.call(
            () -> knowledgeBaseRepository.tryStartAdopt(id2));
        assertThat(adoptAffected).isEqualTo(1);

        // 模拟消费者领取：ADOPTING → PROCESSING
        int claimAffected = transactionalExecutor.call(
            () -> knowledgeBaseRepository.tryClaimForProcessing(id2));
        assertThat(claimAffected).isEqualTo(1);

        entityManager.clear();
        KnowledgeBaseEntity kb2 = knowledgeBaseRepository.findById(id2).orElseThrow();
        assertThat(kb2.getVectorStatus()).isEqualTo(VectorStatus.PROCESSING);

        // 模拟最大重试次数超过：markFailed 设置 CONFLICT
        String maxRetryError = "向量化 failed after retry 3: embedding timeout";
        vectorizeStreamProducer.markFailedForAdopt(id2, maxRetryError);

        // 最终断言
        entityManager.clear();
        KnowledgeBaseEntity kb2Final = knowledgeBaseRepository.findById(id2).orElseThrow();
        assertThat(kb2Final.getVectorStatus())
            .as("最大重试后应回到 CONFLICT（不卡在 PROCESSING）")
            .isEqualTo(VectorStatus.CONFLICT);
        assertThat(kb2Final.getVectorError())
            .as("vectorError 应包含最大重试信息")
            .contains("failed after retry");

        // 旧版本仍然 active
        KnowledgeBaseEntity kb1Final = knowledgeBaseRepository.findById(id1).orElseThrow();
        assertThat(kb1Final.getActive())
            .as("最大重试失败后旧版本应保持 active")
            .isTrue();
        assertThat(kb1Final.getVectorStatus()).isEqualTo(VectorStatus.COMPLETED);
        assertThat(vectorRowCount(id1)).isGreaterThan(0);
        assertThat(vectorRowCount(id2)).isZero();
    }

    @Test
    @DisplayName("resolve-context 只返回 active 版本（排除 conflict + abandoned 记录）")
    void resolveContextOnlyReturnsActive() throws Exception {
        // 创建 active 版本
        MockMultipartFile v1 = mdFile("resolve-v1.md",
            "resolve测试 支付网关 生产环境 端口配置 8080 基础版本");
        Long activeId = extractId(uploadService.uploadKnowledgeBase(
            v1, "resolve测试文档", null, "resolve-svc", "prod", "billing",
            "runbook", "wiki", "v1.0", null));
        awaitStatus(activeId, VectorStatus.COMPLETED, 30_000);

        // 创建冲突版本
        MockMultipartFile v2 = mdFile("resolve-v2.md",
            "resolve测试 支付网关 生产环境 端口配置 9090 冲突内容");
        Long conflictId = extractId(uploadService.uploadKnowledgeBase(
            v2, "resolve测试文档", null, "resolve-svc", "prod", "billing",
            "runbook", "wiki", "v1.0", null));

        // 创建另一个冲突版本然后放弃
        MockMultipartFile v3 = mdFile("resolve-v3.md",
            "resolve测试 支付网关 生产环境 端口配置 AAA 放弃内容");
        Long abandonedId = extractId(uploadService.uploadKnowledgeBase(
            v3, "resolve测试文档", null, "resolve-svc", "prod", "billing",
            "runbook", "wiki", "v1.0", null));
        conflictService.abandonVersion(abandonedId);

        // 验证三种状态
        KnowledgeBaseEntity kbConflict = knowledgeBaseRepository.findById(conflictId).orElseThrow();
        assertThat(kbConflict.getConflict()).isTrue();
        KnowledgeBaseEntity kbAbandoned = knowledgeBaseRepository.findById(abandonedId).orElseThrow();
        assertThat(kbAbandoned.getConflict()).isFalse();
        assertThat(kbAbandoned.getActive()).isFalse();

        // resolve-context 只返回 active
        var scope = listService.resolveContext("resolve-svc", "prod", "billing");
        assertThat(scope).extracting(item -> item.id())
            .contains(activeId)
            .doesNotContain(conflictId)
            .doesNotContain(abandonedId);
    }
}
