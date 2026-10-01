package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KnowledgeBasePersistenceService 单元测试
 * 覆盖批次 B：重复上传响应包含 service/environment/accessCount
 */
@DisplayName("知识库持久化服务测试")
@ExtendWith(MockitoExtension.class)
class KnowledgeBasePersistenceServiceTest {

  @Mock
  private KnowledgeBaseRepository knowledgeBaseRepository;

  private KnowledgeBasePersistenceService persistenceService;

  @BeforeEach
  void setUp() {
    persistenceService = new KnowledgeBasePersistenceService(knowledgeBaseRepository);
  }

  private KnowledgeBaseEntity buildKb(Long id, String name, String service, String environment,
                                       int accessCount) {
    KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
    kb.setId(id);
    kb.setName(name);
    kb.setService(service);
    kb.setEnvironment(environment);
    kb.setFileSize(1024L);
    kb.setStorageKey("key-" + id);
    kb.setStorageUrl("url-" + id);
    kb.setAccessCount(accessCount);
    kb.setLastAccessedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
    return kb;
  }

  @DisplayName("重复上传处理")
  @Nested
  class HandleDuplicate {

    @Test
    @DisplayName("返回的 Map 包含已有 KB 的 name、service、environment")
    void returnsExistingKbInfo() {
      KnowledgeBaseEntity kb = buildKb(10L, "测试文档", "支付网关", "生产", 5);
      when(knowledgeBaseRepository.save(any())).thenReturn(kb);

      Map<String, Object> result = persistenceService.handleDuplicateKnowledgeBase(kb, "abc123");

      assertThat(result).containsKey("duplicate");
      assertThat(result.get("duplicate")).isEqualTo(true);

      @SuppressWarnings("unchecked")
      Map<String, Object> kbMap = (Map<String, Object>) result.get("knowledgeBase");
      assertThat(kbMap.get("name")).isEqualTo("测试文档");
      assertThat(kbMap.get("service")).isEqualTo("支付网关");
      assertThat(kbMap.get("environment")).isEqualTo("生产");
    }

    @Test
    @DisplayName("重复上传会增加 accessCount")
    void incrementsAccessCount() {
      KnowledgeBaseEntity kb = buildKb(10L, "测试文档", "支付网关", "生产", 5);
      when(knowledgeBaseRepository.save(any())).thenReturn(kb);

      persistenceService.handleDuplicateKnowledgeBase(kb, "abc123");

      assertThat(kb.getAccessCount()).isEqualTo(6);
      ArgumentCaptor<KnowledgeBaseEntity> captor = ArgumentCaptor.forClass(KnowledgeBaseEntity.class);
      verify(knowledgeBaseRepository).save(captor.capture());
      assertThat(captor.getValue().getAccessCount()).isEqualTo(6);
    }

    @Test
    @DisplayName("重复上传会更新 lastAccessedAt")
    void updatesLastAccessedAt() {
      LocalDateTime oldTime = LocalDateTime.of(2020, 1, 1, 0, 0);
      KnowledgeBaseEntity kb = buildKb(10L, "测试文档", null, null, 1);
      kb.setLastAccessedAt(oldTime);
      when(knowledgeBaseRepository.save(any())).thenReturn(kb);

      persistenceService.handleDuplicateKnowledgeBase(kb, "abc123");

      assertThat(kb.getLastAccessedAt()).isAfter(oldTime);
    }

    @Test
    @DisplayName("重复上传不修改原 service/environment")
    void doesNotModifyServiceEnvironment() {
      KnowledgeBaseEntity kb = buildKb(10L, "测试文档", "支付网关", "生产", 3);
      when(knowledgeBaseRepository.save(any())).thenReturn(kb);

      persistenceService.handleDuplicateKnowledgeBase(kb, "abc123");

      assertThat(kb.getService()).isEqualTo("支付网关");
      assertThat(kb.getEnvironment()).isEqualTo("生产");
    }

    @Test
    @DisplayName("service/environment 为 null 时返回空字符串")
    void nullServiceEnvironmentReturnsEmptyString() {
      KnowledgeBaseEntity kb = buildKb(10L, "测试文档", null, null, 1);
      when(knowledgeBaseRepository.save(any())).thenReturn(kb);

      Map<String, Object> result = persistenceService.handleDuplicateKnowledgeBase(kb, "abc123");

      @SuppressWarnings("unchecked")
      Map<String, Object> kbMap = (Map<String, Object>) result.get("knowledgeBase");
      assertThat(kbMap.get("service")).isEqualTo("");
      assertThat(kbMap.get("environment")).isEqualTo("");
    }

    @Test
    @DisplayName("返回的 Map 包含更新后的 accessCount")
    void returnsUpdatedAccessCount() {
      KnowledgeBaseEntity kb = buildKb(10L, "测试文档", null, null, 7);
      when(knowledgeBaseRepository.save(any())).thenReturn(kb);

      Map<String, Object> result = persistenceService.handleDuplicateKnowledgeBase(kb, "abc123");

      @SuppressWarnings("unchecked")
      Map<String, Object> kbMap = (Map<String, Object>) result.get("knowledgeBase");
      assertThat(kbMap.get("accessCount")).isEqualTo(8);
    }
  }
}
