package interview.guide.modules.knowledgebase.model;

/**
 * 上下文解析结果项，用于 resolve-context 端点返回
 */
public record ContextKbItem(
    Long id,
    String name,
    String service,
    String environment
) {
}
