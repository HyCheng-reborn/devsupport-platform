package interview.guide.modules.resume;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.mapper.ResumeMapper;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.resume.model.ResumeAnalysisEntity;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeAnalysisRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.ResumePersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ResumePersistenceService 单元测试
 * 覆盖去重、保存简历、保存分析、删除等核心场景
 */
@DisplayName("简历持久化服务测试")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResumePersistenceServiceTest {

  @Mock
  private ResumeRepository resumeRepository;
  @Mock
  private ResumeAnalysisRepository analysisRepository;
  @Mock
  private ResumeMapper resumeMapper;
  @Mock
  private FileHashService fileHashService;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private ResumePersistenceService service;

  @BeforeEach
  void setUp() {
    service = new ResumePersistenceService(
        resumeRepository, analysisRepository, objectMapper, resumeMapper, fileHashService);
  }

  // ========== 去重 ==========

  @Nested
  @DisplayName("简历去重")
  class FindExistingResume {

    @Test
    @DisplayName("去重命中：返回已有实体并增加访问计数")
    void duplicateFound() {
      MultipartFile file = mockFile("resume.pdf");
      when(fileHashService.calculateHash(file)).thenReturn("abc123hash");

      ResumeEntity existing = new ResumeEntity();
      existing.setId(1L);
      existing.setAccessCount(2);
      when(resumeRepository.findByFileHash("abc123hash")).thenReturn(Optional.of(existing));

      Optional<ResumeEntity> result = service.findExistingResume(file);

      assertThat(result).isPresent();
      assertThat(result.get().getAccessCount()).isEqualTo(3);
      verify(resumeRepository).save(existing);
    }

    @Test
    @DisplayName("去重未命中：返回空 Optional")
    void duplicateNotFound() {
      MultipartFile file = mockFile("new-resume.pdf");
      when(fileHashService.calculateHash(file)).thenReturn("newhash");
      when(resumeRepository.findByFileHash("newhash")).thenReturn(Optional.empty());

      Optional<ResumeEntity> result = service.findExistingResume(file);

      assertThat(result).isEmpty();
      verify(resumeRepository, never()).save(any());
    }

    @Test
    @DisplayName("计算 hash 异常时返回空（容错处理）")
    void hashCalculationFails() {
      MultipartFile file = mockFile("resume.pdf");
      when(fileHashService.calculateHash(file)).thenThrow(new RuntimeException("IO error"));

      Optional<ResumeEntity> result = service.findExistingResume(file);

      assertThat(result).isEmpty();
    }
  }

  // ========== 保存简历 ==========

  @Nested
  @DisplayName("保存简历")
  class SaveResume {

    @Test
    @DisplayName("保存简历成功：验证字段设置和 repository.save 被调用")
    void saveResumeSuccess() {
      MultipartFile file = mockFile("resume.pdf");
      when(fileHashService.calculateHash(file)).thenReturn("hash123");

      ResumeEntity savedEntity = new ResumeEntity();
      savedEntity.setId(1L);
      when(resumeRepository.save(any(ResumeEntity.class))).thenReturn(savedEntity);

      ResumeEntity result = service.saveResume(file, "简历文本", "storage-key", "http://url");

      assertThat(result.getId()).isEqualTo(1L);

      ArgumentCaptor<ResumeEntity> captor = ArgumentCaptor.forClass(ResumeEntity.class);
      verify(resumeRepository).save(captor.capture());
      ResumeEntity captured = captor.getValue();
      assertThat(captured.getFileHash()).isEqualTo("hash123");
      assertThat(captured.getOriginalFilename()).isEqualTo("resume.pdf");
      assertThat(captured.getResumeText()).isEqualTo("简历文本");
      assertThat(captured.getStorageKey()).isEqualTo("storage-key");
      assertThat(captured.getStorageUrl()).isEqualTo("http://url");
    }
  }

  // ========== 保存分析结果 ==========

  @Nested
  @DisplayName("保存分析结果")
  class SaveAnalysis {

    @Test
    @DisplayName("保存分析结果成功：JSON 序列化 strengths/suggestions 并调用 save")
    void saveAnalysisSuccess() {
      ResumeEntity resume = new ResumeEntity();
      resume.setId(1L);

      ResumeAnalysisResponse analysis = new ResumeAnalysisResponse(
          80,
          new ResumeAnalysisResponse.ScoreDetail(20, 15, 20, 12, 13),
          "良好简历",
          List.of("经验丰富", "技术全面"),
          List.of(new ResumeAnalysisResponse.Suggestion("内容", "中", "缺少项目描述", "补充项目经验")),
          "原文"
      );

      ResumeAnalysisEntity mappedEntity = new ResumeAnalysisEntity();
      when(resumeMapper.toAnalysisEntity(analysis)).thenReturn(mappedEntity);
      when(analysisRepository.save(any(ResumeAnalysisEntity.class))).thenAnswer(inv -> inv.getArgument(0));

      ResumeAnalysisEntity result = service.saveAnalysis(resume, analysis);

      assertThat(result.getResume()).isEqualTo(resume);
      assertThat(result.getStrengthsJson()).contains("经验丰富");
      assertThat(result.getSuggestionsJson()).contains("缺少项目描述");
      verify(analysisRepository).save(any(ResumeAnalysisEntity.class));
    }
  }

  // ========== 获取最新分析 ==========

  @Nested
  @DisplayName("获取最新分析")
  class GetLatestAnalysis {

    @Test
    @DisplayName("存在分析记录：返回 Optional 有值")
    void analysisExists() {
      ResumeAnalysisEntity entity = new ResumeAnalysisEntity();
      entity.setId(10L);
      entity.setOverallScore(85);
      when(analysisRepository.findFirstByResumeIdOrderByAnalyzedAtDesc(1L)).thenReturn(entity);

      Optional<ResumeAnalysisEntity> result = service.getLatestAnalysis(1L);

      assertThat(result).isPresent();
      assertThat(result.get().getOverallScore()).isEqualTo(85);
    }

    @Test
    @DisplayName("无分析记录：返回空 Optional")
    void noAnalysis() {
      when(analysisRepository.findFirstByResumeIdOrderByAnalyzedAtDesc(2L)).thenReturn(null);

      Optional<ResumeAnalysisEntity> result = service.getLatestAnalysis(2L);

      assertThat(result).isEmpty();
    }
  }

  // ========== 删除简历 ==========

  @Nested
  @DisplayName("删除简历")
  class DeleteResume {

    @Test
    @DisplayName("删除成功：先删除分析记录，再删除简历实体")
    void deleteResumeSuccess() {
      ResumeEntity resume = new ResumeEntity();
      resume.setId(1L);
      resume.setOriginalFilename("resume.pdf");
      when(resumeRepository.findById(1L)).thenReturn(Optional.of(resume));

      ResumeAnalysisEntity analysis = new ResumeAnalysisEntity();
      analysis.setId(10L);
      when(analysisRepository.findByResumeIdOrderByAnalyzedAtDesc(1L)).thenReturn(List.of(analysis));

      service.deleteResume(1L);

      verify(analysisRepository).deleteAll(List.of(analysis));
      verify(resumeRepository).delete(resume);
    }

    @Test
    @DisplayName("删除不存在的简历：抛出 RESUME_NOT_FOUND")
    void deleteResumeNotFound() {
      when(resumeRepository.findById(99L)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.deleteResume(99L))
          .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("无分析记录时只删除简历实体")
    void deleteResumeNoAnalyses() {
      ResumeEntity resume = new ResumeEntity();
      resume.setId(1L);
      resume.setOriginalFilename("resume.pdf");
      when(resumeRepository.findById(1L)).thenReturn(Optional.of(resume));
      when(analysisRepository.findByResumeIdOrderByAnalyzedAtDesc(1L)).thenReturn(List.of());

      service.deleteResume(1L);

      verify(analysisRepository, never()).deleteAll(any());
      verify(resumeRepository).delete(resume);
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
