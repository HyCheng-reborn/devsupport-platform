import type { VectorStatus } from '../api/knowledgebase';

/**
 * 文档中心展示状态（阶段 2）：合并"向量索引状态"与"生命周期 active 标记"。
 * 停用/被新版本取代（active=false）优先，即使索引曾完成也显示"已停用"且退出检索。
 */
export type DocStatus = 'processing' | 'pending' | 'searchable' | 'failed' | 'retired';

export function deriveStatus(
  vectorStatus: VectorStatus | undefined,
  active: boolean | undefined
): DocStatus {
  if (active === false) return 'retired';
  switch (vectorStatus) {
    case 'PROCESSING':
      return 'processing';
    case 'PENDING':
      return 'pending';
    case 'FAILED':
      return 'failed';
    case 'COMPLETED':
      return 'searchable';
    default:
      return 'pending';
  }
}

export const DOC_STATUS_LABEL: Record<DocStatus, string> = {
  processing: '处理中',
  pending: '待处理',
  searchable: '可检索',
  failed: '失败',
  retired: '已停用',
};

/** 仅启用中的文档提供"停用"操作；已停用不再重复停用。 */
export function canRetire(active: boolean | undefined): boolean {
  return active !== false;
}
