package interview.guide.modules.evalregression.model;

/**
 * 触发回归运行的请求体。
 *
 * @param topK 检索返回条数（可选，为空或非正数时使用服务端默认值）
 */
public record RegressionRunRequest(
  Integer topK
) {}
