package interview.guide.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * P1-C 检索执行与准入（设计 v1.4 §7 Phase 2 回退契约的可执行实现）。
 *
 * <p>状态契约——四种结果互斥：
 * <ul>
 *   <li>{@link Status#OK_WITH_HITS}：主检索或回退成功且有命中。</li>
 *   <li>{@link Status#OK_ZERO_RESULT}：主检索<b>正常返回但无命中</b>（如阈值截断），
 *       计零分、计入宏平均分母，属正常数据点。</li>
 *   <li>{@link Status#RETRIEVAL_FAILED}：主检索异常且回退也失败／回退返回空候选集。
 *       计零分并计入宏平均分母，但状态与计数独立记录，
 *       <b>不声称与生产 fallback 等价</b>（本实现是评测自有的单次受预算约束回退）。</li>
 *   <li>{@link Status#BUDGET_EXCEEDED}：主检索连一次预算都拿不到，请求从未发出。
 *       零 HTTP、零 attempt、零 recordFailure，调用方应据此中止整轮
 *       （后续题必然同样拿不到预算，继续跑只会得到被清零的宏平均）。</li>
 * </ul>
 *
 * <p>整轮可用性不是本类的职责：{@code RETRIEVAL_FAILED} 只保证单题口径不缩水，
 * 调用方必须在循环结束后用 {@link P1cRoundAvailability} 判断是否存在失败题。
 *
 * <p>回退契约：主检索（topK=10，带 kb_id 过滤）抛异常时，至多 1 次回退——
 * 预算 tryAcquire（耗尽则回退不发起，判 RETRIEVAL_FAILED 且不产生 attempt/HTTP）→
 * 无过滤 topK=30（topK×3）→ <b>对原始候选逐条归属判定</b>。
 *
 * <p>回退候选的处置只有两种，没有第三种"静默丢掉"：
 * <ul>
 *   <li><b>保留</b>：{@code eval_run_id} 等于本次运行，且 {@code eval_chunk_id} 非空并落在冻结 28 集合内、
 *       {@code kb_id} 等于评测值且为 JSON 字符串型。这类候选计入指标。</li>
 *   <li><b>中止整轮</b>：其余全部——候选为 null、{@code eval_run_id} 缺失（无法归属为任何一次评测）、
 *       {@code eval_run_id} 属于别的运行、本次 runId 但 chunkId 越界或缺失、kb_id 值越界或非字符串型。
 *       按设计 §7 步骤 6「回退结果中出现非本次 runId 的文档不静默过滤，而是中止评测并报告」执行：
 *       过滤前先看原始候选，否则混入的外来行不会进入违例检查，只要同时有合法行整题仍会被记成成功。</li>
 * </ul>
 * 回退请求成功返回后，归属判定与 {@link #admit} 都在"请求失败"的捕获范围之外：
 * 违例抛出的 {@link IllegalStateException} 既不会被降级成单题 {@code RETRIEVAL_FAILED}，
 * 也不会把一次已经成功的请求反记成 recordFailure。
 *
 * <p>主检索命中同样先经 {@link P1cEvalResultValidator} 隔离核对，再要求 {@code eval_chunk_id}
 * 属于冻结集合；违例直接抛出（隔离破坏不是单题失败，是整个实验无效）。
 */
public final class P1cRetrievalHandler {

  public enum Status { OK_WITH_HITS, OK_ZERO_RESULT, RETRIEVAL_FAILED, BUDGET_EXCEEDED }

  /** 单条命中：向量得分 + 数据库实际元数据文本抽取值（null 表示字段缺失），由装配层从 Document 转换。 */
  public record Hit(
      String evalChunkId,
      Double score,
      String evalRunId,
      String kbId,
      boolean kbIdIsString) {
  }

  /** 检索执行函数：filtered=true 主检索（topK=10，kb_id 过滤）；false 回退（topK=30，无过滤）。 */
  public interface SearchFunction {
    List<Hit> search(String query, boolean filtered, int topK) throws Exception;
  }

  /**
   * 单题检索结果。
   *
   * @param fallbackRawCandidateCount 回退实际看到的原始候选条数（未发起回退时为 0）。
   *                                  归属判定是在这批候选上做的，写出条数才能证明"先看原始候选"
   *                                  而不是先看过滤后的子集。
   */
  public record Outcome(
      Status status,
      boolean fallbackAttempted,
      List<Hit> hits,
      String mainError,
      String fallbackError,
      int fallbackRawCandidateCount) {

    public boolean failed() {
      return status == Status.RETRIEVAL_FAILED;
    }
  }

  private final P1cEvalCallBudget budget;
  private final SearchFunction searchFunction;
  private final String expectedRunId;
  private final String expectedKbId;
  private final Set<String> frozenChunkIds;
  private final int mainTopK;
  private final int fallbackTopK;

  public P1cRetrievalHandler(P1cEvalCallBudget budget, SearchFunction searchFunction,
      String expectedRunId, String expectedKbId, Set<String> frozenChunkIds,
      int mainTopK, int fallbackTopK) {
    if (fallbackTopK != mainTopK * 3) {
      throw new IllegalArgumentException(
          "回退候选数契约: fallbackTopK 必须 = mainTopK×3 = " + mainTopK * 3 + "，实际 " + fallbackTopK);
    }
    this.budget = budget;
    this.searchFunction = searchFunction;
    this.expectedRunId = expectedRunId;
    this.expectedKbId = expectedKbId;
    this.frozenChunkIds = Set.copyOf(frozenChunkIds);
    this.mainTopK = mainTopK;
    this.fallbackTopK = fallbackTopK;
  }

  public Outcome retrieve(String query) {
    List<Hit> hits;
    try {
      budget.tryAcquire();
    } catch (RuntimeException budgetExhausted) {
      return new Outcome(Status.BUDGET_EXCEEDED, false, List.of(), null,
          "主检索未发起（预算耗尽，零 attempt / 零 HTTP）: " + budgetExhausted.getMessage(), 0);
    }
    try {
      hits = searchFunction.search(query, true, mainTopK);
      budget.recordSuccess();
    } catch (Exception mainError) {
      budget.recordFailure();
      return fallback(query, mainError);
    }
    admit(query, hits);
    return new Outcome(hits.isEmpty() ? Status.OK_ZERO_RESULT : Status.OK_WITH_HITS,
        false, hits, null, null, 0);
  }

  private Outcome fallback(String query, Exception mainError) {
    try {
      budget.tryAcquire();
    } catch (IllegalStateException budgetExhausted) {
      return failed(false, mainError, "回退未发起（预算耗尽）: " + budgetExhausted.getMessage(), 0);
    }
    List<Hit> raw;
    try {
      raw = searchFunction.search(query, false, fallbackTopK);
      budget.recordSuccess();
    } catch (Exception fallbackError) {
      budget.recordFailure();
      return failed(true, mainError, describe(fallbackError), 0);
    }

    // 归属判定与准入都放在"请求失败"的捕获范围之外：回退请求已经成功，此后发现的任何隔离违例
    // 必须作为实验级异常抛出，不能被 catch 降级成单题 RETRIEVAL_FAILED。
    List<Hit> kept = attributeFallback(query, raw);
    admit(query, kept);
    if (kept.isEmpty()) {
      return failed(true, mainError,
          "回退成功但候选集为空（主链路已异常，空结果无法区分隔离问题，拒绝解释为正常零命中）", 0);
    }
    return new Outcome(Status.OK_WITH_HITS, true, kept, describe(mainError), null, raw.size());
  }

  /**
   * 对回退<b>原始</b>候选逐条归属判定；只有确属本次运行且数据合规的候选才返回，其余一律中止整轮。
   *
   * <p>先判定再取舍是关键顺序：若像旧实现那样先按 runId/kbId/冻结 ID 过滤、只对剩下的候选做准入，
   * 混进来的外来行根本不会进入违例检查，只要同时存在合法行，这道题就会被记成成功。
   */
  private List<Hit> attributeFallback(String query, List<Hit> raw) {
    if (raw == null) {
      throw fallbackBreach(query, 0, "回退返回 null 候选列表，无法归属");
    }
    List<Hit> kept = new ArrayList<>(raw.size());
    for (int i = 0; i < raw.size(); i++) {
      int rank = i + 1;
      Hit hit = raw.get(i);
      if (hit == null) {
        throw fallbackBreach(query, rank, "候选为 null");
      }
      String runId = hit.evalRunId();
      if (runId == null || runId.isBlank()) {
        throw fallbackBreach(query, rank,
            "缺失 eval_run_id，无法归属为任何一次评测运行（回退禁止静默过滤）: chunkId="
                + hit.evalChunkId());
      }
      if (!expectedRunId.equals(runId)) {
        throw fallbackBreach(query, rank,
            "非本次 runId（设计 §7 步骤 6：不静默过滤，中止并报告）: eval_run_id=" + runId
                + ", chunkId=" + hit.evalChunkId());
      }
      if (hit.evalChunkId() == null || hit.evalChunkId().isBlank()) {
        throw fallbackBreach(query, rank, "本次 runId 的候选缺失 eval_chunk_id");
      }
      if (!frozenChunkIds.contains(hit.evalChunkId())) {
        throw fallbackBreach(query, rank, "本次 runId 的候选不在冻结 "
            + frozenChunkIds.size() + " ID 集合内: chunkId=" + hit.evalChunkId());
      }
      if (!expectedKbId.equals(hit.kbId())) {
        throw fallbackBreach(query, rank, "本次 runId 的候选 kb_id 越界: chunkId="
            + hit.evalChunkId() + ", kb_id=" + hit.kbId());
      }
      if (!hit.kbIdIsString()) {
        throw fallbackBreach(query, rank, "本次 runId 的候选 kb_id 非字符串型: chunkId="
            + hit.evalChunkId());
      }
      kept.add(hit);
    }
    return kept;
  }

  private static IllegalStateException fallbackBreach(String query, int rank, String reason) {
    return new IllegalStateException(
        "回退候选隔离违例（实验隔离破坏，整轮中止）: query=\"" + query + "\", rank=" + rank + ", "
            + reason);
  }

  private Outcome failed(boolean fallbackAttempted, Exception mainError, String fallbackError,
      int fallbackRawCandidateCount) {
    return new Outcome(Status.RETRIEVAL_FAILED, fallbackAttempted, List.of(),
        describe(mainError), fallbackError, fallbackRawCandidateCount);
  }

  /** 隔离核对 + 冻结 ID 准入；违例直接抛出（实验级中止，不吞入单题状态）。 */
  private void admit(String query, List<Hit> hits) {
    if (hits == null) {
      throw new IllegalStateException("检索返回 null 命中列表（隔离破坏，实验中止）: query=\"" + query + "\"");
    }
    List<P1cEvalResultValidator.HitMetadata> metas = new ArrayList<>();
    for (int i = 0; i < hits.size(); i++) {
      Hit hit = hits.get(i);
      metas.add(new P1cEvalResultValidator.HitMetadata(
          i + 1, hit.evalRunId(), hit.kbId(), hit.evalChunkId()));
    }
    List<P1cEvalResultValidator.HitMetadata> validated =
        P1cEvalResultValidator.validate(metas, expectedRunId);
    for (P1cEvalResultValidator.HitMetadata meta : validated) {
      if (!frozenChunkIds.contains(meta.evalChunkId())) {
        throw new IllegalStateException(
            "检索命中含非冻结 eval_chunk_id（隔离破坏，实验中止）: query=\""
            + query + "\", chunkId=" + meta.evalChunkId());
      }
    }
    for (Hit hit : hits) {
      if (!hit.kbIdIsString()) {
        throw new IllegalStateException(
            "检索命中 kb_id 非字符串型（隔离破坏，实验中止）: query=\"" + query
            + "\", chunkId=" + hit.evalChunkId());
      }
    }
  }

  private static String describe(Exception e) {
    if (e == null) {
      return null;
    }
    return e.getClass().getSimpleName() + ": " + e.getMessage();
  }
}
