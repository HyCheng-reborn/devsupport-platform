package interview.guide.modules.knowledgebase.model;

/**
 * 上下文解析结果项，用于 resolve-context 端点返回。
 * 阶段 2 追加 project/versionNo/documentKey/active，供检索范围按项目/版本过滤与展示。
 * 保留 4 参兼容构造器（默认 versionNo=1、active=true），不破坏既有调用。
 */
public record ContextKbItem(
    Long id,
    String name,
    String service,
    String environment,
    String project,
    Integer versionNo,
    String documentKey,
    boolean active
) {

    public ContextKbItem(Long id, String name, String service, String environment) {
        this(id, name, service, environment, null, 1, null, true);
    }
}
