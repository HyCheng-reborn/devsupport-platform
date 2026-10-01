package interview.guide.modules.knowledgebase.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KnowledgeBaseEntity service/environment 字段单元测试
 */
@DisplayName("KnowledgeBaseEntity 模型测试")
class KnowledgeBaseEntityTest {

  @DisplayName("KnowledgeBaseEntity service/environment 字段")
  @Nested
  class ServiceEnvironmentFields {
    @Test
    @DisplayName("service 和 environment 默认为 null")
    void defaultValues() {
      KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
      assertThat(entity.getService()).isNull();
      assertThat(entity.getEnvironment()).isNull();
    }

    @Test
    @DisplayName("可设置和读取 service/environment")
    void setAndGet() {
      KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
      entity.setService("支付网关");
      entity.setEnvironment("生产");
      assertThat(entity.getService()).isEqualTo("支付网关");
      assertThat(entity.getEnvironment()).isEqualTo("生产");
    }
  }
}
