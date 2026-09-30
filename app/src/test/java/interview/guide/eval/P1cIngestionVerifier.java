package interview.guide.eval;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P1-C 严格入库核对：数据库实际行 vs 冻结期望行，逐字段对照。
 *
 * <p>核对维度（对本次 runId 的行集）：
 * <ol>
 *   <li>行数恰为期望（28）；表中不属于本次 runId 的行视为额外行。</li>
 *   <li>{@code eval_chunk_id} 集合与冻结集合完全一致：缺失、重复、额外一律拒绝。</li>
 *   <li>每行文本 SHA-256（{@link P1cExpectedChunk} 归一化口径）一致——拒绝内容错位。</li>
 *   <li>{@code doc_id}、{@code chunk_index}（文本口径）一致——拒绝来源错位。</li>
 *   <li>{@code eval_run_id} 等于本次运行；{@code kb_id} 等于 900001 且为 JSON 字符串
 *       （{@code kbIdRawJson} 以双引号开头；数字型 900001 判定为非字符串）。</li>
 * </ol>
 *
 * <p>返回全部违例清单（不短路），供失败报告聚合展示。空列表 = 核对通过。
 * 纯静态计算，无数据库依赖，离线可测。
 */
public final class P1cIngestionVerifier {

  /**
   * 数据库读回的一行。{@code kbIdRawJson} 为 {@code metadata->'kb_id'} 的原始 JSON 文本
   * （用于判定存储类型）；其余为 {@code metadata->>'...'} 的文本抽取值，缺失时为 null。
   */
  public record StoredRow(
      String content,
      String evalChunkId,
      String docId,
      String chunkIndexText,
      String evalRunId,
      String kbIdText,
      String kbIdRawJson) {
  }

  private P1cIngestionVerifier() {
  }

  public static List<String> verify(List<P1cExpectedChunk> expected, List<StoredRow> actual,
      String expectedRunId, String expectedKbId) {
    List<String> violations = new ArrayList<>();

    if (actual == null) {
      violations.add("actual 行集为 null");
      return violations;
    }

    Map<String, List<Integer>> idToRowCount = new LinkedHashMap<>();
    Map<String, StoredRow> firstRowById = new LinkedHashMap<>();
    int foreignRunRows = 0;
    for (int i = 0; i < actual.size(); i++) {
      StoredRow row = actual.get(i);
      if (row == null) {
        violations.add("第 " + (i + 1) + " 行为 null");
        continue;
      }
      if (!expectedRunId.equals(row.evalRunId())) {
        foreignRunRows++;
        violations.add("第 " + (i + 1) + " 行 eval_run_id 越界: " + row.evalRunId()
            + " != " + expectedRunId);
        continue;
      }
      String id = row.evalChunkId();
      if (id == null || id.isBlank()) {
        violations.add("第 " + (i + 1) + " 行缺失 eval_chunk_id");
        continue;
      }
      idToRowCount.computeIfAbsent(id, k -> new ArrayList<>()).add(i + 1);
      firstRowById.putIfAbsent(id, row);
    }
    if (foreignRunRows > 0) {
      violations.add("存在 " + foreignRunRows + " 行不属于本次 runId（额外行，拒绝）");
    }

    Set<String> expectedIds = new HashSet<>();
    for (P1cExpectedChunk chunk : expected) {
      expectedIds.add(chunk.evalChunkId());
    }

    for (P1cExpectedChunk chunk : expected) {
      List<Integer> rows = idToRowCount.get(chunk.evalChunkId());
      if (rows == null) {
        violations.add("缺失行: eval_chunk_id=" + chunk.evalChunkId());
        continue;
      }
      if (rows.size() > 1) {
        violations.add("重复 eval_chunk_id=" + chunk.evalChunkId() + " 出现于行 " + rows);
      }
      StoredRow row = firstRowById.get(chunk.evalChunkId());
      String actualHash = P1cExpectedChunk.sha256NormalizedUtf8(
          row.content() == null ? "" : row.content());
      if (!actualHash.equals(chunk.normalizedTextSha256())) {
        violations.add("文本错位: eval_chunk_id=" + chunk.evalChunkId()
            + " 期望 sha256=" + chunk.normalizedTextSha256().substring(0, 12)
            + "… 实际 sha256=" + actualHash.substring(0, 12) + "…");
      }
      if (!chunk.docId().equals(row.docId())) {
        violations.add("来源错位(doc_id): eval_chunk_id=" + chunk.evalChunkId()
            + " 期望 " + chunk.docId() + " 实际 " + row.docId());
      }
      String expectedIndex = String.valueOf(chunk.chunkIndex());
      if (!expectedIndex.equals(row.chunkIndexText())) {
        violations.add("来源错位(chunk_index): eval_chunk_id=" + chunk.evalChunkId()
            + " 期望 " + expectedIndex + " 实际 " + row.chunkIndexText());
      }
      if (!expectedKbId.equals(row.kbIdText())) {
        violations.add("kb_id 不匹配: eval_chunk_id=" + chunk.evalChunkId()
            + " 期望 " + expectedKbId + " 实际 " + row.kbIdText());
      } else if (row.kbIdRawJson() == null || !row.kbIdRawJson().startsWith("\"")) {
        violations.add("kb_id 非字符串型: eval_chunk_id=" + chunk.evalChunkId()
            + " 原始 JSON=" + row.kbIdRawJson() + "（应为 JSON 字符串）");
      }
    }

    for (String id : idToRowCount.keySet()) {
      if (!expectedIds.contains(id)) {
        violations.add("额外 eval_chunk_id（不在冻结集）: " + id);
      }
    }

    long runRowTotal = idToRowCount.values().stream().mapToLong(List::size).sum();
    if (runRowTotal != expected.size()) {
      violations.add("本次 runId 行数 " + runRowTotal + " != 期望 " + expected.size());
    }
    if (actual.size() != expected.size() && foreignRunRows == 0) {
      violations.add("vector_store 全表行数 " + actual.size() + " != 期望 " + expected.size());
    }
    return violations;
  }
}
