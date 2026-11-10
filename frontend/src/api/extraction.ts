// ① AI obligation extraction: start a run on a permit's PDF, then poll it. Results land in /review.
import { api } from './client';

export interface AiRun {
  id: number;
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED';
  errorMessage: string | null;
  inputRef: { document_id: number; permit_item_id: number; sha256: string; prev_document_id?: number } | null;
  model: string | null;
  startedAt: string;
  finishedAt: string | null;
}

export interface Company {
  id: number;
  name: string;
  aiExtractionEnabled: boolean;
}

export const getCompany = () => api<Company>('/api/company');
export const setAiExtraction = (enabled: boolean) =>
  api<Company>('/api/company/ai-extraction', { method: 'PUT', body: JSON.stringify({ enabled }) });

/** 409 = same file was extracted before; retry with confirmDuplicate after asking the user. */
export const startExtraction = (pid: number, permitId: number, docId: number, confirmDuplicate = false) =>
  api<{ runId: number }>(`/api/projects/${pid}/permit-items/${permitId}/documents/${docId}/extractions`, {
    method: 'POST',
    body: JSON.stringify({ confirmDuplicate }),
  });
/** ④ "개정 비교": extraction of a new version, diffed against the version it supersedes. */
export const startRevision = (pid: number, permitId: number, docId: number) =>
  api<{ runId: number }>(`/api/projects/${pid}/permit-items/${permitId}/documents/${docId}/revisions`, { method: 'POST' });
export const getRun = (id: number) => api<AiRun>(`/api/ai-runs/${id}`);
export const listRuns = (pid: number) => api<AiRun[]>(`/api/projects/${pid}/ai-runs`);
