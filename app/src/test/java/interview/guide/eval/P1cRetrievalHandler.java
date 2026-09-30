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
 *   <li>{@link Status#RETRIEVAL_FAILED}：主检索异常且回退也失败／回退过滤后无结果。
 *       计零分并计入宏平均分母，但状态与计数独立记录，
 *       <b>不声称与生产 fallback 等价</b>（本实现是评测自有的单次受预算约束回退）。</li>
 *   <li>{@link Status#BUDGET_EXCEEDED}：主检索连一次预算都拿不到，请求从未发出。
 *       零 HTTP、零 attempt、零 recordFailure，调用方应据此中止整轮
 *       （后续题必然同样拿不到预算，继续跑只会得到被清零的宏平均）。</li>
 * </ul>
 *
 * <p>回退契约：主检索（topK=10，带 kb_id 过滤）抛异常时，
 * 至多 1 次回退——预算 tryAcquire（耗尽则回退不发起，判 RETRIEVAL_FAILED 且不产生 attempt/HTTP）→
 * 无过滤 topK=30（topK×3）→ 本地按 eval_run_id / kb_id / 冻结 28 ID 过滤 →
 * 过滤后为空仍判 RETRIEVAL_FAILED（主链路已异常，空结果无法区分隔离问题）。
 *
 * <p>任何阶段返回的命中都会先经 {@link P1cEvalResultValidator} 隔离核对，
 * 再要求 {@code eval_chunk_id} 属于冻结 28 集合；违例直接抛
 * {@link IllegalStateException} 中止（隔离破坏不是单题失败，是整个实验无效）。
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

  /** 单题检索结果。 */
  public record Outcome(
      Status status,
      boolean fallbackAttempted,
      List<Hit> hits,
      String mainError,
      String fallbackError) {

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
          "主检索未发起（预算耗尽，零 attempt / 零 HTTP）: " + budgetExhausted.getMessage());
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
        false, hits, null, null);
  }

  private Outcome fallback(String query, Exception mainError) {
    try {
      budget.tryAcquire();
    } catch (IllegalStateException budgetExhausted) {
      return failed(false, mainError, "回退未发起（预算耗尽）: " + budgetExhausted.getMessage());
    }
    try {
      List<Hit> raw = searchFunction.search(query, false, fallbackTopK);
      budget.recordSuccess();
      List<Hit> kept = new ArrayList<>();
      for (Hit hit : raw) {
        if (hit != null && expectedRunId.equals(hit.evalRunId())
            && expectedKbId.equals(hit.kbId()) && frozenChunkIds.contains(hit.evalChunkId())) {
          kept.add(hit);
        }
      }
      admit(query, kept);
      if (kept.isEmpty()) {
        return failed(true, mainError,
            "回退成功但本地过滤后无命中（主链路异常叠加隔离空集，拒绝解释为正常零命中）");
      }
      return new Outcome(Status.OK_WITH_HITS, true, kept, describe(mainError), null);
    } catch (Exception fallbackError) {
      budget.recordFailure();
      return failed(true, mainError, describe(fallbackError));
    }
  }

  private Outcome failed(boolean fallbackAttempted, Exception mainError, String fallbackError) {
    return new Outcome(Status.RETRIEVAL_FAILED, fallbackAttempted, List.of(),
        describe(mainError), fallbackError);
  }

  /** 隔离核对 + 冻结 ID 准入；违例直接抛出（实验级中止，不吞入单题状态）。 */
  private void admit(String query, List<Hit> hits) {
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
