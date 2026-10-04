package interview.guide.modules.knowledgebase;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVersionService;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段 2 知识库版本生命周期集成测试（Testcontainers PostgreSQL/pgvector + Redis）。
 *
 * <p>真实：PostgreSQL 元数据、pgvector 向量存储与检索过滤、向量删除 SQL、版本停用、add→promote 异步业务方法。
 * <p>Embedding：确定性 @Primary bean（零付费 API，返回固定 1024 维向量）。
 * <p>隔离：独立测试库，不触碰现有 Docker 卷/数据库；不调用真实 LLM。
 */
@DisplayName("阶段 2 知识库版本生命周期集成测试（Testcontainers 隔离）")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(KnowledgeBaseLifecycleIntegrationTest.DeterministicEmbeddingConfig.class)
class KnowledgeBaseLifecycleIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16"))
        .withDatabaseName("kb_lifecycle_test")
        .withUsername("test")
        .withPassword("test");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
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

    /** 确定性向量：1024 维，末位为 1（与 pgvector 维度一致，避免真实 Embedding）。 */
    static float[] deterministicVector() {
        float[] v = new float[1024];
        v[1023] = 1f;
        return v;
    }

    /** 用 @Primary 覆盖 app 的 registry-delegate EmbeddingModel，实现全部方法走 call() → 确定性向量。 */
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

    @Autowired
    KnowledgeBaseRepository knowledgeBaseRepository;

    @Autowired
    KnowledgeBaseVectorService vectorService;

    @Autowired
    KnowledgeBaseVersionService versionService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private KnowledgeBaseEntity newKb(String hash, String documentKey, int versionNo, boolean active) {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setFileHash(hash);
        kb.setName("支付网关-" + versionNo);
        kb.setOriginalFilename("pay-" + versionNo + ".md");
        kb.setService("支付网关");
        kb.setEnvironment("生产");
        kb.setProject("billing");
        kb.setDocType("runbook");
        kb.setDocumentKey(documentKey);
        kb.setVersionNo(versionNo);
        kb.setActive(active);
        return knowledgeBaseRepository.save(kb);
    }

    private int vectorRowCount(Long kbId) {
        Integer c = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM vector_store WHERE metadata->>'kb_id' = ?",
            Integer.class, String.valueOf(kbId));
        return c == null ? 0 : c;
    }

    @Test
    @DisplayName("元数据持久化到 PostgreSQL：project/docType/versionNo/active/documentKey 落库")
    void metadataPersistsToPostgres() {
        String dk = "dk-meta-" + System.nanoTime();
        KnowledgeBaseEntity kb = newKb("hash-meta-" + System.nanoTime(), dk, 3, true);

        var row = jdbcTemplate.queryForMap(
            "SELECT project, doc_type, version_no, active, document_key FROM knowledge_bases WHERE id = ?",
            kb.getId());
        assertThat(row.get("project")).isEqualTo("billing");
        assertThat(row.get("doc_type")).isEqualTo("runbook");
        assertThat(row.get("version_no")).isEqualTo(3);
        assertThat(row.get("active")).isEqualTo(true);
        assertThat(row.get("document_key")).isEqualTo(dk);
    }

    @Test
    @DisplayName("新版本停用旧版本后，旧版本向量被删除且不再被检索召回")
    void retiredVersionExitsRetrieval() {
        String dk = "dk-life-" + System.nanoTime();
        KnowledgeBaseEntity v1 = newKb("hash-v1-" + System.nanoTime(), dk, 1, true);
        KnowledgeBaseEntity v2 = newKb("hash-v2-" + System.nanoTime(), dk, 2, true);

        // 走真实 add→promote（与异步消费者同一业务方法），确定性向量
        vectorService.vectorizeAndStore(v1.getId(), "支付网关 生产环境 端口配置 8080");
        vectorService.vectorizeAndStore(v2.getId(), "支付网关 生产环境 端口配置 9090");

        assertThat(vectorRowCount(v1.getId())).isGreaterThan(0);
        assertThat(vectorRowCount(v2.getId())).isGreaterThan(0);

        // 新版本替换旧版本：停用 v1 并删除其向量
        versionService.retireSupersededVersions(dk, v2.getId());

        assertThat(knowledgeBaseRepository.findById(v1.getId()).orElseThrow().getActive()).isFalse();
        assertThat(vectorRowCount(v1.getId())).as("旧版本向量应被物理删除").isZero();
        assertThat(vectorRowCount(v2.getId())).isGreaterThan(0);

        // 检索过滤：即使把 v1、v2 都传入，也只召回 v2（旧版本已无向量可召回）
        List<Document> hits = vectorService.similaritySearch("端口配置", List.of(v1.getId(), v2.getId()), 5, 0.0);
        assertThat(hits).isNotEmpty();
        assertThat(hits).allSatisfy(doc ->
            assertThat(String.valueOf(doc.getMetadata().get("kb_id"))).isEqualTo(String.valueOf(v2.getId())));
    }
}
