package interview.guide.eval;

import java.util.List;

/**
 * P1-C 检索结果元数据核对。
 *
 * <p>纯静态、无 Spring/数据库依赖，方便离线单元测试。
 * 每条检索结果必须同时携带 {@code eval_run_id / kb_id / eval_chunk_id}，
 * 且分别等于本次评测锁定值，否则视为隔离失败并拒绝计入指标。
 */
public final class P1cEvalResultValidator {

  public static final String EXPECTED_KB_ID = "900001";

  private P1cEvalResultValidator() {
  }

  /** 单条检索命中项的元数据快照。 */
  public record HitMetadata(
      int rank,
      String evalRunId,
      String kbId,
      String evalChunkId) {
  }

  /**
   * 校验一批命中：全部通过则返回列表；任一命中缺字段或值越界则抛异常。
   *
   * @param hits            检索结果元数据（按排名 1 开始）
   * @param expectedRunId   本次评测的 runId
   * @return 校验通过的 hits（原顺序）
   * @throws IllegalStateException 隔离失败：任一 hit 字段缺失或值不在期望范围
   */
  public static List<HitMetadata> validate(List<HitMetadata> hits, String expectedRunId) {
    if (hits == null) {
      throw new IllegalArgumentException("hits 不能为 null");
    }
    for (HitMetadata hit : hits) {
      int rank = hit.rank();
      if (hit.evalRunId() == null || hit.evalRunId().isBlank()) {
        throw new IllegalStateException(
            "检索结果 rank=" + rank + " 缺失 eval_run_id，隔离失败");
      }
      if (!hit.evalRunId().equals(expectedRunId)) {
        throw new IllegalStateException(
            "检索结果 rank=" + rank + " 的 eval_run_id=" + hit.evalRunId()
            + " 与本次 " + expectedRunId + " 不一致，隔离失败");
      }
      if (hit.kbId() == null || hit.kbId().isBlank()) {
        throw new IllegalStateException(
            "检索结果 rank=" + rank + " 缺失 kb_id，隔离失败");
      }
      if (!EXPECTED_KB_ID.equals(hit.kbId())) {
        throw new IllegalStateException(
            "检索结果 rank=" + rank + " 的 kb_id=" + hit.kbId()
            + " 不是评测专用 " + EXPECTED_KB_ID + "，隔离失败");
      }
      if (hit.evalChunkId() == null || hit.evalChunkId().isBlank()) {
        throw new IllegalStateException(
            "检索结果 rank=" + rank + " 缺失 eval_chunk_id，隔离失败");
      }
    }
    return hits;
  }

  /**
   * 校验并返回 eval_chunk_id 列表（用于指标计算）。
   *
   * @throws IllegalStateException 隔离失败时抛出
   */
  public static List<String> extractEvalChunkIds(List<HitMetadata> validated) {
    return validated.stream().map(HitMetadata::evalChunkId).toList();
  }
}
