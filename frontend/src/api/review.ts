// ③ review pipeline: suggestions wait here until a person approves them.
import { api, apiBlob } from './client';

export type SuggestionStatus = 'PENDING' | 'SUBMITTED' | 'ACCEPTED' | 'EDITED_ACCEPTED' | 'REJECTED';
export type Frequency = 'ONCE' | 'MONTHLY' | 'QUARTERLY' | 'SEMIANNUAL' | 'ANNUAL' | 'OTHER';
export type QuoteCheck = 'MISMATCH' | 'UNVERIFIABLE' | 'VERIFIED';

/** OBLIGATION payload, snake_case like the AI output (design §3.3). */
export interface ObligationPayload {
  citation_raw?: string | null;
  citation_key?: string | null; // server-computed (design §3.4)
  citation_synthetic?: boolean;
  legal_basis?: string[];
  summary: string;
  requirement_text: string;
  type: string;
  category: string;
  frequency: Frequency;
  responsible_party?: string | null;
  page_no?: number | null;
  source_quote?: string | null;
  quote_check?: QuoteCheck | null;
}

export interface Suggestion {
  id: number;
  projectId: number;
  runId: number;
  kind: 'OBLIGATION';
  targetParentId: number;
  origin: 'AI' | 'MANUAL';
  payload: ObligationPayload;
  editedPayload: ObligationPayload | null;
  status: SuggestionStatus;
  proposedDecision: 'ACCEPT' | 'REJECT' | null;
  rejectReason: string | null;
  appliedEntityId: number | null;
  version: number;
}

export interface SuggestionEvent {
  id: number;
  actorId: number | null;
  at: string;
  type: 'CREATED' | 'EDITED' | 'PAGE_VIEWED' | 'SUBMITTED' | 'RETURNED' | 'ACCEPTED' | 'REJECTED' | 'REOPENED';
  data: { page_no?: number } | null;
}

export const effective = (s: Suggestion) => s.editedPayload ?? s.payload;

const post = <T>(path: string, body: unknown) => api<T>(path, { method: 'POST', body: JSON.stringify(body) });

export const listSuggestions = (pid: number, runId?: number) =>
  api<Suggestion[]>(`/api/projects/${pid}/suggestions${runId ? `?runId=${runId}` : ''}`);
export const suggestionEvents = (id: number) => api<SuggestionEvent[]>(`/api/suggestions/${id}/events`);
export const addSuggestion = (pid: number, permitItemId: number, payload: ObligationPayload) =>
  post<Suggestion>(`/api/projects/${pid}/suggestions`, { permitItemId, payload });
export const editSuggestion = (s: Suggestion, editedPayload: ObligationPayload) =>
  api<Suggestion>(`/api/suggestions/${s.id}`, { method: 'PATCH', body: JSON.stringify({ editedPayload, version: s.version }) });
export const submitSuggestion = (s: Suggestion, proposedDecision: 'ACCEPT' | 'REJECT', reason?: string) =>
  post<Suggestion>(`/api/suggestions/${s.id}/submit`, { proposedDecision, reason, version: s.version });
export const acceptSuggestion = (s: Suggestion, firstDueDate: string | null) =>
  post<Suggestion>(`/api/suggestions/${s.id}/accept`, { firstDueDate, version: s.version });
export const rejectSuggestion = (s: Suggestion, reason: string) =>
  post<Suggestion>(`/api/suggestions/${s.id}/reject`, { reason, version: s.version });
export const returnSuggestion = (s: Suggestion, reason: string) =>
  post<Suggestion>(`/api/suggestions/${s.id}/return`, { reason, version: s.version });
export const reopenSuggestion = (s: Suggestion) => post<Suggestion>(`/api/suggestions/${s.id}/reopen`, { version: s.version });
export const bulkAccept = (ids: number[]) =>
  post<{ id: number; ok: boolean; reason: string | null }[]>('/api/suggestions/bulk-accept', { ids });
// D7: opening a scan's page in the viewer is what allows approving it
export const recordPageView = (id: number, pageNo: number) => post<void>(`/api/suggestions/${id}/page-views`, { pageNo });
export const pageImage = (id: number, pageNo: number) => apiBlob(`/api/suggestions/${id}/pages/${pageNo}`);
