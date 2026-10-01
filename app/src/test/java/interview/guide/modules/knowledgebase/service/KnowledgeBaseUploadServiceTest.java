package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.modules.knowledgebase.listener.VectorizeStreamProducer;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;

/**
 * KnowledgeBaseUploadService 单元测试
 * 覆盖上传前 service/environment 长度校验
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

  private KnowledgeBaseUploadService uploadService;

  @BeforeEach
  void setUp() {
    uploadService = new KnowledgeBaseUploadService(
        parseService, persistenceService, storageService,
        knowledgeBaseRepository, fileValidationService,
        fileHashService, vectorizeStreamProducer
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

  @Nested
  @DisplayName("上传前 service/environment 长度校验")
  class LengthValidation {

    @Test
    @DisplayName("service 超过 100 字符时拒绝，无副作用")
    void rejectLongService() {
      String longService = "a".repeat(101);
      MockMultipartFile file = dummyFile();

      assertThatThrownBy(() ->
          uploadService.uploadKnowledgeBase(file, "name", "cat", longService, null)
      ).isInstanceOf(BusinessException.class)
          .hasMessageContaining("service 长度不能超过 100");

      verifyNoInteractions(fileHashService);
      verifyNoInteractions(storageService);
      verifyNoInteractions(persistenceService);
      verifyNoInteractions(vectorizeStreamProducer);
      verify(fileValidationService, never()).validateFile(any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("service 101 字符 → BusinessException，且 S3/fileHash/Redis 未调用")
    void serviceTooLong() {
      String longService = "a".repeat(101);
      MockMultipartFile file = dummyFile();

      assertThatThrownBy(() ->
          uploadService.uploadKnowledgeBase(file, "name", "cat", longService, null)
      ).isInstanceOf(BusinessException.class)
          .hasMessageContaining("service 长度不能超过 100");

      verifyNoInteractions(fileHashService);
      verifyNoInteractions(storageService);
      verifyNoInteractions(vectorizeStreamProducer);
    }

    @Test
    @DisplayName("environment 51 字符 → BusinessException，且 S3/fileHash/Redis 未调用")
    void environmentTooLong() {
      String longEnv = "b".repeat(51);
      MockMultipartFile file = dummyFile();

      assertThatThrownBy(() ->
          uploadService.uploadKnowledgeBase(file, "name", "cat", null, longEnv)
      ).isInstanceOf(BusinessException.class)
          .hasMessageContaining("environment 长度不能超过 50");

      verifyNoInteractions(fileHashService);
      verifyNoInteractions(storageService);
      verifyNoInteractions(vectorizeStreamProducer);
    }

    @Test
    @DisplayName("service 正好 100 字符时通过长度校验，正常完成上传")
    void serviceExactly100() {
      String exactService = "a".repeat(100);
      MockMultipartFile file = dummyFile();

      // 打桩完整上传链路
      when(fileHashService.calculateHash(file)).thenReturn("somehash");
      when(knowledgeBaseRepository.findByFileHash("somehash")).thenReturn(Optional.empty());
      when(parseService.detectContentType(file)).thenReturn("text/markdown");
      when(parseService.parseContent(file)).thenReturn("content");
      when(storageService.uploadKnowledgeBase(file)).thenReturn("key");
      when(storageService.getFileUrl("key")).thenReturn("url");
      when(persistenceService.saveKnowledgeBase(any(), any(), any(), eq(exactService), isNull(), any(), any(), any()))
          .thenReturn(createTestEntity());

      uploadService.uploadKnowledgeBase(file, "name", "cat", exactService, null);

      verify(persistenceService).saveKnowledgeBase(any(), any(), any(), eq(exactService), isNull(), any(), any(), any());
    }

    @Test
    @DisplayName("旧客户端不传 service/environment 时正常上传，null 透传到持久化")
    void nullServiceAndEnvironment() {
      MockMultipartFile file = dummyFile();

      when(fileHashService.calculateHash(file)).thenReturn("somehash");
      when(knowledgeBaseRepository.findByFileHash("somehash")).thenReturn(Optional.empty());
      when(parseService.detectContentType(file)).thenReturn("text/markdown");
      when(parseService.parseContent(file)).thenReturn("content");
      when(storageService.uploadKnowledgeBase(file)).thenReturn("key");
      when(storageService.getFileUrl("key")).thenReturn("url");
      when(persistenceService.saveKnowledgeBase(any(), any(), any(), isNull(), isNull(), any(), any(), any()))
          .thenReturn(createTestEntity());

      uploadService.uploadKnowledgeBase(file, "name", "cat", null, null);

      verify(persistenceService).saveKnowledgeBase(any(), any(), any(), isNull(), isNull(), any(), any(), any());
    }
  }
}
