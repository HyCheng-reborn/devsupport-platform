/**
 * 知识库筛选逻辑（纯函数，可独立测试）
 */

// 筛选状态类型：区分"全部"、"未分类(NULL)"、"具体值"三种语义
export type FilterState =
  | { type: 'all' }
  | { type: 'unclassified' }
  | { type: 'value'; value: string };

export interface FilterableKB {
  service: string | null;
  environment: string | null;
}

/**
 * 根据 service 和 environment 的 FilterState 筛选知识库列表。
 * - 'all'：不过滤
 * - 'unclassified'：只保留对应字段为 null/undefined 的项
 * - 'value'：只保留对应字段严格等于 value 的项
 */
export function applyFilters<T extends FilterableKB>(
  items: T[],
  serviceFilter: FilterState,
  environmentFilter: FilterState
): T[] {
  let result = items;

  if (serviceFilter.type === 'unclassified') {
    result = result.filter(kb => kb.service === null || kb.service === undefined);
  } else if (serviceFilter.type === 'value') {
    result = result.filter(kb => kb.service === serviceFilter.value);
  }

  if (environmentFilter.type === 'unclassified') {
    result = result.filter(kb => kb.environment === null || kb.environment === undefined);
  } else if (environmentFilter.type === 'value') {
    result = result.filter(kb => kb.environment === environmentFilter.value);
  }

  return result;
}
