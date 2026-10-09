/**
 * 评测数据 API 模块
 *
 * 使用 Vite import.meta.glob 将 eval JSON 文件作为静态模块加载，
 * 不经过 public/ 目录，构建时即可确定文件是否存在。
 *
 * 另含案例回归评测（/api/eval/regression/*）的远程端点客户端，复用 request.ts 实例。
 */

import { request } from './request';
import type {
  RegressionItemDTO,
  RegressionRunDetailDTO,
  RegressionRunRequest,
  RegressionRunSummaryDTO,
} from '../types/eval';

// ── 类型定义 ──────────────────────────────────────────

export interface EvalMetricAtK {
  k: number;
  hitAtK: number;
  mrrAtK: number;
  fullCoverageAtK: number;
  coveredPoints: number;
  totalPoints: number;
}

export interface EvalDatasetSummary {
  id: string;
  name: string;
  version: string;
  label: string;
  description: string;
  embeddingModel?: string;
  embeddingProvider?: string;
  embeddingDimensions?: number;
  distanceType?: string;
  queryCount: number;
  answerableQueries: number;
  noAnswerQueries: number;
  chunkCount: number;
  totalAnswerPoints: number;
  budgetGuard?: string;
  executionMode: 'real-embedding' | 'offline-recompute' | 'offline-chunking';
  metricsByK: EvalMetricAtK[];
  /** v0.1 recompute 的变化说明 */
  recomputeNote?: string;
}

// ── import.meta.glob 加载评测文件 ─────────────────────

// 加载各数据集的关键 JSON 文件（eager: 构建时打包）
const evalModules = import.meta.glob<{ default: unknown }>(
  [
    '../../../eval/datasets/devsupport-v0.1/candidate-gold.json',
    '../../../eval/datasets/devsupport-heading-aware-v0/p1c-l1-report.json',
    '../../../eval/datasets/devsupport-heading-aware-v0/p1c-l1-per-query.json',
    '../../../eval/datasets/devsupport-heading-aware-v0/candidate-gold.json',
    '../../../eval/datasets/devsupport-heading-aware-v0.1/p1c-l1-recompute-offline.json',
    '../../../eval/datasets/devsupport-heading-aware-v0.1/candidate-gold.json',
  ],
  { eager: true },
);

// ── 辅助函数 ──────────────────────────────────────────

function getModule(path: string): unknown | undefined {
  return evalModules[path]?.default;
}

interface GoldJson {
  statistics: {
    totalQueries: number;
    answerableQueries: number;
    noAnswerQueries: number;
  };
}

interface P1cReport {
  executionMode: string;
  embedding: {
    providerId: string;
    modelName: string;
    configuredDimensions: number;
  };
  vectorStore: {
    distanceType: string;
  };
  callGuard: {
    outerOperations: {
      hardLimit: number;
      totalAttempts: number;
    };
  };
  queryCounts: {
    totalQueries: number;
    answerableMetricQueries: number;
    noAnswerDiagnosticQueries: number;
  };
  macroMetricsByK: Record<string, {
    k: number;
    macroHitAtK: number;
    macroMrrAtK: number;
    macroFullCoverageAtK: number;
    coveredPointsAtK: number;
    totalAnswerPoints: number;
  }>;
  ingestionVerification: {
    expectedChunks: number;
  };
  startTime: string;
}

interface RecomputeOffline {
  provenance: {
    basedOnEvalRunId: string;
    change: string;
  };
  macroByK_after: Record<string, {
    macroHitAtK: number;
    macroMrrAtK: number;
    macroFullCoverageAtK: number;
    coveredPointsAtK: number;
    totalAnswerPoints: number;
  }>;
}

function extractMetricsByK(
  macroByK: Record<string, {
    k?: number;
    macroHitAtK: number;
    macroMrrAtK: number;
    macroFullCoverageAtK: number;
    coveredPointsAtK: number;
    totalAnswerPoints: number;
  }>,
): EvalMetricAtK[] {
  return Object.entries(macroByK)
    .sort(([a], [b]) => {
      const ka = parseInt(a.replace('k=', ''), 10);
      const kb = parseInt(b.replace('k=', ''), 10);
      return ka - kb;
    })
    .map(([key, v]) => ({
      k: v.k ?? parseInt(key.replace('k=', ''), 10),
      hitAtK: v.macroHitAtK,
      mrrAtK: v.macroMrrAtK,
      fullCoverageAtK: v.macroFullCoverageAtK,
      coveredPoints: v.coveredPointsAtK,
      totalPoints: v.totalAnswerPoints,
    }));
}

// ── 构建各数据集摘要 ─────────────────────────────────

function buildV01Summary(): EvalDatasetSummary {
  const gold = getModule('../../../eval/datasets/devsupport-v0.1/candidate-gold.json') as GoldJson | undefined;
  const stats = gold?.statistics;

  return {
    id: 'devsupport-v0.1',
    name: 'DevSupport 基线数据集',
    version: 'v0.1',
    label: '基线 (Baseline)',
    description: '原始文档级切分，28 chunks，仅完成离线切分验证 (P1-B)，未执行真实向量检索评测。',
    queryCount: stats?.totalQueries ?? 20,
    answerableQueries: stats?.answerableQueries ?? 16,
    noAnswerQueries: stats?.noAnswerQueries ?? 4,
    chunkCount: 28,
    totalAnswerPoints: 38,
    executionMode: 'offline-chunking',
    metricsByK: [],
  };
}

function buildHeadingAwareV0Summary(): EvalDatasetSummary {
  const report = getModule('../../../eval/datasets/devsupport-heading-aware-v0/p1c-l1-report.json') as P1cReport | undefined;
  const gold = getModule('../../../eval/datasets/devsupport-heading-aware-v0/candidate-gold.json') as GoldJson | undefined;
  const stats = gold?.statistics;

  return {
    id: 'devsupport-heading-aware-v0',
    name: 'Heading-Aware 数据集',
    version: 'v0',
    label: '真实向量评测',
    description: 'Heading-aware 切分策略，49 chunks。使用真实 Embedding API 执行向量检索评测 (P1-C L1)。',
    embeddingModel: report?.embedding.modelName,
    embeddingProvider: report?.embedding.providerId,
    embeddingDimensions: report?.embedding.configuredDimensions,
    distanceType: report?.vectorStore.distanceType,
    queryCount: report?.queryCounts.totalQueries ?? stats?.totalQueries ?? 20,
    answerableQueries: report?.queryCounts.answerableMetricQueries ?? stats?.answerableQueries ?? 16,
    noAnswerQueries: report?.queryCounts.noAnswerDiagnosticQueries ?? stats?.noAnswerQueries ?? 4,
    chunkCount: report?.ingestionVerification.expectedChunks ?? 49,
    totalAnswerPoints: 38,
    budgetGuard: report
      ? `预算上限 ${report.callGuard.outerOperations.hardLimit} 次，实际使用 ${report.callGuard.outerOperations.totalAttempts} 次`
      : undefined,
    executionMode: 'real-embedding',
    metricsByK: report ? extractMetricsByK(report.macroMetricsByK) : [],
  };
}

function buildHeadingAwareV01Summary(): EvalDatasetSummary {
  const recompute = getModule('../../../eval/datasets/devsupport-heading-aware-v0.1/p1c-l1-recompute-offline.json') as RecomputeOffline | undefined;
  const gold = getModule('../../../eval/datasets/devsupport-heading-aware-v0.1/candidate-gold.json') as GoldJson | undefined;
  const stats = gold?.statistics;

  return {
    id: 'devsupport-heading-aware-v0.1',
    name: 'Heading-Aware 数据集 (修正版)',
    version: 'v0.1',
    label: '离线重计算',
    description: '基于 v0 评测结果，修正 q11 金标支撑集（补充逐字引用 chunk）后离线重算指标，无新检索。',
    embeddingModel: 'text-embedding-v3',
    embeddingProvider: 'dashscope',
    embeddingDimensions: 1024,
    distanceType: 'COSINE_DISTANCE',
    queryCount: stats?.totalQueries ?? 20,
    answerableQueries: stats?.answerableQueries ?? 16,
    noAnswerQueries: stats?.noAnswerQueries ?? 4,
    chunkCount: 49,
    totalAnswerPoints: 38,
    budgetGuard: '基于 v0 检索结果离线重算，无额外 API 调用',
    executionMode: 'offline-recompute',
    metricsByK: recompute ? extractMetricsByK(recompute.macroByK_after) : [],
    recomputeNote: recompute?.provenance.change,
  };
}

// ── 公开 API ──────────────────────────────────────────

/**
 * 加载全部评测数据集摘要，按版本排序。
 * 纯前端计算，无网络请求。
 */
export function loadEvalSummaries(): EvalDatasetSummary[] {
  return [
    buildV01Summary(),
    buildHeadingAwareV0Summary(),
    buildHeadingAwareV01Summary(),
  ];
}

/**
 * 检查评测数据是否可用（至少有一个数据集文件被成功加载）。
 */
export function hasEvalData(): boolean {
  return Object.keys(evalModules).length > 0;
}

// ── 案例回归评测远程端点 ──────────────────────────────

/**
 * 获取全部回归项。
 * GET /api/eval/regression/items
 */
export function getRegressionItems(): Promise<RegressionItemDTO[]> {
  return request.get<RegressionItemDTO[]>('/api/eval/regression/items');
}

/**
 * 触发一轮回归运行（可选 topK）。
 * POST /api/eval/regression/runs
 */
export function runRegression(body?: RegressionRunRequest): Promise<RegressionRunDetailDTO> {
  return request.post<RegressionRunDetailDTO>('/api/eval/regression/runs', body ?? {});
}

/**
 * 获取回归运行历史列表。
 * GET /api/eval/regression/runs
 */
export function getRegressionRuns(): Promise<RegressionRunSummaryDTO[]> {
  return request.get<RegressionRunSummaryDTO[]>('/api/eval/regression/runs');
}

/**
 * 获取某次运行详情（含逐项结果）。
 * GET /api/eval/regression/runs/{id}
 */
export function getRegressionRunDetail(id: number): Promise<RegressionRunDetailDTO> {
  return request.get<RegressionRunDetailDTO>(`/api/eval/regression/runs/${id}`);
}
