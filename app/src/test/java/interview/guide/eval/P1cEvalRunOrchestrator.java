package interview.guide.eval;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P1-C 统一失败与清理流程编排（设计 v1.4 §7 Phase 5 契约）。
 *
 * <p>固定顺序：<b>实验主体 → 清理 → 报告写入</b>。清理先于报告，因此
 * 「一旦开始写入，后续所有阶段都走同一套 try/finally 清理」与
 * 「报告写入自身失败时也已尝试过清理」由构造顺序保证，不依赖各阶段自己写 finally。
 *
 * <p>三条不被破坏的不变式：
 * <ol>
 *   <li><b>原始异常优先</b>：清理失败或报告写入失败都不会替换实验主体抛出的异常；
 *       它们各自记录在 {@link RunResult#cleanup()} / {@link RunResult#reportError()}，
 *       并作为 suppressed 挂到原始异常上，不丢信息。</li>
 *   <li><b>清理失败不记为成功</b>：状态只能是
 *       {@link CleanupStatus#CLEAN_FAILED}（抛异常）或
 *       {@link CleanupStatus#CLEAN_PARTIAL}（残留行 &gt; 0）。</li>
 *   <li><b>无主异常时清理失败即实验失败</b>：若主体成功但清理失败，
 *       {@link RunResult#failure()} 记录 CLEANUP 阶段失败，
 *       绝不返回一个「全绿但库里留了 28 行」的报告。</li>
 * </ol>
 */
public final class P1cEvalRunOrchestrator {

  public enum CleanupStatus { NOT_REQUIRED, CLEANED, CLEAN_PARTIAL, CLEAN_FAILED }

  /** 清理执行结果：删除行数 + 校验后剩余行数。 */
  public record CleanupRowCounts(int deletedRows, int remainingRows) {
  }

  /** 按 runId 清理并回读剩余行数的动作（真实实现走 JDBC）。 */
  public interface CleanupAction {
    CleanupRowCounts clean() throws Exception;
  }

  /** 报告写入动作（真实实现序列化并落盘）。 */
  public interface ReportWriter {
    void write(RunResult result) throws Exception;
  }

  /** 实验主体：通过 {@link RunContext} 声明阶段、写入起点与部分结果。 */
  public interface Body {
    void execute(RunContext ctx) throws Exception;
  }

  /** 主体与装配层共享的可变上下文。 */
  public static final class RunContext {
    private boolean writesStarted;
    private String phase = "INIT";
    private Object partialResults;
    private final Map<String, Object> observations = new LinkedHashMap<>();

    public void phase(String name) {
      this.phase = name;
    }

    public String phase() {
      return phase;
    }

    /** 标记「已可能产生写副作用」：从此刻起清理必须被尝试。 */
    public void startWrites() {
      this.writesStarted = true;
    }

    public boolean writesStarted() {
      return writesStarted;
    }

    public void partialResults(Object results) {
      this.partialResults = results;
    }

    public Object partialResults() {
      return partialResults;
    }

    public void observe(String key, Object value) {
      observations.put(key, value);
    }

    public Map<String, Object> observations() {
      return Map.copyOf(observations);
    }
  }

  /** 失败记录：阶段 + 异常类 + 消息 + 原始异常引用。 */
  public record Failure(String phase, String exceptionClass, String message) {
  }

  /** 清理记录。{@code attempted=false} 表示从未进入写入阶段。 */
  public record CleanupRecord(CleanupStatus status, boolean attempted, int deletedRows,
      int remainingRows, String error) {

    public boolean success() {
      return status == CleanupStatus.CLEANED;
    }
  }

  /** 编排终态：实验失败信息 + 清理状态 + 报告写入结果 + 部分结果与请求观测。 */
  public record RunResult(
      Failure failure,
      CleanupRecord cleanup,
      boolean reportWritten,
      String reportError,
      Object partialResults,
      Map<String, Object> observations) {

    public boolean experimentSucceeded() {
      return failure == null;
    }

    /** 报告可用 = 已写入且无实验失败。清理失败时报告仍然写出。 */
    public boolean reportUsable() {
      return reportWritten && failure == null;
    }

    RunResult withReportWritten() {
      return new RunResult(failure, cleanup, true, null, partialResults, observations);
    }

    RunResult withReportError(String error) {
      return new RunResult(failure, cleanup, false, error, partialResults, observations);
    }
  }

  private final CleanupAction cleanupAction;
  private final ReportWriter reportWriter;
  private Throwable lastPrimaryError;

  public P1cEvalRunOrchestrator(CleanupAction cleanupAction, ReportWriter reportWriter) {
    this.cleanupAction = cleanupAction;
    this.reportWriter = reportWriter;
  }

  /**
   * 最近一次 {@link #execute(Body)} 的原始实验异常（无异常时为 null）。
   *
   * <p>刻意不放进 {@link RunResult}：报告 record 需可 JSON 序列化，堆栈不应进报告；
   * 装配层用它 rethrow，使 JUnit 显示真实异常链（清理/报告失败已挂为 suppressed）。
   */
  public Throwable primaryError() {
    return lastPrimaryError;
  }

  public RunResult execute(Body body) {
    RunContext ctx = new RunContext();
    Throwable primary = null;
    String primaryPhase = null;
    lastPrimaryError = null;

    // ═══ 阶段 A：实验主体（Phase 0~4 全部在此内部按 ctx.phase() 标注）═══
    try {
      body.execute(ctx);
    } catch (Throwable t) {
      primary = t;
      primaryPhase = ctx.phase();
    }

    // ═══ 阶段 B：清理（先于报告，状态必须先确定）═══
    CleanupAttempt cleanupAttempt = attemptCleanup(ctx);
    CleanupRecord cleanup = cleanupAttempt.record();
    if (primary != null && cleanupAttempt.error() != null) {
      addSuppressedQuietly(primary, cleanupAttempt.error());
    }

    Failure failure = describeFailure(primary, primaryPhase, cleanup);
    RunResult result = new RunResult(failure, cleanup, false, null,
        ctx.partialResults(), ctx.observations());

    lastPrimaryError = primary != null ? primary : cleanupError(cleanupAttempt, cleanup);

    // ═══ 阶段 C：报告写入（失败也不掩盖前面的信息）═══
    try {
      reportWriter.write(result);
      return result.withReportWritten();
    } catch (Throwable reportError) {
      if (primary != null) {
        addSuppressedQuietly(primary, reportError);
      } else if (lastPrimaryError != null) {
        addSuppressedQuietly(lastPrimaryError, reportError);
      } else {
        // 实验与清理都成功，只有报告没写出来——报告是交付物，不能记为通过
        lastPrimaryError = reportError;
      }
      return result.withReportError(describe(reportError));
    }
  }

  /** 主体无异常时，清理失败/残留仍要作为可 rethrow 的异常暴露。 */
  private static Throwable cleanupError(CleanupAttempt attempt, CleanupRecord cleanup) {
    if (attempt.error() != null) {
      return attempt.error();
    }
    if (cleanup.status() == CleanupStatus.CLEAN_PARTIAL) {
      return new IllegalStateException(cleanup.error());
    }
    return null;
  }

  /** 内部：清理记录 + 原始清理异常（后者只用于挂到主异常链，不进报告）。 */
  private record CleanupAttempt(CleanupRecord record, Throwable error) {
  }

  private CleanupAttempt attemptCleanup(RunContext ctx) {
    if (!ctx.writesStarted()) {
      return new CleanupAttempt(new CleanupRecord(CleanupStatus.NOT_REQUIRED, false, 0, 0,
          "未进入写入阶段，无需清理"), null);
    }
    try {
      CleanupRowCounts counts = cleanupAction.clean();
      if (counts.remainingRows() > 0) {
        return new CleanupAttempt(new CleanupRecord(CleanupStatus.CLEAN_PARTIAL, true,
            counts.deletedRows(), counts.remainingRows(),
            "清理后本次 runId 仍有 " + counts.remainingRows() + " 行残留"), null);
      }
      return new CleanupAttempt(
          new CleanupRecord(CleanupStatus.CLEANED, true, counts.deletedRows(), 0, null), null);
    } catch (Throwable t) {
      // 清理失败：状态与错误必须记录，且不能被当成 CLEANED
      return new CleanupAttempt(
          new CleanupRecord(CleanupStatus.CLEAN_FAILED, true, 0, -1, describe(t)), t);
    }
  }

  private static Failure describeFailure(Throwable primary, String phase, CleanupRecord cleanup) {
    if (primary != null) {
      return new Failure(phase, primary.getClass().getSimpleName(), primary.getMessage());
    }
    if (cleanup.status() == CleanupStatus.CLEAN_FAILED) {
      return new Failure("CLEANUP", "CleanupFailed", cleanup.error());
    }
    if (cleanup.status() == CleanupStatus.CLEAN_PARTIAL) {
      return new Failure("CLEANUP", "CleanupPartial", cleanup.error());
    }
    return null;
  }

  private static void addSuppressedQuietly(Throwable primary, Throwable extra) {
    if (extra != null && extra != primary) {
      try {
        primary.addSuppressed(extra);
      } catch (RuntimeException ignored) {
        // addSuppressed 在自引用/空引用时抛错，不影响主流程
      }
    }
  }

  private static String describe(Throwable t) {
    return t.getClass().getSimpleName() + ": " + t.getMessage();
  }
}
