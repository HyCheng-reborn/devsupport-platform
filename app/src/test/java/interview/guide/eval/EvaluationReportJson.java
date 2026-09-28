package interview.guide.eval;

import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 评测夹具读取与报告 JSON 序列化。
 *
 * <p>复用项目现有的 Jackson 3（{@code tools.jackson.databind}），不引入新依赖。
 */
public final class EvaluationReportJson {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private static final String K_CONSTRAINT =
      "夹具字段 k 必须是 JSON 整数且满足 1 <= k <= Integer.MAX_VALUE";

  private EvaluationReportJson() {
  }

  /** 固定夹具文件结构：一个 K 值 + 一组查询标注及其检索结果。 */
  public record Fixture(int k, List<QueryJudgement> judgements) {
  }

  /**
   * 从 JSON 文本读取固定夹具。
   *
   * <p>先以 JSON 树校验 {@code k} 的节点类型与范围，再映射夹具对象，避免 Jackson 把小数
   * （如 {@code 1.9}、{@code 1.0}）静默截断为 int。{@code k} 必须是 JSON 整数、
   * 且 {@code 1 <= k <= Integer.MAX_VALUE}；小数、字符串、null、缺失、布尔、超出 int 范围
   * 及非正值一律拒绝，不做截断 / 四舍五入 / 字符串转整数 / 默认值补齐。
   *
   * @throws IllegalArgumentException 当 {@code k} 不满足上述约束时；此时不会产出任何夹具或报告
   */
  public static Fixture readFixture(String json) {
    JsonNode root = MAPPER.readTree(json);
    int k = validateK(root);
    Fixture mapped = MAPPER.readValue(json, Fixture.class);
    return new Fixture(k, mapped.judgements());
  }

  private static int validateK(JsonNode root) {
    JsonNode kNode = root == null ? null : root.get("k");
    if (kNode == null || kNode.isMissingNode() || kNode.isNull()) {
      throw new IllegalArgumentException(K_CONSTRAINT + "；实际: k 缺失或为 null");
    }
    if (!kNode.isNumber()) {
      throw new IllegalArgumentException(K_CONSTRAINT + "；实际: k 不是 JSON 数字（节点类型 "
          + kNode.getNodeType() + "，值 " + kNode.asText() + "）");
    }
    if (!kNode.isIntegralNumber()) {
      throw new IllegalArgumentException(K_CONSTRAINT + "；实际: k 含小数（值 " + kNode.asText()
          + "），拒绝 1.9 与 1.0 等任何小数");
    }
    if (!kNode.canConvertToInt()) {
      throw new IllegalArgumentException(K_CONSTRAINT + "；实际: k 超出 int 范围（值 "
          + kNode.asText() + "）");
    }
    int k = kNode.intValue();
    if (k < 1) {
      throw new IllegalArgumentException(K_CONSTRAINT + "；实际: k=" + k + "（必须 >= 1）");
    }
    return k;
  }

  /** 将报告序列化为带缩进的 JSON 文本。 */
  public static String toJson(EvaluationReport report) {
    return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report);
  }

  /** 从 JSON 文本反序列化报告（用于导出结果与计算结果的一致性校验）。 */
  public static EvaluationReport readReport(String json) {
    return MAPPER.readValue(json, EvaluationReport.class);
  }
}
