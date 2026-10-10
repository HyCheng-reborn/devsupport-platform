package interview.guide.common.ai.tools;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-request 工具调用记录器。
 * <p>通过 Spring AI {@code ToolContext} 传递，每个请求创建独立实例，
 * 天然隔离并发 SSE 请求，无需清理。</p>
 */
public class ToolInvocationRecorder {

  /** 单次请求最大工具调用次数 */
  public static final int MAX_INVOCATIONS = 3;

  private final List<ToolCallRecord> records = new CopyOnWriteArrayList<>();
  private final AtomicInteger executedCount = new AtomicInteger(0);

  /**
   * 尝试领取一次执行额度。
   * 原子地检查并领取，确保不超过 {@link #MAX_INVOCATIONS} 次实际探测。
   *
   * @return true 如果成功领取（可以执行探测），false 如果已达上限
   */
  public boolean tryAcquireSlot() {
    return executedCount.incrementAndGet() <= MAX_INVOCATIONS;
  }

  /**
   * 获取已实际执行的次数（包括超限后跳过的次数）
   */
  public int getExecutedCount() {
    return executedCount.get();
  }

  /**
   * 记录一次工具调用。
   * LIMIT_EXCEEDED 状态最多追加 1 条，防止模型反复请求时记录无限增长。
   * 使用 synchronized 保证 check-then-add 的原子性。
   */
  public synchronized void record(ToolCallRecord record) {
    if ("LIMIT_EXCEEDED".equals(record.status())) {
      boolean hasLimitRecord = records.stream()
          .anyMatch(r -> "LIMIT_EXCEEDED".equals(r.status()));
      if (!hasLimitRecord) {
        records.add(record);
      }
    } else if (records.size() < MAX_INVOCATIONS) {
      records.add(record);
    }
  }

  /**
   * 获取已记录的调用列表（不可变视图）
   */
  public List<ToolCallRecord> getRecords() {
    return Collections.unmodifiableList(records);
  }

  /**
   * 是否已达调用上限
   */
  public boolean hasReachedLimit() {
    return records.size() >= MAX_INVOCATIONS;
  }

  /**
   * 清空记录
   */
  public void reset() {
    records.clear();
    executedCount.set(0);
  }
}
