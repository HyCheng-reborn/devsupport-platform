package interview.guide.modules.evalregression;

import interview.guide.common.result.Result;
import interview.guide.modules.evalregression.model.RegressionItemDTO;
import interview.guide.modules.evalregression.model.RegressionRunDetailDTO;
import interview.guide.modules.evalregression.model.RegressionRunRequest;
import interview.guide.modules.evalregression.model.RegressionRunSummaryDTO;
import interview.guide.modules.evalregression.service.CaseRegressionItemService;
import interview.guide.modules.evalregression.service.CaseRegressionRunService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 案例回归评测 Controller。
 * <p>
 * 统一使用 {@code /api/eval/regression/*} 前缀，只做路由 / 校验 / 委托，
 * 业务编排全部下沉到 Service；对外统一返回 {@link Result}。
 */
@RestController
@RequestMapping("/api/eval/regression")
@RequiredArgsConstructor
public class CaseRegressionController {

  private final CaseRegressionItemService itemService;
  private final CaseRegressionRunService runService;

  /**
   * 列出全部回归项
   */
  @GetMapping("/items")
  public Result<List<RegressionItemDTO>> listItems() {
    return Result.success(itemService.listItems());
  }

  /**
   * 触发一轮确定性回归运行
   */
  @PostMapping("/runs")
  public Result<RegressionRunDetailDTO> runRegression(
    @RequestBody(required = false) RegressionRunRequest request
  ) {
    Integer topK = request != null ? request.topK() : null;
    return Result.success(runService.runRegression(topK));
  }

  /**
   * 列出回归运行历史
   */
  @GetMapping("/runs")
  public Result<List<RegressionRunSummaryDTO>> listRuns() {
    return Result.success(runService.listRuns());
  }

  /**
   * 获取某次运行的详情（含逐项结果）
   */
  @GetMapping("/runs/{id}")
  public Result<RegressionRunDetailDTO> getRunDetail(@PathVariable Long id) {
    return Result.success(runService.getRunDetail(id));
  }
}
