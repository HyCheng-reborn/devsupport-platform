package interview.guide.modules.evalregression.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.mapper.CaseRegressionMapper;
import interview.guide.modules.caselibrary.model.CaseEntity;
import interview.guide.modules.caselibrary.model.CaseStatus;
import interview.guide.modules.caselibrary.repository.CaseRepository;
import interview.guide.modules.evalregression.model.CaseRegressionItemEntity;
import interview.guide.modules.evalregression.model.CaseRegressionResultEntity;
import interview.guide.modules.evalregression.model.CaseRegressionRunEntity;
import interview.guide.modules.evalregression.model.RegressionResultDTO;
import interview.guide.modules.evalregression.model.RegressionRunDetailDTO;
import interview.guide.modules.evalregression.model.RegressionRunStatus;
import interview.guide.modules.evalregression.repository.CaseRegressionItemRepository;
import interview.guide.modules.evalregression.repository.CaseRegressionResultRepository;
import interview.guide.modules.evalregression.repository.CaseRegressionRunRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 案例回归运行服务单元测试（零付费，纯 Mockito）。
 * <p>
 * 覆盖确定性判定逻辑：passed = 证据命中 且 无缺失要点；证据未命中 / 要点缺失 → failed；
 * 单项检索异常仅记该项 failed 且不中断整轮；空 query / 空证据 → skipped；
 * total = passed + failed + skipped；运行汇总与逐项结果正确落库。
 * <p>
 * 采用内存态假仓储（runStore / resultStore）模拟"先算后存"的写入与读回，
 * JSON 编解码使用真实 {@link RegressionJsonCodec}，检索结果由 mock 的
 * {@link KnowledgeBaseQueryService} 构造，全程不触发任何 embedding / LLM 调用。
 */
@DisplayName("案例回归运行服务单元测试")
@ExtendWith(MockitoExtension.class)
class CaseRegressionRunServiceTest {

  @Mock private CaseRegressionItemRepository itemRepository;
  @Mock private CaseRegressionRunRepository runRepository;
  @Mock private CaseRegressionResultRepository resultRepository;
  @Mock private CaseRepository caseRepository;
  @Mock private KnowledgeBaseQueryService queryService;
  @Mock private TransactionalExecutor transactionalExecutor;
  @Mock private EmbeddingMetadataResolver embeddingMetadataResolver;
  @Mock private CaseRegressionMapper mapper;

  private final RegressionJsonCodec jsonCodec = new RegressionJsonCodec(new ObjectMapper());

  private CaseRegressionRunService service;

  // ─────────── 内存态假仓储 ───────────
  private final List<CaseRegressionItemEntity> activeItems = new ArrayList<>();
  private final List<CaseRegressionItemEntity> allItems = new ArrayList<>();
  private final List<CaseEntity> allCases = new ArrayList<>();
  private final Map<Long, CaseRegressionRunEntity> runStore = new HashMap<>();
  private final List<CaseRegressionResultEntity> resultStore = new ArrayList<>();
  private final AtomicLong runSeq = new AtomicLong(100);

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    service = new CaseRegressionRunService(
      itemRepository, runRepository, resultRepository, caseRepository,
      queryService, transactionalExecutor, jsonCodec, embeddingMetadataResolver, mapper);

    // 事务执行器：直接同步执行传入的 Supplier / Runnable
    lenient().when(transactionalExecutor.call(any())).thenAnswer(inv ->
      ((Supplier<Object>) inv.getArgument(0)).get());
    lenient().doAnswer(inv -> {
      ((Runnable) inv.getArgument(0)).run();
      return null;
    }).when(transactionalExecutor).run(any());

    // 回归项仓储
    lenient().when(itemRepository.findByActiveTrueOrderByIdAsc()).thenAnswer(inv -> activeItems);
    lenient().when(itemRepository.findAllById(any())).thenAnswer(inv ->
      filterByIds(inv.getArgument(0), allItems, CaseRegressionItemEntity::getId));

    // 案例仓储
    lenient().when(caseRepository.findAllById(any())).thenAnswer(inv ->
      filterByIds(inv.getArgument(0), allCases, CaseEntity::getId));

    // 运行仓储：save 时分配 ID 并入 store，findById 从 store 读回（同一引用，反映后续变更）
    lenient().when(runRepository.save(any(CaseRegressionRunEntity.class))).thenAnswer(inv -> {
      CaseRegressionRunEntity run = inv.getArgument(0);
      if (run.getId() == null) {
        run.setId(runSeq.incrementAndGet());
      }
      runStore.put(run.getId(), run);
      return run;
    });
    lenient().when(runRepository.findById(anyLong())).thenAnswer(inv ->
      Optional.ofNullable(runStore.get((Long) inv.getArgument(0))));

    // 结果仓储
    lenient().when(resultRepository.saveAll(any())).thenAnswer(inv -> {
      Iterable<CaseRegressionResultEntity> entities = inv.getArgument(0);
      entities.forEach(resultStore::add);
      return entities;
    });
    lenient().when(resultRepository.findByRunIdOrderByIdAsc(anyLong())).thenAnswer(inv -> {
      Long runId = inv.getArgument(0);
      return resultStore.stream().filter(r -> runId.equals(r.getRunId())).toList();
    });

    // 元数据解析器
    lenient().when(embeddingMetadataResolver.resolveModelName()).thenReturn("test-embedding-model");

    // 检索默认返回空；各用例按需覆盖
    lenient().when(queryService.retrieveAndMerge(any(), any(), any(), anyInt(), anyDouble()))
      .thenReturn(List.of());

    // Mapper：由实体真实构造 DTO，验证读回组装
    lenient().when(mapper.toResultDTO(any(), any())).thenAnswer(inv -> {
      CaseRegressionResultEntity r = inv.getArgument(0);
      String title = inv.getArgument(1);
      return new RegressionResultDTO(r.getId(), r.getItemId(), title, r.getPassed(),
        jsonCodec.toStringList(r.getRetrievedEvidenceIds()),
        jsonCodec.toStringList(r.getMatchedKeyPoints()),
        jsonCodec.toStringList(r.getMissingKeyPoints()),
        jsonCodec.toSnapshotList(r.getTopkSnapshot()),
        r.getFailureReason());
    });
    lenient().when(mapper.toRunDetailDTO(any(), any())).thenAnswer(inv -> {
      CaseRegressionRunEntity run = inv.getArgument(0);
      List<RegressionResultDTO> results = inv.getArgument(1);
      return new RegressionRunDetailDTO(run.getId(), run.getStartedAt(), run.getFinishedAt(),
        run.getTotalItems(), run.getPassed(), run.getFailed(), run.getSkipped(),
        run.getTriggerSource(), run.getEmbeddingModel(),
        run.getStatus() != null ? run.getStatus().name() : null, results);
    });
  }

  // ─────────── 辅助方法 ───────────

  private static <T> List<T> filterByIds(Iterable<Long> ids, List<T> all, Function<T, Long> idGetter) {
    Set<Long> idSet = new HashSet<>();
    ids.forEach(idSet::add);
    return all.stream().filter(t -> idSet.contains(idGetter.apply(t))).toList();
  }

  private void givenItems(CaseRegressionItemEntity... items) {
    activeItems.clear();
    allItems.clear();
    activeItems.addAll(List.of(items));
    allItems.addAll(List.of(items));
  }

  private void givenCases(CaseEntity... cases) {
    allCases.clear();
    allCases.addAll(List.of(cases));
  }

  private CaseRegressionItemEntity item(Long id, Long caseId, String query,
                                        List<String> evidence, List<String> keyPoints) {
    return CaseRegressionItemEntity.builder()
      .id(id).caseId(caseId).query(query)
      .expectedEvidence(jsonCodec.toJson(evidence))
      .keyPoints(jsonCodec.toJson(keyPoints))
      .active(true).build();
  }

  private CaseEntity caseEntity(Long id, String title, String service,
                                String environment, String affectedVersions) {
    return CaseEntity.builder()
      .id(id).title(title).service(service).environment(environment)
      .affectedVersions(affectedVersions).status(CaseStatus.PUBLISHED).build();
  }

  private Document doc(String id, String text) {
    return Document.builder().id(id).text(text).build();
  }

  private void givenRetrieval(String query, Document... docs) {
    when(queryService.retrieveAndMerge(eq(query), any(), any(), anyInt(), anyDouble()))
      .thenReturn(List.of(docs));
  }

  // ═══════════════ 无激活回归项 ═══════════════

  @Nested
  @DisplayName("无激活回归项时拒绝运行")
  class NoActiveItems {

    @Test
    @DisplayName("无激活回归项抛 EVAL_REGRESSION_NO_ITEMS，且不创建运行记录")
    void noItems_throwsBusinessException() {
      givenItems();

      assertThatThrownBy(() -> service.runRegression(5))
        .isInstanceOf(BusinessException.class)
        .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
          .isEqualTo(ErrorCode.EVAL_REGRESSION_NO_ITEMS.getCode()));

      verify(runRepository, never()).save(any());
    }
  }

  // ═══════════════ 判定逻辑 ═══════════════

  @Nested
  @DisplayName("逐项确定性判定")
  class Evaluation {

    @Test
    @DisplayName("证据命中且要点全含 → passed")
    void evidenceHitAndAllKeyPoints_passed() {
      givenItems(item(1L, 10L, "登录超时", List.of("ev-1"), List.of("重启服务", "扩容连接池")));
      givenCases(caseEntity(10L, "登录超时案例", "auth", "prod", "v1.0"));
      givenRetrieval("登录超时", doc("ev-1", "先重启服务，再扩容连接池上限"));

      RegressionRunDetailDTO detail = service.runRegression(5);

      assertThat(detail.passed()).isEqualTo(1);
      assertThat(detail.failed()).isEqualTo(0);
      assertThat(detail.skipped()).isEqualTo(0);
      RegressionResultDTO r = detail.results().getFirst();
      assertThat(r.passed()).isTrue();
      assertThat(r.retrievedEvidenceIds()).containsExactly("ev-1");
      assertThat(r.matchedKeyPoints()).containsExactly("重启服务", "扩容连接池");
      assertThat(r.missingKeyPoints()).isEmpty();
      assertThat(r.failureReason()).isNull();
    }

    @Test
    @DisplayName("证据未命中（即便要点全含）→ failed，failureReason 指出证据未命中")
    void evidenceMiss_failed() {
      givenItems(item(1L, 10L, "登录超时", List.of("ev-1"), List.of("重启服务")));
      givenCases(caseEntity(10L, "案例", "auth", "prod", null));
      // 召回的是其它证据 ID，但文本恰好包含要点
      givenRetrieval("登录超时", doc("ev-OTHER", "无关文档但提到重启服务"));

      RegressionRunDetailDTO detail = service.runRegression(5);

      assertThat(detail.failed()).isEqualTo(1);
      assertThat(detail.passed()).isZero();
      RegressionResultDTO r = detail.results().getFirst();
      assertThat(r.passed()).isFalse();
      assertThat(r.missingKeyPoints()).isEmpty();
      assertThat(r.failureReason()).contains("期望证据未在 top-K 召回中命中");
    }

    @Test
    @DisplayName("证据命中但要点缺失 → failed，missingKeyPoints 精确记录缺失项")
    void keyPointMissing_failed() {
      givenItems(item(1L, 10L, "登录超时", List.of("ev-1"), List.of("重启服务", "扩容连接池")));
      givenCases(caseEntity(10L, "案例", "auth", "prod", null));
      // 命中证据但文本只含部分要点
      givenRetrieval("登录超时", doc("ev-1", "只需重启服务即可"));

      RegressionRunDetailDTO detail = service.runRegression(5);

      assertThat(detail.failed()).isEqualTo(1);
      RegressionResultDTO r = detail.results().getFirst();
      assertThat(r.passed()).isFalse();
      assertThat(r.matchedKeyPoints()).containsExactly("重启服务");
      assertThat(r.missingKeyPoints()).containsExactly("扩容连接池");
      assertThat(r.failureReason()).contains("缺失解决要点").contains("扩容连接池");
    }

    @Test
    @DisplayName("证据未命中且要点缺失 → failed，failureReason 同时包含两类原因")
    void bothEvidenceAndKeyPointFail_failed() {
      givenItems(item(1L, 10L, "登录超时", List.of("ev-1"), List.of("重启服务")));
      givenCases(caseEntity(10L, "案例", "auth", "prod", null));
      givenRetrieval("登录超时", doc("ev-X", "完全无关内容"));

      RegressionResultDTO r = service.runRegression(5).results().getFirst();

      assertThat(r.passed()).isFalse();
      assertThat(r.missingKeyPoints()).containsExactly("重启服务");
      assertThat(r.failureReason())
        .contains("期望证据未在 top-K 召回中命中")
        .contains("缺失解决要点");
    }

    @Test
    @DisplayName("证据命中来自多个期望 ID 中任意一个即算命中")
    void anyExpectedEvidenceHit_counts() {
      givenItems(item(1L, 10L, "登录超时", List.of("ev-1", "ev-2"), List.of("重启服务")));
      givenCases(caseEntity(10L, "案例", "auth", "prod", null));
      givenRetrieval("登录超时", doc("noise", "噪声"), doc("ev-2", "含重启服务的分块"));

      RegressionResultDTO r = service.runRegression(5).results().getFirst();

      assertThat(r.passed()).isTrue();
      assertThat(r.retrievedEvidenceIds()).containsExactly("noise", "ev-2");
    }

    @Test
    @DisplayName("要点跨 chunk 用 \\n 拼接（空白归一后）仍命中 → passed")
    void keyPointAcrossNewlineJoin_matched() {
      // 要点在源文中以空白相连，分块后被拆到两个 chunk，拼接串用 \n 连接
      givenItems(item(1L, 10L, "登录超时", List.of("ev-1"), List.of("重启服务 扩容连接池")));
      givenCases(caseEntity(10L, "案例", "auth", "prod", null));
      // 两个 chunk 拼接为 "重启服务\n扩容连接池"，空白归一折叠为单空格
      givenRetrieval("登录超时", doc("ev-1", "重启服务"), doc("ev-2", "扩容连接池"));

      RegressionResultDTO r = service.runRegression(5).results().getFirst();

      assertThat(r.passed()).isTrue();
      assertThat(r.matchedKeyPoints()).containsExactly("重启服务 扩容连接池");
      assertThat(r.missingKeyPoints()).isEmpty();
    }
  }

  // ═══════════════ 单项异常隔离 ═══════════════

  @Nested
  @DisplayName("单项异常不中断整轮")
  class ItemExceptionIsolation {

    @Test
    @DisplayName("某项检索抛异常 → 该项 failed 且带 failureReason，其余项继续评测")
    void oneItemThrows_othersContinue() {
      givenItems(
        item(1L, 10L, "会异常的查询", List.of("ev-1"), List.of("要点")),
        item(2L, 20L, "正常查询", List.of("ev-2"), List.of("重启服务")));
      givenCases(caseEntity(10L, "异常案例", "auth", "prod", null),
        caseEntity(20L, "正常案例", "billing", "prod", null));

      when(queryService.retrieveAndMerge(eq("会异常的查询"), any(), any(), anyInt(), anyDouble()))
        .thenThrow(new RuntimeException("embedding 服务不可用"));
      givenRetrieval("正常查询", doc("ev-2", "执行重启服务"));

      RegressionRunDetailDTO detail = service.runRegression(5);

      assertThat(detail.totalItems()).isEqualTo(2);
      assertThat(detail.passed()).isEqualTo(1);
      assertThat(detail.failed()).isEqualTo(1);

      RegressionResultDTO failedItem = detail.results().stream()
        .filter(r -> r.itemId().equals(1L)).findFirst().orElseThrow();
      RegressionResultDTO passedItem = detail.results().stream()
        .filter(r -> r.itemId().equals(2L)).findFirst().orElseThrow();

      assertThat(failedItem.passed()).isFalse();
      assertThat(failedItem.failureReason())
        .contains("检索或判定异常")
        .contains("embedding 服务不可用");
      assertThat(passedItem.passed()).isTrue();
    }
  }

  // ═══════════════ skipped 语义与 total ═══════════════

  @Nested
  @DisplayName("skipped 语义与 total 统计")
  class SkippedSemantics {

    @Test
    @DisplayName("空 query 或空 expectedEvidence 的项被跳过，不触发检索")
    void blankQueryOrEmptyEvidence_skipped() {
      givenItems(
        item(1L, 10L, "   ", List.of("ev-1"), List.of("要点")),   // 空 query
        item(2L, 20L, "有效查询", List.of(), List.of("要点")),      // 空证据
        item(3L, 30L, "正常查询", List.of("ev-3"), List.of("重启服务")));
      givenCases(caseEntity(10L, "空查询案例", "a", "p", null),
        caseEntity(20L, "空证据案例", "b", "p", null),
        caseEntity(30L, "正常案例", "c", "p", null));
      givenRetrieval("正常查询", doc("ev-3", "执行重启服务"));

      RegressionRunDetailDTO detail = service.runRegression(5);

      assertThat(detail.totalItems()).isEqualTo(3);
      assertThat(detail.passed()).isEqualTo(1);
      assertThat(detail.failed()).isZero();
      assertThat(detail.skipped()).isEqualTo(2);

      // total = passed + failed + skipped
      assertThat(detail.totalItems())
        .isEqualTo(detail.passed() + detail.failed() + detail.skipped());

      // 被跳过的项不应触发检索
      verify(queryService, never()).retrieveAndMerge(eq("   "), any(), any(), anyInt(), anyDouble());
      verify(queryService, never()).retrieveAndMerge(eq("有效查询"), any(), any(), anyInt(), anyDouble());
      verify(queryService, times(1)).retrieveAndMerge(eq("正常查询"), any(), any(), anyInt(), anyDouble());

      RegressionResultDTO skippedBlankQuery = detail.results().stream()
        .filter(r -> r.itemId().equals(1L)).findFirst().orElseThrow();
      RegressionResultDTO skippedEmptyEvidence = detail.results().stream()
        .filter(r -> r.itemId().equals(2L)).findFirst().orElseThrow();
      assertThat(skippedBlankQuery.passed()).isFalse();
      assertThat(skippedBlankQuery.failureReason()).contains("缺少查询文本");
      assertThat(skippedEmptyEvidence.passed()).isFalse();
      assertThat(skippedEmptyEvidence.failureReason()).contains("无期望证据");
    }
  }

  // ═══════════════ 运行汇总与落库 ═══════════════

  @Nested
  @DisplayName("运行汇总字段与结果落库")
  class RunSummaryAndPersistence {

    @Test
    @DisplayName("运行记录状态置为 COMPLETED，汇总字段与触发来源正确")
    void runSummaryFields() {
      givenItems(
        item(1L, 10L, "通过项", List.of("ev-1"), List.of("重启服务")),
        item(2L, 20L, "失败项", List.of("ev-2"), List.of("扩容连接池")),
        item(3L, 30L, "", List.of("ev-3"), List.of("要点")));
      givenCases(caseEntity(10L, "A", "a", "p", null),
        caseEntity(20L, "B", "b", "p", null),
        caseEntity(30L, "C", "c", "p", null));
      givenRetrieval("通过项", doc("ev-1", "重启服务"));
      givenRetrieval("失败项", doc("ev-X", "无关"));

      RegressionRunDetailDTO detail = service.runRegression(5);

      assertThat(detail.status()).isEqualTo(RegressionRunStatus.COMPLETED.name());
      assertThat(detail.triggerSource()).isEqualTo("MANUAL");
      assertThat(detail.embeddingModel()).isEqualTo("test-embedding-model");
      assertThat(detail.totalItems()).isEqualTo(3);
      assertThat(detail.passed()).isEqualTo(1);
      assertThat(detail.failed()).isEqualTo(1);
      assertThat(detail.skipped()).isEqualTo(1);
      assertThat(detail.startedAt()).isNotNull();
      assertThat(detail.finishedAt()).isNotNull();

      // 落库的运行实体与返回 DTO 一致
      CaseRegressionRunEntity persistedRun = runStore.get(detail.id());
      assertThat(persistedRun.getStatus()).isEqualTo(RegressionRunStatus.COMPLETED);
      assertThat(persistedRun.getPassed()).isEqualTo(1);
      assertThat(persistedRun.getFailed()).isEqualTo(1);
      assertThat(persistedRun.getSkipped()).isEqualTo(1);
    }

    @Test
    @DisplayName("逐项结果全部落库，字段与判定一致，top-K 快照可反序列化")
    void resultsPersistedPerItem() {
      givenItems(item(1L, 10L, "通过项", List.of("ev-1"), List.of("重启服务")));
      givenCases(caseEntity(10L, "A", "auth", "prod", "v1"));
      givenRetrieval("通过项", doc("ev-1", "执行重启服务"));

      RegressionRunDetailDTO detail = service.runRegression(5);

      assertThat(resultStore).hasSize(1);
      CaseRegressionResultEntity persisted = resultStore.getFirst();
      assertThat(persisted.getRunId()).isEqualTo(detail.id());
      assertThat(persisted.getItemId()).isEqualTo(1L);
      assertThat(persisted.getPassed()).isTrue();
      assertThat(jsonCodec.toStringList(persisted.getRetrievedEvidenceIds())).containsExactly("ev-1");
      assertThat(jsonCodec.toStringList(persisted.getMatchedKeyPoints())).containsExactly("重启服务");
      assertThat(jsonCodec.toStringList(persisted.getMissingKeyPoints())).isEmpty();
      assertThat(jsonCodec.toSnapshotList(persisted.getTopkSnapshot()))
        .singleElement()
        .satisfies(entry -> {
          assertThat(entry.evidenceId()).isEqualTo("ev-1");
          assertThat(entry.content()).isEqualTo("执行重启服务");
        });
      // 逐项结果 DTO 携带关联案例标题
      assertThat(detail.results().getFirst().caseTitle()).isEqualTo("A");
    }
  }

  // ═══════════════ topK 与检索上下文 ═══════════════

  @Nested
  @DisplayName("检索参数与案例上下文过滤")
  class RetrievalParams {

    @Test
    @DisplayName("topK 为空时使用默认值 20，KB 列表为空、阈值为 0.0")
    void nullTopK_usesDefault() {
      givenItems(item(1L, 10L, "查询", List.of("ev-1"), List.of("要点")));
      givenCases(caseEntity(10L, "A", "auth", "prod", null));
      givenRetrieval("查询", doc("ev-1", "要点内容"));

      service.runRegression(null);

      verify(queryService).retrieveAndMerge(eq("查询"), eq(List.of()), any(), eq(20), eq(0.0));
    }

    @Test
    @DisplayName("topK 非正数时回退默认值 20")
    void nonPositiveTopK_usesDefault() {
      givenItems(item(1L, 10L, "查询", List.of("ev-1"), List.of("要点")));
      givenCases(caseEntity(10L, "A", "auth", "prod", null));
      givenRetrieval("查询", doc("ev-1", "要点内容"));

      service.runRegression(0);

      verify(queryService).retrieveAndMerge(eq("查询"), eq(List.of()), any(), eq(20), eq(0.0));
    }

    @Test
    @DisplayName("超大 topK 被钳制到上限 200")
    void oversizedTopK_clampedToMax() {
      givenItems(item(1L, 10L, "查询", List.of("ev-1"), List.of("要点")));
      givenCases(caseEntity(10L, "A", "auth", "prod", null));
      givenRetrieval("查询", doc("ev-1", "要点内容"));

      service.runRegression(500);

      verify(queryService).retrieveAndMerge(eq("查询"), eq(List.of()), any(), eq(200), eq(0.0));
    }

    @Test
    @DisplayName("显式 topK 透传至检索")
    void explicitTopK_passedThrough() {
      givenItems(item(1L, 10L, "查询", List.of("ev-1"), List.of("要点")));
      givenCases(caseEntity(10L, "A", "auth", "prod", null));
      givenRetrieval("查询", doc("ev-1", "要点内容"));

      service.runRegression(8);

      verify(queryService).retrieveAndMerge(eq("查询"), eq(List.of()), any(), eq(8), eq(0.0));
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("案例上下文过滤按 service/environment/affected_versions 构建")
    void caseContextFilterBuilt() {
      givenItems(item(1L, 10L, "查询", List.of("ev-1"), List.of("要点")));
      givenCases(caseEntity(10L, "A", "auth", "prod", "v2.1"));
      givenRetrieval("查询", doc("ev-1", "要点内容"));

      service.runRegression(5);

      ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
      verify(queryService).retrieveAndMerge(eq("查询"), eq(List.of()), captor.capture(), eq(5), eq(0.0));
      assertThat(captor.getValue())
        .containsEntry("service", "auth")
        .containsEntry("environment", "prod")
        .containsEntry("affected_versions", "v2.1");
    }
  }

  // ═══════════════ 读取接口 ═══════════════

  @Nested
  @DisplayName("运行历史与详情读取")
  class ReadApis {

    @Test
    @DisplayName("getRunDetail 运行不存在时抛 EVAL_REGRESSION_RUN_NOT_FOUND")
    void getRunDetail_notFound() {
      when(runRepository.findById(999L)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.getRunDetail(999L))
        .isInstanceOf(BusinessException.class)
        .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
          .isEqualTo(ErrorCode.EVAL_REGRESSION_RUN_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("getRunDetail 返回运行汇总与逐项结果")
    void getRunDetail_returnsAssembled() {
      givenItems(item(1L, 10L, "查询", List.of("ev-1"), List.of("要点")));
      givenCases(caseEntity(10L, "A", "auth", "prod", null));
      givenRetrieval("查询", doc("ev-1", "要点内容"));
      RegressionRunDetailDTO created = service.runRegression(5);

      RegressionRunDetailDTO fetched = service.getRunDetail(created.id());

      assertThat(fetched.id()).isEqualTo(created.id());
      assertThat(fetched.results()).hasSize(1);
      assertThat(fetched.results().getFirst().passed()).isTrue();
    }
  }
}
