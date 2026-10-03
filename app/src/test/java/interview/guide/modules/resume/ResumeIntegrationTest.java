package interview.guide.modules.resume;

import interview.guide.infrastructure.file.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Resume 端到端集成测试（Testcontainers 隔离）
 *
 * <p>验证真实服务链路：HTTP 上传 → Tika 解析 → S3 存储（Mock）→ PostgreSQL 入库
 * → Redis Stream 异步投递 → DemoResumeGradingService 分析 → 结果持久化 → 前端查询接口读回。
 *
 * <p>Profile 组合说明：
 * <ul>
 *   <li>{@code demo} —— 激活 {@code DemoResumeGradingService}（返回固定分数 82），
 *       互斥掉需要真实 LLM 的 {@code ResumeGradingService}（{@code @Profile("!demo")}），
 *       演示链路零真实 LLM/Embedding 调用。</li>
 *   <li>{@code test} —— 复用 {@code application-test.yml} 的安全基础设施默认值
 *       （占位 API Key、加密密钥、存储凭据），保证完整 Spring 上下文可启动。
 *       数据源/Redis/Flyway/JPA 由下方 {@link DynamicPropertySource} 覆盖为 Testcontainers。</li>
 * </ul>
 *
 * <p>隔离保证：
 * <ul>
 *   <li>PostgreSQL（pgvector/pgvector:pg16）与 Redis（redis:7-alpine）均为 Testcontainers 隔离容器。</li>
 *   <li>{@link FileStorageService} 被 {@link MockitoBean} 替换，不依赖真实 S3/RustFS。</li>
 *   <li>Tika 解析纯文本、Redis Stream 异步消费、Demo 分析均为真实链路，不做 Mock。</li>
 * </ul>
 *
 * <p>说明：Spring Boot 4.1 已移除 {@code TestRestTemplate}，且测试自动配置模块未提供
 * {@code @AutoConfigureMockMvc}，故此处基于 {@link WebApplicationContext} 手动构建
 * {@link MockMvc}（spring-test 提供），依旧完整经过 Spring MVC 派发与 {@code @RateLimit} 切面。
 *
 * <p>使用 {@code RANDOM_PORT} 而非 {@code MOCK}：语音面试模块的 {@code WebSocketConfig}
 * 需要真实 Servlet 容器提供的 {@code jakarta.websocket.server.ServerContainer}，
 * MOCK 环境的 MockServletContext 不具备该属性，会导致上下文加载失败。
 */
@DisplayName("Resume 端到端集成测试（Testcontainers + demo profile）")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"demo", "test"})
class ResumeIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
      DockerImageName.parse("pgvector/pgvector:pg16"))
    .withDatabaseName("resume_it_test")
    .withUsername("test")
    .withPassword("test");

  @Container
  static final GenericContainer<?> redis = new GenericContainer<>(
      DockerImageName.parse("redis:7-alpine"))
    .withExposedPorts(6379);

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    // 数据源：覆盖 application-test.yml 的 H2 → Testcontainers PostgreSQL
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

    // Flyway 启用，创建真实 PostgreSQL schema（含 resumes / resume_analyses 表）
    registry.add("spring.flyway.enabled", () -> "true");

    // JPA：禁用 ddl-auto（schema 由 Flyway 管理），使用 PostgreSQL 方言
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    registry.add("spring.jpa.properties.hibernate.dialect",
      () -> "org.hibernate.dialect.PostgreSQLDialect");

    // Redis：覆盖 application-test.yml 的 localhost:6379 → Testcontainers Redis
    registry.add("spring.redis.redisson.config", () ->
      "singleServerConfig:\n  address: \"redis://" +
        redis.getHost() + ":" + redis.getMappedPort(6379) + "\"\n  database: 0");
  }

  // ===== Mock 外部依赖：文件存储（避免真实 S3/RustFS）=====

  @MockitoBean
  FileStorageService storageService;

  // ===== 注入真实 Bean =====

  @Autowired
  WebApplicationContext webApplicationContext;

  @Autowired
  ObjectMapper objectMapper;

  private MockMvc mockMvc;

  // ===== 常量 =====

  private static final String MOCK_STORAGE_KEY = "resumes/test/mock-resume-key.txt";
  private static final String MOCK_STORAGE_URL =
    "http://localhost:9000/interview-guide/resumes/test/mock-resume-key.txt";

  /** DemoResumeGradingService 返回的固定总分。 */
  private static final int EXPECTED_SCORE = 82;

  /** 异步分析最长等待时间（毫秒）。 */
  private static final long MAX_WAIT_MS = 30_000L;

  /** 轮询间隔（毫秒）。 */
  private static final long POLL_INTERVAL_MS = 2_000L;

  /** 简历正文模板（不含唯一标记），足够长以保证 Tika 解析与清洗后非空。 */
  private static final String RESUME_BODY = """
    张三 - Java 后端开发工程师

    联系方式：手机 138-0000-0000，邮箱 zhangsan@example.com

    教育背景：
    2015-2019 某某大学 计算机科学与技术 本科

    专业技能：
    - 精通 Java、Spring Boot、Spring Cloud 微服务开发
    - 熟悉 PostgreSQL、Redis、消息队列
    - 掌握 Docker、Kubernetes 容器化部署

    工作经历：
    2019-至今 某科技公司 高级 Java 工程师
    - 负责支付网关系统的设计与开发，日均处理交易百万级
    - 主导微服务拆分，系统可用性提升至 99.99%

    项目经验：
    - 分布式任务调度平台：支持定时任务与异步处理
    - 实时数据分析系统：基于流式计算的数据处理
    """;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

    // 上传返回固定 storageKey；getFileUrl 返回固定 URL
    lenient().when(storageService.uploadResume(any())).thenReturn(MOCK_STORAGE_KEY);
    lenient().when(storageService.getFileUrl(anyString())).thenReturn(MOCK_STORAGE_URL);
    // 重新分析/删除路径会用到，这里提供宽松桩，避免 NPE
    lenient().when(storageService.fileExists(anyString())).thenReturn(true);
    lenient().when(storageService.downloadFile(anyString()))
      .thenReturn(RESUME_BODY.getBytes(StandardCharsets.UTF_8));
  }

  // ===== 场景 1：完整链路 =====

  @Test
  @DisplayName("场景1：上传→Tika解析→入库(PENDING)→Redis异步→Demo分析→持久化(COMPLETED,score=82)")
  @SuppressWarnings("unchecked")
  void uploadAnalyzeAndQueryFullChain() throws Exception {
    byte[] content = buildResumeContent("scenario-1-" + UUID.randomUUID());
    Map<String, Object> uploadData = uploadResume("resume-s1.txt", content);

    // 上传同步响应：非重复，初始状态 PENDING
    assertThat(uploadData.get("duplicate")).isEqualTo(false);
    Map<String, Object> resume = (Map<String, Object>) uploadData.get("resume");
    assertThat(resume).isNotNull();
    assertThat(resume.get("analyzeStatus")).isEqualTo("PENDING");
    Long resumeId = ((Number) resume.get("id")).longValue();
    assertThat(resumeId).isPositive();

    // 存储信息来自 Mock 的 FileStorageService
    Map<String, Object> storage = (Map<String, Object>) uploadData.get("storage");
    assertThat(storage.get("fileUrl")).isEqualTo(MOCK_STORAGE_URL);

    // 轮询等待异步分析完成（Redis Stream 消费者 → DemoResumeGradingService → 持久化）
    Map<String, Object> detail = waitForAnalysisCompleted(resumeId);

    // 最终状态 COMPLETED，分析结果落库
    assertThat(detail.get("analyzeStatus")).isEqualTo("COMPLETED");
    assertThat(detail.get("storageUrl")).isEqualTo(MOCK_STORAGE_URL);
    // Tika 解析 + 清洗后的简历文本已持久化
    assertThat((String) detail.get("resumeText")).contains("Java");

    List<Map<String, Object>> analyses = (List<Map<String, Object>>) detail.get("analyses");
    assertThat(analyses).isNotEmpty();
    Map<String, Object> analysis = analyses.get(0);
    assertThat(((Number) analysis.get("overallScore")).intValue()).isEqualTo(EXPECTED_SCORE);
    assertThat((List<Object>) analysis.get("strengths")).isNotEmpty();
    assertThat((List<Object>) analysis.get("suggestions")).isNotEmpty();
    assertThat((String) analysis.get("summary")).isNotBlank();

    // 列表接口应包含该简历
    List<Map<String, Object>> list = getAllResumes();
    assertThat(list).anySatisfy(item ->
      assertThat(((Number) item.get("id")).longValue()).isEqualTo(resumeId));
  }

  // ===== 场景 2：列表与详情查询 =====

  @Test
  @DisplayName("场景2：简历列表与详情查询（含分析历史）")
  @SuppressWarnings("unchecked")
  void listAndDetailQuery() throws Exception {
    byte[] content = buildResumeContent("scenario-2-" + UUID.randomUUID());
    Map<String, Object> uploadData = uploadResume("resume-s2.txt", content);
    Long resumeId = extractResumeId(uploadData);

    waitForAnalysisCompleted(resumeId);

    // GET /api/resumes 列表：定位到本简历，验证聚合字段
    List<Map<String, Object>> list = getAllResumes();
    Map<String, Object> listItem = list.stream()
      .filter(item -> ((Number) item.get("id")).longValue() == resumeId)
      .findFirst()
      .orElseThrow(() -> new AssertionError("列表中未找到 resumeId=" + resumeId));
    assertThat(listItem.get("filename")).isEqualTo("resume-s2.txt");
    assertThat(listItem.get("analyzeStatus")).isEqualTo("COMPLETED");
    assertThat(((Number) listItem.get("latestScore")).intValue()).isEqualTo(EXPECTED_SCORE);

    // GET /api/resumes/{id}/detail 详情：含分析历史
    Map<String, Object> detail = getDetail(resumeId);
    assertThat(((Number) detail.get("id")).longValue()).isEqualTo(resumeId);
    assertThat(detail.get("filename")).isEqualTo("resume-s2.txt");
    assertThat(detail.get("contentType")).isEqualTo("text/plain");

    List<Map<String, Object>> analyses = (List<Map<String, Object>>) detail.get("analyses");
    assertThat(analyses).hasSize(1);
    Map<String, Object> analysis = analyses.get(0);
    assertThat(((Number) analysis.get("overallScore")).intValue()).isEqualTo(EXPECTED_SCORE);
    assertThat((List<Object>) analysis.get("strengths")).isNotEmpty();
    assertThat((List<Object>) analysis.get("suggestions")).isNotEmpty();
  }

  // ===== 场景 3：重复上传检测 =====

  @Test
  @DisplayName("场景3：重复上传同一文件返回 duplicate=true 且指向同一简历")
  @SuppressWarnings("unchecked")
  void duplicateUploadDetection() throws Exception {
    // 相同内容 → 相同 SHA-256 → 触发去重
    byte[] content = buildResumeContent("scenario-3-" + UUID.randomUUID());

    Map<String, Object> first = uploadResume("resume-s3.txt", content);
    assertThat(first.get("duplicate")).isEqualTo(false);
    Long firstId = extractResumeId(first);

    Map<String, Object> second = uploadResume("resume-s3.txt", content);
    assertThat(second.get("duplicate")).isEqualTo(true);

    // 重复上传返回的仍是同一份简历
    Map<String, Object> storage = (Map<String, Object>) second.get("storage");
    assertThat(storage).isNotNull();
    assertThat(((Number) storage.get("resumeId")).longValue()).isEqualTo(firstId);
  }

  // ===== 辅助方法 =====

  /**
   * 构造简历文本内容，marker 保证各场景内容唯一（SHA-256 不同），避免跨测试去重干扰。
   */
  private byte[] buildResumeContent(String marker) {
    String text = RESUME_BODY + "\n备注：" + marker + "\n";
    return text.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * 通过 Spring MVC 上传简历（multipart/form-data），返回 Result.data。
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> uploadResume(String filename, byte[] content) throws Exception {
    MockMultipartFile file =
      new MockMultipartFile("file", filename, "text/plain", content);
    MvcResult result = mockMvc.perform(multipart("/api/resumes/upload").file(file))
      .andExpect(status().isOk())
      .andReturn();
    return (Map<String, Object>) parseSuccessfulBody(result).get("data");
  }

  /**
   * GET /api/resumes/{id}/detail，返回 Result.data。
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> getDetail(Long resumeId) throws Exception {
    MvcResult result = mockMvc.perform(get("/api/resumes/{id}/detail", resumeId))
      .andExpect(status().isOk())
      .andReturn();
    return (Map<String, Object>) parseSuccessfulBody(result).get("data");
  }

  /**
   * GET /api/resumes，返回 Result.data（列表）。
   */
  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> getAllResumes() throws Exception {
    MvcResult result = mockMvc.perform(get("/api/resumes"))
      .andExpect(status().isOk())
      .andReturn();
    return (List<Map<String, Object>>) parseSuccessfulBody(result).get("data");
  }

  /**
   * 解析响应体为 Map，并断言 HTTP 200 + 业务码 200。
   */
  private Map<String, Object> parseSuccessfulBody(MvcResult result) throws Exception {
    String json = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    Map<String, Object> body =
      objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    assertThat(body).isNotNull();
    assertThat(((Number) body.get("code")).intValue())
      .as("业务码应为 200，message=%s", body.get("message"))
      .isEqualTo(200);
    return body;
  }

  @SuppressWarnings("unchecked")
  private Long extractResumeId(Map<String, Object> uploadData) {
    Map<String, Object> resume = (Map<String, Object>) uploadData.get("resume");
    assertThat(resume).as("上传响应应包含 resume 字段").isNotNull();
    return ((Number) resume.get("id")).longValue();
  }

  /**
   * 轮询等待异步分析完成（最多 {@link #MAX_WAIT_MS}，每 {@link #POLL_INTERVAL_MS} 查一次）。
   *
   * <p>状态机 PENDING → PROCESSING → COMPLETED；其中 PROCESSING 为瞬态，
   * Demo 分析极快，轮询不一定能观测到，因此仅断言最终落到 COMPLETED。
   *
   * @return 完成时的详情数据
   */
  private Map<String, Object> waitForAnalysisCompleted(Long resumeId) throws Exception {
    long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
    Map<String, Object> detail = null;
    String status = null;
    while (System.currentTimeMillis() < deadline) {
      detail = getDetail(resumeId);
      status = (String) detail.get("analyzeStatus");
      if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
        break;
      }
      Thread.sleep(POLL_INTERVAL_MS);
    }
    assertThat(status)
      .as("简历分析应在 %d 秒内完成，最终状态=%s，错误=%s",
        MAX_WAIT_MS / 1000, status, detail == null ? null : detail.get("analyzeError"))
      .isEqualTo("COMPLETED");
    return detail;
  }
}
