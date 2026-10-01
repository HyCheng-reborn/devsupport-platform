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
import static org.mockito.Mockito.*;

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

  @Nested
  @DisplayName("上传前 service/environment 长度校验")
  class LengthValidation {

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
    @DisplayName("service 100 字符 → 正常通过校验（fileHashService 被调用）")
    void serviceExactly100() {
      String exactService = "a".repeat(100);
      MockMultipartFile file = dummyFile();

      // 校验通过后会继续执行到 fileHashService，打桩返回 hash
      when(fileHashService.calculateHash(file)).thenReturn("somehash");

      // 方法会继续往下走，可能抛其他异常或正常返回，这里只验证不抛长度校验异常
      try {
        uploadService.uploadKnowledgeBase(file, "name", "cat", exactService, null);
      } catch (BusinessException e) {
        // 不应该是因为长度校验失败
        if (e.getMessage().contains("长度不能超过")) {
          throw new AssertionError("100字符不应触发长度校验", e);
        }
      } catch (Exception ignored) {
        // 其他异常（如 parseService 未打桩导致的 NPE）可接受
      }

      // 关键断言：fileHashService 被调用说明校验已通过
      verify(fileHashService).calculateHash(file);
    }

    @Test
    @DisplayName("null service/environment → 正常通过校验")
    void nullServiceEnvironment() {
      MockMultipartFile file = dummyFile();
      when(fileHashService.calculateHash(file)).thenReturn("somehash");

      try {
        uploadService.uploadKnowledgeBase(file, "name", "cat", null, null);
      } catch (BusinessException e) {
        if (e.getMessage().contains("长度不能超过")) {
          throw new AssertionError("null不应触发长度校验", e);
        }
      } catch (Exception ignored) {
        // 其他异常可接受
      }

      verify(fileHashService).calculateHash(file);
    }
  }
}
