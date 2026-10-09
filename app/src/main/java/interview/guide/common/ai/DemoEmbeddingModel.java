package interview.guide.common.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

/**
 * Demo profile 下的 Embedding 模型，返回基于内容 hash 的确定性 1024 维向量。
 *
 * <p>仅用于本地演示，不调用任何真实 Embedding API，零付费。
 * 相同内容始终返回相同向量，确保向量检索的确定性行为。</p>
 */
@Component
@Profile("demo")
@Slf4j
public class DemoEmbeddingModel implements EmbeddingModel {

  private static final int DIMENSIONS = 1024;

  @Override
  public EmbeddingResponse call(EmbeddingRequest request) {
    log.info("[Demo] 生成确定性 Embedding，请求文本数: {}", request.getInstructions().size());

    List<float[]> embeddings = request.getInstructions().stream()
        .map(this::deterministicEmbedding)
        .toList();

    EmbeddingResponseMetadata metadata = new EmbeddingResponseMetadata();
    return new EmbeddingResponse(
        embeddings.stream()
            .map(embedding -> new org.springframework.ai.embedding.Embedding(embedding, 0))
            .toList(),
        metadata
    );
  }

  @Override
  public float[] embed(String text) {
    return deterministicEmbedding(text);
  }

  @Override
  public float[] embed(org.springframework.ai.document.Document document) {
    return deterministicEmbedding(document.getText());
  }

  @Override
  public List<float[]> embed(List<String> texts) {
    return texts.stream()
        .map(this::deterministicEmbedding)
        .toList();
  }

  @Override
  public int dimensions() {
    return DIMENSIONS;
  }

  /**
   * 基于内容 hash 生成确定性 1024 维向量。
   * 使用 SHA-256 生成 32 字节 hash，然后循环填充到 1024 维。
   * 相同内容始终返回相同向量。
   */
  private float[] deterministicEmbedding(String content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));

      float[] embedding = new float[DIMENSIONS];
      for (int i = 0; i < DIMENSIONS; i++) {
        // 使用 hash 字节循环填充，归一化到 [-1, 1] 范围
        byte hashByte = hash[i % hash.length];
        embedding[i] = (hashByte & 0xFF) / 127.5f - 1.0f;
      }

      // L2 归一化，使向量长度为 1（模拟真实 embedding 行为）
      float norm = 0.0f;
      for (float v : embedding) {
        norm += v * v;
      }
      norm = (float) Math.sqrt(norm);
      if (norm > 0) {
        for (int i = 0; i < DIMENSIONS; i++) {
          embedding[i] /= norm;
        }
      }

      return embedding;
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 在所有 JDK 中都可用，理论上不会到达这里
      throw new RuntimeException("SHA-256 algorithm not available", e);
    }
  }
}
