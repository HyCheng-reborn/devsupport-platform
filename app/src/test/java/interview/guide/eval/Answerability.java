package interview.guide.eval;

/**
 * 查询的可答性 / 标注状态。
 *
 * <p>只有 {@link #ANSWERABLE} 且带有非空 gold 片段标注的查询才参与 Hit/Recall/MRR 宏平均；
 * 其余状态被排除并单独计数，避免制造除零或虚假满分。
 */
public enum Answerability {
  /** 已标注为语料中存在相关证据，参与宏平均。 */
  ANSWERABLE,
  /** 已标注为语料中无相关证据（无答案题），不参与宏平均。 */
  NO_ANSWER,
  /** 尚未标注，不参与宏平均。 */
  UNANNOTATED
}
