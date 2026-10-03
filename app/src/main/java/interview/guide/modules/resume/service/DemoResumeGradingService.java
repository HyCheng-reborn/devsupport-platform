package interview.guide.modules.resume.service;

import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.interview.model.ResumeAnalysisResponse.ScoreDetail;
import interview.guide.modules.interview.model.ResumeAnalysisResponse.Suggestion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * Demo profile 下的简历分析服务，返回固定的逼真分析结果。
 *
 * <p>仅用于本地演示，不调用任何真实 LLM/Embedding API，因此无需付费 API Key 即可跑通
 * “简历上传 → 异步分析 → 结果展示”的完整链路。生产环境不会激活此 Bean
 * （仅当 {@code spring.profiles.active=demo} 时生效），默认的
 * {@link ResumeGradingService} 标注了 {@code @Profile("!demo")}，两者互斥。</p>
 *
 * <p>实现方式：继承 {@link ResumeGradingService} 并覆写 {@link #analyzeResume(String)}。
 * 由于覆写后不会触发父类中任何依赖 LLM 的逻辑，构造时对
 * {@code LlmProviderRegistry} 与 {@code StructuredOutputInvoker} 传入 {@code null}；
 * 父类构造器仍需要 {@link ResumeAnalysisProperties} 与 {@link ResourceLoader} 加载提示词模板，
 * 因此这两个依赖正常注入。</p>
 */
@Service
@Profile("demo")
public class DemoResumeGradingService extends ResumeGradingService {

  private static final Logger log = LoggerFactory.getLogger(DemoResumeGradingService.class);

  /**
   * 构造 Demo 简历分析服务。
   *
   * <p>{@code llmProviderRegistry} 与 {@code structuredOutputInvoker} 传 {@code null}，
   * 因为 {@link #analyzeResume(String)} 已被覆写，不会调用父类中依赖它们的真实 AI 逻辑。</p>
   */
  public DemoResumeGradingService(
      ResumeAnalysisProperties properties,
      ResourceLoader resourceLoader) throws IOException {
    super(null, null, properties, resourceLoader);
  }

  /**
   * 返回一份固定的、逼真的简历分析结果，用于本地演示。
   *
   * @param resumeText 简历文本内容（原样透传到结果的 originalText 字段）
   * @return 固定的分析结果，总分 82
   */
  @Override
  public ResumeAnalysisResponse analyzeResume(String resumeText) {
    log.info("[Demo] 使用固定演示数据分析简历，文本长度: {} 字符", resumeText == null ? 0 : resumeText.length());

    ScoreDetail scoreDetail = new ScoreDetail(
        20,  // 内容完整性 (0-25)
        16,  // 结构清晰度 (0-20)
        21,  // 技能匹配度 (0-25)
        13,  // 表达专业性 (0-15)
        12   // 项目经验 (0-15)
    );

    List<String> strengths = List.of(
        "技术栈覆盖全面，涵盖 Java、Spring Boot、微服务等主流技术",
        "项目经验丰富，有多个完整项目落地经历",
        "教育背景扎实，计算机相关专业"
    );

    List<Suggestion> suggestions = List.of(
        new Suggestion(
            "项目",
            "高",
            "项目描述偏重职责罗列，缺少可量化的成果指标",
            "在项目经历中补充量化数据，如性能提升百分比、并发量、用户规模或交付周期，让成果更有说服力"
        ),
        new Suggestion(
            "技能",
            "中",
            "技术关键词分布较散，不利于 ATS 系统匹配目标岗位",
            "根据目标岗位 JD 提炼核心技术关键词，前置到技能清单与项目描述中，提升简历的机器可读性"
        ),
        new Suggestion(
            "格式",
            "低",
            "部分段落篇幅较长，招聘方快速浏览时抓取重点成本高",
            "使用短句与要点式表达，控制单条经历在 2-3 行内，突出「做了什么—怎么做—结果如何」的逻辑"
        )
    );

    String summary = "该简历整体质量较高，技术栈覆盖全面，项目经验丰富。"
        + "建议在项目描述中增加量化指标，并优化技术关键词的布局以提升 ATS 系统匹配度。";

    return new ResumeAnalysisResponse(
        82,
        scoreDetail,
        summary,
        strengths,
        suggestions,
        resumeText
    );
  }
}
