package interview.guide.modules.resume;

import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.resume.service.DemoResumeGradingService;
import interview.guide.modules.resume.service.ResumeAnalysisProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DemoResumeGradingService 单元测试。
 *
 * <p>验证 demo profile 下的固定分析结果满足前端展示所需的最小契约：
 * 非空、总分大于 0、优点/建议/摘要齐全，且不依赖任何真实 LLM 调用。</p>
 */
@DisplayName("Demo 简历分析服务测试")
class DemoResumeGradingServiceTest {

  private DemoResumeGradingService service;

  @BeforeEach
  void setUp() throws IOException {
    // 父类构造器需要 ResumeAnalysisProperties 与 ResourceLoader 加载提示词模板（classpath 下已存在），
    // LLM 相关依赖在 demo 实现中传 null，此处不涉及。
    service = new DemoResumeGradingService(
        new ResumeAnalysisProperties(),
        new DefaultResourceLoader()
    );
  }

  @Test
  @DisplayName("analyzeResume 返回非空的分析结果")
  void analyzeResume_shouldReturnNonNullResponse() {
    ResumeAnalysisResponse response = service.analyzeResume("这是一份用于演示的简历内容");

    assertThat(response).isNotNull();
  }

  @Test
  @DisplayName("总分大于 0，且各维度评分明细非空")
  void analyzeResume_shouldHavePositiveScore() {
    ResumeAnalysisResponse response = service.analyzeResume("演示简历");

    assertThat(response.overallScore()).isGreaterThan(0);
    assertThat(response.scoreDetail()).isNotNull();
  }

  @Test
  @DisplayName("优点列表非空")
  void analyzeResume_shouldHaveStrengths() {
    ResumeAnalysisResponse response = service.analyzeResume("演示简历");

    assertThat(response.strengths()).isNotEmpty();
  }

  @Test
  @DisplayName("改进建议列表非空，且每条建议字段完整")
  void analyzeResume_shouldHaveSuggestions() {
    ResumeAnalysisResponse response = service.analyzeResume("演示简历");

    assertThat(response.suggestions()).isNotEmpty();
    assertThat(response.suggestions())
        .allSatisfy(suggestion -> {
          assertThat(suggestion.category()).isNotBlank();
          assertThat(suggestion.priority()).isNotBlank();
          assertThat(suggestion.issue()).isNotBlank();
          assertThat(suggestion.recommendation()).isNotBlank();
        });
  }

  @Test
  @DisplayName("摘要非空")
  void analyzeResume_shouldHaveSummary() {
    ResumeAnalysisResponse response = service.analyzeResume("演示简历");

    assertThat(response.summary()).isNotBlank();
  }

  @Test
  @DisplayName("原始简历文本被原样透传到结果中")
  void analyzeResume_shouldPassThroughOriginalText() {
    String originalText = "张三 - Java 后端工程师";

    ResumeAnalysisResponse response = service.analyzeResume(originalText);

    assertThat(response.originalText()).isEqualTo(originalText);
  }
}
