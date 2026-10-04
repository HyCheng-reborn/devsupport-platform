package interview.guide.modules.knowledgebase;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
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
    @Autowired FileStorageService storageService;
    @Autowired JdbcTemplate jdbcTemplate;

    @SuppressWarnings("unchecked")
    private Long extractId(Map<String, Object> result) {
        return ((Number) ((Map<String, Object>) result.get("knowledgeBase")).get("id")).longValue();
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
}
