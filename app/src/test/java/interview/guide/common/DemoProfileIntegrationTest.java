package interview.guide.common;

import interview.guide.common.ai.DemoChatModel;
import interview.guide.common.ai.DemoEmbeddingModel;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.infrastructure.file.FileStorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demo Profile 集成测试
 *
 * <p>验证 demo profile 下 RAG 问答和向量化可完整演示，零付费调用。
 *
 * <p>Profile 组合说明：
 * <ul>
 *   <li>{@code demo} —— 激活 Demo 实现（DemoChatModel、DemoEmbeddingModel），
 *       互斥掉真实 LLM/Embedding 依赖。</li>
 *   <li>{@code test} —— 复用 {@code application-test.yml} 的安全基础设施默认值。</li>
 * </ul>
 *
 * <p>隔离保证：
 * <ul>
 *   <li>PostgreSQL（pgvector/pgvector:pg16）与 Redis（redis:7-alpine）均为 Testcontainers 隔离容器。</li>
 *   <li>{@link FileStorageService} 被 {@link MockitoBean} 替换，不依赖真实 S3/RustFS。</li>
 *   <li>所有 AI 功能走 Demo 实现，零真实 API 调用。</li>
 * </ul>
 */
@DisplayName("Demo Profile 集成测试")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"demo", "test"})
class DemoProfileIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
      DockerImageName.parse("pgvector/pgvector:pg16"))
    .withDatabaseName("demo_test")
    .withUsername("test")
    .withPassword("test");

  @Container
  static final GenericContainer<?> redis = new GenericContainer<>(
      DockerImageName.parse("redis:7-alpine"))
    .withExposedPorts(6379);

  @DynamicPropertySource
  static void overrideProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
  }

  @MockitoBean
  private FileStorageService fileStorageService;

  @Autowired
  private DemoChatModel demoChatModel;

  @Autowired
  private DemoEmbeddingModel demoEmbeddingModel;

  @Autowired
  private LlmProviderRegistry llmProviderRegistry;

  @Test
  @DisplayName("demo profile 下 Spring 上下文加载成功")
  void demoProfile_contextLoads() {
    assertThat(demoChatModel).isNotNull();
    assertThat(demoEmbeddingModel).isNotNull();
    assertThat(llmProviderRegistry).isNotNull();
  }

  @Test
  @DisplayName("DemoChatModel 返回固定响应")
  void demoProfile_chatModel_returnsFixedResponse() {
    // 测试端口相关问题
    Prompt prompt = new Prompt("项目后端端口是多少？");
    ChatResponse response = demoChatModel.call(prompt);

    assertThat(response).isNotNull();
    assertThat(response.getResults()).isNotEmpty();
    String content = response.getResults().get(0).getOutput().getText();
    assertThat(content).contains("8080");
  }

  @Test
  @DisplayName("DemoChatModel 数据库问题返回数据库相关信息")
  void demoProfile_chatModel_databaseResponse() {
    Prompt prompt = new Prompt("数据库连接配置是什么？");
    ChatResponse response = demoChatModel.call(prompt);

    assertThat(response).isNotNull();
    assertThat(response.getResults()).isNotEmpty();
    String content = response.getResults().get(0).getOutput().getText();
    assertThat(content).containsIgnoringCase("postgres");
  }

  /**
   * 覆盖 DemoChatModel 全部模板分支（端口/数据库/Redis/LLM/配置/默认），
   * 每个触发问题按 {@code DemoChatModel} 的关键词匹配优先级设计，确保命中不同模板。
   */
  @ParameterizedTest(name = "模板响应「{0}」带有 Demo 标识且不含检索生成措辞")
  @ValueSource(strings = {
      "8080 端口启动",        // PORT 模板
      "数据库连接",            // DB 模板
      "redis 缓存",           // REDIS 模板
      "AI 模型 embedding",    // LLM 模板
      "配置 config yml",       // CONFIG 模板
      "你好啊"                // DEFAULT 模板（无任何关键词命中）
  })
  @DisplayName("DemoChatModel 所有模板响应都带 Demo 标识且不含检索生成措辞")
  void demoProfile_allTemplates_carryMarkerAndNoRetrievalWording(String question) {
    ChatResponse response = demoChatModel.call(new Prompt(question));

    assertThat(response).isNotNull();
    assertThat(response.getResults()).isNotEmpty();
    String content = response.getResults().get(0).getOutput().getText();

    // 1. 每个模板响应都以 Demo 标识开头，界面上可识别
    assertThat(content).startsWith("[Demo 模板响应");
    // 2. 不含暗示回答由检索证据生成的措辞
    assertThat(content)
        .doesNotContain("根据知识库文档")
        .doesNotContain("根据检索结果")
        .doesNotContain("知识库文档检索结果");
  }

  @Test
  @DisplayName("DemoChatModel 流式响应首块也带有 Demo 标识")
  void demoProfile_stream_carriesMarker() {
    ChatResponse firstChunk = demoChatModel.stream(new Prompt("8080 端口")).blockFirst();

    assertThat(firstChunk).isNotNull();
    String text = firstChunk.getResults().get(0).getOutput().getText();
    assertThat(text).startsWith("[Demo 模板响应");
  }

  @Test
  @DisplayName("DemoChatModel 仅在 demo profile 下激活，真实 profile 不受影响")
  void demoChatModel_isIsolatedToDemoProfile() {
    Profile profile = DemoChatModel.class.getAnnotation(Profile.class);

    assertThat(profile).as("DemoChatModel 必须标注 @Profile 以隔离真实 LLM profile").isNotNull();
    assertThat(profile.value()).containsExactly("demo");
  }

  @Test
  @DisplayName("DemoEmbeddingModel 返回确定性向量")
  void demoProfile_embeddingModel_deterministicEmbedding() {
    String text = "测试文本";

    // 第一次调用
    float[] embedding1 = demoEmbeddingModel.embed(text);
    // 第二次调用
    float[] embedding2 = demoEmbeddingModel.embed(text);

    // 验证维度
    assertThat(embedding1).hasSize(1024);
    assertThat(embedding2).hasSize(1024);

    // 验证确定性：相同内容返回相同向量
    assertThat(embedding1).isEqualTo(embedding2);
  }

  @Test
  @DisplayName("DemoEmbeddingModel 不同内容返回不同向量")
  void demoProfile_embeddingModel_differentContentDifferentEmbedding() {
    String text1 = "文本一";
    String text2 = "文本二";

    float[] embedding1 = demoEmbeddingModel.embed(text1);
    float[] embedding2 = demoEmbeddingModel.embed(text2);

    // 验证不同内容返回不同向量
    assertThat(embedding1).isNotEqualTo(embedding2);
  }

  @Test
  @DisplayName("DemoEmbeddingModel 批量 embedding 正常工作")
  void demoProfile_embeddingModel_batchEmbedding() {
    List<String> texts = List.of("文本一", "文本二", "文本三");

    List<float[]> embeddings = demoEmbeddingModel.embed(texts);

    assertThat(embeddings).hasSize(3);
    embeddings.forEach(embedding ->
        assertThat(embedding).hasSize(1024));
  }

  @Test
  @DisplayName("LlmProviderRegistry 在 demo 模式下返回可用 ChatClient")
  void demoProfile_registry_returnsUsableChatClient() {
    // 获取默认 ChatClient
    var chatClient = llmProviderRegistry.getDefaultChatClient();

    assertThat(chatClient).isNotNull();

    // 验证可以正常调用
    String response = chatClient.prompt()
        .user("测试问题")
        .call()
        .content();

    assertThat(response).isNotNull();
    assertThat(response).isNotBlank();
  }

  @Test
  @DisplayName("LlmProviderRegistry 在 demo 模式下返回可用 EmbeddingModel")
  void demoProfile_registry_returnsUsableEmbeddingModel() {
    var embeddingModel = llmProviderRegistry.getDefaultEmbeddingModel();

    assertThat(embeddingModel).isNotNull();
    assertThat(embeddingModel.dimensions()).isEqualTo(1024);

    float[] embedding = embeddingModel.embed("测试文本");
    assertThat(embedding).hasSize(1024);
  }
}
