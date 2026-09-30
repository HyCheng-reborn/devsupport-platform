package interview.guide.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * P1-C 整轮可用性判定：把"请求是否完成"与"检索质量"两件事分开。
 *
 * <p>检索失败题按零计入宏平均是<b>单题</b>处理口径，用来保证分母不缩水；但如果没有人在
 * 循环结束后判断，20 题全部 {@code RETRIEVAL_FAILED} 的一轮同样能带着全零指标成功退出。
 * 自动读取宏平均的人会把请求故障读成检索质量为零，所以这里给出实验级结论：
 * <ul>
 *   <li>{@code queriesCompleted < expectedQueries}：正式基线要求全部题请求完成，未完成不解释质量。</li>
 *   <li>{@code failedRetrievalQueries > 0}：存在请求失败，整轮指标不可用（失败题记录与零分口径照旧保留）。</li>
 *   <li>{@code OK_ZERO_RESULT} 是正常数据点，<b>不</b>影响可用性——它与 {@code RETRIEVAL_FAILED}
 *       的区别正是这套状态机存在的理由，不能在这里被合并回去。</li>
 * </ul>
 */
public record P1cRoundAvailability(
    int expectedQueries,
    int queriesCompleted,
    int failedRetrievalQueries,
    int zeroResultQueries,
    int queriesWithFallbackAttempted,
    Status status,
    List<String> reasons,
    String note) {

  public enum Status { USABLE, NOT_USABLE }

  public boolean usable() {
    return status == Status.USABLE;
  }

  public static P1cRoundAvailability evaluate(
      int expectedQueries,
      int queriesCompleted,
      int failedRetrievalQueries,
      int zeroResultQueries,
      int queriesWithFallbackAttempted) {
    List<String> reasons = new ArrayList<>();
    if (queriesCompleted < expectedQueries) {
      reasons.add("请求完成题数 " + queriesCompleted + "/" + expectedQueries
          + "：正式基线要求全部题请求完成，未完成的轮次不解释检索质量");
    }
    if (failedRetrievalQueries > 0) {
      reasons.add("存在 " + failedRetrievalQueries + " 题 RETRIEVAL_FAILED：按零计入宏平均是失败题的单题口径，"
          + "不是检索质量结论，整轮指标不可用");
    }
    return new P1cRoundAvailability(expectedQueries, queriesCompleted, failedRetrievalQueries,
        zeroResultQueries, queriesWithFallbackAttempted,
        reasons.isEmpty() ? Status.USABLE : Status.NOT_USABLE, List.copyOf(reasons),
        "判定只影响本轮是否可作为正式基线；失败题的原始记录、错误串与按零计入的多 K 指标仍完整保存在报告里。"
        + "OK_ZERO_RESULT(" + zeroResultQueries + " 题) 属正常数据点，不参与本判定");
  }
}
