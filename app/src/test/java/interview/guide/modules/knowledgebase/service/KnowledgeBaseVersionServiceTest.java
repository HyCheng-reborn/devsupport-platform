package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * KnowledgeBaseVersionService 单元测试
 * 覆盖：新版本停用并清理同 documentKey 旧版本向量；已停用旧版本不重复删；手动停用。
 */
@DisplayName("知识库版本生命周期服务测试")
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseVersionServiceTest {

  @Mock private KnowledgeBaseRepository knowledgeBaseRepository;
  @Mock private KnowledgeBasePersistenceService persistenceService;
  @Mock private KnowledgeBaseVectorService vectorService;

  @InjectMocks private KnowledgeBaseVersionService versionService;

  private static KnowledgeBaseEntity kb(Long id, boolean active) {
    KnowledgeBaseEntity e = new KnowledgeBaseEntity();
    e.setId(id);
    e.setActive(active);
    return e;
  }

  @Test
  @DisplayName("新版本落库后停用并删除同 documentKey 旧启用版本的向量")
  void retiresAndDeletesSupersededActiveVersions() {
    when(knowledgeBaseRepository.findByDocumentKeyOrderByVersionNoDesc("doc-key"))
        .thenReturn(List.of(kb(5L, true), kb(2L, true), kb(1L, true)));

    versionService.retireSupersededVersions("doc-key", 5L);

    verify(persistenceService).retireOtherVersions("doc-key", 5L);
    verify(vectorService).deleteByKnowledgeBaseId(2L);
    verify(vectorService).deleteByKnowledgeBaseId(1L);
    verify(vectorService, never()).deleteByKnowledgeBaseId(5L);
  }

  @Test
  @DisplayName("已停用的旧版本不再重复删除向量")
  void skipsAlreadyInactiveVersions() {
    when(knowledgeBaseRepository.findByDocumentKeyOrderByVersionNoDesc("doc-key"))
        .thenReturn(List.of(kb(5L, true), kb(2L, false)));

    versionService.retireSupersededVersions("doc-key", 5L);

    verify(persistenceService).retireOtherVersions("doc-key", 5L);
    verify(vectorService, never()).deleteByKnowledgeBaseId(anyLong());
  }

  @Test
  @DisplayName("documentKey 为空时为无操作，不触达任何依赖")
  void blankDocumentKeyIsNoOp() {
    versionService.retireSupersededVersions(null, 1L);
    verifyNoInteractions(knowledgeBaseRepository, persistenceService, vectorService);
  }

  @Test
  @DisplayName("手动停用单个知识库：置 inactive 并删除其向量")
  void retireDeletesItsVectors() {
    versionService.retire(7L);
    verify(persistenceService).retire(7L);
    verify(vectorService).deleteByKnowledgeBaseId(7L);
  }
}
