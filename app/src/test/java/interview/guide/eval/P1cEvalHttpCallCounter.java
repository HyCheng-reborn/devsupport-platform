package interview.guide.eval;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * P1-C HTTP 请求观测器。
 *
 * <p>通过 OkHttp 拦截器记录实际 HTTP 请求数（信息性，不阻断）。
 * 与 {@link P1cEvalCallBudget} 的外层操作计数分别记录，两者可能不等。
 */
public final class P1cEvalHttpCallCounter {

  private final AtomicInteger count = new AtomicInteger(0);

  public int incrementAndGet() {
    return count.incrementAndGet();
  }

  public int get() {
    return count.get();
  }

  /**
   * 返回 OkHttp 拦截器回调（函数式接口，避免直接依赖 okhttp3 类型）。
   *
   * <p>用法：{@code SpringAiOpenAiHttpClient.builder().interceptor(counter.toInterceptor())}
   */
  public okhttp3.Interceptor toInterceptor() {
    return chain -> {
      count.incrementAndGet();
      return chain.proceed(chain.request());
    };
  }
}
