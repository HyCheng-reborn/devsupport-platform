/**
 * 案例回归评测相关类型定义。
 *
 * 严格对应后端 `interview.guide.modules.evalregression.model.*` DTO：
 * - RegressionItemDTO
 * - RegressionRunSummaryDTO
 * - RegressionRunDetailDTO
 * - RegressionResultDTO
 * - TopKSnapshotEntry
 *
 * 所有响应包在 Result<T> 中，Axios 拦截器已解包为 data，故此处直接定义 data 结构。
 * 时间戳为字符串（后端 LocalDateTime 序列化）。
 */

/** 回归运行状态：RUNNING（进行中）/ COMPLETED（已完成）。 */
export type RegressionRunStatus = 'RUNNING' | 'COMPLETED';

/** top-K 召回快照条目。 */
export interface TopKSnapshotEntry {
  evidenceId: string;
  content: string;
  score: number;
}

/** 回归项 DTO。caseTitle / caseStatus 来自关联案例。 */
export interface RegressionItemDTO {
  id: number;
  caseId: number;
  caseTitle: string;
  caseStatus: string;
  query: string;
  expectedEvidence: string[];
  keyPoints: string[];
  embeddingModel: string;
  embeddingDimension: number;
  active: boolean;
}

/** 回归运行汇总 DTO。total = passed + failed + skipped。 */
export interface RegressionRunSummaryDTO {
  id: number;
  startedAt: string;
  finishedAt: string | null;
  totalItems: number;
  passed: number;
  failed: number;
  skipped: number;
  triggerSource: string;
  embeddingModel: string;
  status: RegressionRunStatus;
}

/** 回归逐项结果 DTO。 */
export interface RegressionResultDTO {
  id: number;
  itemId: number;
  caseTitle: string;
  passed: boolean;
  retrievedEvidenceIds: string[];
  matchedKeyPoints: string[];
  missingKeyPoints: string[];
  topKSnapshot: TopKSnapshotEntry[];
  failureReason: string | null;
}

/** 回归运行详情 DTO：汇总全字段 + 逐项结果。 */
export interface RegressionRunDetailDTO extends RegressionRunSummaryDTO {
  results: RegressionResultDTO[];
}

/** 触发回归运行的请求体（topK 可选）。 */
export interface RegressionRunRequest {
  topK?: number;
}
