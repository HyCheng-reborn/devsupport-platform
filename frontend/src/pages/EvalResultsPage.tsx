import { useMemo } from 'react';
import { motion } from 'framer-motion';
import {
  AlertTriangle,
  BarChart3,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Database,
  Info,
  Layers,
  Target,
  Zap,
} from 'lucide-react';
import { useState } from 'react';
import { loadEvalSummaries, type EvalDatasetSummary, type EvalMetricAtK } from '../api/eval';
import CaseRegressionPanel from '../components/CaseRegressionPanel';

// ── 工具函数 ──────────────────────────────────────────

function formatPercent(v: number): string {
  return `${(v * 100).toFixed(1)}%`;
}

function getMetricColor(v: number): string {
  if (v >= 0.95) return 'text-emerald-600 dark:text-emerald-400';
  if (v >= 0.8) return 'text-blue-600 dark:text-blue-400';
  if (v >= 0.5) return 'text-amber-600 dark:text-amber-400';
  return 'text-red-600 dark:text-red-400';
}

function getMetricBg(v: number): string {
  if (v >= 0.95) return 'bg-emerald-50 dark:bg-emerald-900/20';
  if (v >= 0.8) return 'bg-blue-50 dark:bg-blue-900/20';
  if (v >= 0.5) return 'bg-amber-50 dark:bg-amber-900/20';
  return 'bg-red-50 dark:bg-red-900/20';
}

function getExecutionModeLabel(mode: string): { text: string; color: string } {
  switch (mode) {
    case 'real-embedding':
      return { text: '真实向量评测', color: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300' };
    case 'offline-recompute':
      return { text: '离线重计算', color: 'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300' };
    case 'offline-chunking':
      return { text: '离线切分验证', color: 'bg-slate-100 text-slate-600 dark:bg-slate-700/50 dark:text-slate-300' };
    default:
      return { text: mode, color: 'bg-slate-100 text-slate-600 dark:bg-slate-700/50 dark:text-slate-300' };
  }
}

// ── 指标卡片 ──────────────────────────────────────────

function MetricCell({ metric, k }: { metric: EvalMetricAtK; k: string }) {
  const value = metric[k as keyof EvalMetricAtK] as number;
  const isCoverage = k === 'fullCoverageAtK';
  const display = isCoverage ? formatPercent(value) : formatPercent(value);
  const colorClass = getMetricColor(value);
  const bgClass = getMetricBg(value);

  return (
    <td className={`px-3 py-2.5 text-center text-sm font-medium ${colorClass}`}>
      <span className={`inline-block px-2 py-0.5 rounded-md ${bgClass}`}>
        {display}
      </span>
    </td>
  );
}

// ── 数据集卡片 ────────────────────────────────────────

function DatasetCard({ dataset, index }: { dataset: EvalDatasetSummary; index: number }) {
  const [expanded, setExpanded] = useState(false);
  const modeLabel = getExecutionModeLabel(dataset.executionMode);
  const hasMetrics = dataset.metricsByK.length > 0;

  return (
    <motion.div
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.4, delay: index * 0.1 }}
      className="dark-card overflow-hidden"
    >
      {/* 卡片头部 */}
      <div className="p-6 border-b border-slate-100 dark:border-slate-700/50">
        <div className="flex items-start justify-between gap-4">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-primary-500 to-primary-600 flex items-center justify-center text-white shadow-lg shadow-primary-500/20">
              <Database className="w-5 h-5" />
            </div>
            <div>
              <h3 className="text-lg font-display font-semibold text-slate-900 dark:text-white">
                {dataset.name}
              </h3>
              <p className="text-sm text-slate-500 dark:text-slate-400 mt-0.5">
                {dataset.description}
              </p>
            </div>
          </div>
          <span className={`px-3 py-1 rounded-full text-xs font-semibold whitespace-nowrap ${modeLabel.color}`}>
            {modeLabel.text}
          </span>
        </div>

        {/* 基本信息网格 */}
        <div className="mt-5 grid grid-cols-2 sm:grid-cols-4 gap-3">
          <InfoItem icon={<Layers className="w-4 h-4" />} label="版本" value={dataset.version} />
          <InfoItem icon={<Target className="w-4 h-4" />} label="查询数" value={`${dataset.queryCount} 题 (${dataset.answerableQueries} 可答)`} />
          <InfoItem icon={<Database className="w-4 h-4" />} label="Chunks" value={`${dataset.chunkCount} 个`} />
          <InfoItem
            icon={<Zap className="w-4 h-4" />}
            label="答案要点"
            value={`${dataset.totalAnswerPoints} 个`}
          />
        </div>

        {/* Embedding 信息 */}
        {dataset.embeddingModel && (
          <div className="mt-3 flex flex-wrap gap-2">
            <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-slate-100 dark:bg-slate-700/50 text-xs text-slate-600 dark:text-slate-300">
              <BarChart3 className="w-3.5 h-3.5" />
              {dataset.embeddingProvider}/{dataset.embeddingModel}
            </span>
            {dataset.embeddingDimensions && (
              <span className="inline-flex items-center px-2.5 py-1 rounded-lg bg-slate-100 dark:bg-slate-700/50 text-xs text-slate-600 dark:text-slate-300">
                {dataset.embeddingDimensions} 维
              </span>
            )}
            {dataset.distanceType && (
              <span className="inline-flex items-center px-2.5 py-1 rounded-lg bg-slate-100 dark:bg-slate-700/50 text-xs text-slate-600 dark:text-slate-300">
                {dataset.distanceType.replace('_', ' ')}
              </span>
            )}
          </div>
        )}

        {/* 预算信息 */}
        {dataset.budgetGuard && (
          <div className="mt-3 flex items-center gap-2 text-xs text-slate-500 dark:text-slate-400">
            <AlertTriangle className="w-3.5 h-3.5 text-amber-500" />
            <span>{dataset.budgetGuard}</span>
          </div>
        )}
      </div>

      {/* 指标表格 */}
      {hasMetrics ? (
        <div className="p-6">
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="border-b border-slate-100 dark:border-slate-700/50">
                  <th className="text-left text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider pb-3 pr-4">
                    K 值
                  </th>
                  <th className="text-center text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider pb-3 px-3">
                    Hit@K
                  </th>
                  <th className="text-center text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider pb-3 px-3">
                    MRR@K
                  </th>
                  <th className="text-center text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider pb-3 px-3">
                    FullCoverage@K
                  </th>
                  <th className="text-center text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider pb-3 pl-3">
                    覆盖要点
                  </th>
                </tr>
              </thead>
              <tbody>
                {dataset.metricsByK.map((m) => (
                  <tr key={m.k} className="border-b border-slate-50 dark:border-slate-700/30 last:border-0">
                    <td className="px-3 py-2.5 text-sm font-semibold text-slate-700 dark:text-slate-200">
                      K={m.k}
                    </td>
                    <MetricCell metric={m} k="hitAtK" />
                    <MetricCell metric={m} k="mrrAtK" />
                    <MetricCell metric={m} k="fullCoverageAtK" />
                    <td className="px-3 py-2.5 text-center text-sm text-slate-600 dark:text-slate-300">
                      {m.coveredPoints}/{m.totalPoints}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* 展开/收起逐题说明 */}
          {dataset.recomputeNote && (
            <button
              onClick={() => setExpanded(!expanded)}
              className="mt-4 flex items-center gap-1.5 text-sm text-primary-600 dark:text-primary-400 hover:text-primary-700 dark:hover:text-primary-300 transition-colors"
            >
              {expanded ? <ChevronDown className="w-4 h-4" /> : <ChevronRight className="w-4 h-4" />}
              <span className="font-medium">变更说明</span>
            </button>
          )}
          {expanded && dataset.recomputeNote && (
            <motion.div
              initial={{ opacity: 0, height: 0 }}
              animate={{ opacity: 1, height: 'auto' }}
              className="mt-2 p-3 rounded-lg bg-slate-50 dark:bg-slate-800/50 text-sm text-slate-600 dark:text-slate-300"
            >
              <p className="font-medium text-slate-700 dark:text-slate-200 mb-1">金标修正内容：</p>
              <p>{dataset.recomputeNote}</p>
            </motion.div>
          )}
        </div>
      ) : (
        <div className="p-6">
          <div className="flex items-center gap-3 p-4 rounded-xl bg-slate-50 dark:bg-slate-800/50 border border-slate-200/50 dark:border-slate-700/50">
            <Info className="w-5 h-5 text-slate-400 dark:text-slate-500 flex-shrink-0" />
            <div className="text-sm text-slate-600 dark:text-slate-300">
              <p className="font-medium text-slate-700 dark:text-slate-200">仅完成离线切分验证</p>
              <p className="mt-0.5">该数据集已完成 P1-B 离线切分验证（28 chunks，7 份文档），尚未执行 P1-C 真实向量检索评测。</p>
            </div>
          </div>
        </div>
      )}
    </motion.div>
  );
}

function InfoItem({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return (
    <div className="flex items-center gap-2 px-3 py-2 rounded-lg bg-slate-50 dark:bg-slate-800/50">
      <span className="text-slate-400 dark:text-slate-500">{icon}</span>
      <div className="min-w-0">
        <p className="text-xs text-slate-500 dark:text-slate-400">{label}</p>
        <p className="text-sm font-medium text-slate-700 dark:text-slate-200 truncate">{value}</p>
      </div>
    </div>
  );
}

// ── 主页面 ────────────────────────────────────────────

export default function EvalResultsPage() {
  const datasets = useMemo(() => loadEvalSummaries(), []);

  return (
    <div className="max-w-5xl mx-auto">
      {/* 页面标题 */}
      <motion.div
        initial={{ opacity: 0, y: -10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.3 }}
        className="mb-8"
      >
        <div className="flex items-center gap-3 mb-2">
          <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-indigo-500 to-purple-600 flex items-center justify-center text-white shadow-lg shadow-indigo-500/20">
            <BarChart3 className="w-5 h-5" />
          </div>
          <div>
            <h1 className="text-2xl font-display font-bold text-slate-900 dark:text-white">
              评测结果
            </h1>
            <p className="text-sm text-slate-500 dark:text-slate-400">
              P1-C L1 向量检索离线评测结果 · RAG 知识库基线
            </p>
          </div>
        </div>
      </motion.div>

      {/* 说明卡片 */}
      <motion.div
        initial={{ opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.3, delay: 0.05 }}
        className="dark-card p-5 mb-6"
      >
        <div className="flex items-start gap-3">
          <CheckCircle2 className="w-5 h-5 text-emerald-500 mt-0.5 flex-shrink-0" />
          <div className="text-sm text-slate-600 dark:text-slate-300 space-y-1.5">
            <p>
              以下为 <strong className="text-slate-800 dark:text-slate-100">离线评测</strong> 结果，
              数据来自 P1-C L1 向量检索评测流程，使用冻结的评测数据集和预算门控（硬上限 50 次 API 调用）。
            </p>
            <p>
              指标说明：<strong>Hit@K</strong> = 前 K 个检索结果中是否命中金标支撑 chunk；
              <strong> MRR@K</strong> = 首个命中结果的排名倒数均值；
              <strong> FullCoverage@K</strong> = 前 K 个结果是否覆盖全部答案要点。
            </p>
            <p className="text-xs text-slate-400 dark:text-slate-500">
              注：这些是静态评测快照，不代表实时 AI 性能。NO_ANSWER 题（4 道）不参与宏平均计算。
            </p>
          </div>
        </div>
      </motion.div>

      {/* 数据集卡片列表 */}
      <div className="space-y-6">
        {datasets.map((ds, i) => (
          <DatasetCard key={ds.id} dataset={ds} index={i} />
        ))}
      </div>

      {/* 案例回归评测区块 */}
      <div className="mt-8">
        <CaseRegressionPanel />
      </div>

      {/* 底部注脚 */}
      <motion.p
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ duration: 0.3, delay: 0.5 }}
        className="mt-8 text-center text-xs text-slate-400 dark:text-slate-500"
      >
        离线评测数据来自 eval/datasets/ 目录（构建时静态加载）· 案例回归评测数据来自后端 /api/eval/regression/*
      </motion.p>
    </div>
  );
}
