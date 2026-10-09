package interview.guide.modules.evalregression.service;

import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 回归项 / 运行的 Embedding 元数据解析器。
 * <p>
 * 从 {@code app.ai} 配置解析当前默认 Embedding Provider 的模型名与维度，
 * <b>不触发任何 embedding 调用</b>——因此可安全地在数据库事务内使用
 * （区别于 {@code EmbeddingModel.dimensions()}，后者会通过一次真实 embed 探测维度）。
 * demo / test 环境下这些配置值同样确定，保证回归项绑定信息可复现。
 */
@Component
@RequiredArgsConstructor
public class EmbeddingMetadataResolver {

  private static final int FALLBACK_DIMENSION = 1024;
  private static final String UNKNOWN_MODEL = "unknown";

  private final LlmProviderProperties properties;

  /**
   * 解析当前默认 Embedding 模型名：优先取默认 embedding provider 的 embedding-model 配置，
   * 缺失时回退到 provider id，再回退到 "unknown"。
   */
  public String resolveModelName() {
    String providerId = resolveDefaultEmbeddingProviderId();
    ProviderConfig config = providerConfig(providerId);
    if (config != null && isNotBlank(config.getEmbeddingModel())) {
      return config.getEmbeddingModel().trim();
    }
    return isNotBlank(providerId) ? providerId.trim() : UNKNOWN_MODEL;
  }

  /**
   * 解析当前 Embedding 维度：优先取 provider 级配置，其次取全局 app.ai.embedding-dimensions，
   * 最终回退到 1024。
   */
  public int resolveDimension() {
    ProviderConfig config = providerConfig(resolveDefaultEmbeddingProviderId());
    if (config != null && config.getEmbeddingDimensions() != null && config.getEmbeddingDimensions() > 0) {
      return config.getEmbeddingDimensions();
    }
    Integer global = properties.getEmbeddingDimensions();
    if (global != null && global > 0) {
      return global;
    }
    return FALLBACK_DIMENSION;
  }

  private String resolveDefaultEmbeddingProviderId() {
    if (isNotBlank(properties.getDefaultEmbeddingProvider())) {
      return properties.getDefaultEmbeddingProvider();
    }
    return properties.getDefaultProvider();
  }

  private ProviderConfig providerConfig(String providerId) {
    if (!isNotBlank(providerId) || properties.getProviders() == null) {
      return null;
    }
    return properties.getProviders().get(providerId);
  }

  private boolean isNotBlank(String value) {
    return value != null && !value.isBlank();
  }
}
