package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.modules.knowledgebase.listener.VectorizeStreamProducer;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * KnowledgeBaseUploadService 单元测试
 * 覆盖：上传前元数据长度校验、内容去重、版本分组（versionNo 递增 + 停用旧版本）
 */
@DisplayName("知识库上传服务测试")
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseUploadServiceTest {

  @Mock private KnowledgeBaseParseService parseService;
  @Mock private KnowledgeBasePersistenceService persistenceService;
  @Mock private FileStorageService storageService;
  @Mock private KnowledgeBaseRepository knowledgeBaseRepository;
  @Mock private FileValidationService fileValidationService;
  @Mock private FileHashService fileHashService;
  @Mock private VectorizeStreamProducer vectorizeStreamProducer;
  @Mock private KnowledgeBaseVersionService versionService;

  private KnowledgeBaseUploadService uploadService;

  @BeforeEach
  void setUp() {
    uploadService = new KnowledgeBaseUploadService(
        parseService, persistenceService, storageService,
        knowledgeBaseRepository, fileValidationService,
        fileHashService, vectorizeStreamProducer, versionService
    );
  }

  private MockMultipartFile dummyFile() {
    return new MockMultipartFile("file", "test.md", "text/markdown", "hello".getBytes());
  }

  private static KnowledgeBaseEntity createTestEntity() {
    KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
    entity.setId(1L);
    entity.setName("test");
    entity.setCategory("cat");
    entity.setFileSize(5L);
    return entity;
  }

  /** 打桩一次完整"新内容"上传链路（不含版本查询与持久化返回值）。 */
  private void stubHappyPath(String hash) {
    when(fileHashService.calculateHash(any(MultipartFile.class))).thenReturn(hash);
    when(knowledgeBaseRepository.findByFileHash(hash)).thenReturn(Optional.empty());
    when(parseService.detectContentType(any())).thenReturn("text/markdown");
    when(parseService.parseContent(any())).thenReturn("content");
    when(storageService.uploadKnowledgeBase(any())).thenReturn("key");
    when(storageService.getFileUrl("key")).thenReturn("url");
  }

  @Nested
  @DisplayName("上传前元数据长度校验")
  class LengthValidation {

    @Test
    @DisplayName("service 超过 100 字符时拒绝，无副作用")
    void rejectLongService() {
      String longService = "a".repeat(101);
      MockMultipartFile file = dummyFile();

      assertThatThrownBy(() ->
          uploadService.uploadKnowledgeBase(file, "name", "cat", longService, null, null, null, null, null, null)
      ).isInstanceOf(BusinessException.class)
          .hasMessageContaining("service 长度不能超过 100");

      verifyNoInteractions(fileHashService, storageService, persistenceService, vectorizeStreamProducer, versionService);
      verify(fileValidationService, never()).validateFile(any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("environment 51 字符 → BusinessException，且 S3/fileHash/Redis 未调用")
    void environmentTooLong() {
      String longEnv = "b".repeat(51);
      MockMultipartFile file = dummyFile();

      assertThatThrownBy(() ->
          uploadService.uploadKnowledgeBase(file, "name", "cat", null, longEnv, null, null, null, null, null)
      ).isInstanceOf(BusinessException.class)
          .hasMessageContaining("environment 长度不能超过 50");

      verifyNoInteractions(fileHashService, storageService, vectorizeStreamProducer);
    }

    @Test
    @DisplayName("docType 超过 50 字符时拒绝（阶段 2 元数据校验）")
    void rejectLongDocType() {
      String longDocType = "d".repeat(51);
      MockMultipartFile file = dummyFile();

      assertThatThrownBy(() ->
          uploadService.uploadKnowledgeBase(file, "name", "cat", null, null, null, longDocType, null, null, null)
      ).isInstanceOf(BusinessException.class)
          .hasMessageContaining("docType 长度不能超过 50");

      verifyNoInteractions(fileHashService, persistenceService);
    }

    @Test
    @DisplayName("旧客户端只传 name/category/service/environment 时正常上传（其余元数据为 null）")
    void nullExtraMetadataUploads() {
      MockMultipartFile file = dummyFile();
      stubHappyPath("somehash");
      when(persistenceService.saveKnowledgeBase(any(), any(), any(), any(), any(), anyInt(), any(), any(), any()))
          .thenReturn(createTestEntity());

      Map<String, Object> result =
          uploadService.uploadKnowledgeBase(file, "name", "cat", null, null, null, null, null, null, null);

      verify(persistenceService).saveKnowledgeBase(any(), any(), any(), any(), any(), anyInt(), any(), any(), any());
      assertThat(result.get("duplicate")).isEqualTo(false);
    }
  }

  @Nested
  @DisplayName("内容去重与版本分组")
  class DedupAndVersioning {

    @Test
    @DisplayName("相同 fileHash 命中去重：不保存/不向量化/不停用旧版本")
    void duplicateContentNoNewRow() {
      MockMultipartFile file = dummyFile();
      when(fileHashService.calculateHash(any(MultipartFile.class))).thenReturn("dup-hash");
      KnowledgeBaseEntity existing = createTestEntity();
      when(knowledgeBaseRepository.findByFileHash("dup-hash")).thenReturn(Optional.of(existing));
      when(parseService.detectContentType(any())).thenReturn("text/markdown");
      when(persistenceService.handleDuplicateKnowledgeBase(eq(existing), eq("dup-hash")))
          .thenReturn(Map.of("duplicate", true));

      Map<String, Object> result =
          uploadService.uploadKnowledgeBase(file, "name", "cat", "S", "E", null, null, null, null, null);

      assertThat(result.get("duplicate")).isEqualTo(true);
      verify(persistenceService, never()).saveKnowledgeBase(any(), any(), any(), any(), any(), anyInt(), any(), any(), any());
      verifyNoInteractions(vectorizeStreamProducer);
      verifyNoInteractions(versionService);
    }

    @Test
    @DisplayName("同一 documentKey 的新内容上传：versionNo 递增并停用旧版本")
    void newVersionIncrementsAndRetiresSiblings() {
      MockMultipartFile file = dummyFile();
      stubHappyPath("v2-hash");
      KnowledgeBaseEntity prev = new KnowledgeBaseEntity();
      prev.setId(9L);
      prev.setVersionNo(3);
      prev.setActive(true);
      when(knowledgeBaseRepository.findByDocumentKeyOrderByVersionNoDesc(anyString()))
          .thenReturn(List.of(prev));
      when(persistenceService.saveKnowledgeBase(any(), any(), any(), any(), any(), eq(4), any(), any(), any()))
          .thenReturn(createTestEntity());

      uploadService.uploadKnowledgeBase(file, "name", "cat", "S", "E", "P", "D", "wiki", "v1", null);

      // versionNo = 现有最大值 3 + 1 = 4
      verify(persistenceService).saveKnowledgeBase(any(), any(), any(), any(), any(), eq(4), any(), any(), any());
      // 保存新版本后停用同 documentKey 旧版本（id=1 为新行）
      verify(versionService).retireSupersededVersions(anyString(), eq(1L));
      verify(vectorizeStreamProducer).sendVectorizeTask(eq(1L), any());
    }

    @Test
    @DisplayName("显式传入 documentKey 时作为该文档的新版本")
    void explicitDocumentKey() {
      MockMultipartFile file = dummyFile();
      stubHappyPath("explicit-hash");
      when(knowledgeBaseRepository.findByDocumentKeyOrderByVersionNoDesc("given-key"))
          .thenReturn(List.of());
      when(persistenceService.saveKnowledgeBase(any(), any(), any(), any(), eq("given-key"), eq(1), any(), any(), any()))
          .thenReturn(createTestEntity());

      uploadService.uploadKnowledgeBase(file, "name", "cat", "S", "E", "P", "D", null, null, "given-key");

      verify(persistenceService).saveKnowledgeBase(any(), any(), any(), any(), eq("given-key"), eq(1), any(), any(), any());
      verify(versionService).retireSupersededVersions("given-key", 1L);
    }
  }
}
