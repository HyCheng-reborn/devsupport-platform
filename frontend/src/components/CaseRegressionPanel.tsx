import { useCallback, useEffect, useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import {
  AlertCircle,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Inbox,
  Layers,
  Loader2,
  Play,
  RefreshCw,
  Target,
  XCircle,
  History,
  ListChecks,
  Cpu,
  SkipForward,
} from 'lucide-react';
import {
  getRegressionItems,
  getRegressionRunDetail,
  getRegressionRuns,
  runRegression,
} from '../api/eval';
import type {
  RegressionItemDTO,
  RegressionResultDTO,
  RegressionRunDetailDTO,
  RegressionRunSummaryDTO,
} from '../types/eval';
import {
  calcPassRate,
  formatRate,
  formatTimestamp,
  getRunStatusMeta,
  isEvidenceHit,
} from '../utils/regression';
import { caseStatusLabels, caseStatusStyles } from '../utils/caseStatus';
import type { CaseStatus } from '../types/cases';

// ── 小组件 ────────────────────────────────────────────

function SectionTitle({ icon, title, hint }: { icon: React.ReactNode; title: string; hint?: string }) {
  return (
    <div className="flex items-center gap-2 mb-3">
      <span className="text-primary-500">{icon}</span>
      <h3 className="text-base font-display font-semibold text-slate-800 dark:text-white">{title}</h3>
      {hint && <span className="text-xs text-slate-400 dark:text-slate-500">{hint}</span>}
    </div>
  );
}

function CaseStatusBadge({ status }: { status: string }) {
  const style = caseStatusStyles[status as CaseStatus] ?? 'bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300';
  const label = caseStatusLabels[status as CaseStatus] ?? status;
  return (
    <span className={`px-2 py-0.5 rounded-full text-xs font-medium whitespace-nowrap ${style}`}>
      {label}
    </span>
  );
}

// ── 回归项列表 ────────────────────────────────────────

function RegressionItemList({ items }: { items: RegressionItemDTO[] }) {
  if (items.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-10 text-center">
        <Inbox className="w-10 h-10 text-slate-300 dark:text-slate-600 mb-3" />
        <p className="text-sm text-slate-500 dark:text-slate-400">暂无回归项</p>
        <p className="text-xs text-slate-400 dark:text-slate-500 mt-1">
          发布案例后会自动纳入回归评测数据集
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {items.map((item) => (
        <div
          key={item.id}
          className="p-4 rounded-xl border border-slate-100 dark:border-slate-700/50 bg-slate-50/50 dark:bg-slate-800/40"
        >
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-2 flex-wrap">
                <span className="text-sm font-semibold text-slate-800 dark:text-slate-100 truncate">
                  {item.caseTitle}
                </span>
                <CaseStatusBadge status={item.caseStatus} />
                <span
                  className={`px-2 py-0.5 rounded-full text-xs font-medium ${
                    item.active
                      ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300'
                      : 'bg-slate-200 text-slate-500 dark:bg-slate-700 dark:text-slate-400'
                  }`}
                >
                  {item.active ? '启用' : '停用'}
                </span>
              </div>
              <p className="text-sm text-slate-600 dark:text-slate-300 mt-2 line-clamp-2">
                <span className="text-slate-400 dark:text-slate-500">查询：</span>
                {item.query}
              </p>
              <div className="mt-2 flex flex-wrap items-center gap-2 text-xs">
                <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md bg-slate-100 dark:bg-slate-700/50 text-slate-600 dark:text-slate-300">
                  <Target className="w-3.5 h-3.5" />
                  期望证据 {item.expectedEvidence?.length ?? 0}
                </span>
                <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md bg-slate-100 dark:bg-slate-700/50 text-slate-600 dark:text-slate-300">
                  <ListChecks className="w-3.5 h-3.5" />
                  关键要点 {item.keyPoints?.length ?? 0}
                </span>
                <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md bg-slate-100 dark:bg-slate-700/50 text-slate-600 dark:text-slate-300">
                  <Cpu className="w-3.5 h-3.5" />
                  {item.embeddingModel}
                  {item.embeddingDimension ? ` · ${item.embeddingDimension}维` : ''}
                </span>
              </div>
            </div>
          </div>
        </div>
      ))}
    </div>
  );
}

// ── 历史运行列表 ──────────────────────────────────────

function RunHistoryList({
  runs,
  selectedId,
  onSelect,
}: {
  runs: RegressionRunSummaryDTO[];
  selectedId: number | null;
  onSelect: (id: number) => void;
}) {
  if (runs.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-8 text-center">
        <History className="w-9 h-9 text-slate-300 dark:text-slate-600 mb-2" />
        <p className="text-sm text-slate-500 dark:text-slate-400">暂无运行历史</p>
      </div>
    );
  }

  return (
    <div className="space-y-2">
      {runs.map((run) => {
        const meta = getRunStatusMeta(run.status);
        const rate = calcPassRate(run.passed, run.totalItems);
        const active = run.id === selectedId;
        return (
          <button
            key={run.id}
            onClick={() => onSelect(run.id)}
            className={`w-full text-left p-3 rounded-xl border transition-colors ${
              active
                ? 'border-primary-400 dark:border-primary-500 bg-primary-50/60 dark:bg-primary-900/20'
                : 'border-slate-100 dark:border-slate-700/50 bg-white dark:bg-slate-800/40 hover:border-slate-200 dark:hover:border-slate-600'
            }`}
          >
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2 min-w-0">
                <span className={`px-2 py-0.5 rounded-full text-xs font-semibold whitespace-nowrap ${meta.color}`}>
                  {meta.text}
                </span>
                <span className="text-sm text-slate-600 dark:text-slate-300 truncate">
                  {formatTimestamp(run.startedAt)}
                </span>
              </div>
              <div className="flex items-center gap-2 flex-shrink-0">
                <span className="text-xs font-semibold text-slate-700 dark:text-slate-200">
                  通过率 {formatRate(rate)}
                </span>
                <span className="text-xs text-slate-400 dark:text-slate-500">
                  {run.passed}/{run.totalItems}
                </span>
              </div>
            </div>
            <div className="mt-1.5 flex items-center gap-3 text-xs text-slate-400 dark:text-slate-500">
              <span className="inline-flex items-center gap-1">
                <CheckCircle2 className="w-3.5 h-3.5 text-emerald-500" />
                {run.passed}
              </span>
              <span className="inline-flex items-center gap-1">
                <XCircle className="w-3.5 h-3.5 text-red-500" />
                {run.failed}
              </span>
              <span className="inline-flex items-center gap-1">
                <SkipForward className="w-3.5 h-3.5 text-slate-400" />
                {run.skipped}
              </span>
              {run.embeddingModel && (
                <span className="inline-flex items-center gap-1">
                  <Cpu className="w-3.5 h-3.5" />
                  {run.embeddingModel}
                </span>
              )}
            </div>
          </button>
        );
      })}
    </div>
  );
}

// ── 逐项结果 ──────────────────────────────────────────

function ResultRow({ result, expectedEvidence }: { result: RegressionResultDTO; expectedEvidence: string[] }) {
  const [expanded, setExpanded] = useState(false);
  const hit = isEvidenceHit(result.retrievedEvidenceIds, expectedEvidence);

  return (
    <div
      className={`rounded-xl border p-4 ${
        result.passed
          ? 'border-emerald-100 dark:border-emerald-900/40 bg-emerald-50/40 dark:bg-emerald-900/10'
          : 'border-red-100 dark:border-red-900/40 bg-red-50/40 dark:bg-red-900/10'
      }`}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="flex items-start gap-2 min-w-0">
          {result.passed ? (
            <CheckCircle2 className="w-5 h-5 text-emerald-500 flex-shrink-0 mt-0.5" />
          ) : (
            <XCircle className="w-5 h-5 text-red-500 flex-shrink-0 mt-0.5" />
          )}
          <div className="min-w-0">
            <p className="text-sm font-semibold text-slate-800 dark:text-slate-100 truncate">
              {result.caseTitle}
            </p>
            <div className="mt-1 flex items-center gap-2 flex-wrap text-xs">
              <span
                className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-md ${
                  hit
                    ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300'
                    : 'bg-slate-100 text-slate-500 dark:bg-slate-700/50 dark:text-slate-400'
                }`}
              >
                <Target className="w-3.5 h-3.5" />
                证据{hit ? '命中' : '未命中'}
              </span>
              <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md bg-slate-100 text-slate-600 dark:bg-slate-700/50 dark:text-slate-300">
                要点 {result.matchedKeyPoints?.length ?? 0}/{(result.matchedKeyPoints?.length ?? 0) + (result.missingKeyPoints?.length ?? 0)}
              </span>
            </div>
          </div>
        </div>
        {result.topKSnapshot?.length > 0 && (
          <button
            onClick={() => setExpanded(!expanded)}
            className="flex items-center gap-1 text-xs text-primary-600 dark:text-primary-400 hover:text-primary-700 dark:hover:text-primary-300 flex-shrink-0"
          >
            {expanded ? <ChevronDown className="w-4 h-4" /> : <ChevronRight className="w-4 h-4" />}
            召回快照
          </button>
        )}
      </div>

      {/* 检索到的证据 ID */}
      {result.retrievedEvidenceIds?.length > 0 && (
        <div className="mt-3">
          <p className="text-xs text-slate-500 dark:text-slate-400 mb-1">检索到的证据 ID：</p>
          <div className="flex flex-wrap gap-1.5">
            {result.retrievedEvidenceIds.map((id) => {
              const isExpected = expectedEvidence.includes(id);
              return (
                <span
                  key={id}
                  className={`px-2 py-0.5 rounded-md text-xs font-mono ${
                    isExpected
                      ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300'
                      : 'bg-slate-100 text-slate-500 dark:bg-slate-700/50 dark:text-slate-400'
                  }`}
                >
                  {id}
                </span>
              );
            })}
          </div>
        </div>
      )}

      {/* 缺失要点 */}
      {result.missingKeyPoints?.length > 0 && (
        <div className="mt-3">
          <p className="text-xs text-red-500 dark:text-red-400 mb-1">缺失要点：</p>
          <ul className="space-y-0.5">
            {result.missingKeyPoints.map((p, i) => (
              <li key={i} className="text-xs text-slate-600 dark:text-slate-300 flex items-start gap-1.5">
                <span className="text-red-400 mt-0.5">•</span>
                {p}
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* 失败原因 */}
      {result.failureReason && (
        <div className="mt-3 flex items-start gap-2 p-2.5 rounded-lg bg-red-50 dark:bg-red-900/20 border border-red-100 dark:border-red-900/40">
          <AlertCircle className="w-4 h-4 text-red-500 flex-shrink-0 mt-0.5" />
          <p className="text-xs text-red-700 dark:text-red-300">{result.failureReason}</p>
        </div>
      )}

      {/* top-K 召回快照 */}
      <AnimatePresence>
        {expanded && result.topKSnapshot?.length > 0 && (
          <motion.div
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: 'auto' }}
            exit={{ opacity: 0, height: 0 }}
            className="overflow-hidden"
          >
            <div className="mt-3 space-y-2">
              <p className="text-xs text-slate-500 dark:text-slate-400">Top-K 召回快照：</p>
              {result.topKSnapshot.map((entry, idx) => {
                const isExpected = expectedEvidence.includes(entry.evidenceId);
                return (
                  <div
                    key={`${entry.evidenceId}-${idx}`}
                    className="p-2.5 rounded-lg bg-white dark:bg-slate-800/60 border border-slate-100 dark:border-slate-700/50"
                  >
                    <div className="flex items-center justify-between gap-2 mb-1">
                      <span
                        className={`px-1.5 py-0.5 rounded text-xs font-mono ${
                          isExpected
                            ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300'
                            : 'bg-slate-100 text-slate-500 dark:bg-slate-700/50 dark:text-slate-400'
                        }`}
                      >
                        #{idx + 1} {entry.evidenceId}
                      </span>
                      <span className="text-xs font-semibold text-slate-600 dark:text-slate-300">
                        score {typeof entry.score === 'number' ? entry.score.toFixed(4) : entry.score}
                      </span>
                    </div>
                    <p className="text-xs text-slate-600 dark:text-slate-300 whitespace-pre-wrap line-clamp-4">
                      {entry.content}
                    </p>
                  </div>
                );
              })}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

// ── 运行详情 ──────────────────────────────────────────

function RunDetail({
  detail,
  items,
}: {
  detail: RegressionRunDetailDTO;
  items: RegressionItemDTO[];
}) {
  const meta = getRunStatusMeta(detail.status);
  const rate = calcPassRate(detail.passed, detail.totalItems);

  // 建立 itemId -> expectedEvidence 映射，用于命中判定
  const expectedByItemId = new Map<number, string[]>();
  items.forEach((it) => expectedByItemId.set(it.id, it.expectedEvidence ?? []));

  return (
    <div className="space-y-4">
      {/* 汇总 */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        <SummaryCell label="状态" value={meta.text} />
        <SummaryCell label="通过率" value={formatRate(rate)} accent />
        <SummaryCell label="通过/失败/跳过" value={`${detail.passed}/${detail.failed}/${detail.skipped}`} />
        <SummaryCell label="开始时间" value={formatTimestamp(detail.startedAt)} />
      </div>
      <div className="flex flex-wrap gap-2 text-xs text-slate-500 dark:text-slate-400">
        {detail.embeddingModel && (
          <span className="inline-flex items-center gap-1 px-2 py-1 rounded-lg bg-slate-100 dark:bg-slate-700/50">
            <Cpu className="w-3.5 h-3.5" />
            {detail.embeddingModel}
          </span>
        )}
        {detail.triggerSource && (
          <span className="inline-flex items-center gap-1 px-2 py-1 rounded-lg bg-slate-100 dark:bg-slate-700/50">
            触发来源：{detail.triggerSource}
          </span>
        )}
        <span className="inline-flex items-center gap-1 px-2 py-1 rounded-lg bg-slate-100 dark:bg-slate-700/50">
          结束：{formatTimestamp(detail.finishedAt)}
        </span>
      </div>

      {/* 逐项结果 */}
      <div className="space-y-3">
        {detail.results?.length > 0 ? (
          detail.results.map((r) => (
            <ResultRow
              key={r.id}
              result={r}
              expectedEvidence={expectedByItemId.get(r.itemId) ?? []}
            />
          ))
        ) : (
          <p className="text-sm text-slate-400 dark:text-slate-500 py-4 text-center">该次运行暂无逐项结果</p>
        )}
      </div>
    </div>
  );
}

function SummaryCell({ label, value, accent }: { label: string; value: string; accent?: boolean }) {
  return (
    <div className="px-3 py-2 rounded-lg bg-slate-50 dark:bg-slate-800/50">
      <p className="text-xs text-slate-500 dark:text-slate-400">{label}</p>
      <p className={`text-sm font-semibold truncate ${accent ? 'text-primary-600 dark:text-primary-400' : 'text-slate-700 dark:text-slate-200'}`}>
        {value}
      </p>
    </div>
  );
}

// ── 主面板 ────────────────────────────────────────────

export default function CaseRegressionPanel() {
  const [items, setItems] = useState<RegressionItemDTO[]>([]);
  const [runs, setRuns] = useState<RegressionRunSummaryDTO[]>([]);
  const [itemsLoading, setItemsLoading] = useState(true);
  const [runsLoading, setRunsLoading] = useState(true);
  const [running, setRunning] = useState(false);
  const [runError, setRunError] = useState<string | null>(null);
  const [runNotice, setRunNotice] = useState<string | null>(null);
  const [selectedRunId, setSelectedRunId] = useState<number | null>(null);
  const [detail, setDetail] = useState<RegressionRunDetailDTO | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const loadItems = useCallback(async () => {
    setItemsLoading(true);
    try {
      const data = await getRegressionItems();
      setItems(data ?? []);
    } catch (err) {
      console.error('加载回归项失败', err);
    } finally {
      setItemsLoading(false);
    }
  }, []);

  const loadRuns = useCallback(async () => {
    setRunsLoading(true);
    try {
      const data = await getRegressionRuns();
      setRuns(data ?? []);
    } catch (err) {
      console.error('加载运行历史失败', err);
    } finally {
      setRunsLoading(false);
    }
  }, []);

  useEffect(() => {
    loadItems();
    loadRuns();
  }, [loadItems, loadRuns]);

  const loadDetail = useCallback(async (id: number) => {
    setSelectedRunId(id);
    setDetailLoading(true);
    try {
      const data = await getRegressionRunDetail(id);
      setDetail(data);
    } catch (err) {
      console.error('加载运行详情失败', err);
      setDetail(null);
    } finally {
      setDetailLoading(false);
    }
  }, []);

  const handleRun = async () => {
    if (running) return;
    setRunning(true);
    setRunError(null);
    setRunNotice(null);
    try {
      const data = await runRegression();
      setRunNotice(`回归运行完成：通过率 ${formatRate(calcPassRate(data.passed, data.totalItems))}（${data.passed}/${data.totalItems}）`);
      setDetail(data);
      setSelectedRunId(data.id);
      await Promise.all([loadRuns(), loadItems()]);
    } catch (err) {
      // request.ts 拦截器已将 BusinessException 的中文 message 转为 Error
      setRunError(err instanceof Error ? err.message : '运行回归失败，请重试');
    } finally {
      setRunning(false);
    }
  };

  const hasItems = items.length > 0;

  return (
    <motion.div
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.4 }}
      className="dark-card overflow-hidden"
    >
      {/* 头部 */}
      <div className="p-6 border-b border-slate-100 dark:border-slate-700/50">
        <div className="flex items-start justify-between gap-4">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-emerald-500 to-teal-600 flex items-center justify-center text-white shadow-lg shadow-emerald-500/20">
              <RefreshCw className="w-5 h-5" />
            </div>
            <div>
              <h3 className="text-lg font-display font-semibold text-slate-900 dark:text-white">
                案例回归评测
              </h3>
              <p className="text-sm text-slate-500 dark:text-slate-400 mt-0.5">
                基于已发布案例的确定性证据 ID 校验，验证向量检索质量是否回归
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2 flex-shrink-0">
            <button
              onClick={() => { loadItems(); loadRuns(); }}
              disabled={itemsLoading || runsLoading}
              className="p-2 rounded-lg text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors disabled:opacity-50"
              title="刷新"
            >
              <RefreshCw className={`w-4 h-4 ${(itemsLoading || runsLoading) ? 'animate-spin' : ''}`} />
            </button>
            <motion.button
              onClick={handleRun}
              disabled={running}
              whileHover={{ scale: running ? 1 : 1.02 }}
              whileTap={{ scale: running ? 1 : 0.98 }}
              className="flex items-center gap-2 px-4 py-2 bg-emerald-500 text-white rounded-lg hover:bg-emerald-600 transition-colors text-sm font-medium disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {running ? <Loader2 className="w-4 h-4 animate-spin" /> : <Play className="w-4 h-4" />}
              {running ? '运行中…' : '运行回归'}
            </motion.button>
          </div>
        </div>

        {/* 运行反馈 */}
        <AnimatePresence>
          {runError && (
            <motion.div
              initial={{ opacity: 0, height: 0 }}
              animate={{ opacity: 1, height: 'auto' }}
              exit={{ opacity: 0, height: 0 }}
              className="overflow-hidden"
            >
              <div className="mt-4 flex items-start gap-2 p-3 rounded-lg bg-red-50 dark:bg-red-900/20 border border-red-100 dark:border-red-900/40">
                <AlertCircle className="w-4 h-4 text-red-500 flex-shrink-0 mt-0.5" />
                <p className="text-sm text-red-700 dark:text-red-300">{runError}</p>
              </div>
            </motion.div>
          )}
          {runNotice && !runError && (
            <motion.div
              initial={{ opacity: 0, height: 0 }}
              animate={{ opacity: 1, height: 'auto' }}
              exit={{ opacity: 0, height: 0 }}
              className="overflow-hidden"
            >
              <div className="mt-4 flex items-start gap-2 p-3 rounded-lg bg-emerald-50 dark:bg-emerald-900/20 border border-emerald-100 dark:border-emerald-900/40">
                <CheckCircle2 className="w-4 h-4 text-emerald-500 flex-shrink-0 mt-0.5" />
                <p className="text-sm text-emerald-700 dark:text-emerald-300">{runNotice}</p>
              </div>
            </motion.div>
          )}
        </AnimatePresence>
      </div>

      {/* 回归项列表 */}
      <div className="p-6 border-b border-slate-100 dark:border-slate-700/50">
        <SectionTitle
          icon={<Layers className="w-5 h-5" />}
          title="回归项"
          hint={hasItems ? `共 ${items.length} 项` : undefined}
        />
        {itemsLoading ? (
          <div className="flex items-center justify-center py-8">
            <Loader2 className="w-6 h-6 text-primary-500 animate-spin" />
          </div>
        ) : (
          <RegressionItemList items={items} />
        )}
      </div>

      {/* 历史运行列表 */}
      <div className="p-6 border-b border-slate-100 dark:border-slate-700/50">
        <SectionTitle icon={<History className="w-5 h-5" />} title="运行历史" />
        {runsLoading ? (
          <div className="flex items-center justify-center py-8">
            <Loader2 className="w-6 h-6 text-primary-500 animate-spin" />
          </div>
        ) : (
          <RunHistoryList runs={runs} selectedId={selectedRunId} onSelect={loadDetail} />
        )}
      </div>

      {/* 运行详情 */}
      {(detailLoading || detail) && (
        <div className="p-6">
          <SectionTitle icon={<ListChecks className="w-5 h-5" />} title="运行详情" />
          {detailLoading ? (
            <div className="flex items-center justify-center py-8">
              <Loader2 className="w-6 h-6 text-primary-500 animate-spin" />
            </div>
          ) : detail ? (
            <RunDetail detail={detail} items={items} />
          ) : null}
        </div>
      )}
    </motion.div>
  );
}
