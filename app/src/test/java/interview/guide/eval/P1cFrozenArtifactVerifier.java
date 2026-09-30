package interview.guide.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * P1-C 冻结工件校验：把数据集目录内工件的<b>实测</b>哈希与<b>已提交冻结清单</b>逐项比对。
 *
 * <p>只计算哈希不比对批准的哈希，等于没有校验：内容被改写而条数/行数不变时，
 * 28/20/16/4/38 之类的数量契约全部照常通过。因此本类比对的粒度是整份文件的字节内容，
 * 数量校验留给各自的读取阶段，两者互不替代。
 *
 * <p><b>比对字段是行尾归一化后的 SHA-256</b>（{@code normalizeNewlineBytes} → UTF-8 → SHA-256）：
 * {@code core.autocrlf=true} 的 checkout 会把 LF 改写成 CRLF，整文件原始字节哈希因此随平台而变
 * （本数据集 {@code chunks.jsonl}：CRLF 侧 {@code 3cf452e5…}，LF 侧 {@code d611203d…}，
 * 后者恰好等于仓库 blob 内容）。归一化哈希对同一提交内容跨平台恒等，
 * 而任何非行尾的字节改动仍会改变它，所以既不误报也不放过漂移。
 * 原始字节哈希只作参考记录，不参与判定。
 *
 * <p>清单自身坏了也要失败，不能静默放行：{@code kind} 不符、条目缺失、{@code fileName} 重复、
 * 期望哈希不是 64 位十六进制，都会成为违例；清单条目集合必须是必需工件的超集——
 * 靠裁剪清单来通过校验是不允许的。
 */
public final class P1cFrozenArtifactVerifier {

  public static final String FREEZE_LIST_FILE_NAME = "p1c-frozen-artifacts.json";
  public static final String EXPECTED_KIND = "p1c-frozen-artifact-freeze-list";
  public static final String COMPARED_FIELD = "expectedSha256NormalizedLf";
  public static final String HASH_CALIBER =
      "字节级 CRLF/CR→LF 归一化 → UTF-8 字节 → SHA-256 小写 hex（与 P1cExpectedChunk 同口径）";

  /** 冻结清单里的一条期望记录。 */
  public record ExpectedArtifact(
      String fileName,
      String expectedSha256NormalizedLf,
      String sha256RawBytesAtRecord) {
  }

  /** 单个工件的比对结果（期望值与实测值都写出，便于复核者独立验算）。 */
  public record Check(
      String fileName,
      String expectedSha256NormalizedLf,
      String actualSha256NormalizedLf,
      String actualSha256RawBytes,
      String sha256RawBytesAtRecord,
      boolean normalizedLfMatched) {
  }

  /** 一轮校验的完整结果：违例为空即 PASS。 */
  public record Verification(
      String freezeListFile,
      String hashCaliber,
      String comparedField,
      List<String> requiredFileNames,
      List<Check> checks,
      List<String> violations,
      String status) {

    public boolean passed() {
      return violations.isEmpty();
    }
  }

  private P1cFrozenArtifactVerifier() {
  }

  /**
   * 装配入口：读取 {@code datasetDir/p1c-frozen-artifacts.json}，对清单内每个文件计算实测哈希并比对。
   *
   * <p>只做本地文件读取与哈希计算：不建客户端、不连数据库、不写入，也不产生任何付费调用。
   *
   * @throws IllegalStateException 冻结清单缺失、结构损坏、{@code kind} 不符或条目重复
   *                               （此时调用方尚未接触任何外部系统）
   */
  public static Verification verifyAgainstFreezeList(Path datasetDir, Set<String> requiredFileNames)
      throws IOException {
    Path freezeListPath = datasetDir.resolve(FREEZE_LIST_FILE_NAME);
    if (!Files.isRegularFile(freezeListPath)) {
      throw new IllegalStateException("P1-B 冻结工件清单缺失，拒绝继续（无批准哈希可比对）: "
          + freezeListPath.toAbsolutePath());
    }
    List<ExpectedArtifact> expected = readFreezeList(freezeListPath);

    Map<String, String> actualNormalized = new LinkedHashMap<>();
    List<Check> checks = new ArrayList<>();
    for (ExpectedArtifact artifact : expected) {
      Path file = datasetDir.resolve(artifact.fileName());
      String actual = null;
      String actualRaw = null;
      if (Files.isRegularFile(file)) {
        byte[] bytes = Files.readAllBytes(file);
        actual = sha256NormalizedLf(bytes);
        actualRaw = sha256Hex(bytes);
      }
      actualNormalized.put(artifact.fileName(), actual);
      checks.add(new Check(artifact.fileName(), artifact.expectedSha256NormalizedLf(), actual,
          actualRaw, artifact.sha256RawBytesAtRecord(),
          actual != null && actual.equalsIgnoreCase(artifact.expectedSha256NormalizedLf())));
    }

    List<String> violations = compare(expected, actualNormalized, requiredFileNames);
    return new Verification(FREEZE_LIST_FILE_NAME, HASH_CALIBER, COMPARED_FIELD,
        List.copyOf(new TreeSet<>(requiredFileNames)), List.copyOf(checks),
        List.copyOf(violations), violations.isEmpty() ? "PASS" : "FAIL");
  }

  /**
   * 纯比对（离线单元测试入口）：期望记录 + 实测归一化哈希 → 全部违例，不短路。
   *
   * @param actualNormalizedLfSha256ByFile 文件名 → 实测归一化哈希；缺失或读不到文件时值为 null
   */
  public static List<String> compare(List<ExpectedArtifact> expected,
      Map<String, String> actualNormalizedLfSha256ByFile, Set<String> requiredFileNames) {
    List<String> violations = new ArrayList<>();
    Set<String> listed = new LinkedHashSet<>();

    if (expected.isEmpty()) {
      violations.add("冻结清单 artifacts 为空，无任何批准哈希可比对");
      return violations;
    }
    for (ExpectedArtifact artifact : expected) {
      if (artifact.fileName() == null || artifact.fileName().isBlank()) {
        violations.add("冻结清单存在空 fileName");
        continue;
      }
      if (!listed.add(artifact.fileName())) {
        violations.add("冻结清单存在重复 fileName: " + artifact.fileName());
      }
    }
    for (String required : new TreeSet<>(requiredFileNames)) {
      if (!listed.contains(required)) {
        violations.add("冻结清单缺少必需工件条目: " + required
            + "（清单条目必须是必需工件的超集，不能靠裁剪清单通过校验）");
      }
    }

    for (ExpectedArtifact artifact : expected) {
      String fileName = artifact.fileName();
      if (fileName == null || fileName.isBlank()) {
        continue;
      }
      String expectedHash = artifact.expectedSha256NormalizedLf();
      if (!isSha256Hex(expectedHash)) {
        violations.add("冻结清单期望哈希非法（应为 64 位十六进制）: fileName=" + fileName
            + ", 值=" + expectedHash);
        continue;
      }
      String actualHash = actualNormalizedLfSha256ByFile.get(fileName);
      if (actualHash == null || actualHash.isBlank()) {
        violations.add("工件文件不存在或读不到，无法与批准哈希比对: " + fileName);
        continue;
      }
      if (!expectedHash.equalsIgnoreCase(actualHash)) {
        violations.add("冻结工件内容漂移: " + fileName
            + " 期望 " + COMPARED_FIELD + "=" + expectedHash
            + " 实测=" + actualHash
            + "（条数/行数不变也会在此暴露）");
      }
    }
    return violations;
  }

  /**
   * 解析冻结清单。结构问题（{@code kind} 不符、{@code artifacts} 缺失或非数组、条目类型错）
   * 直接抛出：这类情况下没有任何"批准哈希"可言，不允许退化成放行。
   */
  public static List<ExpectedArtifact> readFreezeList(Path freezeListPath) throws IOException {
    ObjectMapper mapper = JsonMapper.builder().build();
    Map<String, Object> root = mapper.readValue(
        Files.readString(freezeListPath, StandardCharsets.UTF_8), Map.class);
    Object kind = root.get("kind");
    if (!EXPECTED_KIND.equals(kind)) {
      throw new IllegalStateException("冻结清单 kind 不符（期望 " + EXPECTED_KIND + "）: "
          + freezeListPath.toAbsolutePath() + " 实际 " + kind);
    }
    Object artifacts = root.get("artifacts");
    if (!(artifacts instanceof List<?> list) || list.isEmpty()) {
      throw new IllegalStateException("冻结清单缺少非空 artifacts 数组: "
          + freezeListPath.toAbsolutePath());
    }
    List<ExpectedArtifact> out = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> map)) {
        throw new IllegalStateException("冻结清单 artifacts 条目不是对象: " + item);
      }
      out.add(new ExpectedArtifact(
          asText(map.get("fileName")),
          asText(map.get(COMPARED_FIELD)),
          asText(map.get("sha256RawBytesAtRecord"))));
    }
    return out;
  }

  /** 冻结口径：字节级 CRLF/CR→LF → SHA-256 小写 hex。 */
  public static String sha256NormalizedLf(byte[] fileBytes) {
    return sha256Hex(normalizeNewlineBytes(fileBytes));
  }

  public static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }

  /** 只改写行尾分隔符本身：CRLF 与孤立 CR 都变 LF，其余字节逐字节保留。 */
  public static byte[] normalizeNewlineBytes(byte[] bytes) {
    byte[] out = new byte[bytes.length];
    int n = 0;
    for (int i = 0; i < bytes.length; i++) {
      if (bytes[i] == '\r') {
        if (i + 1 < bytes.length && bytes[i + 1] == '\n') {
          continue;
        }
        out[n++] = '\n';
      } else {
        out[n++] = bytes[i];
      }
    }
    return java.util.Arrays.copyOf(out, n);
  }

  private static boolean isSha256Hex(String value) {
    if (value == null || value.length() != 64) {
      return false;
    }
    return value.toLowerCase(Locale.ROOT).chars()
        .allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
  }

  private static String asText(Object value) {
    return value == null ? null : String.valueOf(value);
  }
}
