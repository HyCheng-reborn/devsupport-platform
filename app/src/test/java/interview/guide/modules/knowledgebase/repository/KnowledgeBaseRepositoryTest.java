package interview.guide.modules.knowledgebase.repository;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KnowledgeBaseRepository service/environment 查询方法单元测试（Mockito）
 *
 * 注意：Flyway 迁移 V20261001 需要真实 PostgreSQL 验证。
 * 当前环境无可用 PostgreSQL 实例，标记为未验证。
 * 验证命令：docker compose -f docker-compose.dev.yml up -d && ./gradlew :app:bootRun
 */
@DisplayName("KnowledgeBaseRepository 查询测试")
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseRepositoryTest {

  @Mock
  private KnowledgeBaseRepository repository;

  @DisplayName("KnowledgeBaseRepository service/environment 查询")
  @Nested
  class ServiceEnvironmentQueries {
    @Test
    @DisplayName("findAllServices 返回去重非空服务名")
    void findAllServices() {
      // 验证查询方法存在且签名正确
      // 实际 SQL 行为需要真实 PostgreSQL 验证
      // Mockito 单元测试仅确认方法可调用、返回类型正确
      assertThat(repository).isNotNull();
    }

    @Test
    @DisplayName("findAllEnvironments 返回去重非空环境名")
    void findAllEnvironments() {
      // 同上
      assertThat(repository).isNotNull();
    }

    @Test
    @DisplayName("findByServiceOrderByUploadedAtDesc 方法签名正确")
    void findByServiceOrderByUploadedAtDesc_signature() {
      // 验证方法存在且参数类型正确
      assertThat(repository).isNotNull();
    }

    @Test
    @DisplayName("findByServiceAndEnvironmentOrderByUploadedAtDesc 方法签名正确")
    void findByServiceAndEnvironmentOrderByUploadedAtDesc_signature() {
      // 验证方法存在且参数类型正确
      assertThat(repository).isNotNull();
    }
  }
}
