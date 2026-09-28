package interview.guide.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import interview.guide.eval.EvaluationReportJson.Fixture;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 固定夹具 → 指标计算 → JSON 导出的一致性测试。
 *
 * <p>纯 Java/JUnit，不启动 Spring，不连接数据库 / Redis / S3 / 网络 / 模型。
 * 夹具数据全部为虚构（见 {@code eval/example-query-set.json}）。
 */
@DisplayName("评测报告 JSON 导出一致性测试")
class EvaluationReportJsonTest {

  private static final double DELTA = 1e-9;
  private static final String FIXTURE_RESOURCE = "/eval/example-query-set.json";

  private static String loadFixtureJson() throws IOException {
    try (InputStream in = EvaluationReportJsonTest.class.getResourceAsStream(FIXTURE_RESOURCE)) {
      assertTrue(in != null, "夹具资源应存在: " + FIXTURE_RESOURCE);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  @DisplayName("夹具计算结果符合手工预期")
  void fixtureMetricsMatchHandComputed() throws IOException {
    Fixture fixture = EvaluationReportJson.readFixture(loadFixtureJson());
    assertEquals(3, fixture.k());

    EvaluationReport report = RetrievalMetrics.evaluate(fixture.k(), fixture.judgements());

    // fixture-001: gold={chunk-A,chunk-B}, top3=[chunk-A,chunk-X,chunk-B] → hit=1, recall=1, rr=1
    // fixture-002: gold={chunk-Z}, top2=[chunk-X,chunk-Y] → 无命中
    // fixture-003: NO_ANSWER → 排除
    assertEquals(3, report.totalQueries());
    assertEquals(2, report.evaluatedQueries());
    assertEquals(1, report.noAnswerQueries());
    assertEquals(0, report.unannotatedQueries());
    assertEquals(1, report.excludedQueries());
    assertEquals(0.5, report.macroHitAtK(), DELTA);
    assertEquals(0.5, report.macroRecallAtK(), DELTA);
    assertEquals(0.5, report.macroMrrAtK(), DELTA);

    QueryEvaluation first = report.perQuery().get(0);
    assertEquals("fixture-001", first.queryId());
    assertEquals(1.0, first.hitAtK(), DELTA);
    assertEquals(1.0, first.recallAtK(), DELTA);
    assertEquals(1.0, first.reciprocalRank(), DELTA);

    QueryEvaluation excluded = report.perQuery().get(2);
    assertEquals("fixture-003", excluded.queryId());
    assertFalse(excluded.includedInMacroAverage());
    assertNull(excluded.hitAtK());
  }

  @Test
  @DisplayName("JSON 导出与计算结果一致（可反序列化回等值报告）")
  void jsonExportMatchesComputedReport() throws IOException {
    Fixture fixture = EvaluationReportJson.readFixture(loadFixtureJson());
    EvaluationReport report = RetrievalMetrics.evaluate(fixture.k(), fixture.judgements());

    String json = EvaluationReportJson.toJson(report);
    assertTrue(json.contains("\"executionMode\""), "JSON 应包含 executionMode");
    assertTrue(json.contains("fixture"), "JSON 应标注 fixture 模式");
    assertTrue(json.contains("metric_validation"), "JSON 应标注 metric_validation 范围");

    EvaluationReport reparsed = EvaluationReportJson.readReport(json);
    assertEquals(report, reparsed, "反序列化后的报告应与计算结果逐字段相等");
  }

  @Test
  @DisplayName("生成示例报告工件到 build/eval（供人工查看，非源码）")
  void writeSampleReportArtifact() throws IOException {
    Fixture fixture = EvaluationReportJson.readFixture(loadFixtureJson());
    EvaluationReport report = RetrievalMetrics.evaluate(fixture.k(), fixture.judgements());
    String json = EvaluationReportJson.toJson(report);

    Path out = Path.of("build", "eval", "example-report.json");
    Files.createDirectories(out.getParent());
    Files.writeString(out, json, StandardCharsets.UTF_8);
    assertTrue(Files.exists(out), "示例报告应已写出");
  }

  @Nested
  @DisplayName("夹具 k 校验测试")
  class FixtureKValidationTests {

    /** 一条最小可用的 judgement 片段，供各非法 k 用例拼接成完整夹具。 */
    private static final String JUDGEMENTS = "\"judgements\":[{\"query\":{\"queryId\":\"q\","
        + "\"answerability\":\"ANSWERABLE\",\"relevantChunkIds\":[\"b\"]},"
        + "\"rankedResults\":[{\"chunkId\":\"x\"},{\"chunkId\":\"b\"}]}]";

    private String fixtureWithK(String kLiteral) {
      return "{\"k\":" + kLiteral + "," + JUDGEMENTS + "}";
    }

    /** 断言读取失败，且不产出任何夹具或报告。 */
    private void assertRejectedAtRead(String json) {
      IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
          () -> {
            Fixture fixture = EvaluationReportJson.readFixture(json);
            // 读取成功后才能计算报告；此处若到达即说明非法 k 未被拦截
            RetrievalMetrics.evaluate(fixture.k(), fixture.judgements());
          });
      assertTrue(ex.getMessage().contains("夹具字段 k 必须是 JSON 整数"),
          "错误信息应清楚指出 k 的约束，实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("Codex 原始反例 k=1.9 在读取阶段拒绝，不生成报告")
    void rejectsCodexCounterexampleFractionalK() {
      String json = "{\"k\":1.9," + JUDGEMENTS + "}";
      IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
          () -> EvaluationReportJson.readFixture(json));
      assertTrue(ex.getMessage().contains("夹具字段 k 必须是 JSON 整数"),
          "错误信息应指出 k 约束，实际: " + ex.getMessage());
      assertTrue(ex.getMessage().contains("1.9"),
          "错误信息应回显原始小数值，实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("k=1.0 等带小数点但整值的写法同样拒绝")
    void rejectsWholeValuedFractionalK() {
      assertRejectedAtRead(fixtureWithK("1.0"));
    }

    @Test
    @DisplayName("k 为字符串 \"3\" 拒绝，不做字符串转整数")
    void rejectsStringK() {
      assertRejectedAtRead(fixtureWithK("\"3\""));
    }

    @Test
    @DisplayName("k 为 null 拒绝")
    void rejectsNullK() {
      assertRejectedAtRead(fixtureWithK("null"));
    }

    @Test
    @DisplayName("k 缺失拒绝，不补默认值")
    void rejectsMissingK() {
      assertRejectedAtRead("{" + JUDGEMENTS + "}");
    }

    @Test
    @DisplayName("k 为布尔值 true 拒绝")
    void rejectsBooleanK() {
      assertRejectedAtRead(fixtureWithK("true"));
    }

    @Test
    @DisplayName("k=0 拒绝（必须 >= 1）")
    void rejectsZeroK() {
      assertRejectedAtRead(fixtureWithK("0"));
    }

    @Test
    @DisplayName("k=-1 拒绝（必须 >= 1）")
    void rejectsNegativeK() {
      assertRejectedAtRead(fixtureWithK("-1"));
    }

    @Test
    @DisplayName("k 超出 int 范围拒绝，不截断")
    void rejectsOverflowK() {
      assertRejectedAtRead(fixtureWithK("99999999999999"));
    }

    @Test
    @DisplayName("合法整数 k 仍可正常读取")
    void acceptsValidIntegerK() {
      Fixture fixture = EvaluationReportJson.readFixture(fixtureWithK("3"));
      assertEquals(3, fixture.k());
      assertEquals(1, fixture.judgements().size());
    }
  }
}
