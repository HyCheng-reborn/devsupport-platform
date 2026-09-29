package interview.guide.eval;

import interview.guide.infrastructure.file.DocumentParseService;
import interview.guide.infrastructure.file.TextCleaningService;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P1-B 离线切分工具（复用生产类，不启动 Spring 上下文）。
 *
 * 调用链（与生产上传管线一致）：
 *   1. DocumentParseService.parseContent(rawBytes, fileName)
 *      → Tika AutoDetectParser + BodyContentHandler(5MB) + TextCleaningService.cleanText()
 *   2. TokenTextSplitter.builder().build().apply(documents) —— 默认配置切分
 *
 * Tika 差异已由 TikaDifferenceVerifier 验证：6/7 .md 文件 clean-only 与 Tika+clean 完全一致，
 * ig-readme-root.md 存在 107 字符差异（Tika 剥离 HTML div 装饰标签）。本工具统一走生产路径。
 */
public class OfflineChunker {

  private static final String CONFIG_ID = "tts-default-cl100k-800-0";

  public static void main(String[] args) throws Exception {
    Path baseDir = Paths.get(args.length > 0 ? args[0] : "eval/datasets/devsupport-v0.1");
    Path corpusDir = baseDir.resolve("corpus");

    System.out.println("=== P1-B 离线切分（Java 生产类复用）===");
    System.out.println("baseDir: " + baseDir.toAbsolutePath());
    System.out.println("corpusDir: " + corpusDir.toAbsolutePath());
    System.out.println();

    TextCleaningService cleaningService = new TextCleaningService();
    DocumentParseService parseService = new DocumentParseService(cleaningService);
    TokenTextSplitter splitter = TokenTextSplitter.builder().build();

    List<Map<String, Object>> allChunks = new ArrayList<>();
    List<Map<String, Object>> docStats = new ArrayList<>();

    List<Path> mdFiles = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(corpusDir, "*.md")) {
      for (Path entry : stream) {
        mdFiles.add(entry);
      }
    }
    mdFiles.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));

    for (Path file : mdFiles) {
      String docId = file.getFileName().toString().replace(".md", "");
      byte[] rawBytes = Files.readAllBytes(file);
      String rawText = new String(rawBytes, StandardCharsets.UTF_8);

      String rawHash = sha256(rawBytes);
      int rawChars = rawText.length();

      // Step 1: 生产解析（Tika + 清洗）
      String fileName = file.getFileName().toString();
      String cleanedText = parseService.parseContent(rawBytes, fileName);
      String cleanedHash = sha256(cleanedText.getBytes(StandardCharsets.UTF_8));
      int cleanedChars = cleanedText.length();

      // Step 2: 生产切分
      List<Document> docs = List.of(new Document(cleanedText));
      List<Document> chunks = splitter.apply(docs);

      System.out.printf("文档: %s | 原始 %d 字符 (sha256:%s) | 解析后 %d 字符 (sha256:%s) | %d chunks%n",
          docId, rawChars, rawHash.substring(0, 12), cleanedChars, cleanedHash.substring(0, 12), chunks.size());

      // 验证无 U+FFFD
      int ffdCount = 0;
      for (int i = 0; i < cleanedText.length(); i++) {
        if (cleanedText.charAt(i) == '\uFFFD') {
          ffdCount++;
        }
      }
      if (ffdCount > 0) {
        System.out.printf("  [WARN] 清洗后文本含 %d 个 U+FFFD 替换字符%n", ffdCount);
      }

      int seq = 0;
      for (Document chunk : chunks) {
        String chunkText = chunk.getText();

        // 验证 chunk 无 U+FFFD
        int chunkFfd = 0;
        for (int i = 0; i < chunkText.length(); i++) {
          if (chunkText.charAt(i) == '\uFFFD') {
            chunkFfd++;
          }
        }

        String chunkId = generateChunkId(docId, CONFIG_ID, seq, chunkText);

        Map<String, Object> chunkRecord = new LinkedHashMap<>();
        chunkRecord.put("chunkId", chunkId);
        chunkRecord.put("docId", docId);
        chunkRecord.put("seq", seq);
        chunkRecord.put("text", chunkText);
        chunkRecord.put("charCount", chunkText.length());
        chunkRecord.put("configId", CONFIG_ID);
        chunkRecord.put("replacementChars", chunkFfd);

        allChunks.add(chunkRecord);
        seq++;
      }

      Map<String, Object> stat = new LinkedHashMap<>();
      stat.put("docId", docId);
      stat.put("rawChars", rawChars);
      stat.put("rawSha256", rawHash);
      stat.put("cleanedChars", cleanedChars);
      stat.put("cleanedSha256", cleanedHash);
      stat.put("chunkCount", chunks.size());
      stat.put("rawBytes", rawBytes.length);
      stat.put("cleaningChanged", !rawText.equals(cleanedText));
      stat.put("replacementCharsInCleaned", ffdCount);
      docStats.add(stat);
    }

    // 写入 chunks.jsonl
    Path chunksFile = baseDir.resolve("chunks.jsonl");
    try (var writer = Files.newBufferedWriter(chunksFile, StandardCharsets.UTF_8)) {
      for (Map<String, Object> chunk : allChunks) {
        writer.write(toJson(chunk));
        writer.newLine();
      }
    }
    System.out.printf("%n已写入: %s (%d 条)%n", chunksFile, allChunks.size());

    // 写入 chunk-manifest.json
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("datasetId", "devsupport");
    manifest.put("version", "v0.1");
    manifest.put("status", "draft");
    Map<String, Object> chunkerConfig = new LinkedHashMap<>();
    chunkerConfig.put("splitterClass", "org.springframework.ai.transformer.splitter.TokenTextSplitter");
    chunkerConfig.put("constructionCode", "TokenTextSplitter.builder().build()");
    chunkerConfig.put("configId", CONFIG_ID);
    chunkerConfig.put("sourceLocation", "KnowledgeBaseVectorService.java:52");
    chunkerConfig.put("sourceCommit", "d2fd5e633248b2c008cf387ab82651a56cca8502");
    manifest.put("chunkerConfig", chunkerConfig);
    Map<String, Object> cleaningConfig = new LinkedHashMap<>();
    cleaningConfig.put("cleanerClass", "interview.guide.infrastructure.file.TextCleaningService");
    cleaningConfig.put("methodCalled", "cleanText(String)");
    cleaningConfig.put("sourceLocation", "TextCleaningService.java:80");
    cleaningConfig.put("applied", true);
    cleaningConfig.put("layers", List.of(
        "Layer1-semanticDenoising: controlChars, imageFilenames, imageUrls, fileUrls, separatorLines",
        "Layer2-formatNormalization: unifyLineEndings(\\n), stripTrailingWhitespace, compress3+Newlines, finalStrip"
    ));
    manifest.put("cleaningConfig", cleaningConfig);
    Map<String, Object> parsingPolicy = new LinkedHashMap<>();
    parsingPolicy.put("applied", true);
    parsingPolicy.put("productionClass", "interview.guide.infrastructure.file.DocumentParseService");
    parsingPolicy.put("productionMethod", "parseContent(byte[], fileName) → AutoDetectParser + BodyContentHandler(5MB) + TextCleaningService.cleanText()");
    Map<String, Object> tikaVerification = new LinkedHashMap<>();
    tikaVerification.put("verified", true);
    tikaVerification.put("method", "TikaDifferenceVerifier.java — 逐文件对比 cleanText(rawText) 与 parseContent(rawBytes, fileName)");
    tikaVerification.put("tool", "./gradlew :app:evalVerifyTika");
    tikaVerification.put("exitCode", 1);
    tikaVerification.put("result", "6/7 文件完全一致；ig-readme-root.md 存在 107 字符差异（cleanOnly=18008 vs tikaThenClean=17901）");
    tikaVerification.put("differenceDetail", "Tika AutoDetectParser 将 .md 中的 HTML 标签（<div align=\"center\">...</div>）剥离，仅影响 ig-readme-root.md 开头的装饰性 div 包裹；语义内容不受影响");
    tikaVerification.put("decision", "已统一走生产解析路径（Tika + clean）；TikaDifferenceVerifier 退出码 1 表示存在差异，差异仅为 HTML 装饰标签");
    parsingPolicy.put("tikaVerification", tikaVerification);
    manifest.put("parsingPolicy", parsingPolicy);
    Map<String, Object> evalChunkIdPolicy = new LinkedHashMap<>();
    evalChunkIdPolicy.put("format", "{docId}__{configId}__{seq:04d}__{sha256(text):12}");
    evalChunkIdPolicy.put("note", "评测用稳定 ID，与生产 VectorStore 中的 Document ID（UUID）不同。生产 ID 在 vectorizeAndStore() 中由 VectorStore 实现生成。");
    manifest.put("evalChunkIdPolicy", evalChunkIdPolicy);
    manifest.put("documentStats", docStats);
    manifest.put("totalChunks", allChunks.size());
    manifest.put("totalReplacementChars", allChunks.stream()
        .mapToInt(c -> (int) c.getOrDefault("replacementChars", 0)).sum());

    Path manifestFile = baseDir.resolve("chunk-manifest.json");
    Files.writeString(manifestFile, toJsonPretty(manifest), StandardCharsets.UTF_8);
    System.out.printf("已写入: %s%n", manifestFile);

    // 汇总
    System.out.println("\n=== 切分完成 ===");
    System.out.printf("总 chunks: %d%n", allChunks.size());
    int totalFfd = allChunks.stream().mapToInt(c -> (int) c.getOrDefault("replacementChars", 0)).sum();
    System.out.printf("总 U+FFFD: %d%n", totalFfd);
    if (totalFfd > 0) {
      System.out.println("[WARN] 存在替换字符，请检查切分配置！");
    }
  }

  private static String generateChunkId(String docId, String configId, int seq, String text)
      throws Exception {
    String contentHash = sha256(text.getBytes(StandardCharsets.UTF_8)).substring(0, 12);
    return String.format("%s__%s__%04d__%s", docId, configId, seq, contentHash);
  }

  private static String sha256(byte[] data) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(data);
    return HexFormat.of().formatHex(hash);
  }

  // --- 简易 JSON 序列化（避免引入额外依赖）---

  private static String toJson(Map<String, Object> map) {
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    boolean first = true;
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      if (!first) sb.append(",");
      first = false;
      sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
      sb.append(toJsonValue(entry.getValue()));
    }
    sb.append("}");
    return sb.toString();
  }

  private static String toJsonPretty(Map<String, Object> map) {
    return toJsonPrettyImpl(map, 0);
  }

  private static String toJsonPrettyImpl(Object value, int indent) {
    String pad = "  ".repeat(indent);
    String pad1 = "  ".repeat(indent + 1);
    if (value instanceof Map<?, ?> map) {
      StringBuilder sb = new StringBuilder();
      sb.append("{\n");
      boolean first = true;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!first) sb.append(",\n");
        first = false;
        sb.append(pad1).append("\"").append(escapeJson(entry.getKey().toString())).append("\": ");
        sb.append(toJsonPrettyImpl(entry.getValue(), indent + 1));
      }
      sb.append("\n").append(pad).append("}");
      return sb.toString();
    }
    if (value instanceof List<?> list) {
      StringBuilder sb = new StringBuilder();
      sb.append("[\n");
      boolean first = true;
      for (Object item : list) {
        if (!first) sb.append(",\n");
        first = false;
        sb.append(pad1).append(toJsonPrettyImpl(item, indent + 1));
      }
      sb.append("\n").append(pad).append("]");
      return sb.toString();
    }
    return toJsonValue(value);
  }

  private static String toJsonValue(Object value) {
    if (value == null) return "null";
    if (value instanceof String) return "\"" + escapeJson((String) value) + "\"";
    if (value instanceof Number) return value.toString();
    if (value instanceof Boolean) return value.toString();
    if (value instanceof Map) return toJsonPretty((Map<String, Object>) value);
    if (value instanceof List<?> list) {
      StringBuilder sb = new StringBuilder();
      sb.append("[");
      boolean first = true;
      for (Object item : list) {
        if (!first) sb.append(",");
        first = false;
        sb.append(toJsonValue(item));
      }
      sb.append("]");
      return sb.toString();
    }
    return "\"" + escapeJson(value.toString()) + "\"";
  }

  private static String escapeJson(String text) {
    return text.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }
}
