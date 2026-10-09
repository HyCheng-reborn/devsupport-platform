package interview.guide.common.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Demo profile 下的 ChatModel，返回预定义的排查建议模板。
 *
 * <p>仅用于本地演示，不调用任何真实 LLM API，零付费。
 * 基于问题关键词匹配 3-5 个模板响应，模拟真实的 RAG 问答输出。</p>
 */
@Component
@Profile("demo")
@Slf4j
public class DemoChatModel implements ChatModel {

  private static final Set<String> KEYWORD_PORT = Set.of("端口", "port", "8080", "启动");
  private static final Set<String> KEYWORD_DB = Set.of("数据库", "database", "postgres", "postgresql", "连接");
  private static final Set<String> KEYWORD_REDIS = Set.of("redis", "缓存", "cache", "stream");
  private static final Set<String> KEYWORD_LLM = Set.of("llm", "ai", "模型", "chat", "embedding", "向量");
  private static final Set<String> KEYWORD_CONFIG = Set.of("配置", "config", "yml", "yaml", "环境变量", "env");

  @Override
  public ChatResponse call(Prompt prompt) {
    String question = extractQuestion(prompt);
    log.info("[Demo] 生成演示回答，问题: {}", truncate(question, 50));

    String response = generateDemoResponse(question);
    AssistantMessage message = new AssistantMessage(response);
    Generation generation = new Generation(message);

    return new ChatResponse(List.of(generation));
  }

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    String question = extractQuestion(prompt);
    log.info("[Demo] 生成演示流式回答，问题: {}", truncate(question, 50));

    String response = generateDemoResponse(question);

    // 模拟流式输出：按字符分块返回
    return Flux.fromArray(response.split("(?<=。)"))
        .map(chunk -> {
          AssistantMessage message = new AssistantMessage(chunk);
          Generation generation = new Generation(message);
          return new ChatResponse(List.of(generation));
        });
  }

  /**
   * 从 Prompt 中提取用户问题文本。
   */
  private String extractQuestion(Prompt prompt) {
    if (prompt == null || prompt.getInstructions() == null || prompt.getInstructions().isEmpty()) {
      return "";
    }
    // 取最后一条用户消息作为问题
    return prompt.getInstructions().stream()
        .filter(msg -> msg instanceof org.springframework.ai.chat.messages.UserMessage)
        .reduce((first, last) -> last)
        .map(msg -> msg.getText())
        .orElse("");
  }

  /**
   * 基于问题关键词匹配生成演示响应。
   */
  private String generateDemoResponse(String question) {
    if (question == null || question.isBlank()) {
      return getDefaultResponse();
    }

    String lowerQuestion = question.toLowerCase();

    // 关键词匹配，返回对应的模板响应
    if (containsAny(lowerQuestion, KEYWORD_PORT)) {
      return getPortResponse();
    }
    if (containsAny(lowerQuestion, KEYWORD_DB)) {
      return getDatabaseResponse();
    }
    if (containsAny(lowerQuestion, KEYWORD_REDIS)) {
      return getRedisResponse();
    }
    if (containsAny(lowerQuestion, KEYWORD_LLM)) {
      return getLlmResponse();
    }
    if (containsAny(lowerQuestion, KEYWORD_CONFIG)) {
      return getConfigResponse();
    }

    return getDefaultResponse();
  }

  private boolean containsAny(String text, Set<String> keywords) {
    return keywords.stream().anyMatch(text::contains);
  }

  private String getPortResponse() {
    return """
        根据知识库文档，项目的后端服务默认端口配置如下：

        **后端端口**: 8080
        - 配置文件: `application.yml` 中的 `server.port: ${SERVER_PORT:8080}`
        - 可通过环境变量 `SERVER_PORT` 覆盖
        - 前端开发服务器通过 Vite 代理转发到 `http://localhost:8080`

        **启动方式**:
        ```bash
        ./gradlew :app:bootRun
        ```

        如果需要修改端口，可以在启动时指定：
        ```bash
        ./gradlew :app:bootRun --args='--server.port=9090'
        ```

        希望这能帮助你解决端口相关的问题！""";
  }

  private String getDatabaseResponse() {
    return """
        根据知识库文档，项目数据库配置如下：

        **数据库**: PostgreSQL + pgvector
        - 向量维度: 1024
        - 距离类型: COSINE
        - 开发环境使用 Testcontainers 隔离测试

        **连接配置**:
        - 主机: localhost (开发环境)
        - 端口: 5432
        - 数据库名: interview_guide
        - 用户名/密码: 通过环境变量配置

        **Docker 启动**:
        ```bash
        docker compose -f docker-compose.dev.yml up -d
        ```

        **Flyway 迁移**:
        - 迁移脚本位于 `app/src/main/resources/db/migration/`
        - 开发环境 `ddl-auto` 可设为 `update`
        - 生产环境不能依赖自动建表

        如果遇到数据库连接问题，请检查：
        1. Docker 容器是否正常运行
        2. 环境变量是否正确配置
        3. 端口是否被占用""";
  }

  private String getRedisResponse() {
    return """
        根据知识库文档，项目 Redis 配置如下：

        **Redis 用途**:
        - 缓存与异步任务
        - Redis Stream 消息队列
        - 限流计数器

        **配置信息**:
        - 主机: localhost (开发环境)
        - 端口: 6379
        - 客户端: Redisson 4.0

        **Redis Stream 异步链路**:
        - 使用 `AbstractStreamProducer` / `AbstractStreamConsumer` 模板
        - 5 个 Stream Key 用于不同异步任务
        - 异步处理前先校验实体是否存在

        **Docker 启动**:
        ```bash
        docker compose -f docker-compose.dev.yml up -d
        ```

        如果 Redis 连接失败，请检查：
        1. Redis 容器是否正常运行
        2. 端口 6379 是否可访问
        3. 是否需要密码认证""";
  }

  private String getLlmResponse() {
    return """
        根据知识库文档，项目 AI/LLM 配置如下：

        **Spring AI 2.0.0 集成**:
        - 推荐 Provider: 阿里云 DashScope (通义千问)
        - Chat 模型: qwen-plus 或兼容模型
        - Embedding 模型: text-embedding-v3 (1024 维)

        **Provider 管理**:
        - 支持多 Provider 动态配置
        - 通过 `LlmProviderRegistry` 统一管理
        - API Key 加密存储，不明文提交

        **RAG 问答**:
        - 向量检索 + LLM 生成
        - 支持多知识库范围隔离
        - SSE 流式输出

        **配置方式**:
        - API Key 通过环境变量 `AI_BAILIAN_API_KEY` 配置
        - 或在 LLM Provider 管理界面配置

        如果需要使用 Demo 模式（零付费），可以激活 demo profile：
        ```bash
        ./gradlew :app:bootRun --args='--spring.profiles.active=demo'
        ```""";
  }

  private String getConfigResponse() {
    return """
        根据知识库文档，项目配置管理如下：

        **配置文件结构**:
        - `application.yml`: 主配置
        - `application-demo.yml`: Demo profile 配置
        - `application-test.yml`: 测试环境配置
        - `.env`: 环境变量（不提交到 Git）

        **敏感信息**:
        - API Key、数据库密码等只放 `.env`
        - 不得提交到 Git
        - 使用 `@ConfigurationProperties` 绑定

        **环境变量优先级**:
        1. 命令行参数
        2. `.env` 文件
        3. `application.yml` 默认值

        **常用环境变量**:
        - `AI_BAILIAN_API_KEY`: 阿里云 AI API Key
        - `POSTGRES_PASSWORD`: 数据库密码
        - `SERVER_PORT`: 后端端口（默认 8080）

        更多配置说明请参考 `SETUP_API_KEYS.md` 文档。""";
  }

  private String getDefaultResponse() {
    return """
        根据知识库文档检索结果，我为您提供以下排查建议：

        1. **检查环境配置**
           - 确认所有必需的服务已启动（PostgreSQL、Redis）
           - 验证环境变量是否正确配置
           - 查看日志输出是否有异常

        2. **查阅项目文档**
           - `README.md`: 项目概述与快速启动
           - `SETUP_API_KEYS.md`: API Key 配置指南
           - `AGENTS.md`: 开发规范与架构说明

        3. **常见问题排查**
           - 端口占用：检查 8080、5432、6379 是否被占用
           - 依赖服务：确认 Docker 容器正常运行
           - 配置缺失：检查 `.env` 文件是否存在

        4. **构建与启动**
           ```bash
           # 后端编译
           ./gradlew :app:compileJava

           # 后端启动
           ./gradlew :app:bootRun

           # 前端开发
           cd frontend && pnpm run dev
           ```

        如果您有更具体的问题，请提供更多细节，我会尽力帮助您！""";
  }

  private String truncate(String text, int maxLength) {
    if (text == null || text.length() <= maxLength) {
      return text;
    }
    return text.substring(0, maxLength) + "...";
  }
}
