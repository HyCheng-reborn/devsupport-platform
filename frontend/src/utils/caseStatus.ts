import type { CaseStatus } from '../types/cases';

export const caseStatusLabels: Record<CaseStatus, string> = {
  DRAFT: '草稿',
  PENDING_REVIEW: '待审核',
  PUBLISHED: '已发布',
  REJECTED: '已退回',
  DEPRECATED: '已废弃',
};

export const caseStatusStyles: Record<CaseStatus, string> = {
  DRAFT: 'bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300',
  PENDING_REVIEW: 'bg-amber-50 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400',
  PUBLISHED: 'bg-emerald-50 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-400',
  REJECTED: 'bg-red-50 text-red-700 dark:bg-red-900/30 dark:text-red-400',
  DEPRECATED: 'bg-slate-50 text-slate-400 line-through dark:bg-slate-800 dark:text-slate-500',
};

export function getCaseStatusLabel(status: CaseStatus): string {
  return caseStatusLabels[status] || status;
}
