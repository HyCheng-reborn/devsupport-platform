export type CaseStatus = 'DRAFT' | 'PENDING_REVIEW' | 'PUBLISHED' | 'REJECTED' | 'DEPRECATED';

export interface CaseItem {
  id: number;
  title: string;
  problemDescription: string | null;
  rootCause: string | null;
  resolutionSteps: string | null;
  resolutionResult: string | null;
  affectedVersions: string | null;
  environment: string | null;
  service: string | null;
  aiGeneratedContent: string | null;
  userConfirmedContent: string | null;
  status: CaseStatus;
  versionNo: number;
  sourceSessionId: number | null;
  sourceMessageId: number | null;
  active: boolean;
  createdBy: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateDraftRequest {
  sessionId: number;
  messageId: number;
}

export interface CaseUpdateRequest {
  title?: string;
  problemDescription?: string;
  rootCause?: string;
  resolutionSteps?: string;
  resolutionResult?: string;
  affectedVersions?: string;
  environment?: string;
  service?: string;
  aiGeneratedContent?: string;
  userConfirmedContent?: string;
}

export interface RejectRequest {
  remark?: string;
}
