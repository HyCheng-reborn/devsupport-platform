import { request } from './request';
import type { CaseItem, CreateDraftRequest, CaseUpdateRequest, RejectRequest } from '../types/cases';

export const casesApi = {
  createDraft: (data: CreateDraftRequest) =>
    request.post<CaseItem>('/api/cases/draft', data),

  list: (params?: { status?: string; service?: string }) =>
    request.get<CaseItem[]>('/api/cases', { params }),

  get: (id: number) =>
    request.get<CaseItem>(`/api/cases/${id}`),

  update: (id: number, data: CaseUpdateRequest) =>
    request.put<CaseItem>(`/api/cases/${id}`, data),

  submit: (id: number) =>
    request.post<CaseItem>(`/api/cases/${id}/submit`),

  approve: (id: number) =>
    request.post<CaseItem>(`/api/cases/${id}/approve`),

  reject: (id: number, data?: RejectRequest) =>
    request.post<CaseItem>(`/api/cases/${id}/reject`, data),

  deprecate: (id: number) =>
    request.post<CaseItem>(`/api/cases/${id}/deprecate`),
};
