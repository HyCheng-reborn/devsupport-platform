package interview.guide.common.ai.tools;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-request 工具调用记录器。
 * <p>通过 {@link DependencyHealthTools#setRecorder(ToolInvocationRecorder)} 绑定到当前请求，
 * 在 SSE 流完成后由 Controller 取出记录并清理。</p>
 */
public class ToolInvocationRecorder {

  /** 单次请求最大工具调用次数 */
  public static final int MAX_INVOCATIONS = 3;

  private final List<ToolCallRecord> records = new CopyOnWriteArrayList<>();

  /**
   * 记录一次工具调用。
   * LIMIT_EXCEEDED 状态不受 MAX_INVOCATIONS 限制，确保超限标记始终被记录。
   * 使用 synchronized 保证 check-then-add 的原子性。
   */
  public synchronized void record(ToolCallRecord record) {
    if ("LIMIT_EXCEEDED".equals(record.status())) {
      records.add(record);
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
  }
}
