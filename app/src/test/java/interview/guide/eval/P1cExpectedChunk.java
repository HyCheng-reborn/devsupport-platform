package interview.guide.eval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * P1-C 入库核对的期望行：来自冻结 chunks.jsonl 的稳定标识 + 文本哈希。
 *
 * <p><b>字节口径</b>：文本先做换行归一化（CRLF / CR → LF），再取 UTF-8 字节，
 * 计算 SHA-256 小写十六进制。Java 侧（读文件）与 PostgreSQL 侧（读回 content 列）
 * 使用同一函数，保证跨环境可比：
 * <ul>
 *   <li>工作区 checkout 可能把行内 {@code \n} 之外的字节改动（本数据集行内仅有 JSON
 *       转义 {@code \n}，解码后恒为 LF），也可能不影响；归一化消除残余差异。</li>
 *   <li>PostgreSQL {@code text} 列按数据库编码（UTF-8）存储，JDBC 读回为 Java String，
 *       与写入字节往返一致；不可编码字符会先被驱动拒绝，不产生静默替换。</li>
 * </ul>
 * 归一化不掩盖内容漂移：任何可见字符差异仍会改变哈希。
 */
public record P1cExpectedChunk(
    String evalChunkId,
    String docId,
    int chunkIndex,
    String normalizedTextSha256) {

  public static P1cExpectedChunk from(String evalChunkId, String docId, int chunkIndex, String text) {
    return new P1cExpectedChunk(evalChunkId, docId, chunkIndex, sha256NormalizedUtf8(text));
  }

  /** 冻结口径：normalizeNewlines → UTF-8 字节 → SHA-256 hex。 */
  public static String sha256NormalizedUtf8(String text) {
    byte[] bytes = normalizeNewlines(text).getBytes(StandardCharsets.UTF_8);
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }

  public static String normalizeNewlines(String text) {
    return text.replace("\r\n", "\n").replace("\r", "\n");
  }
}
