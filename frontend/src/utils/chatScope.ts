/**
 * 排查会话检索范围计算（纯函数，可独立测试）。
 * 与后端 RagChatSessionService.createSession 的范围语义保持一致：
 * service/environment 解析出的集合是「限制范围」，手动勾选只能在范围内缩小，不能扩大；
 * 范围为空或与显式选择无交集时得到空集合（调用方据此提示错误，而非退回全量检索）。
 */

/** 已选上下文时返回范围内的 ID，未选上下文时返回全部 ID（旧行为）。 */
export function selectableIds(
  allIds: number[],
  rangeIds: Set<number> | null,
  contextActive: boolean
): number[] {
  if (!contextActive || !rangeIds) return allIds;
  return allIds.filter((id) => rangeIds.has(id));
}

/**
 * 计算最终会话使用的 KB 集合（用于展示与提交，等价于后端的交集校验）。
 * - 未选上下文：保持显式勾选，去重返回。
 * - 已选上下文：显式勾选与范围取交集，范围外的被安全排除、取消的不会被加回；
 *   范围为空时返回空数组。
 */
export function narrowScope(
  selectedIds: number[],
  rangeIds: Set<number> | null,
  contextActive: boolean
): number[] {
  if (!contextActive) return Array.from(new Set(selectedIds));
  if (!rangeIds || rangeIds.size === 0) return [];
  const selected = new Set(selectedIds);
  return Array.from(rangeIds).filter((id) => selected.has(id));
}
