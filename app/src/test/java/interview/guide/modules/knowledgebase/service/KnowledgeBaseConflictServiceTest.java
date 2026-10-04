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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
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
    @DisplayName("正常采纳：tryStartAdopt 返回 1，S3 解析成功，发送向量化任务（adoptMode=true）")
    void adopt_success() {
      // 准备：冲突版本
      KnowledgeBaseEntity conflictEntity = buildConflictEntity(2L, "dk1", true);
      conflictEntity.setVectorStatus(VectorStatus.ADOPTING); // tryStartAdopt 后状态

      // transactionalExecutor.call() 直接执行 Supplier
      doAnswer(invocation -> {
        java.util.function.Supplier<?> supplier = invocation.getArgument(0);
        return supplier.get();
      }).when(transactionalExecutor).call(any());
      // tryStartAdopt 原子更新成功
      when(repository.tryStartAdopt(2L)).thenReturn(1);
      // doAdoptParseAndSend 中 re-read
      when(repository.findById(2L)).thenReturn(Optional.of(conflictEntity));
      when(parseService.downloadAndParseContent("storage-key-2", "doc-2.md"))
          .thenReturn("这是冲突版本的文档内容");
      doNothing().when(vectorizeStreamProducer).sendVectorizeTask(eq(2L), anyString(), eq(true));

      // 执行
      conflictService.adoptVersion(2L);

      // 验证：向量化任务已发送（adoptMode=true）
      verify(vectorizeStreamProducer).sendVectorizeTask(eq(2L), eq("这是冲突版本的文档内容"), eq(true));
    }

    @Test
    @DisplayName("tryStartAdopt 返回 0 + 状态为 ADOPTING → 幂等无操作")
    void adopt_tryStartAdopt0_adopting_idempotent() {
      KnowledgeBaseEntity entity = buildConflictEntity(2L, "dk1", true);
      entity.setActive(false);
      entity.setConflict(true);
      entity.setVectorStatus(VectorStatus.ADOPTING);

      // transactionalExecutor.call() 直接执行 Supplier
      doAnswer(invocation -> {
        java.util.function.Supplier<?> supplier = invocation.getArgument(0);
        return supplier.get();
      }).when(transactionalExecutor).call(any());
      when(repository.tryStartAdopt(2L)).thenReturn(0);
      when(repository.findById(2L)).thenReturn(Optional.of(entity));

      // 执行：不应抛异常
      conflictService.adoptVersion(2L);

      // 验证：没有向量化操作（幂等）
      verify(vectorizeStreamProducer, never()).sendVectorizeTask(anyLong(), anyString());
    }

    @Test
    @DisplayName("tryStartAdopt 返回 0 + 状态为 COMPLETED 且 conflict=false → 幂等无操作")
    void adopt_tryStartAdopt0_completed_idempotent() {
      KnowledgeBaseEntity entity = buildConflictEntity(2L, "dk1", false);
      entity.setActive(true);
      entity.setConflict(false);
      entity.setVectorStatus(VectorStatus.COMPLETED);

      doAnswer(invocation -> {
        java.util.function.Supplier<?> supplier = invocation.getArgument(0);
        return supplier.get();
      }).when(transactionalExecutor).call(any());
      when(repository.tryStartAdopt(2L)).thenReturn(0);
      when(repository.findById(2L)).thenReturn(Optional.of(entity));

      // 执行：不应抛异常
      conflictService.adoptVersion(2L);

      // 验证：没有向量化操作（幂等）
      verify(vectorizeStreamProducer, never()).sendVectorizeTask(anyLong(), anyString());
    }

    @Test
    @DisplayName("tryStartAdopt 返回 0 + 状态为 ABANDONED → 抛出 BusinessException")
    void adopt_tryStartAdopt0_abandoned_throws() {
      KnowledgeBaseEntity entity = buildConflictEntity(2L, "dk1", false);
      entity.setActive(false);
      entity.setConflict(false);
      entity.setVectorStatus(VectorStatus.ABANDONED); // 已放弃

      doAnswer(invocation -> {
        java.util.function.Supplier<?> supplier = invocation.getArgument(0);
        return supplier.get();
      }).when(transactionalExecutor).call(any());
      when(repository.tryStartAdopt(2L)).thenReturn(0);
      when(repository.findById(2L)).thenReturn(Optional.of(entity));

      assertThatThrownBy(() -> conflictService.adoptVersion(2L))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("已放弃");

      verify(vectorizeStreamProducer, never()).sendVectorizeTask(anyLong(), anyString());
    }

    @Test
    @DisplayName("tryStartAdopt 返回 0 + 非法状态 → 抛出 BusinessException")
    void adopt_tryStartAdopt0_illegalStatus_throws() {
      KnowledgeBaseEntity entity = buildConflictEntity(2L, "dk1", true);
      entity.setActive(false);
      entity.setConflict(true);
      entity.setVectorStatus(VectorStatus.FAILED);

      doAnswer(invocation -> {
        java.util.function.Supplier<?> supplier = invocation.getArgument(0);
        return supplier.get();
      }).when(transactionalExecutor).call(any());
      when(repository.tryStartAdopt(2L)).thenReturn(0);
      when(repository.findById(2L)).thenReturn(Optional.of(entity));

      assertThatThrownBy(() -> conflictService.adoptVersion(2L))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("非法状态");

      verify(vectorizeStreamProducer, never()).sendVectorizeTask(anyLong(), anyString());
    }

    @Test
    @DisplayName("S3 下载失败 → 回滚 CONFLICT 并抛出 BusinessException")
    void adopt_s3Failure_throwsAndRecovers() {
      KnowledgeBaseEntity conflictEntity = buildConflictEntity(2L, "dk1", true);
      conflictEntity.setVectorStatus(VectorStatus.ADOPTING);

      doAnswer(invocation -> {
        java.util.function.Supplier<?> supplier = invocation.getArgument(0);
        return supplier.get();
      }).when(transactionalExecutor).call(any());
      doAnswer(invocation -> {
        Runnable action = invocation.getArgument(0);
        action.run();
        return null;
      }).when(transactionalExecutor).runRequiresNew(any());
      when(repository.tryStartAdopt(2L)).thenReturn(1);
      when(repository.findById(2L)).thenReturn(Optional.of(conflictEntity));
      when(parseService.downloadAndParseContent("storage-key-2", "doc-2.md"))
          .thenThrow(new RuntimeException("连接超时"));
      when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      assertThatThrownBy(() -> conflictService.adoptVersion(2L))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("S3 下载失败");

      // 验证：状态回滚到 CONFLICT
      assertThat(conflictEntity.getVectorStatus()).isEqualTo(VectorStatus.CONFLICT);
      assertThat(conflictEntity.getVectorError()).contains("S3 下载失败");
      verify(repository).save(conflictEntity);
      // 验证：没有发送向量化任务
      verify(vectorizeStreamProducer, never()).sendVectorizeTask(anyLong(), anyString());
    }

    @Test
    @DisplayName("Redis 发送失败 → 回滚 CONFLICT 并抛出 BusinessException")
    void adopt_redisFailure_throwsAndRecovers() {
      KnowledgeBaseEntity conflictEntity = buildConflictEntity(2L, "dk1", true);
      conflictEntity.setVectorStatus(VectorStatus.ADOPTING);

      doAnswer(invocation -> {
        java.util.function.Supplier<?> supplier = invocation.getArgument(0);
        return supplier.get();
      }).when(transactionalExecutor).call(any());
      doAnswer(invocation -> {
        Runnable action = invocation.getArgument(0);
        action.run();
        return null;
      }).when(transactionalExecutor).runRequiresNew(any());
      when(repository.tryStartAdopt(2L)).thenReturn(1);
      when(repository.findById(2L)).thenReturn(Optional.of(conflictEntity));
      when(parseService.downloadAndParseContent("storage-key-2", "doc-2.md"))
          .thenReturn("文档内容");
      doThrow(new RuntimeException("Redis 连接断开"))
          .when(vectorizeStreamProducer).sendVectorizeTask(eq(2L), anyString(), eq(true));
      when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      assertThatThrownBy(() -> conflictService.adoptVersion(2L))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("Redis 发送失败");

      // 验证：状态回滚到 CONFLICT
      assertThat(conflictEntity.getVectorStatus()).isEqualTo(VectorStatus.CONFLICT);
      assertThat(conflictEntity.getVectorError()).contains("Redis 发送失败");
      verify(repository).save(conflictEntity);
    }

    @Test
    @DisplayName("不存在的 ID 调用 adopt → tryStartAdopt 返回 0，re-read 抛 NotFoundException")
    void adopt_notFound_throws() {
      doAnswer(invocation -> {
        java.util.function.Supplier<?> supplier = invocation.getArgument(0);
        return supplier.get();
      }).when(transactionalExecutor).call(any());
      when(repository.tryStartAdopt(999L)).thenReturn(0);
      when(repository.findById(999L)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> conflictService.adoptVersion(999L))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("知识库不存在");
    }
  }

  @DisplayName("放弃冲突版本")
  @Nested
  class AbandonVersion {

    @Test
    @DisplayName("正常放弃：清除冲突标记和 active，vectorStatus 设为 ABANDONED")
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
      // 验证：vectorStatus 设为 ABANDONED
      assertThat(conflictEntity.getVectorStatus()).isEqualTo(VectorStatus.ABANDONED);
      // 验证：active 为 false
      assertThat(conflictEntity.getActive()).isFalse();
      // 验证：保存操作被调用
      verify(repository).save(conflictEntity);
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

    @Test
    @DisplayName("放弃已放弃的版本（vectorStatus=ABANDONED）→ 幂等无操作")
    void abandon_alreadyAbandoned_idempotent() {
      KnowledgeBaseEntity entity = buildConflictEntity(2L, "dk1", false);
      entity.setActive(false);
      entity.setConflict(false);
      entity.setVectorStatus(VectorStatus.ABANDONED); // 已放弃

      when(repository.findById(2L)).thenReturn(Optional.of(entity));

      // 执行：不应抛异常
      conflictService.abandonVersion(2L);

      // 验证：没有保存操作（幂等）
      verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("放弃正在 ADOPTING 中的版本 → 抛出 BusinessException")
    void abandon_whileAdopting_throws() {
      KnowledgeBaseEntity entity = buildConflictEntity(2L, "dk1", true);
      entity.setActive(false);
      entity.setConflict(true);
      entity.setVectorStatus(VectorStatus.ADOPTING);

      when(repository.findById(2L)).thenReturn(Optional.of(entity));

      assertThatThrownBy(() -> conflictService.abandonVersion(2L))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("正在采纳中");

      verify(repository, never()).save(any());
    }
  }
}
