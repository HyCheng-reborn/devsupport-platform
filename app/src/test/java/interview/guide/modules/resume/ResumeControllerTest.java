package interview.guide.modules.resume;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resume.model.ResumeDetailDTO;
import interview.guide.modules.resume.model.ResumeListItemDTO;
import interview.guide.modules.resume.service.ResumeDeleteService;
import interview.guide.modules.resume.service.ResumeHistoryService;
import interview.guide.modules.resume.service.ResumeHistoryService.ExportResult;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ResumeController 单元测试
 * 覆盖 7 个端点的正常与异常路径
 */
@DisplayName("简历控制器测试")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResumeControllerTest {

  @Mock
  private ResumeUploadService uploadService;
  @Mock
  private ResumeDeleteService deleteService;
  @Mock
  private ResumeHistoryService historyService;

  private ResumeController controller;

  @BeforeEach
  void setUp() {
    controller = new ResumeController(uploadService, deleteService, historyService);
  }

  // ========== 上传简历 ==========

  @Nested
  @DisplayName("上传简历端点")
  class UploadAndAnalyze {

    @Test
    @DisplayName("上传成功：返回 200 + Result 结构，duplicate=false")
    void uploadSuccess() {
      MultipartFile file = mockMultipartFile("resume.pdf");
      Map<String, Object> serviceResult = Map.of(
          "duplicate", false,
          "resume", Map.of("id", 1L, "filename", "resume.pdf", "analyzeStatus", "PENDING")
      );
      when(uploadService.uploadAndAnalyze(file)).thenReturn(serviceResult);

      var result = controller.uploadAndAnalyze(file);

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getCode()).isEqualTo(200);
      assertThat(result.getData()).containsKey("duplicate");
      assertThat(result.getData()).containsKey("resume");
      verify(uploadService).uploadAndAnalyze(file);
    }

    @Test
    @DisplayName("上传重复文件：返回特殊 message 提示已返回历史结果")
    void uploadDuplicate() {
      MultipartFile file = mockMultipartFile("resume.pdf");
      Map<String, Object> serviceResult = Map.of(
          "duplicate", true,
          "analysis", Map.of("overallScore", 85)
      );
      when(uploadService.uploadAndAnalyze(file)).thenReturn(serviceResult);

      var result = controller.uploadAndAnalyze(file);

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getMessage()).contains("相同简历");
      assertThat(result.getMessage()).contains("历史分析结果");
      assertThat(result.getData()).containsKey("analysis");
    }
  }

  // ========== 获取列表 ==========

  @Nested
  @DisplayName("获取简历列表端点")
  class GetAllResumes {

    @Test
    @DisplayName("获取列表成功：返回 DTO 列表")
    void getAllResumesSuccess() {
      ResumeListItemDTO dto = new ResumeListItemDTO(
          1L, "resume.pdf", 1024L, null, 1, 85, null, 0, null, null);
      when(historyService.getAllResumes()).thenReturn(List.of(dto));

      var result = controller.getAllResumes();

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getData()).hasSize(1);
      assertThat(result.getData().get(0).filename()).isEqualTo("resume.pdf");
    }

    @Test
    @DisplayName("获取列表为空：返回空列表")
    void getAllResumesEmpty() {
      when(historyService.getAllResumes()).thenReturn(List.of());

      var result = controller.getAllResumes();

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getData()).isEmpty();
    }
  }

  // ========== 获取详情 ==========

  @Nested
  @DisplayName("获取简历详情端点")
  class GetResumeDetail {

    @Test
    @DisplayName("获取详情成功：返回完整 DTO")
    void getResumeDetailSuccess() {
      ResumeDetailDTO detail = new ResumeDetailDTO(
          1L, "resume.pdf", 1024L, "application/pdf",
          "http://url", null, 1, "text", null, null,
          List.of(), List.of());
      when(historyService.getResumeDetail(1L)).thenReturn(detail);

      var result = controller.getResumeDetail(1L);

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getData().filename()).isEqualTo("resume.pdf");
    }
  }

  // ========== 导出 PDF ==========

  @Nested
  @DisplayName("导出 PDF 端点")
  class ExportPdf {

    @Test
    @DisplayName("导出 PDF 成功：返回 byte[] + Content-Disposition")
    void exportPdfSuccess() {
      byte[] pdfBytes = new byte[]{1, 2, 3};
      ExportResult exportResult = new ExportResult(pdfBytes, "简历分析报告_resume.pdf");
      when(historyService.exportAnalysisPdf(1L)).thenReturn(exportResult);

      ResponseEntity<byte[]> response = controller.exportAnalysisPdf(1L);

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(response.getBody()).isEqualTo(pdfBytes);
      assertThat(response.getHeaders().getFirst("Content-Disposition"))
          .contains("attachment");
    }

    @Test
    @DisplayName("导出 PDF 异常：返回 HTTP 500")
    void exportPdfError() {
      when(historyService.exportAnalysisPdf(anyLong()))
          .thenThrow(new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "导出失败"));

      ResponseEntity<byte[]> response = controller.exportAnalysisPdf(99L);

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  // ========== 删除简历 ==========

  @Nested
  @DisplayName("删除简历端点")
  class DeleteResume {

    @Test
    @DisplayName("删除成功：调用 deleteService 并返回 success")
    void deleteResumeSuccess() {
      var result = controller.deleteResume(1L);

      assertThat(result.isSuccess()).isTrue();
      verify(deleteService).deleteResume(1L);
    }
  }

  // ========== 重新分析 ==========

  @Nested
  @DisplayName("重新分析端点")
  class Reanalyze {

    @Test
    @DisplayName("重新分析成功：调用 uploadService.reanalyze")
    void reanalyzeSuccess() {
      var result = controller.reanalyze(1L);

      assertThat(result.isSuccess()).isTrue();
      verify(uploadService).reanalyze(1L);
    }
  }

  // ========== 健康检查 ==========

  @Nested
  @DisplayName("健康检查端点")
  class Health {

    @Test
    @DisplayName("健康检查：返回 status=UP")
    void healthCheck() {
      var result = controller.health();

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getData()).containsEntry("status", "UP");
      assertThat(result.getData()).containsKey("service");
    }
  }

  // ========== 辅助方法 ==========

  private MultipartFile mockMultipartFile(String filename) {
    MultipartFile file = org.mockito.Mockito.mock(MultipartFile.class);
    when(file.getOriginalFilename()).thenReturn(filename);
    when(file.getSize()).thenReturn(1024L);
    when(file.getContentType()).thenReturn("application/pdf");
    return file;
  }
}
