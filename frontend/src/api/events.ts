// Project events and trips (design-schedule/04): project CRUD and the company / project calendar feed.
import { api } from './client';

export type EventKind = 'SITE_BRIEFING' | 'QNA_DEADLINE' | 'SUBMISSION' | 'CLIENT_REPORT' | 'REVIEW' | 'MEETING' | 'TRIP' | 'OTHER';

export const EVENT_KIND_LABEL: Record<EventKind, string> = {
  SITE_BRIEFING: '현장설명회', QNA_DEADLINE: '질의 마감', SUBMISSION: '입찰·제출', CLIENT_REPORT: '발주처 보고',
  REVIEW: '심의', MEETING: '회의', TRIP: '출장', OTHER: '기타',
};
export const EVENT_KINDS = Object.keys(EVENT_KIND_LABEL) as EventKind[];
/** 출장·회의 need 상대 기관 (server answers 400 otherwise). */
export const NEEDS_COUNTERPART: EventKind[] = ['TRIP', 'MEETING'];

export interface Attendee { userId: number | null; name: string | null; externalName: string | null; role: string | null }

export interface ProjectEvent {
  id: number;
  projectId: number;
  projectName: string;
  kind: EventKind;
  subkind: string | null;
  title: string;
  startsAt: string; // local YYYY-MM-DDTHH:mm:ss
  endsAt: string; // all-day: the last day at 00:00
  allDay: boolean;
  location: string | null;
  counterpartOrg: string | null;
  purpose: string | null;
  disciplineId: number | null;
  disciplineName: string | null;
  checklist: string | null;
  version: number;
  attendees: Attendee[];
}

export interface CalendarMilestone {
  id: number; projectId: number; projectName: string; title: string; date: string; disciplineName: string | null; done: boolean;
}
export interface CalendarDeadline { id: number; kind: string; projectId: number; projectName: string; title: string; dueDate: string }
export interface CalendarData { events: ProjectEvent[]; milestones: CalendarMilestone[]; deadlines: CalendarDeadline[] }

export interface EventInput {
  kind: EventKind;
  subkind: string | null;
  title: string;
  startsAt: string;
  endsAt: string;
  allDay: boolean;
  location: string | null;
  counterpartOrg: string | null;
  purpose: string | null;
  disciplineId: number | null;
  checklist: string | null;
  attendees: { userId: number | null; externalName: string | null; role: string | null }[];
  /** create only; server default = on for 제출·보고회 */
  addMilestone?: boolean;
  version?: number;
}

export interface CalendarFilter { kind?: string; dept?: string; team?: string; project?: string }

const send = (method: string, body?: unknown) => ({ method, body: body === undefined ? undefined : JSON.stringify(body) });
const base = (projectId: number) => `/api/projects/${projectId}/events`;

export const getCalendar = (from: string, to: string, filter: CalendarFilter = {}) => {
  const q = new URLSearchParams({ from, to });
  Object.entries(filter).forEach(([k, v]) => v && q.set(k, v));
  return api<CalendarData>(`/api/calendar?${q}`);
};
export const getProjectEvents = (projectId: number) => api<ProjectEvent[]>(base(projectId));
export const createEvent = (projectId: number, input: EventInput) => api<ProjectEvent>(base(projectId), send('POST', input));
export const updateEvent = (projectId: number, id: number, input: EventInput) =>
  api<ProjectEvent>(`${base(projectId)}/${id}`, send('PATCH', input));
export const deleteEvent = (projectId: number, id: number) => api<void>(`${base(projectId)}/${id}`, send('DELETE'));

export const toEventInput = (e: ProjectEvent): EventInput => ({
  kind: e.kind, subkind: e.subkind, title: e.title, startsAt: e.startsAt.slice(0, 16), endsAt: e.endsAt.slice(0, 16),
  allDay: e.allDay, location: e.location, counterpartOrg: e.counterpartOrg, purpose: e.purpose,
  disciplineId: e.disciplineId, checklist: e.checklist,
  attendees: e.attendees.map(({ userId, externalName, role }) => ({ userId, externalName, role })), version: e.version,
});

/** "1/20(화) 14:00–16:00", "1/20(화)–1/22(목)" — one line for tables and panels. */
export function eventWhen(e: Pick<ProjectEvent, 'startsAt' | 'endsAt' | 'allDay'>): string {
  const day = (s: string) => {
    const d = new Date(s);
    return `${d.getMonth() + 1}/${d.getDate()}(${'일월화수목금토'[d.getDay()]})`;
  };
  const sameDay = e.startsAt.slice(0, 10) === e.endsAt.slice(0, 10);
  if (e.allDay) return sameDay ? day(e.startsAt) : `${day(e.startsAt)}–${day(e.endsAt)}`;
  const time = (s: string) => s.slice(11, 16);
  return sameDay ? `${day(e.startsAt)} ${time(e.startsAt)}–${time(e.endsAt)}` : `${day(e.startsAt)} ${time(e.startsAt)}–${day(e.endsAt)} ${time(e.endsAt)}`;
}
