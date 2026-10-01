package interview.guide.modules.knowledgebase.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SourceReference 与 buildSourceReferences 逻辑的单元测试
 */
@DisplayName("来源引用模型测试")
class SourceReferenceTest {

  @Test
  @DisplayName("SourceReference_内容截断至200字符")
  void contentSnippet_truncatedTo200Chars() {
    // 创建超过 200 字符的文本
    String longText = "A".repeat(300);
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("kb_id", 1L);
    Document doc = new Document(longText, metadata);
    setScore(doc, 0.95);

    // 模拟截断逻辑（与 KnowledgeBaseQueryService.buildSourceReferences 一致）
    String text = doc.getText();
    String snippet = text.length() > 200 ? text.substring(0, 200) + "..." : text;

    SourceReference ref = new SourceReference(1L, "测试文档.pdf", snippet, 0.95, "支付网关", "生产");

    assertThat(ref.contentSnippet()).hasSize(203); // 200 + "..."
    assertThat(ref.contentSnippet()).endsWith("...");
    assertThat(ref.contentSnippet()).startsWith("AAA");
  }

  @Test
  @DisplayName("SourceReference_score为null时不报错")
  void score_nullDoesNotThrow() {
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("kb_id", 1L);
    Document doc = new Document("短文本内容", metadata);
    // 不调用 setScore，getScore() 应返回 null

    SourceReference ref = new SourceReference(1L, "文档.txt", doc.getText(), doc.getScore(), null, null);

    assertThat(ref.score()).isNull();
    assertThat(ref.documentName()).isEqualTo("文档.txt");
    assertThat(ref.contentSnippet()).isEqualTo("短文本内容");
  }

  @Test
  @DisplayName("SourceRecord_record访问器正常工作")
  void record_accessorsWorkCorrectly() {
    SourceReference ref = new SourceReference(42L, "简历.pdf", "这是一段摘要内容", 0.87, "用户中心", "预发");

    assertThat(ref.kbId()).isEqualTo(42L);
    assertThat(ref.documentName()).isEqualTo("简历.pdf");
    assertThat(ref.contentSnippet()).isEqualTo("这是一段摘要内容");
    assertThat(ref.score()).isEqualTo(0.87);
  }

  /**
   * 通过反射设置 Document 的 score（Spring AI 2.0.0 未暴露 setScore 方法）
   */
  private static void setScore(Document doc, Double score) {
    try {
      Field scoreField = Document.class.getDeclaredField("score");
      scoreField.setAccessible(true);
      scoreField.set(doc, score);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException("Failed to set Document score via reflection", e);
    }
  }
}
