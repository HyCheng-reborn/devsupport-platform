package interview.guide.modules.knowledgebase;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.MessageStatus;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SendMessageRequest;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * I-4 RAG Chat SSE 集成测试
 *
 * <p>使用 Testcontainers PostgreSQL/pgvector + Redis 隔离环境，
 * Mock LLM 和向量检索，验证 SSE 事件顺序、来源快照和 PostgreSQL 落库行为。
 *
 * <p>隔离保证：不调用真实 LLM / Embedding / Redis Stream / S3。
 */
@DisplayName("I-4 RAG Chat SSE 集成测试（Testcontainers 隔离）")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RagChatSseIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16"))
        .withDatabaseName("rag_sse_test")
        .withUsername("test")
        .withPassword("test");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // 数据源覆盖 application-test.yml 的 H2 → Testcontainers PostgreSQL
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        // Flyway 启用（创建真实 PostgreSQL schema）
        registry.add("spring.flyway.enabled", () -> "true");

        // JPA：禁用 ddl-auto（Flyway 管理 schema），自动检测 PostgreSQL 方言
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.dialect",
            () -> "org.hibernate.dialect.PostgreSQLDialect");

        // Redis 覆盖 application-test.yml 的 localhost:6379 → Testcontainers Redis
        registry.add("spring.redis.redisson.config", () ->
            "singleServerConfig:\n  address: \"redis://" +
                redis.getHost() + ":" + redis.getMappedPort(6379) + "\"\n  database: 0");
    }

    // ===== Mock 外部依赖 =====

    @MockitoBean
    LlmProviderRegistry llmProviderRegistry;

    @MockitoBean
    KnowledgeBaseVectorService vectorService;

    // ===== 注入真实 Bean =====

    @Autowired
    RagChatController ragChatController;

    @Autowired
    RagChatSessionRepository sessionRepository;

    @Autowired
    RagChatMessageRepository messageRepository;

    @Autowired
    KnowledgeBaseRepository knowledgeBaseRepository;

    // ===== 测试数据 =====

    private static final String ANSWER_TEXT = "测试回答内容";

    private KnowledgeBaseEntity testKb;
    private RagChatSessionEntity testSession;

    @BeforeEach
    void setUpTestData() {
        // 每个测试方法使用唯一 ID 避免跨测试数据冲突
        testKb = new KnowledgeBaseEntity();
        testKb.setFileHash("i4-test-hash-" + System.nanoTime());
        testKb.setName("I-4 测试知识库");
        testKb.setOriginalFilename("i4-test-doc.md");
        testKb.setService("支付网关");
        testKb.setEnvironment("生产");
        testKb.setVersionLabel("v2.1");
        testKb.setVersionNo(3);
        testKb.setDocumentKey("i4-doc-key-001");
        testKb = knowledgeBaseRepository.save(testKb);

        testSession = new RagChatSessionEntity();
        testSession.setTitle("I-4 测试会话");
        testSession.getKnowledgeBases().add(testKb);
        testSession = sessionRepository.save(testSession);

        setupChatClientMock();
    }

    /**
     * Mock ChatClient fluent API 链，复用 KnowledgeBaseQueryServiceTest 的模式。
     * rewrite 路径的 call() 不显式 mock → NPE 被 catch → 降级使用原问题继续检索。
     */
    private void setupChatClientMock() {
        ChatClient mockChatClient = org.mockito.Mockito.mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec mockRequestSpec =
            org.mockito.Mockito.mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec mockStreamSpec =
            org.mockito.Mockito.mock(ChatClient.StreamResponseSpec.class);

        when(llmProviderRegistry.getDefaultChatClient()).thenReturn(mockChatClient);
        when(mockChatClient.prompt()).thenReturn(mockRequestSpec);
        lenient().when(mockRequestSpec.system(anyString())).thenReturn(mockRequestSpec);
        lenient().when(mockRequestSpec.user(anyString())).thenReturn(mockRequestSpec);
        lenient().when(mockRequestSpec.messages(anyList())).thenReturn(mockRequestSpec);
        when(mockRequestSpec.stream()).thenReturn(mockStreamSpec);
        when(mockStreamSpec.content()).thenReturn(Flux.just(ANSWER_TEXT));
    }

    private Document createTestDocument() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("kb_id", testKb.getId());
        return new Document("项目的后端端口是 8080", metadata);
    }

    private List<ServerSentEvent<String>> collectEvents(Long sessionId, String question) {
        Flux<ServerSentEvent<String>> flux =
            ragChatController.sendMessageStream(sessionId, new SendMessageRequest(question));
        List<ServerSentEvent<String>> events = flux.collectList().block();
        assertThat(events).isNotNull();
        return events;
    }

    // ===== 测试场景 =====

    @Test
    @DisplayName("SSE 事件顺序 data→sources→done 且 PostgreSQL 落库正确")
    void sseEventOrderAndPostgresPersistence() {
        Document doc = createTestDocument();
        when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
            .thenReturn(List.of(doc));

        List<ServerSentEvent<String>> events = collectEvents(testSession.getId(), "后端端口是多少？");

        // 事件顺序：data... → sources → done
        int sourcesIdx = -1, doneIdx = -1;
        for (int i = 0; i < events.size(); i++) {
            if ("sources".equals(events.get(i).event())) sourcesIdx = i;
            if ("done".equals(events.get(i).event())) doneIdx = i;
        }
        assertThat(sourcesIdx).as("sources 事件应存在").isGreaterThan(-1);
        assertThat(doneIdx).as("done 事件应存在").isGreaterThan(sourcesIdx);

        // done 携带 COMPLETED 状态
        assertThat(events.get(doneIdx).data()).contains("COMPLETED");

        // PostgreSQL 落库验证：用户消息
        List<RagChatMessageEntity> messages =
            messageRepository.findBySessionIdOrderByMessageOrderAsc(testSession.getId());
        assertThat(messages).hasSizeGreaterThanOrEqualTo(2);

        RagChatMessageEntity userMsg = messages.stream()
            .filter(m -> m.getType() == RagChatMessageEntity.MessageType.USER)
            .findFirst().orElseThrow();
        assertThat(userMsg.getContent()).isEqualTo("后端端口是多少？");
        assertThat(userMsg.getCompleted()).isTrue();

        // PostgreSQL 落库验证：AI 消息
        RagChatMessageEntity aiMsg = messages.stream()
            .filter(m -> m.getType() == RagChatMessageEntity.MessageType.ASSISTANT)
            .findFirst().orElseThrow();
        assertThat(aiMsg.getContent()).isEqualTo(ANSWER_TEXT);
        assertThat(aiMsg.getCompleted()).isTrue();
        assertThat(aiMsg.getStatus()).isEqualTo(MessageStatus.COMPLETED);
        assertThat(aiMsg.getSourcesJson()).isNotNull().isNotEmpty();
    }

    @Test
    @DisplayName("sources 事件包含正确的 kb_id、service、environment 标签和版本字段")
    void sourceSnapshotContainsCorrectTags() {
        Document doc = createTestDocument();
        when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
            .thenReturn(List.of(doc));

        List<ServerSentEvent<String>> events = collectEvents(testSession.getId(), "后端端口？");

        ServerSentEvent<String> sourcesEvent = events.stream()
            .filter(e -> "sources".equals(e.event()))
            .findFirst().orElseThrow();

        String sourcesJson = sourcesEvent.data();
        assertThat(sourcesJson)
            .contains("\"kbId\"").contains(String.valueOf(testKb.getId()))
            .contains("\"documentName\"").contains("i4-test-doc.md")
            .contains("\"service\"").contains("支付网关")
            .contains("\"environment\"").contains("生产")
            .contains("\"versionLabel\"").contains("v2.1")
            .contains("\"versionNo\"").contains("3")
            .contains("\"documentKey\"").contains("i4-doc-key-001");
    }

    @Test
    @DisplayName("PostgreSQL 读取：getSessionDetail 返回持久化的消息和来源")
    void postgresReadBackViaSessionDetail() {
        Document doc = createTestDocument();
        when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
            .thenReturn(List.of(doc));

        // 先执行一次 SSE 流式写入
        collectEvents(testSession.getId(), "后端端口是多少？");

        // 通过 getSessionDetail 从 PostgreSQL 读取并验证
        var detail = ragChatController.getSessionDetail(testSession.getId());
        assertThat(detail).isNotNull();
        assertThat(detail.getData()).isNotNull();
        assertThat(detail.getData().messages()).hasSizeGreaterThanOrEqualTo(2);

        // 验证 AI 消息内容已持久化且可从 DB 读回
        var aiMessage = detail.getData().messages().stream()
            .filter(m -> "assistant".equals(m.type()))
            .findFirst().orElseThrow();
        assertThat(aiMessage.content()).isEqualTo(ANSWER_TEXT);
    }
}
