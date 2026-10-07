package interview.guide.modules.caselibrary.model;

/**
 * 案例库请求记录
 */
public record CaseRequests() {

  /**
   * 创建草稿请求
   */
  public record CreateDraftRequest(Long sessionId, Long messageId) {}

  /**
   * 更新案例请求
   */
  public record CaseUpdateRequest(
    String title,
    String problemDescription,
    String rootCause,
    String resolutionSteps,
    String resolutionResult,
    String affectedVersions,
    String environment,
    String service,
    String aiGeneratedContent,
    String userConfirmedContent
  ) {}

  /**
   * 拒绝请求
   */
  public record RejectRequest(String remark) {}
}
