package interview.guide.modules.knowledgebase.model;

/**
 * 知识库上传元数据（阶段 2）。
 * 承载 UI→API→DB→异步→检索过滤 所需的 DevSupport 维度：服务/环境/项目/文档类型/来源/适用版本标签。
 */
public record KbUploadMetadata(
    String service,
    String environment,
    String project,
    String docType,
    String source,
    String versionLabel
) {
}
