package interview.guide.eval;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P1-C 外层 Embedding 操作预算门控。
 *
 * <p>门控的是<b>外层操作次数</b>（每次 {@code vectorStore.add()} 或 {@code similaritySearch()} 调用），
 * 不是底层 HTTP 请求数。两者可能不等——外层操作可能在发送前失败（0 HTTP），
 * SDK 内部也可能拆成多个请求。实际 HTTP 请求数由 OkHttp 拦截器独立观测。
 *
 * <p>{@code tryAcquire()} 原子地检查上限并递增：未达上限时递增并返回，达上限时不递增并抛出。
 * 使用 {@code compareAndSet} 循环保证并发安全，不存在 TOCTOU 竞态。
 */
public final class P1cEvalCallBudget {

  private final int hardLimit;
  private final AtomicInteger attempts = new AtomicInteger(0);
  private final AtomicInteger successes = new AtomicInteger(0);
  private final AtomicInteger failures = new AtomicInteger(0);

  public P1cEvalCallBudget(int hardLimit) {
    this.hardLimit = hardLimit;
  }

  /**
   * 外层操作发送前调用：原子地检查预算 + 递增尝试计数。
   *
   * @return 本次 attempt 编号（从 1 开始）
   * @throws IllegalStateException 预算耗尽时抛出，本次未递增、未执行
   */
  public int tryAcquire() {
    while (true) {
      int current = attempts.get();
      if (current >= hardLimit) {
        throw new IllegalStateException(
            "外层操作预算耗尽: 已完成 " + current + " 次，上限 " + hardLimit
            + "（本次未递增，未执行）");
      }
      if (attempts.compareAndSet(current, current + 1)) {
        return current + 1;
      }
      // CAS 失败说明有并发递增，重试即可
    }
  }

  public void recordSuccess() {
    successes.incrementAndGet();
  }

  public void recordFailure() {
    failures.incrementAndGet();
  }

  public int getAttempts() {
    return attempts.get();
  }

  public int getSuccesses() {
    return successes.get();
  }

  public int getFailures() {
    return failures.get();
  }

  public int getHardLimit() {
    return hardLimit;
  }

  public Map<String, Integer> snapshot() {
    Map<String, Integer> s = new LinkedHashMap<>();
    s.put("attempts", attempts.get());
    s.put("successes", successes.get());
    s.put("failures", failures.get());
    s.put("hardLimit", hardLimit);
    return s;
  }
}
