package interview.guide.modules.resume;

import interview.guide.common.config.AppConfigProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.resume.listener.AnalyzeStreamProducer;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.ResumeParseService;
import interview.guide.modules.resume.service.ResumePersistenceService;
import interview.guide.modules.resume.service.ResumeUploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
/**
 * ResumeUploadService 单元测试
 * 覆盖上传完整流程、去重、文件验证失败、重新分析等场景
 */
@DisplayName("简历上传服务测试")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResumeUploadServiceTest {

  @Mock
  private ResumeParseService parseService;
  @Mock
  private FileStorageService storageService;
  @Mock
  private ResumePersistenceService persistenceService;
  @Mock
  private AppConfigProperties appConfig;
  @Mock
  private FileValidationService fileValidationService;
  @Mock
  private AnalyzeStreamProducer analyzeStreamProducer;
  @Mock
  private ResumeRepository resumeRepository;
  @Mock
  private TransactionalExecutor transactionalExecutor;

  private ResumeUploadService service;

  @BeforeEach
  void setUp() {
    service = new ResumeUploadService(
        parseService, storageService, persistenceService,
        appConfig, fileValidationService, analyzeStreamProducer,
        resumeRepository, transactionalExecutor);
  }

  // ========== 上传成功 ==========

  @Nested
  @DisplayName("上传并分析")
  class UploadAndAnalyze {

    @Test
    @DisplayName("上传成功完整流程：验证 → 解析 → 存储 → 入库 → 发送 Stream")
    void uploadSuccessFullFlow() {
      MultipartFile file = mockFile("resume.pdf");
      when(parseService.detectContentType(file)).thenReturn("application/pdf");
      when(appConfig.getAllowedTypes()).thenReturn(List.of("application/pdf", "text/plain"));
      when(persistenceService.findExistingResume(file)).thenReturn(Optional.empty());
      when(parseService.parseResume(file)).thenReturn("张三 Java 开发工程师 5年经验");

      ResumeEntity savedResume = new ResumeEntity();
      savedResume.setId(1L);
      savedResume.setOriginalFilename("resume.pdf");
      when(storageService.uploadResume(file)).thenReturn("resumes/resume-key");
      when(storageService.getFileUrl("resumes/resume-key")).thenReturn("http://cdn/resume-key");
      when(persistenceService.saveResume(eq(file), anyString(), eq("resumes/resume-key"), eq("http://cdn/resume-key")))
          .thenReturn(savedResume);

      Map<String, Object> result = service.uploadAndAnalyze(file);

      // 验证各步骤被调用
      verify(fileValidationService).validateFile(eq(file), eq(10L * 1024 * 1024), eq("简历"));
      verify(fileValidationService).validateContentTypeByList(
          eq("application/pdf"), eq(List.of("application/pdf", "text/plain")), anyString());
      verify(parseService).parseResume(file);
      verify(storageService).uploadResume(file);
      verify(persistenceService).saveResume(eq(file), anyString(), eq("resumes/resume-key"), eq("http://cdn/resume-key"));
      verify(analyzeStreamProducer).sendAnalyzeTask(eq(1L), anyString());

      assertThat(result).containsKey("duplicate");
      assertThat(result.get("duplicate")).isEqualTo(false);
    }

    @Test
    @DisplayName("重复文件检测：已有分析结果时返回 duplicate=true + analysis")
    void duplicateResumeWithAnalysis() {
      MultipartFile file = mockFile("resume.pdf");
      when(parseService.detectContentType(file)).thenReturn("application/pdf");
      when(appConfig.getAllowedTypes()).thenReturn(List.of("application/pdf"));

      ResumeEntity existingResume = new ResumeEntity();
      existingResume.setId(5L);
      existingResume.setOriginalFilename("resume.pdf");
      existingResume.setStorageKey("old-key");
      existingResume.setStorageUrl("old-url");
      when(persistenceService.findExistingResume(file)).thenReturn(Optional.of(existingResume));

      ResumeAnalysisResponse analysisResponse = new ResumeAnalysisResponse(
          85,
          new ResumeAnalysisResponse.ScoreDetail(20, 15, 20, 12, 10),
          "优秀简历",
          List.of("经验丰富"),
          List.of(),
          "原文"
      );
      when(persistenceService.getLatestAnalysisAsDTO(5L)).thenReturn(Optional.of(analysisResponse));

      Map<String, Object> result = service.uploadAndAnalyze(file);

      assertThat(result.get("duplicate")).isEqualTo(true);
      assertThat(result).containsKey("analysis");
      // 不应重新解析或发送 Stream
      verify(parseService, never()).parseResume(any());
      verify(analyzeStreamProducer, never()).sendAnalyzeTask(anyLong(), anyString());
    }

    @Test
    @DisplayName("重复文件无分析结果：返回 duplicate=true + resume 状态")
    void duplicateResumeWithoutAnalysis() {
      MultipartFile file = mockFile("resume.pdf");
      when(parseService.detectContentType(file)).thenReturn("application/pdf");
      when(appConfig.getAllowedTypes()).thenReturn(List.of("application/pdf"));

      ResumeEntity existingResume = new ResumeEntity();
      existingResume.setId(5L);
      existingResume.setOriginalFilename("resume.pdf");
      existingResume.setStorageKey("old-key");
      existingResume.setStorageUrl("old-url");
      existingResume.setAnalyzeStatus(AsyncTaskStatus.FAILED);
      when(persistenceService.findExistingResume(file)).thenReturn(Optional.of(existingResume));
      when(persistenceService.getLatestAnalysisAsDTO(5L)).thenReturn(Optional.empty());

      Map<String, Object> result = service.uploadAndAnalyze(file);

      assertThat(result.get("duplicate")).isEqualTo(true);
      assertThat(result).containsKey("resume");
    }

    @Test
    @DisplayName("文件验证失败：BusinessException 向上传播")
    void fileValidationFails() {
      MultipartFile file = mockFile("resume.exe");
      doThrow(new BusinessException(ErrorCode.RESUME_FILE_TYPE_NOT_SUPPORTED, "不支持的文件类型"))
          .when(fileValidationService).validateFile(eq(file), anyLong(), anyString());

      assertThatThrownBy(() -> service.uploadAndAnalyze(file))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("不支持的文件类型");

      // 验证后续步骤未被调用
      verify(parseService, never()).parseResume(any());
      verify(storageService, never()).uploadResume(any());
    }

    @Test
    @DisplayName("解析文本为空：抛出 RESUME_PARSE_FAILED")
    void parseResumeReturnsEmpty() {
      MultipartFile file = mockFile("resume.pdf");
      when(parseService.detectContentType(file)).thenReturn("application/pdf");
      when(appConfig.getAllowedTypes()).thenReturn(List.of("application/pdf"));
      when(persistenceService.findExistingResume(file)).thenReturn(Optional.empty());
      when(parseService.parseResume(file)).thenReturn("");

      assertThatThrownBy(() -> service.uploadAndAnalyze(file))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("无法从文件中提取文本");
    }
  }

  // ========== 重新分析 ==========

  @Nested
  @DisplayName("重新分析简历")
  class Reanalyze {

    @Test
    @DisplayName("重新分析成功：加载简历 → 事务更新状态 → 发送 Stream")
    void reanalyzeSuccess() {
      ResumeEntity resume = new ResumeEntity();
      resume.setId(1L);
      resume.setOriginalFilename("resume.pdf");
      resume.setStorageKey("key");
      resume.setResumeText("张三 Java 开发");
      when(resumeRepository.findById(1L)).thenReturn(Optional.of(resume));

      // transactionalExecutor.run 直接执行传入的 Runnable
      org.mockito.Mockito.doAnswer(invocation -> {
        Runnable action = invocation.getArgument(0);
        action.run();
        return null;
      }).when(transactionalExecutor).run(any());

      service.reanalyze(1L);

      verify(resumeRepository).save(any());
      verify(analyzeStreamProducer).sendAnalyzeTask(eq(1L), anyString());
    }

    @Test
    @DisplayName("重新分析时简历不存在：抛出 RESUME_NOT_FOUND")
    void reanalyzeResumeNotFound() {
      when(resumeRepository.findById(99L)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.reanalyze(99L))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("简历不存在");
    }
  }

  // ========== 辅助方法 ==========

  private MultipartFile mockFile(String filename) {
    MultipartFile file = org.mockito.Mockito.mock(MultipartFile.class);
    when(file.getOriginalFilename()).thenReturn(filename);
    when(file.getSize()).thenReturn(2048L);
    when(file.getContentType()).thenReturn("application/pdf");
    return file;
  }
}
