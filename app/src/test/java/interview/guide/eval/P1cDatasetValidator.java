package interview.guide.eval;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * P1-C 数据集离线预检（纯计算，不触网、不连库、不读凭据）。
 *
 * <p>把"基线按 28、候选按 50"这类预期数量与金标 ID 闭环判定集中到一处，供真实评测 Phase 0
 * 在<b>读取凭据、构建 Embedding 客户端、连接数据库之前</b>调用，也供离线单元测试直接验证。
 * 校验粒度是数量与引用闭环，与 {@link P1cFrozenArtifactVerifier} 的整文件哈希校验互不替代：
 * 哈希漂移由后者抓，数量/引用闭环由此类抓。
 *
 * <p>金标 ID 闭环：每个 ANSWERABLE 题的每条 supportingChunkId 必须出现在数据集 chunkId 集合内，
 * 否则该金标指向了不存在于本数据集的 chunk——真实评测里表现为所有命中恒为 0，必须在此拦下。
 */
public final class P1cDatasetValidator {

  /** 预检结果：违例为空即通过。 */
  public record Result(List<String> violations) {
    public boolean passed() {
      return violations.isEmpty();
    }
  }

  private P1cDatasetValidator() {
  }

  /**
   * @param chunkIds                数据集全部 chunkId（应为去重集合）
   * @param chunkCount              数据集 chunk 条数
   * @param totalEntries            金标题目总数
   * @param answerableCount         ANSWERABLE 题数
   * @param noAnswerCount           NO_ANSWER 题数
   * @param totalAnswerPoints       全部 ANSWERABLE 题的答案要点总数（多 K 微平均分母）
   * @param goldSupportingChunkIds  全部 ANSWERABLE 题引用到的 supportingChunkId（可含重复）
   * @param expectedChunkCount      期望 chunk 数（基线 28，候选 50）
   * @param expectedQueryCount      期望题目总数
   * @param expectedAnswerable      期望可答题数
   * @param expectedNoAnswer        期望 NO_ANSWER 数
   * @param expectedAnswerPoints    期望答案要点总数
   * @return 全部违例（不短路），空表示通过
   */
  public static Result validate(
      Set<String> chunkIds,
      int chunkCount,
      int totalEntries,
      int answerableCount,
      int noAnswerCount,
      int totalAnswerPoints,
      Collection<String> goldSupportingChunkIds,
      int expectedChunkCount,
      int expectedQueryCount,
      int expectedAnswerable,
      int expectedNoAnswer,
      int expectedAnswerPoints) {
    List<String> violations = new ArrayList<>();

    if (chunkCount != expectedChunkCount) {
      violations.add("chunk 数量不符: 期望 " + expectedChunkCount + ", 实际 " + chunkCount);
    }
    if (chunkIds.size() != chunkCount) {
      violations.add("存在重复 chunkId: 去重后 " + chunkIds.size() + " ≠ 总条数 " + chunkCount);
    }
    if (totalEntries != expectedQueryCount) {
      violations.add("题目总数不符: 期望 " + expectedQueryCount + ", 实际 " + totalEntries);
    }
    if (answerableCount != expectedAnswerable) {
      violations.add("ANSWERABLE 题数不符: 期望 " + expectedAnswerable + ", 实际 " + answerableCount);
    }
    if (noAnswerCount != expectedNoAnswer) {
      violations.add("NO_ANSWER 题数不符: 期望 " + expectedNoAnswer + ", 实际 " + noAnswerCount);
    }
    if (totalAnswerPoints != expectedAnswerPoints) {
      violations.add("答案要点总数不符: 期望 " + expectedAnswerPoints + ", 实际 " + totalAnswerPoints);
    }

    Set<String> missingGoldIds = new LinkedHashSet<>();
    for (String id : goldSupportingChunkIds) {
      if (id == null || id.isBlank()) {
        violations.add("金标存在空 supportingChunkId");
      } else if (!chunkIds.contains(id)) {
        missingGoldIds.add(id);
      }
    }
    for (String id : missingGoldIds) {
      violations.add("金标 supportingChunkId 不在数据集 chunk 集合内（缺失或错配数据集）: " + id);
    }

    return new Result(violations);
  }
}
