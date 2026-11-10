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
  frequency_text?: string | null;
  period_text?: string | null;
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
  type: 'CREATED' | 'EDITED' | 'PAGE_VIEWED' | 'SUBMITTED' | 'RETURNED' | 'ACCEPTED' | 'REJECTED' | 'REOPENED'
    | 'UNPAIRED' | 'PAIRED' | 'REDIFFED';
  data: { page_no?: number } | null;
}

export const effective = (s: Suggestion) => s.editedPayload ?? s.payload;

const post = <T>(path: string, body: unknown) => api<T>(path, { method: 'POST', body: JSON.stringify(body) });

// this queue renders OBLIGATION payloads only; PERMIT_ITEM rows are reviewed on the derivation screen
export const listSuggestions = (pid: number, runId?: number) =>
  api<Suggestion[]>(`/api/projects/${pid}/suggestions${runId ? `?runId=${runId}` : ''}`).then((all) =>
    all.filter((s) => s.kind === 'OBLIGATION'),
  );
/** ④ OBLIGATION_REVISION payload (version-diff design §2.3). prev = the compared obligation, for display. */
export type ChangeType = 'ADDED' | 'REMOVED' | 'CHANGED' | 'MINOR' | 'UNCHANGED';
export interface RevisionPayload {
  change_type: ChangeType;
  prev_obligation_id: number | null;
  prev_obligation_version: number | null;
  prev_obligation_closed: boolean;
  prev: ObligationPayload | null;
  new: ObligationPayload | null;
  match: { method: 'KEY' | 'SIMILARITY' | 'MANUAL' | null; similarity: number | null; flags: string[] };
  changed_fields: string[];
  numeric_changes: { before: string | null; after: string | null }[];
}
export type RevisionSuggestion = Omit<Suggestion, 'kind' | 'payload' | 'editedPayload'> & {
  kind: 'OBLIGATION_REVISION';
  payload: RevisionPayload;
  editedPayload: RevisionPayload | null;
};
export const listRevisions = (pid: number, runId?: number) =>
  api<(Suggestion | RevisionSuggestion)[]>(`/api/projects/${pid}/suggestions${runId ? `?runId=${runId}` : ''}`).then(
    (all) => all.filter((s): s is RevisionSuggestion => s.kind === 'OBLIGATION_REVISION'),
  );
export const suggestionEvents = (id: number) => api<SuggestionEvent[]>(`/api/suggestions/${id}/events`);
export const addSuggestion = (pid: number, permitItemId: number, payload: ObligationPayload) =>
  post<Suggestion>(`/api/projects/${pid}/suggestions`, { permitItemId, payload });
/** Decisions only need the row's id and version, so PERMIT_ITEM suggestions use them too. */
type Ref = { id: number; version: number };

export const editSuggestion = <P>(s: Ref, editedPayload: P) =>
  api<Suggestion>(`/api/suggestions/${s.id}`, { method: 'PATCH', body: JSON.stringify({ editedPayload, version: s.version }) });
export const submitSuggestion = (s: Suggestion, proposedDecision: 'ACCEPT' | 'REJECT', reason?: string) =>
  post<Suggestion>(`/api/suggestions/${s.id}/submit`, { proposedDecision, reason, version: s.version });
export const acceptSuggestion = (s: Ref, firstDueDate: string | null) =>
  post<Suggestion>(`/api/suggestions/${s.id}/accept`, { firstDueDate, version: s.version });
export const rejectSuggestion = (s: Ref, reason: string) =>
  post<Suggestion>(`/api/suggestions/${s.id}/reject`, { reason, version: s.version });
export const returnSuggestion = (s: Suggestion, reason: string) =>
  post<Suggestion>(`/api/suggestions/${s.id}/return`, { reason, version: s.version });
export const reopenSuggestion = (s: Suggestion) => post<Suggestion>(`/api/suggestions/${s.id}/reopen`, { version: s.version });
export const bulkAccept = (ids: number[]) =>
  post<{ id: number; ok: boolean; reason: string | null }[]>('/api/suggestions/bulk-accept', { ids });
// D7: opening a scan's page in the viewer is what allows approving it
export const recordPageView = (id: number, pageNo: number) => post<void>(`/api/suggestions/${id}/page-views`, { pageNo });
export const pageImage = (id: number, pageNo: number) => apiBlob(`/api/suggestions/${id}/pages/${pageNo}`);

// ④ version-diff/02: approving a revision, pair restructure and rediff
export const acceptRevision = (s: Ref, firstDueDate: string | null, confirmAbsent: boolean) =>
  post<RevisionSuggestion>(`/api/suggestions/${s.id}/accept`, { firstDueDate, confirmAbsent, version: s.version });
export const unpairRevision = (s: Ref) => post<RevisionSuggestion[]>(`/api/suggestions/${s.id}/unpair`, { version: s.version });
export const pairRevisions = (added: Ref, removed: Ref) =>
  post<RevisionSuggestion>('/api/suggestions/pair', {
    addedId: added.id, removedId: removed.id, addedVersion: added.version, removedVersion: removed.version,
  });
export const rediffRevision = (s: Ref) => post<RevisionSuggestion>(`/api/suggestions/${s.id}/rediff`, { version: s.version });
