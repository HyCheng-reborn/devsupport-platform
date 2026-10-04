import type { SourceReference } from '../api/ragChat';

/**
 * 来源标签展示映射：把一条 SourceReference 归一化为页面直接消费的展示视图。
 * 该函数被 KnowledgeBaseQueryPage 的来源面板实际调用，便于以 node:test 单测覆盖
 * 新来源标签 / 旧 sourcesJson 缺字段 / 字段为 null / score 展示等分支，
 * 而无需依赖组件渲染测试基础设施。
 */
export interface SourceTagView {
  /** 原文透传，映射不改动文档名，保证展示不变 */
  documentName: string;
  /** 原文透传，映射不改动片段，保证展示不变 */
  contentSnippet: string;
  /** service 归一：空串/缺失/null 视为无 → null（不渲染蓝色标签） */
  serviceName: string | null;
  /** environment 归一：空串/缺失/null 视为无 → null（不渲染绿色标签） */
  environmentName: string | null;
  /** 版本标签：优先 versionLabel，其次 "v{versionNo}"，都没有则 null（不渲染紫色标签） */
  versionTag: string | null;
  /** 两者皆无时展示「无标签」 */
  showNoLabel: boolean;
  /** score 非空时格式化为百分比字符串（保留原有 toFixed(0) 口径），否则 null */
  scoreLabel: string | null;
}

export function toSourceTagView(src: SourceReference): SourceTagView {
  const serviceName = src.service || null;
  const environmentName = src.environment || null;
  const scoreLabel = src.score != null ? (src.score * 100).toFixed(0) + '%' : null;
  
  // 版本标签：优先 versionLabel，其次 versionNo
  let versionTag: string | null = null;
  if (src.versionLabel && src.versionLabel.trim().length > 0) {
    versionTag = src.versionLabel;
  } else if (src.versionNo != null) {
    versionTag = `v${src.versionNo}`;
  }
  
  return {
    documentName: src.documentName,
    contentSnippet: src.contentSnippet,
    serviceName,
    environmentName,
    versionTag,
    showNoLabel: !serviceName && !environmentName,
    scoreLabel,
  };
}
