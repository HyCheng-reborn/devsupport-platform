package interview.guide.eval;

import interview.guide.infrastructure.file.DocumentParseService;
import interview.guide.infrastructure.file.TextCleaningService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * P1-C 离线工具：复现 baseline 的生产解析+清洗输入，供 heading-aware 候选切分使用。
 *
 * <p>调用与 {@link OfflineChunker} 完全相同的生产解析路径：
 * {@code DocumentParseService.parseContent(rawBytes, fileName)} = Tika AutoDetectParser
 * （BodyContentHandler 5MB + NoOpEmbeddedDocumentExtractor + PDF 排序）后接
 * {@code TextCleaningService.cleanText}。全程本地，不启动 Spring、不触网、不调 Embedding/LLM。
 *
 * <p>把清洗后文本写到指定输出文件（UTF-8, LF），并打印 raw/cleaned 的 sha256 与规模，
 * 以便核对 cleaned sha256 与基线 chunk-manifest 里记录的 {@code cleanedSha256} 一致——
 * 这就是"候选与 baseline 使用同一解析/清洗结果"的离线证明。
 *
 * <p>用法：{@code main <inputCorpusFile> <outputCleanedFile>}
 */
public class P1cCleanedTextDump {

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      throw new IllegalArgumentException("用法: P1cCleanedTextDump <inputCorpusFile> <outputCleanedFile>");
    }
    Path input = Path.of(args[0]);
    Path output = Path.of(args[1]);

    byte[] rawBytes = Files.readAllBytes(input);
    String rawText = new String(rawBytes, StandardCharsets.UTF_8);
    String fileName = input.getFileName().toString();

    TextCleaningService cleaningService = new TextCleaningService();
    DocumentParseService parseService = new DocumentParseService(cleaningService);
    String cleaned = parseService.parseContent(rawBytes, fileName);

    Files.createDirectories(output.getParent());
    Files.writeString(output, cleaned, StandardCharsets.UTF_8);

    long nonEmpty = Arrays.stream(cleaned.split("\n", -1)).filter(l -> !l.isBlank()).count();
    int cleanedLines = cleaned.split("\n", -1).length;
    System.out.println("=== P1cCleanedTextDump (production parse+clean, offline) ===");
    System.out.println("input            : " + input.toAbsolutePath());
    System.out.println("output           : " + output.toAbsolutePath());
    System.out.println("rawSha256        : " + sha256(rawBytes));
    System.out.println("rawChars         : " + rawText.length());
    System.out.println("cleanedSha256    : " + sha256(cleaned.getBytes(StandardCharsets.UTF_8)));
    System.out.println("cleanedChars     : " + cleaned.length());
    System.out.println("cleanedLines     : " + cleanedLines);
    System.out.println("cleanedNonEmpty  : " + nonEmpty);
  }

  private static String sha256(byte[] data) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
  }
}
