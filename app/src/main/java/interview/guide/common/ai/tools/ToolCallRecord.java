package interview.guide.common.ai.tools;

/**
 * 工具调用记录，记录单次工具执行的元信息。
 *
 * @param toolName  工具名称
 * @param status    执行状态：SUCCESS, TIMEOUT, ERROR, LIMIT_EXCEEDED
 * @param result    脱敏后的结果 Map
 * @param durationMs 执行耗时（毫秒）
 * @param timestamp 执行时间戳（ISO-8601）
 * @param demo      是否为 Demo 模拟调用
 */
public record ToolCallRecord(
  String toolName,
  String status,
  Object result,
  long durationMs,
  String timestamp,
  boolean demo
) {}
