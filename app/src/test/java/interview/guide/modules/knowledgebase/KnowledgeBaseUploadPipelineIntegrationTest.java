package interview.guide.modules.knowledgebase;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseConflictService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseListService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseUploadService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import org.junit.jupiter.api.DisplayName;
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

import static org.assertj.core.api.Assertions.assertThat;

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
    @Autowired JdbcTemplate jdbcTemplate;

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

        // 3. 断言 v1 被停用
        KnowledgeBaseEntity kb1After = knowledgeBaseRepository.findById(id1).orElseThrow();
        assertThat(kb1After.getActive()).as("旧版本应被停用").isFalse();

        // 4. 断言 v2 被激活且冲突标记清除
        KnowledgeBaseEntity kb2After = knowledgeBaseRepository.findById(id2).orElseThrow();
        assertThat(kb2After.getActive()).as("冲突版本应被激活").isTrue();
        assertThat(kb2After.getConflict()).as("冲突标记应被清除").isFalse();

        // 5. 等待向量化完成
        KnowledgeBaseEntity kb2Done = awaitStatus(id2, VectorStatus.COMPLETED, 30_000);
        assertThat(kb2Done.getVectorStatus()).isEqualTo(VectorStatus.COMPLETED);
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

        // 3. 断言 v2 冲突标记清除，active 仍为 false
        KnowledgeBaseEntity kb2After = knowledgeBaseRepository.findById(id2).orElseThrow();
        assertThat(kb2After.getActive()).as("放弃的版本应保持 active=false").isFalse();
        assertThat(kb2After.getConflict()).as("冲突标记应被清除").isFalse();

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
}
