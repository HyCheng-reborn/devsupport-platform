package interview.guide.modules.evalregression.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.mapper.CaseRegressionMapper;
import interview.guide.modules.caselibrary.model.CaseEntity;
import interview.guide.modules.caselibrary.repository.CaseRepository;
import interview.guide.modules.evalregression.model.CaseRegressionItemEntity;
import interview.guide.modules.evalregression.model.CaseRegressionResultEntity;
import interview.guide.modules.evalregression.model.CaseRegressionRunEntity;
import interview.guide.modules.evalregression.model.RegressionResultDTO;
import interview.guide.modules.evalregression.model.RegressionRunDetailDTO;
import interview.guide.modules.evalregression.model.RegressionRunStatus;
import interview.guide.modules.evalregression.model.RegressionRunSummaryDTO;
import interview.guide.modules.evalregression.model.TopKSnapshotEntry;
import interview.guide.modules.evalregression.repository.CaseRegressionItemRepository;
import interview.guide.modules.evalregression.repository.CaseRegressionResultRepository;
import interview.guide.modules.evalregression.repository.CaseRegressionRunRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 案例回归运行服务（确定性版，零付费 API）。
 * <p>
 * 核心约束：<b>先算后存</b>。逐项的 embedding 查询与向量检索复用生产检索路径
 * {@link KnowledgeBaseQueryService#retrieveAndMerge}，全部在<b>数据库事务之外</b>完成；
 * 判定结果先在内存中算好，最后在一个新事务里批量写入 results 并更新 run 汇总。
 * 严禁把 embedding / 外部 HTTP 放进数据库事务（AGENTS.md 硬规则）。
 * <p>
 * 判定完全确定性，不调用任何 LLM：
 * <ol>
 *   <li>证据命中：期望证据 ID 是否出现在 top-K 召回文档 ID 中；</li>
 *   <li>要点逐字包含：每个 keyPoint 是否为召回内容拼接串的连续子串（沿用金标"逐字包含原则"，
 *       仅在比对前对拼接串与要点做空白归一，不做词形/大小写等其它放宽）；</li>
 *   <li>passed = 证据命中 且 无缺失要点。</li>
 * </ol>
 * 单项检索 / embedding 异常仅将该项记为未通过并写入 failureReason，不中断整轮。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseRegressionRunService {

  /**
   * 请求未指定 topK（为空或非正数）时使用的默认检索条数。
   * <p>回归运行是<b>离线评测、非延迟敏感</b>，默认值取较大的 20，以降低语料增多时
   * 案例自身证据被挤出 top-K 而误判为假失败的风险。
   */
  private static final int DEFAULT_TOPK = 20;
  /** topK 上限钳制值：防止异常大的 topK 造成检索 / 内存压力 */
  private static final int MAX_TOP_K = 200;
  /** 检索相似度阈值：0 表示不设阈值，最大化确定性召回 */
  private static final double MIN_SCORE = 0.0;
  /** 触发来源标识：手动运行 */
  private static final String TRIGGER_SOURCE_MANUAL = "MANUAL";

  private final CaseRegressionItemRepository itemRepository;
  private final CaseRegressionRunRepository runRepository;
  private final CaseRegressionResultRepository resultRepository;
  private final CaseRepository caseRepository;
  private final KnowledgeBaseQueryService queryService;
  private final TransactionalExecutor transactionalExecutor;
  private final RegressionJsonCodec jsonCodec;
  private final EmbeddingMetadataResolver embeddingMetadataResolver;
  private final CaseRegressionMapper mapper;

  /**
   * 运行一轮确定性回归。
   * <p>
   * <b>topK 边界说明</b>：回归复用全局 KB 检索路径
   * {@link KnowledgeBaseQueryService#retrieveAndMerge}（kbIds 传空 → KB 分支不带过滤），
   * 真实语义 embedding 下案例自身证据通常对其派生 query 排名靠前；而确定性常量向量测试
   * 因相似度并列，需用较大 topK 才能保证召回。故默认 topK 提升至 {@value #DEFAULT_TOPK}
   * 以降低语料增多时自身证据被挤出的假失败风险；同时对显式传入的 topK 以
   * {@value #MAX_TOP_K} 做上限钳制，避免异常大的取值造成检索 / 内存压力。
   *
   * @param topK 检索条数（可选，为空或非正数时使用默认值 {@value #DEFAULT_TOPK}，
   *             超过上限时钳制到 {@value #MAX_TOP_K}）
   * @return 本轮运行详情（含逐项结果）
   */
  public RegressionRunDetailDTO runRegression(Integer topK) {
    int k = (topK == null || topK <= 0) ? DEFAULT_TOPK : Math.min(topK, MAX_TOP_K);

    // 读取激活回归项（无事务的只读查询）
    List<CaseRegressionItemEntity> items = itemRepository.findByActiveTrueOrderByIdAsc();
    if (items.isEmpty()) {
      throw new BusinessException(ErrorCode.EVAL_REGRESSION_NO_ITEMS,
        "无可用回归项，请先审核发布案例后再运行回归");
    }

    List<Long> caseIds = items.stream()
      .map(CaseRegressionItemEntity::getCaseId)
      .filter(Objects::nonNull)
      .distinct()
      .toList();
    Map<Long, CaseEntity> caseMap = caseIds.isEmpty()
      ? Map.of()
      : caseRepository.findAllById(caseIds).stream()
        .collect(Collectors.toMap(CaseEntity::getId, Function.identity()));

    String embeddingModel = embeddingMetadataResolver.resolveModelName();

    // 创建 RUNNING 运行记录（独立事务，仅写库，无 embedding）
    Long runId = transactionalExecutor.call(() -> {
      CaseRegressionRunEntity run = CaseRegressionRunEntity.builder()
        .startedAt(LocalDateTime.now())
        .totalItems(items.size())
        .triggerSource(TRIGGER_SOURCE_MANUAL)
        .embeddingModel(embeddingModel)
        .status(RegressionRunStatus.RUNNING)
        .build();
      return runRepository.save(run).getId();
    });

    // 事务外：逐项 embedding + 检索 + 判定（先算）
    List<ItemOutcome> outcomes = new ArrayList<>(items.size());
    for (CaseRegressionItemEntity item : items) {
      outcomes.add(evaluateItem(item, caseMap.get(item.getCaseId()), k));
    }

    // 新事务：批量写入逐项结果 + 更新运行汇总（后存）
    transactionalExecutor.run(() -> persistRun(runId, outcomes));

    log.info("回归运行完成: runId={}, total={}, topK={}, embeddingModel={}",
      runId, outcomes.size(), k, embeddingModel);
    return buildRunDetail(runId);
  }

  /**
   * 列出回归运行历史（按开始时间倒序）。
   */
  @Transactional(readOnly = true)
  public List<RegressionRunSummaryDTO> listRuns() {
    return runRepository.findAllByOrderByStartedAtDesc().stream()
      .map(mapper::toRunSummaryDTO)
      .toList();
  }

  /**
   * 获取某次运行的详情（含逐项结果）。
   */
  @Transactional(readOnly = true)
  public RegressionRunDetailDTO getRunDetail(Long runId) {
    return buildRunDetail(runId);
  }

  // ========== 事务外：检索与判定 ==========

  private ItemOutcome evaluateItem(CaseRegressionItemEntity item, CaseEntity caseEntity, int topK) {
    List<String> expected = jsonCodec.toStringList(item.getExpectedEvidence());
    List<String> keyPoints = jsonCodec.toStringList(item.getKeyPoints());

    // 结构性无法评测：缺少查询或无期望证据 → skipped（不计入 passed/failed）
    if (item.getQuery() == null || item.getQuery().isBlank()) {
      return ItemOutcome.skipped(item.getId(), "回归项缺少查询文本，已跳过");
    }
    if (expected.isEmpty()) {
      return ItemOutcome.skipped(item.getId(), "回归项无期望证据，已跳过");
    }

    try {
      // 复用生产检索路径：KB（全局）+ 案例（按案例 service/environment/version 过滤）合并
      Map<String, String> caseContextFilter = buildCaseContextFilter(caseEntity);
      List<Document> docs = queryService.retrieveAndMerge(
        item.getQuery(), List.of(), caseContextFilter, topK, MIN_SCORE);

      List<String> retrievedIds = docs.stream()
        .map(Document::getId)
        .filter(Objects::nonNull)
        .toList();
      List<TopKSnapshotEntry> snapshot = docs.stream()
        .map(d -> new TopKSnapshotEntry(d.getId(), d.getText(),
          d.getScore() != null ? d.getScore() : 0.0))
        .toList();
      // 召回内容拼接串做空白归一（连续空白/换行折叠为单个空格），
      // 避免多 chunk 用 "\n" 拼接打断要点的连续性而误判缺失。
      String retrievedContent = normalizeWhitespace(docs.stream()
        .map(Document::getText)
        .filter(Objects::nonNull)
        .collect(Collectors.joining("\n")));

      // (a) 证据命中：期望证据 ID 是否出现在 top-K 召回 ID 中
      boolean evidenceHit = expected.stream().anyMatch(retrievedIds::contains);

      // (b) 要点逐字包含（仅做空白归一，keyPoint 亦归一后比对；matched/missing 仍记录原始要点）
      // 残留限制：要点若在 TokenTextSplitter 分块时被物理切分到不相邻的 chunk，
      // 拼接后仍无法形成连续子串，可能假失败——属已知的确定性限制。
      List<String> matched = new ArrayList<>();
      List<String> missing = new ArrayList<>();
      for (String keyPoint : keyPoints) {
        if (retrievedContent.contains(normalizeWhitespace(keyPoint))) {
          matched.add(keyPoint);
        } else {
          missing.add(keyPoint);
        }
      }

      boolean passed = evidenceHit && missing.isEmpty();
      String failureReason = passed ? null : buildFailureReason(evidenceHit, missing);
      return new ItemOutcome(item.getId(), passed, false,
        retrievedIds, matched, missing, snapshot, failureReason);
    } catch (Exception e) {
      // 单项异常不中断整轮
      log.warn("回归项检索/判定失败，记为未通过: itemId={}, error={}", item.getId(), e.getMessage(), e);
      return ItemOutcome.failure(item.getId(), "检索或判定异常: " + e.getMessage());
    }
  }

  /**
   * 依据案例的 service / environment / affectedVersions 构建案例检索上下文过滤，
   * 与生产 {@code buildCaseContextFilter} 保持一致的键名。
   */
  private Map<String, String> buildCaseContextFilter(CaseEntity caseEntity) {
    Map<String, String> filter = new HashMap<>();
    if (caseEntity == null) {
      return filter;
    }
    if (isNotBlank(caseEntity.getService())) {
      filter.put("service", caseEntity.getService().trim());
    }
    if (isNotBlank(caseEntity.getEnvironment())) {
      filter.put("environment", caseEntity.getEnvironment().trim());
    }
    if (isNotBlank(caseEntity.getAffectedVersions())) {
      filter.put("affected_versions", caseEntity.getAffectedVersions().trim());
    }
    return filter;
  }

  private String buildFailureReason(boolean evidenceHit, List<String> missing) {
    List<String> reasons = new ArrayList<>();
    if (!evidenceHit) {
      reasons.add("期望证据未在 top-K 召回中命中");
    }
    if (!missing.isEmpty()) {
      reasons.add("缺失解决要点: " + missing);
    }
    return String.join("；", reasons);
  }

  private boolean isNotBlank(String value) {
    return value != null && !value.isBlank();
  }

  /**
   * 空白归一：将连续空白字符（含换行、制表符）折叠为单个空格，并去除首尾空白。
   * <p>仅用于要点"逐字包含"判定前的归一，不改变逐字包含原则（不做词形/大小写等其它放宽）。
   */
  private String normalizeWhitespace(String text) {
    if (text == null) {
      return "";
    }
    return text.strip().replaceAll("\\s+", " ");
  }

  // ========== 事务内：写入 ==========

  private void persistRun(Long runId, List<ItemOutcome> outcomes) {
    List<CaseRegressionResultEntity> entities = new ArrayList<>(outcomes.size());
    int passed = 0;
    int failed = 0;
    int skipped = 0;
    for (ItemOutcome outcome : outcomes) {
      if (outcome.skipped()) {
        skipped++;
      } else if (outcome.passed()) {
        passed++;
      } else {
        failed++;
      }
      entities.add(CaseRegressionResultEntity.builder()
        .runId(runId)
        .itemId(outcome.itemId())
        .passed(outcome.passed())
        .retrievedEvidenceIds(jsonCodec.toJson(outcome.retrievedEvidenceIds()))
        .matchedKeyPoints(jsonCodec.toJson(outcome.matchedKeyPoints()))
        .missingKeyPoints(jsonCodec.toJson(outcome.missingKeyPoints()))
        .topkSnapshot(jsonCodec.toJson(outcome.topKSnapshot()))
        .failureReason(outcome.failureReason())
        .build());
    }
    resultRepository.saveAll(entities);

    CaseRegressionRunEntity run = runRepository.findById(runId)
      .orElseThrow(() -> new BusinessException(ErrorCode.EVAL_REGRESSION_RUN_NOT_FOUND,
        "回归运行不存在: " + runId));
    run.setTotalItems(outcomes.size());
    run.setPassed(passed);
    run.setFailed(failed);
    run.setSkipped(skipped);
    run.setFinishedAt(LocalDateTime.now());
    run.setStatus(RegressionRunStatus.COMPLETED);
    runRepository.save(run);
  }

  // ========== 读取组装 ==========

  private RegressionRunDetailDTO buildRunDetail(Long runId) {
    CaseRegressionRunEntity run = runRepository.findById(runId)
      .orElseThrow(() -> new BusinessException(ErrorCode.EVAL_REGRESSION_RUN_NOT_FOUND,
        "回归运行不存在: " + runId));

    List<CaseRegressionResultEntity> results = resultRepository.findByRunIdOrderByIdAsc(runId);
    if (results.isEmpty()) {
      return mapper.toRunDetailDTO(run, List.of());
    }

    // itemId → caseId → caseTitle 两级关联
    List<Long> itemIds = results.stream()
      .map(CaseRegressionResultEntity::getItemId)
      .filter(Objects::nonNull)
      .distinct()
      .toList();
    Map<Long, Long> itemToCase = itemIds.isEmpty()
      ? Map.of()
      : itemRepository.findAllById(itemIds).stream()
        .collect(Collectors.toMap(CaseRegressionItemEntity::getId, CaseRegressionItemEntity::getCaseId));

    List<Long> caseIds = itemToCase.values().stream().filter(Objects::nonNull).distinct().toList();
    Map<Long, String> caseTitles = caseIds.isEmpty()
      ? Map.of()
      : caseRepository.findAllById(caseIds).stream()
        .collect(Collectors.toMap(CaseEntity::getId, CaseEntity::getTitle));

    List<RegressionResultDTO> resultDTOs = results.stream()
      .map(result -> {
        Long caseId = itemToCase.get(result.getItemId());
        String title = caseId != null ? caseTitles.getOrDefault(caseId, "未知案例") : "未知案例";
        return mapper.toResultDTO(result, title);
      })
      .toList();

    return mapper.toRunDetailDTO(run, resultDTOs);
  }

  /**
   * 单项回归的内存计算结果（在事务外算好，事务内落库）。
   */
  private record ItemOutcome(
    Long itemId,
    boolean passed,
    boolean skipped,
    List<String> retrievedEvidenceIds,
    List<String> matchedKeyPoints,
    List<String> missingKeyPoints,
    List<TopKSnapshotEntry> topKSnapshot,
    String failureReason
  ) {
    static ItemOutcome skipped(Long itemId, String reason) {
      return new ItemOutcome(itemId, false, true, List.of(), List.of(), List.of(), List.of(), reason);
    }

    static ItemOutcome failure(Long itemId, String reason) {
      return new ItemOutcome(itemId, false, false, List.of(), List.of(), List.of(), List.of(), reason);
    }
  }
}
