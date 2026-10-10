package interview.guide.common.ai.tools;

import interview.guide.common.config.StorageConfigProperties;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 基础设施依赖健康检查工具。
 * <p>白名单组件：postgresql、redis、objectStorage。
 * 每个组件独立探测，一个失败不影响其他。
 * 不返回原始异常/堆栈，错误时只返回 UNREACHABLE 状态。</p>
 * <p>请求隔离通过 Spring AI {@link ToolContext} 实现：
 * 每个 SSE 请求创建独立的 {@link ToolInvocationRecorder}，
 * 通过 {@code chatClient.prompt().toolContext(...)} 传递，
 * 框架自动注入到 @Tool 方法参数，并发请求不串写。</p>
 */
@Component
@Slf4j
public class DependencyHealthTools {

  private static final long PROBE_TIMEOUT_SECONDS = 5;
  private static final int MAX_SUMMARY_LENGTH = 200;

  /**
   * 健康检查关键词列表，供 Controller 和 Demo 模型共用。
   */
  public static final List<String> HEALTH_KEYWORDS = List.of(
      "健康", "依赖", "状态", "dependency", "health", "组件");

  /**
   * ToolContext 中 recorder 的 key
   */
  public static final String RECORDER_KEY = "toolInvocationRecorder";

  private final DataSource dataSource;
  private final RedissonClient redissonClient;
  private final S3Client s3Client;
  private final StorageConfigProperties storageConfig;
  private final Executor toolExecutor;

  public DependencyHealthTools(DataSource dataSource,
                               RedissonClient redissonClient,
                               S3Client s3Client,
                               StorageConfigProperties storageConfig,
                               @Qualifier("taskExecutor") Executor toolExecutor) {
    this.dataSource = dataSource;
    this.redissonClient = redissonClient;
    this.s3Client = s3Client;
    this.storageConfig = storageConfig;
    this.toolExecutor = toolExecutor;
  }

  @Tool(description = "检查基础设施依赖（PostgreSQL、Redis、对象存储）的健康状态。"
      + "返回每个组件的连接状态，用于排查基础设施故障。")
  public Map<String, Object> checkDependencyHealth(ToolContext toolContext) {
    long startTime = System.currentTimeMillis();
    log.info("[DependencyHealthTools] 开始健康检查");

    // 从 ToolContext 提取 per-request recorder（可能为 null）
    ToolInvocationRecorder recorder = extractRecorder(toolContext);

    // 执行上限检查：在探测之前检查，超限不执行实际探测
    if (recorder != null && !recorder.tryAcquireSlot()) {
      long durationMs = System.currentTimeMillis() - startTime;
      Map<String, Object> skippedResult = new LinkedHashMap<>();
      skippedResult.put("postgresql", Map.of("status", "SKIPPED"));
      skippedResult.put("redis", Map.of("status", "SKIPPED"));
      skippedResult.put("objectStorage", Map.of("status", "SKIPPED"));

      recorder.record(new ToolCallRecord(
          "checkDependencyHealth", "LIMIT_EXCEEDED",
          Map.of("message", "已达单次请求最大调用次数上限，跳过实际探测"),
          durationMs, Instant.now().toString(), false
      ));

      log.info("[DependencyHealthTools] 已达调用上限，跳过探测");
      return skippedResult;
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("postgresql", probeWithTimeout("postgresql", this::probePostgresql));
    result.put("redis", probeWithTimeout("redis", this::probeRedis));
    result.put("objectStorage", probeWithTimeout("objectStorage", this::probeObjectStorage));

    long durationMs = System.currentTimeMillis() - startTime;

    // 记录本次调用
    if (recorder != null) {
      recorder.record(new ToolCallRecord(
          "checkDependencyHealth", "SUCCESS",
          flattenResult(result), durationMs, Instant.now().toString(), false
      ));
    }

    log.info("[DependencyHealthTools] 健康检查完成，耗时 {}ms", durationMs);
    return result;
  }

  /**
   * 从 ToolContext 中提取 recorder。
   * toolContext 为 null 或不包含 recorder 时返回 null，工具仍正常执行但不记录。
   */
  private ToolInvocationRecorder extractRecorder(ToolContext toolContext) {
    if (toolContext == null) {
      return null;
    }
    Object obj = toolContext.getContext().get(RECORDER_KEY);
    return obj instanceof ToolInvocationRecorder r ? r : null;
  }

  private Map<String, Object> probeWithTimeout(String componentName, ProbeAction action) {
    try {
      return CompletableFuture.supplyAsync(action::probe, toolExecutor)
          .orTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
          .join();
    } catch (Exception e) {
      log.warn("[DependencyHealthTools] {} 探测失败: {}", componentName, e.getMessage());
      return Map.of("status", "UNREACHABLE");
    }
  }

  private Map<String, Object> probePostgresql() {
    try (Connection conn = dataSource.getConnection()) {
      // isValid(3) 有 3 秒有界超时 ✓
      boolean valid = conn.isValid(3);
      return valid
          ? Map.of("status", "OK", "detail", "连接正常")
          : Map.of("status", "UNREACHABLE");
    } catch (Exception e) {
      log.warn("[DependencyHealthTools] PostgreSQL 探测异常: {}", e.getMessage());
      return Map.of("status", "UNREACHABLE");
    }
  }

  private Map<String, Object> probeRedis() {
    try {
      // 使用最轻量的方式：检查一个 key 是否存在
      // Redisson isExists 底层有连接超时配置 ✓
      redissonClient.getBucket("__health_check__").isExists();
      return Map.of("status", "OK", "detail", "连接正常");
    } catch (Exception e) {
      log.warn("[DependencyHealthTools] Redis 探测异常: {}", e.getMessage());
      return Map.of("status", "UNREACHABLE");
    }
  }

  private Map<String, Object> probeObjectStorage() {
    if (s3Client == null) {
      return Map.of("status", "UNREACHABLE", "detail", "S3 客户端未配置");
    }
    try {
      String bucket = storageConfig.getBucket();
      // S3Client headBucket 使用 SDK 默认 API 调用超时配置 ✓
      s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
      return Map.of("status", "OK", "detail", "连接正常");
    } catch (Exception e) {
      log.warn("[DependencyHealthTools] 对象存储探测异常: {}", e.getMessage());
      return Map.of("status", "UNREACHABLE");
    }
  }

  /**
   * 将嵌套的探测结果扁平化为状态字符串，供前端直接渲染。
   * 例如：{"postgresql": "OK", "redis": "UNREACHABLE"}
   */
  private Map<String, Object> flattenResult(Map<String, Object> result) {
    Map<String, Object> flat = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : result.entrySet()) {
      Object value = entry.getValue();
      if (value instanceof Map<?, ?> componentMap) {
        Object status = componentMap.get("status");
        flat.put(entry.getKey(), status != null ? status.toString() : "UNKNOWN");
      } else {
        flat.put(entry.getKey(), String.valueOf(value));
      }
    }
    return flat;
  }

  @FunctionalInterface
  private interface ProbeAction {
    Map<String, Object> probe();
  }
}
