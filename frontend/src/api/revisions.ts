// ④ version-diff/03: site implementation of approved revisions, permit obligations and their revision log.
import { api } from './client';
import type { Frequency } from './review';

export interface SiteImplementation {
  suggestionId: number;
  obligationId: number;
  permitItemId: number;
  permitName: string;
  projectId: number;
  citationKey: string | null;
  summary: string;
  changeType: 'CHANGED' | 'ADDED';
  assigneeId: number | null;
  assigneeName: string | null;
  targetDate: string | null;
  note: string | null;
  doneBy: number | null;
  doneAt: string | null;
}

export interface Obligation {
  id: number;
  permitItemId: number;
  citationKey: string | null;
  summary: string;
  frequency: Frequency;
  firstDueDate: string | null;
  nextDueDate: string | null;
  status: 'ACTIVE' | 'CLOSED';
  sourceDocumentId: number | null;
}

export interface RevisionLogEntry {
  id: number;
  changeType: 'CHANGED' | 'MINOR' | 'REMOVED' | 'UNCHANGED';
  before: Record<string, unknown>;
  at: string;
  by: number | null;
  byName: string | null;
}

export const listSiteImplementations = (pid: number, status?: 'open' | 'done') =>
  api<SiteImplementation[]>(`/api/projects/${pid}/revision-implementations${status ? `?status=${status}` : ''}`);
export const openSiteImplementationCount = () => api<{ count: number }>('/api/revision-implementations/count');
/** Sends every field: a null targetDate clears it (and its deadline). */
export const updateSiteImplementation = (r: SiteImplementation) =>
  api<SiteImplementation>(`/api/revision-implementations/${r.suggestionId}`, {
    method: 'PATCH', body: JSON.stringify({ assigneeId: r.assigneeId, targetDate: r.targetDate, note: r.note }),
  });
export const completeSiteImplementation = (suggestionId: number) =>
  api<SiteImplementation>(`/api/revision-implementations/${suggestionId}/complete`, { method: 'POST' });
export const listObligations = (pid: number, permitId: number) =>
  api<Obligation[]>(`/api/projects/${pid}/permits/${permitId}/obligations`);
export const revisionLog = (obligationId: number) => api<RevisionLogEntry[]>(`/api/obligations/${obligationId}/revision-log`);
export const listProjectObligations = (pid: number) => api<Obligation[]>(`/api/projects/${pid}/obligations`);
/** "이행 완료": next due date by frequency; pass nextDueDate when the server can't compute it (OTHER / no date). */
export const completeObligation = (id: number, nextDueDate?: string) =>
  api<Obligation>(`/api/obligations/${id}/complete`, { method: 'POST', body: JSON.stringify({ nextDueDate: nextDueDate ?? null }) });
