package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.knowledgebase.listener.VectorizeStreamProducer;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.QuestionGenStatus;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KnowledgeBaseConflictService 单元测试
 * 测试版本冲突解决策略：adopt（采纳）和 abandon（放弃）
 */
@DisplayName("知识库版本冲突解决服务测试")
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseConflictServiceTest {

  @Mock private KnowledgeBaseRepository repository;
  @Mock private KnowledgeBaseVectorService vectorService;
  @Mock private VectorizeStreamProducer vectorizeStreamProducer;
  @Mock private KnowledgeBaseParseService parseService;
  @Mock private TransactionalExecutor transactionalExecutor;

  private KnowledgeBaseConflictService conflictService;

  @BeforeEach
  void setUp() {
    conflictService = new KnowledgeBaseConflictService(
        repository, vectorService, vectorizeStreamProducer, parseService, transactionalExecutor);
  }

  private static KnowledgeBaseEntity buildConflictEntity(Long id, String documentKey, boolean conflict) {
    KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
    kb.setId(id);
    kb.setName("文档-" + id);
    kb.setOriginalFilename("doc-" + id + ".md");
    kb.setFileSize(100L);
    kb.setUploadedAt(LocalDateTime.now());
    kb.setAccessCount(0);
    kb.setQuestionCount(0);
    kb.setVectorStatus(conflict ? VectorStatus.CONFLICT : VectorStatus.COMPLETED);
    kb.setChunkCount(1);
    kb.setQuestionGenStatus(QuestionGenStatus.NONE);
    kb.setDocumentKey(documentKey);
    kb.setConflict(conflict);
    kb.setActive(!conflict);
    kb.setStorageKey("storage-key-" + id);
    return kb;
  }

  @DisplayName("采纳冲突版本")
  @Nested
  class AdoptVersion {

    @Test
    @DisplayName("正常采纳：停用旧版本，激活冲突版本并发送向量化任务")
    void adopt_success() {
      // 准备：冲突版本和当前 active 版本
      KnowledgeBaseEntity conflictEntity = buildConflictEntity(2L, "dk1", true);
      KnowledgeBaseEntity activeEntity = buildConflictEntity(1L, "dk1", false);
      activeEntity.setActive(true);

      when(repository.findById(2L)).thenReturn(Optional.of(conflictEntity));
      when(repository.findByDocumentKeyAndActiveTrueOrderByVersionNoDesc("dk1"))
          .thenReturn(List.of(activeEntity, conflictEntity));
      when(parseService.downloadAndParseContent("storage-key-2", "doc-2.md"))
          .thenReturn("这是冲突版本的文档内容");
      doNothing().when(vectorizeStreamProducer).sendVectorizeTask(eq(2L), anyString());

      // Mock transactionalExecutor 直接执行 Runnable
      doAnswer(invocation -> {
        Runnable action = invocation.getArgument(0);
        action.run();
        return null;
      }).when(transactionalExecutor).run(any());

      // 执行
      conflictService.adoptVersion(2L);

      // 验证：旧版本被停用
      assertThat(activeEntity.getActive()).isFalse();
      verify(repository).save(activeEntity);

      // 验证：冲突版本被激活
      assertThat(conflictEntity.getActive()).isTrue();
      assertThat(conflictEntity.getConflict()).isFalse();
      assertThat(conflictEntity.getVectorStatus()).isEqualTo(VectorStatus.PENDING);
      verify(repository).save(conflictEntity);

      // 验证：旧版本向量被删除
      verify(vectorService).deleteByKnowledgeBaseId(1L);

      // 验证：向量化任务已发送
      verify(vectorizeStreamProducer).sendVectorizeTask(eq(2L), eq("这是冲突版本的文档内容"));
    }

    @Test
    @DisplayName("非冲突版本调用 adopt → 抛出 BusinessException")
    void adopt_notConflict_throws() {
      KnowledgeBaseEntity normalEntity = buildConflictEntity(1L, "dk1", false);
      normalEntity.setActive(true);
      normalEntity.setConflict(false);

      when(repository.findById(1L)).thenReturn(Optional.of(normalEntity));

      // 执行并验证
      assertThatThrownBy(() -> conflictService.adoptVersion(1L))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("不是冲突状态");

      // 验证：没有触发任何保存或向量化操作
      verify(repository, never()).save(any());
      verify(vectorizeStreamProducer, never()).sendVectorizeTask(anyLong(), anyString());
    }

    @Test
    @DisplayName("不存在的 ID 调用 adopt → 抛出 BusinessException")
    void adopt_notFound_throws() {
      when(repository.findById(999L)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> conflictService.adoptVersion(999L))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("知识库不存在");

      verify(repository, never()).save(any());
    }
  }

  @DisplayName("放弃冲突版本")
  @Nested
  class AbandonVersion {

    @Test
    @DisplayName("正常放弃：清除冲突标记，保持 active=false")
    void abandon_success() {
      KnowledgeBaseEntity conflictEntity = buildConflictEntity(2L, "dk1", true);
      conflictEntity.setActive(false);
      conflictEntity.setConflict(true);
      conflictEntity.setVectorStatus(VectorStatus.CONFLICT);

      when(repository.findById(2L)).thenReturn(Optional.of(conflictEntity));
      when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

      // 执行
      conflictService.abandonVersion(2L);

      // 验证：冲突标记被清除
      assertThat(conflictEntity.getConflict()).isFalse();
      // 验证：vectorStatus 变为 FAILED
      assertThat(conflictEntity.getVectorStatus()).isEqualTo(VectorStatus.FAILED);
      // 验证：active 仍为 false
      assertThat(conflictEntity.getActive()).isFalse();
      // 验证：保存操作被调用
      verify(repository).save(conflictEntity);
    }

    @Test
    @DisplayName("非冲突版本调用 abandon → 抛出 BusinessException")
    void abandon_notConflict_throws() {
      KnowledgeBaseEntity normalEntity = buildConflictEntity(1L, "dk1", false);
      normalEntity.setActive(true);
      normalEntity.setConflict(false);

      when(repository.findById(1L)).thenReturn(Optional.of(normalEntity));

      // 执行并验证
      assertThatThrownBy(() -> conflictService.abandonVersion(1L))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("不是冲突状态");

      // 验证：没有触发保存操作
      verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("不存在的 ID 调用 abandon → 抛出 BusinessException")
    void abandon_notFound_throws() {
      when(repository.findById(999L)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> conflictService.abandonVersion(999L))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("知识库不存在");

      verify(repository, never()).save(any());
    }
  }
}
