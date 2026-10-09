import type { RegressionRunSummaryDTO } from '../types/eval';

/**
 * 案例回归评测的纯函数工具：通过率计算、运行状态映射、证据命中判定。
 * 抽离为纯函数以便轻量单测，且页面逻辑更清晰。
 */

/**
 * 计算通过率（passed / totalItems）。totalItems 为 0 时返回 0，避免除零。
 * 后端约定 total = passed + failed + skipped，故直接以 totalItems 为分母。
 */
export function calcPassRate(passed: number, totalItems: number): number {
  if (!totalItems || totalItems <= 0) return 0;
  return passed / totalItems;
}

/** 将 0~1 的比例格式化为百分比字符串（保留 1 位小数）。 */
export function formatRate(rate: number): string {
  return `${(rate * 100).toFixed(1)}%`;
}

/**
 * 判定检索到的证据 ID 是否命中期望证据集合。
 * 只要 retrievedEvidenceIds 中存在任意一个属于 expectedEvidence 的 ID 即视为命中。
 */
export function isEvidenceHit(retrievedEvidenceIds: string[], expectedEvidence: string[]): boolean {
  if (!retrievedEvidenceIds?.length || !expectedEvidence?.length) return false;
  const expected = new Set(expectedEvidence);
  return retrievedEvidenceIds.some((id) => expected.has(id));
}

/** 运行状态的展示标签与配色（复用现有 slate/emerald/blue 设计语言）。 */
export function getRunStatusMeta(status: RegressionRunSummaryDTO['status']): {
  text: string;
  color: string;
} {
  switch (status) {
    case 'COMPLETED':
      return {
        text: '已完成',
        color: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300',
      };
    case 'RUNNING':
      return {
        text: '运行中',
        color: 'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300',
      };
    default:
      return {
        text: status,
        color: 'bg-slate-100 text-slate-600 dark:bg-slate-700/50 dark:text-slate-300',
      };
  }
}

/**
 * 将后端 LocalDateTime 序列化字符串格式化为本地可读时间。
 * 无效值返回占位符「—」。
 */
export function formatTimestamp(value: string | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString('zh-CN', { hour12: false });
}
