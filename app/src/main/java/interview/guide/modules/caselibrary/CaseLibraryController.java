package interview.guide.modules.caselibrary;

import interview.guide.common.result.Result;
import interview.guide.modules.caselibrary.model.CaseDTO;
import interview.guide.modules.caselibrary.model.CaseRequests.CaseUpdateRequest;
import interview.guide.modules.caselibrary.model.CaseRequests.CreateDraftRequest;
import interview.guide.modules.caselibrary.model.CaseRequests.RejectRequest;
import interview.guide.modules.caselibrary.service.CaseDraftService;
import interview.guide.modules.caselibrary.service.CaseLifecycleService;
import interview.guide.modules.caselibrary.service.CaseReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 案例库 Controller
 */
@RestController
@RequestMapping("/api/cases")
@RequiredArgsConstructor
public class CaseLibraryController {

  private final CaseDraftService draftService;
  private final CaseReviewService reviewService;
  private final CaseLifecycleService lifecycleService;

  /**
   * 从会话消息创建案例草稿
   */
  @PostMapping("/draft")
  public Result<CaseDTO> createDraft(@RequestBody CreateDraftRequest request) {
    CaseDTO dto = draftService.createDraft(request.sessionId(), request.messageId());
    return Result.success(dto);
  }

  /**
   * 列出案例（支持按状态和服务标签过滤）
   */
  @GetMapping
  public Result<List<CaseDTO>> listCases(
    @RequestParam(required = false) String status,
    @RequestParam(required = false) String service
  ) {
    List<CaseDTO> list = lifecycleService.listCases(status, service);
    return Result.success(list);
  }

  /**
   * 获取单个案例
   */
  @GetMapping("/{id}")
  public Result<CaseDTO> getCase(@PathVariable Long id) {
    CaseDTO dto = lifecycleService.getCase(id);
    return Result.success(dto);
  }

  /**
   * 更新案例（仅 DRAFT 或 REJECTED 可编辑）
   */
  @PutMapping("/{id}")
  public Result<CaseDTO> updateCase(@PathVariable Long id, @RequestBody CaseUpdateRequest request) {
    CaseDTO dto = reviewService.updateCase(id, request);
    return Result.success(dto);
  }

  /**
   * 提交审核（DRAFT → PENDING_REVIEW）
   */
  @PostMapping("/{id}/submit")
  public Result<CaseDTO> submitForReview(@PathVariable Long id) {
    CaseDTO dto = reviewService.submitForReview(id);
    return Result.success(dto);
  }

  /**
   * 审核通过（PENDING_REVIEW → PUBLISHED）
   */
  @PostMapping("/{id}/approve")
  public Result<CaseDTO> approve(@PathVariable Long id) {
    CaseDTO dto = reviewService.approve(id);
    return Result.success(dto);
  }

  /**
   * 审核拒绝（PENDING_REVIEW → REJECTED）
   */
  @PostMapping("/{id}/reject")
  public Result<CaseDTO> reject(@PathVariable Long id, @RequestBody(required = false) RejectRequest request) {
    String remark = request != null ? request.remark() : null;
    CaseDTO dto = reviewService.reject(id, remark);
    return Result.success(dto);
  }

  /**
   * 废弃案例（PUBLISHED → DEPRECATED）
   */
  @PostMapping("/{id}/deprecate")
  public Result<CaseDTO> deprecate(@PathVariable Long id) {
    CaseDTO dto = lifecycleService.deprecate(id);
    return Result.success(dto);
  }
}
