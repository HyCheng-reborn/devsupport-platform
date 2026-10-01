package interview.guide.modules.knowledgebase.repository;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KnowledgeBaseRepository service/environment 集成测试（H2 内存数据库）
 * <p>
 * 验证 service/environment 标签相关的 4 个查询方法的真实 SQL 行为。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("KnowledgeBaseRepository service/environment 集成测试")
class KnowledgeBaseRepositoryIntegrationTest {

  @Autowired
  private EntityManager entityManager;

  @Autowired
  private KnowledgeBaseRepository repository;

  // 固定时间基准，用于控制排序
  private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 1, 1, 0, 0);

  @BeforeEach
  void setUp() {
    entityManager.createQuery("DELETE FROM KnowledgeBaseEntity").executeUpdate();
    entityManager.flush();

    // 1. 旧行（service=NULL, environment=NULL）—— 最早上传
    createAndPersist("a".repeat(64), "旧文档", "old-doc.pdf", null, null, BASE_TIME);

    // 2. service="支付网关", environment="生产" —— 第2上传
    createAndPersist("b".repeat(64), "支付网关生产文档", "pay-prod.pdf",
        "支付网关", "生产", BASE_TIME.plusDays(1));

    // 3. service="支付网关", environment="预发" —— 第3上传
    createAndPersist("c".repeat(64), "支付网关预发文档", "pay-staging.pdf",
        "支付网关", "预发", BASE_TIME.plusDays(2));

    // 4. service="用户中心", environment="通用" —— 第4上传
    createAndPersist("d".repeat(64), "用户中心通用文档", "user-general.pdf",
        "用户中心", "通用", BASE_TIME.plusDays(3));

    // 5. service="用户中心", environment=NULL —— 最新上传
    createAndPersist("e".repeat(64), "用户中心无环境文档", "user-noenv.pdf",
        "用户中心", null, BASE_TIME.plusDays(4));
  }

  @Test
  @DisplayName("findByService 返回匹配服务的 KB，按上传时间降序")
  void findByService() {
    List<KnowledgeBaseEntity> results = repository.findByServiceOrderByUploadedAtDesc("支付网关");

    assertThat(results).hasSize(2);
    // 降序：第3上传（预发）在前，第2上传（生产）在后
    assertThat(results.get(0).getEnvironment()).isEqualTo("预发");
    assertThat(results.get(1).getEnvironment()).isEqualTo("生产");
    // 所有结果的 service 都匹配
    assertThat(results).allMatch(kb -> "支付网关".equals(kb.getService()));
  }

  @Test
  @DisplayName("findByServiceAndEnvironment 精确匹配")
  void findByServiceAndEnvironment() {
    List<KnowledgeBaseEntity> results = repository
        .findByServiceAndEnvironmentOrderByUploadedAtDesc("用户中心", "通用");

    assertThat(results).hasSize(1);
    assertThat(results.get(0).getName()).isEqualTo("用户中心通用文档");
    assertThat(results.get(0).getEnvironment()).isEqualTo("通用");
  }

  @Test
  @DisplayName("findAllServices 返回去重非空服务名")
  void findAllServices() {
    List<String> services = repository.findAllServices();

    // 去重、非空、按字母排序（H2 按 Unicode 码点排序）
    // 支(U+652F) < 用(U+7528)，所以 "支付网关" 在前
    assertThat(services).containsExactly("支付网关", "用户中心");
  }

  @Test
  @DisplayName("findAllEnvironments 返回去重非空环境名")
  void findAllEnvironments() {
    List<String> environments = repository.findAllEnvironments();

    // 去重、非空、按字母排序（H2 按 Unicode 码点排序）
    // 生(U+751F) < 通(U+901A) < 预(U+9884)
    assertThat(environments).containsExactly("生产", "通用", "预发");
  }

  @Test
  @DisplayName("旧行 service=NULL 不参与 findByService 结果")
  void nullServiceNotInResults() {
    // 查找任何 service 的结果都不应包含旧行
    List<KnowledgeBaseEntity> payResults = repository.findByServiceOrderByUploadedAtDesc("支付网关");
    List<KnowledgeBaseEntity> userResults = repository.findByServiceOrderByUploadedAtDesc("用户中心");

    assertThat(payResults).noneMatch(kb -> kb.getService() == null);
    assertThat(userResults).noneMatch(kb -> kb.getService() == null);

    // 旧行确实存在
    assertThat(repository.count()).isEqualTo(5);
  }

  // ===== 辅助方法 =====

  private void createAndPersist(String fileHash, String name, String originalFilename,
      String service, String environment, LocalDateTime uploadedAt) {
    KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
    entity.setFileHash(fileHash);
    entity.setName(name);
    entity.setOriginalFilename(originalFilename);
    entity.setService(service);
    entity.setEnvironment(environment);
    entity.setFileSize(1024L);
    entity.setContentType("application/pdf");

    // 先 persist（触发 @PrePersist 设置默认值），再手动覆盖 uploadedAt
    entityManager.persist(entity);
    entity.setUploadedAt(uploadedAt);
    entityManager.merge(entity);
  }
}
