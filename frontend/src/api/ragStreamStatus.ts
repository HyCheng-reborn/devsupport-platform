// RAG 流式回答的前端状态处理：把服务端确认的最终状态与来源展示规则收敛为可测试的纯函数。
// 无副作用、不依赖 React / axios，供 node --test 直接运行。

export type MessageStatus = 'COMPLETED' | 'NO_RESULTS' | 'MODEL_FAILED' | 'CLIENT_DISCONNECTED';

const VALID_STATUSES: readonly string[] = [
  'COMPLETED',
  'NO_RESULTS',
  'MODEL_FAILED',
  'CLIENT_DISCONNECTED',
];

export function isValidMessageStatus(value: unknown): value is MessageStatus {
  return typeof value === 'string' && VALID_STATUSES.includes(value);
}

/**
 * 解析 SSE `done` 事件的 data（形如 {"status":"COMPLETED"}），返回服务端确认的最终状态。
 * 缺失、非法 JSON 或不可识别的状态一律返回 undefined —— 调用方不得据此判定成功。
 */
export function parseDoneStatus(rawData?: string | null): MessageStatus | undefined {
  if (!rawData) {
    return undefined;
  }
  try {
    const parsed = JSON.parse(rawData) as { status?: unknown } | null;
    const status = parsed?.status;
    return isValidMessageStatus(status) ? status : undefined;
  } catch {
    return undefined;
  }
}

/**
 * done 事件到达但服务端未给出可识别状态时，不得当作 COMPLETED。
 * 无确认按失败处理：持久化失败或异常终止都不会向客户端宣告成功。
 */
export function resolveFinalStatus(serverStatus: MessageStatus | undefined): MessageStatus {
  return serverStatus ?? 'MODEL_FAILED';
}

export type SourcesDisplayMode = 'grounded' | 'degraded' | 'none' | 'pending';

/**
 * 根据状态决定来源展示方式，使失败/无结果的回答不会显示成有依据的正常回答。
 * - COMPLETED：作为“有依据”的正常引用展示
 * - NO_RESULTS：不展示来源（无依据）
 * - MODEL_FAILED / CLIENT_DISCONNECTED：降级展示（检索到文档但不代表回答有效）
 * - undefined/null：流式生成中，尚未提交来源，按既有行为不渲染
 */
export function sourcesDisplayMode(status: MessageStatus | null | undefined): SourcesDisplayMode {
  switch (status) {
    case 'COMPLETED':
      return 'grounded';
    case 'NO_RESULTS':
      return 'none';
    case 'MODEL_FAILED':
    case 'CLIENT_DISCONNECTED':
      return 'degraded';
    default:
      return 'pending';
  }
}

/**
 * 选择最终写入消息的来源：NO_RESULTS 一律为空，避免无依据回答带来源。
 */
export function selectSourcesForStatus<T>(sources: T[] | undefined, status: MessageStatus | undefined): T[] {
  if (status === 'NO_RESULTS') {
    return [];
  }
  return sources ?? [];
}
