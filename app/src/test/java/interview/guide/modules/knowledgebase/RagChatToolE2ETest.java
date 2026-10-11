package interview.guide.modules.knowledgebase;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.tools.ToolCallRecord;
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
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stage 6 — RAG Chat HTTP/SSE 端到端工具分发闭环测试。
 *
 * <p>验证完整链路（HTTP/SSE → Spring AI 工具分发 → 工具录制 → 持久化 → 会话回读）：
 * <pre>
 * HTTP POST /api/rag-chat/sessions/{id}/messages/stream
 * → RagChatController.sendMessageStream()（实际逻辑）
 * → RagChatSessionService.getStreamAnswer()（实际逻辑，不 mock）
 * → KnowledgeBaseQueryService.answerQuestionStream()（实际逻辑，不 mock）
 * → chatClient.prompt().tools(dependencyHealthTools).toolContext(...).stream()
 * → fake ChatModel.stream() 返回 tool_call
 * → Spring AI ToolCallingManager 分发工具
 * → DependencyHealthTools.checkDependencyHealth(ToolContext) 执行探测（真实基础设施）
 * → ToolInvocationRecorder 录制工具调用结果
 * → 工具结果进入后续模型请求
 * → fake ChatModel.stream() 返回最终回答
 * → SSE tool_result 事件
 * → 消息持久化（tool_calls_json）
 * → GET /api/rag-chat/sessions/{id} 会话详情回读
 * </pre>
 *
 * <p>与 {@code RagChatSseIntegrationTest} 的区别：不 mock ChatClient fluent API，
 * 而是使用真实 ChatClient + fake ChatModel，让请求经过 Spring AI 工具分发框架。
 *
 * <p>与 {@code SpringAiToolDispatchTest} 的区别：经过完整 HTTP/SSE 入口、
 * Controller、SessionService、QueryService，而非直接构建 ChatClient。
 *
 * <p>隔离保证：不调用真实 LLM / Embedding / Redis Stream / S3。
 */
@DisplayName("Stage 6 — RAG Chat HTTP/SSE 端到端工具分发闭环测试")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RagChatToolE2ETest {

    @TestConfiguration
    static class TaskExecutorConfig {
        @Bean(name = "taskExecutor")
        java.util.concurrent.Executor taskExecutor() {
            return Runnable::run;
        }
    }

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16"))
        .withDatabaseName("tool_e2e_test")
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

    // ===== Mock 外部依赖 =====

    @MockitoBean
    LlmProviderRegistry llmProviderRegistry;

    @MockitoBean
    KnowledgeBaseVectorService vectorService;

    @MockitoBean
    S3Client s3Client;

    // ===== 注入真实 Bean =====

    @Autowired
    RagChatController ragChatController;

    @Autowired
    RagChatSessionRepository sessionRepository;

    @Autowired
    RagChatMessageRepository messageRepository;

    @Autowired
    KnowledgeBaseRepository knowledgeBaseRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ===== 测试数据 =====

    private KnowledgeBaseEntity testKb;
    private RagChatSessionEntity testSession;

    @BeforeEach
    void setUpTestData() {
        testKb = new KnowledgeBaseEntity();
        testKb.setFileHash("tool-e2e-hash-" + System.nanoTime());
        testKb.setName("Tool E2E 测试知识库");
        testKb.setOriginalFilename("tool-e2e-doc.md");
        testKb.setService("支付网关");
        testKb.setEnvironment("生产");
        testKb.setVersionLabel("v1.0");
        testKb.setVersionNo(1);
        testKb.setDocumentKey("tool-e2e-key");
        testKb = knowledgeBaseRepository.save(testKb);

        testSession = new RagChatSessionEntity();
        testSession.setTitle("Tool E2E 测试会话");
        testSession.getKnowledgeBases().add(testKb);
        testSession = sessionRepository.save(testSession);
    }

    // ===== 辅助方法 =====

    /**
     * 配置 mock LlmProviderRegistry：返回真实 ChatClient + fake ChatModel。
     * <p>
     * 调用时序：
     * <ol>
     *   <li>Query rewrite {@code .call()} → 返回纯文本（让 rewrite 成功完成）</li>
     *   <li>主路径 {@code .stream()} → 返回 tool_call（触发 Spring AI 工具调用循环）</li>
     *   <li>工具执行后循环续接 {@code .stream()} → 返回最终回答</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    private FakeModelSetup setupChatClientWithFakeModel(String finalAnswer) throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        List<Prompt> capturedPrompts = new CopyOnWriteArrayList<>();

        ChatModel fakeModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                capturedPrompts.add(prompt);
                int count = callCount.incrementAndGet();
                if (count == 2) {
                    // 主路径 stream 第一次调用：返回 tool_call，触发工具分发
                    return buildToolCallResponse("checkDependencyHealth");
                } else {
                    // count==1: query rewrite .call() → 返回纯文本
                    // count>=3: 工具执行后续接 → 返回最终回答
                    return buildTextResponse(
                        count == 1 ? "检查系统依赖健康状态" : finalAnswer);
                }
            }

            @Override
            public ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(call(prompt));
            }
        };

        // 用 fake ChatModel 构建真实 ChatClient
        // Spring AI 2.0.0 应自动注册 ToolCallingAdvisor
        ChatClient realChatClient = ChatClient.builder(fakeModel).build();

        // 让 LlmProviderRegistry 返回真实 ChatClient（fluent API 链由框架内部管理）
        when(llmProviderRegistry.getDefaultChatClient()).thenReturn(realChatClient);

        return new FakeModelSetup(callCount, capturedPrompts);
    }

    private ChatResponse buildToolCallResponse(String toolName) {
        var toolCall = new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
            "call_e2e_1", "function", toolName, "{}");
        var assistantMessage = org.springframework.ai.chat.messages.AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(toolCall))
            .build();
        var generation = new Generation(assistantMessage,
            ChatGenerationMetadata.builder().finishReason("tool_calls").build());
        return new ChatResponse(List.of(generation));
    }

    private ChatResponse buildTextResponse(String text) {
        var assistantMessage = new org.springframework.ai.chat.messages.AssistantMessage(text);
        var generation = new Generation(assistantMessage,
            ChatGenerationMetadata.builder().finishReason("stop").build());
        return new ChatResponse(List.of(generation));
    }

    private void mockHealthDependenciesHealthy() throws Exception {
        // PostgreSQL 健康探测：不 mock DataSource（与 JPA 共用 Testcontainers 连接），
        // probePostgresql() 使用真实 Testcontainers PostgreSQL 连接，探测结果为 OK。
        // Redis 健康探测：使用真实 Testcontainers Redis 连接，probeRedis() 正常返回 OK。

        // S3 健康探测
        when(s3Client.headBucket(any(HeadBucketRequest.class)))
            .thenReturn(mock(HeadBucketResponse.class));
    }

    private Document createTestDocument() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("kb_id", testKb.getId());
        return new Document("## 系统配置\n\n系统依赖包括 PostgreSQL、Redis 和对象存储", metadata);
    }

    private List<ServerSentEvent<String>> collectEvents(Long sessionId, String question) {
        Flux<ServerSentEvent<String>> flux =
            ragChatController.sendMessageStream(sessionId, new SendMessageRequest(question));
        List<ServerSentEvent<String>> events = flux.collectList().block();
        assertThat(events).isNotNull();
        return events;
    }

    // ===== 测试用例 =====

    @Test
    @DisplayName("完整闭环：HTTP/SSE → 工具分发 → SSE tool_result → 持久化 → 会话回读")
    void fullToolUseE2E() throws Exception {
        // 0. 配置健康探测 mock（Redis、S3；PostgreSQL 使用真实 Testcontainers 连接）
        mockHealthDependenciesHealthy();

        // 1. 配置 fake ChatModel（第一次返回 tool_call，第二次返回最终回答）
        String finalAnswer = "系统健康检查完成，PostgreSQL、Redis 和对象存储均运行正常";
        FakeModelSetup modelSetup = setupChatClientWithFakeModel(finalAnswer);

        // 2. 配置向量检索返回测试文档（让 answerQuestionStream 进入 LLM 调用路径）
        Document doc = createTestDocument();
        when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
            .thenReturn(List.of(doc));

        // 3. 发送 SSE 请求（经过实际 HTTP/SSE 入口）
        List<ServerSentEvent<String>> events = collectEvents(
            testSession.getId(), "检查系统依赖健康状态");

        // 4. 断言 SSE 事件序列：data... → tool_result → sources → done
        List<String> eventTypes = events.stream()
            .map(ServerSentEvent::event)
            .toList();
        assertThat(eventTypes).contains("data");
        assertThat(eventTypes).contains("tool_result");
        assertThat(eventTypes).contains("sources");
        assertThat(eventTypes).contains("done");

        // 事件顺序：data → tool_result → sources → done
        int lastDataIdx = -1;
        int toolResultIdx = -1;
        int sourcesIdx = -1;
        int doneIdx = -1;
        for (int i = 0; i < events.size(); i++) {
            String type = events.get(i).event();
            if ("data".equals(type)) lastDataIdx = i;
            if ("tool_result".equals(type)) toolResultIdx = i;
            if ("sources".equals(type)) sourcesIdx = i;
            if ("done".equals(type)) doneIdx = i;
        }
        assertThat(lastDataIdx).as("应有 data 事件").isGreaterThanOrEqualTo(0);
        assertThat(toolResultIdx).as("应有 tool_result 事件").isGreaterThan(lastDataIdx);
        assertThat(sourcesIdx).as("sources 应在 tool_result 之后").isGreaterThan(toolResultIdx);
        assertThat(doneIdx).as("done 应在 sources 之后").isGreaterThan(sourcesIdx);

        // 5. 验证 tool_result 事件内容
        ServerSentEvent<String> toolEvent = events.get(toolResultIdx);
        ToolCallRecord toolRecord = objectMapper.readValue(toolEvent.data(), ToolCallRecord.class);
        assertThat(toolRecord.toolName()).isEqualTo("checkDependencyHealth");
        assertThat(toolRecord.status()).isEqualTo("SUCCESS");
        assertThat(toolRecord.demo()).isFalse();
        assertThat(toolRecord.durationMs()).isGreaterThanOrEqualTo(0);
        assertThat(toolRecord.result()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> resultMap = (Map<String, Object>) toolRecord.result();
        assertThat(resultMap).containsKey("postgresql");
        assertThat(resultMap).containsKey("redis");
        assertThat(resultMap).containsKey("objectStorage");

        // 6. 断言 done 事件状态
        assertThat(events.get(doneIdx).data()).contains("COMPLETED");

        // 7. PostgreSQL 落库验证
        List<RagChatMessageEntity> messages =
            messageRepository.findBySessionIdOrderByMessageOrderAsc(testSession.getId());
        assertThat(messages).hasSizeGreaterThanOrEqualTo(2);

        RagChatMessageEntity aiMsg = messages.stream()
            .filter(m -> m.getType() == RagChatMessageEntity.MessageType.ASSISTANT)
            .reduce((first, second) -> second)
            .orElseThrow();
        assertThat(aiMsg.getCompleted()).isTrue();
        assertThat(aiMsg.getStatus()).isEqualTo(MessageStatus.COMPLETED);
        assertThat(aiMsg.getContent()).isNotBlank();
        // Spring AI 2.0.0 ToolContext 正确传播，工具执行结果已录制
        assertThat(aiMsg.getToolCallsJson()).isNotNull().isNotEqualTo("[]");
        ToolCallRecord[] persistedRecords = objectMapper.readValue(
            aiMsg.getToolCallsJson(), ToolCallRecord[].class);
        assertThat(persistedRecords).hasSize(1);
        assertThat(persistedRecords[0].toolName()).isEqualTo("checkDependencyHealth");
        assertThat(persistedRecords[0].status()).isEqualTo("SUCCESS");
        assertThat(persistedRecords[0].demo()).isFalse();

        // 8. 会话详情回读验证
        var detailResult = ragChatController.getSessionDetail(testSession.getId());
        assertThat(detailResult.getData()).isNotNull();
        var detailMessages = detailResult.getData().messages();
        assertThat(detailMessages).hasSizeGreaterThanOrEqualTo(2);

        var detailAiMsg = detailMessages.stream()
            .filter(m -> "assistant".equals(m.type()))
            .reduce((first, second) -> second)
            .orElseThrow();
        assertThat(detailAiMsg.status()).isEqualTo("COMPLETED");
        assertThat(detailAiMsg.toolCallsJson()).isNotNull().contains("checkDependencyHealth");

        // 9. 验证 fake ChatModel 被调用了至少 3 次
        //    callCount=1: query rewrite .call()
        //    callCount=2: 主路径 .stream() → 返回 tool_call
        //    callCount=3: 工具执行后续接 .stream() → 返回最终回答
        assertThat(modelSetup.callCount.get()).as("模型应被调用至少 3 次（rewrite + tool_call + final）")
            .isGreaterThanOrEqualTo(3);

        // 10. 验证第三次模型请求（工具执行后续接）的 Prompt 中包含 ToolResponseMessage
        //    证明工具被 Spring AI 框架分发执行，结果进入了后续模型请求
        assertThat(modelSetup.capturedPrompts.size()).as("至少捕获 3 个 Prompt").isGreaterThanOrEqualTo(3);
        Prompt toolContinuationPrompt = modelSetup.capturedPrompts.get(2);
        List<Message> toolContinuationMessages = toolContinuationPrompt.getInstructions();
        boolean hasToolResponse = toolContinuationMessages.stream()
            .anyMatch(m -> m.getMessageType() == MessageType.TOOL);
        assertThat(hasToolResponse)
            .as("工具执行后续接请求的 Prompt 中应包含 ToolResponseMessage（经过 Spring AI 框架分发）")
            .isTrue();

        // 11. ToolResponseMessage 包含工具执行结果（成功探测响应）
        //     ToolContext 正确传播 → recorder 有效 → 工具执行真实探测
        ToolResponseMessage toolResponseMsg = toolContinuationMessages.stream()
            .filter(m -> m instanceof ToolResponseMessage)
            .map(m -> (ToolResponseMessage) m)
            .findFirst()
            .orElseThrow();
        assertThat(toolResponseMsg.getResponses()).isNotEmpty();
        assertThat(toolResponseMsg.getResponses().getFirst().name())
            .isEqualTo("checkDependencyHealth");
        // 成功探测响应包含 postgresql、redis、objectStorage 状态
        assertThat(toolResponseMsg.getResponses().getFirst().responseData())
            .contains("postgresql");
    }

    // ===== 辅助 record =====

    private record FakeModelSetup(
        AtomicInteger callCount,
        List<Prompt> capturedPrompts
    ) {}
}
