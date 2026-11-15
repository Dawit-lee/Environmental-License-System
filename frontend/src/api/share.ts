// Partner read-only sharing and personal .ics (design-schedule/05). The token is shown once, at creation.
import { api } from './client';

export interface ShareLink {
  id: number;
  disciplineIds: number[] | null; // null = every discipline incl. 공통
  includeEvents: boolean;
  expiresAt: string;
  revokedAt: string | null;
  createdByName: string | null;
  createdAt: string;
  lastAccessedAt: string | null;
  active: boolean;
}
export interface CreatedLink { link: ShareLink; path: string; icsPath: string }

/** What a partner sees: title, dates, place, discipline and partner name only. */
export interface SharedItem { kind: 'TASK' | 'MILESTONE'; title: string; startDate: string; endDate: string; disciplineName: string | null; partnerName: string | null }
export interface SharedEvent { title: string; startsAt: string; endsAt: string; allDay: boolean; location: string | null; disciplineName: string | null }
export interface SharedSchedule { projectName: string; expiresAt: string; items: SharedItem[]; events: SharedEvent[] }

const links = (projectId: number) => `/api/projects/${projectId}/share-links`;

export const getShareLinks = (projectId: number) => api<ShareLink[]>(links(projectId));
export const createShareLink = (projectId: number, body: { disciplineIds: number[] | null; includeEvents: boolean; expiresInDays: number }) =>
  api<CreatedLink>(links(projectId), { method: 'POST', body: JSON.stringify(body) });
export const revokeShareLink = (projectId: number, id: number) => api<void>(`${links(projectId)}/${id}`, { method: 'DELETE' });

export const issueCalendarToken = () => api<{ icsPath: string }>('/api/me/calendar-token', { method: 'POST' });

/** Public, no session: null when the link is unknown, expired or revoked (the server does not say which). */
export async function getShared(token: string): Promise<SharedSchedule | null> {
  const res = await fetch(`/share/${encodeURIComponent(token)}`, { headers: { Accept: 'application/json' } });
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(res.status === 429 ? '요청이 너무 많습니다. 잠시 후 다시 열어 주세요.' : `불러오지 못했습니다. (${res.status})`);
  return res.json();
}

/** Absolute URL for a server path; calendar apps want webcal:// for a subscription. */
export const absolute = (path: string) => window.location.origin + path;
export const webcal = (path: string) => absolute(path).replace(/^https?:/, 'webcal:');
