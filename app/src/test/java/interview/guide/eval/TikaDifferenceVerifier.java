package interview.guide.eval;

import interview.guide.infrastructure.file.DocumentParseService;
import interview.guide.infrastructure.file.TextCleaningService;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 验证 Tika（DocumentParseService）对 .md 语料无额外影响。
 *
 * 对比两条路径：
 *   A) OfflineChunker 路径：rawText → TextCleaningService.cleanText()
 *   B) 生产上传路径：rawBytes → DocumentParseService.parseContent(bytes, fileName)（Tika + clean）
 *
 * 退出码：0 = 无差异（Tika 对 .md 安全跳过）；1 = 有差异
 */
public class TikaDifferenceVerifier {

  public static void main(String[] args) throws Exception {
    Path baseDir = Paths.get(args.length > 0 ? args[0] : "eval/datasets/devsupport-v0.1");
    Path corpusDir = baseDir.resolve("corpus");

    System.out.println("=== P1-B Tika 差异验证 ===");
    System.out.println("corpusDir: " + corpusDir.toAbsolutePath());

    TextCleaningService cleaningService = new TextCleaningService();
    DocumentParseService parseService = new DocumentParseService(cleaningService);

    List<Path> mdFiles = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(corpusDir, "*.md")) {
      for (Path entry : stream) {
        mdFiles.add(entry);
      }
    }
    mdFiles.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));

    System.out.printf("找到 %d 份 .md 语料%n%n", mdFiles.size());

    int diffCount = 0;
    for (Path file : mdFiles) {
      String docId = file.getFileName().toString();
      byte[] rawBytes = Files.readAllBytes(file);
      String fileName = file.getFileName().toString();

      // 路径 A: OfflineChunker 路径（clean only）
      String rawText = new String(rawBytes, StandardCharsets.UTF_8);
      String cleanOnly = cleaningService.cleanText(rawText);

      // 路径 B: 生产上传路径（Tika + clean）
      String tikaThenClean = parseService.parseContent(rawBytes, fileName);

      boolean identical = cleanOnly.equals(tikaThenClean);
      String cleanSha = sha256(cleanOnly).substring(0, 12);
      String tikaSha = sha256(tikaThenClean).substring(0, 12);

      if (identical) {
        System.out.printf("  [PASS] %s | %d chars | sha256:%s (Tika 无影响)%n",
            docId, cleanOnly.length(), cleanSha);
      } else {
        diffCount++;
        int firstDiff = -1;
        int minLen = Math.min(cleanOnly.length(), tikaThenClean.length());
        for (int i = 0; i < minLen; i++) {
          if (cleanOnly.charAt(i) != tikaThenClean.charAt(i)) {
            firstDiff = i;
            break;
          }
        }
        System.out.printf("  [DIFF] %s | cleanOnly=%d chars (sha:%s) | tikaThenClean=%d chars (sha:%s) | firstDiff@%s%n",
            docId, cleanOnly.length(), cleanSha, tikaThenClean.length(), tikaSha,
            firstDiff >= 0 ? firstDiff : "length");

        // 输出差异上下文
        if (firstDiff >= 0) {
          int ctxStart = Math.max(0, firstDiff - 30);
          int ctxEnd = Math.min(minLen, firstDiff + 30);
          System.out.printf("         cleanOnly[%d:%d] = %s%n",
              ctxStart, ctxEnd, repr(cleanOnly.substring(ctxStart, ctxEnd)));
          System.out.printf("         tikaPath [%d:%d] = %s%n",
              ctxStart, ctxEnd, repr(tikaThenClean.substring(ctxStart, ctxEnd)));
        }
      }
    }

    System.out.println();
    if (diffCount == 0) {
      System.out.println("=== 结论: Tika 对全部 7 份 .md 语料无额外影响，跳过 Tika 是安全的 ===");
      System.exit(0);
    } else {
      System.out.printf("=== 结论: %d/%d 份语料存在 Tika 差异，需要重新评估输入契约 ===%n", diffCount, mdFiles.size());
      System.exit(1);
    }
  }

  private static String sha256(String text) throws Exception {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    return HexFormat.of().formatHex(digest.digest(bytes));
  }

  private static String repr(String s) {
    return s.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
  }
}
