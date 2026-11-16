// Design schedule (design-schedule/02·03): company disciplines, per-project schedule items, base dates, baseline,
// delays, templates and Excel paste. Dates are computed by the server; the screen only shows them.
import { api } from './client';

export interface Discipline { id: number; code: string | null; name: string; sortOrder: number; active: boolean }

export type ItemKind = 'TASK' | 'MILESTONE';
export type ItemStatus = 'DONE' | 'DELAYED' | 'IN_PROGRESS' | 'PLANNED';
export type Anchor = 'CONTRACT' | 'KICKOFF' | 'SUBMISSION' | 'COMPLETION' | 'REVIEW' | 'SITE_BRIEFING' | 'NOTICE' | 'CONSTRUCTION_START';
export type ScheduleBase = 'SUBMISSION' | 'KICKOFF';

export interface ScheduleItem {
  id: number;
  disciplineId: number | null;
  disciplineName: string | null;
  parentId: number | null;
  kind: ItemKind;
  title: string;
  startDate: string | null; // null while a relative item's base date is not set
  endDate: string | null;
  baselineStart: string | null;
  baselineEnd: string | null;
  actualEnd: string | null;
  anchor: Anchor | null; // null = fixed date
  offsetDays: number | null;
  offsetMonths: number | null;
  durationDays: number | null;
  ownerUserId: number | null;
  ownerName: string | null;
  partnerName: string | null;
  weight: number | null;
  progress: number;
  delayReason: string | null;
  version: number;
  status: ItemStatus;
  delayDays: number; // past the current plan
  slipDays: number | null; // 기준 대비 연기 (end − baseline end); null before the first baseline
  submission: boolean; // 제출·보고 milestone (Google sync option)
}

export interface Dependency { predecessorId: number; successorId: number }
export interface AnchorDate { anchor: Anchor; date: string | null; note: string | null }
export interface Progress { planned: number; actual: number; weightTotal: number }
export interface Schedule {
  today: string;
  base: ScheduleBase;
  baselineNo: number;
  anchors: AnchorDate[];
  items: ScheduleItem[];
  dependencies: Dependency[];
  progress: Progress | null; // null when no item has a 보할
  delayCount: number;
}
export interface Move { id: number; title: string; fromStart: string | null; fromEnd: string | null; toStart: string; toEnd: string }
export interface AnchorChange { anchor: Anchor; from: string | null; to: string; count: number; moves: Move[] }
export interface DelayRow {
  id: number; disciplineName: string | null; title: string; ownerName: string | null; baselineEnd: string | null;
  endDate: string; actualEnd: string | null; delayDays: number; slipDays: number | null; reason: string | null; successors: number;
}
export interface TemplateItem {
  id: number; disciplineCode: string | null; kind: ItemKind; title: string; anchor: Anchor;
  offsetDays: number | null; offsetMonths: number | null; durationDays: number | null; submission: boolean;
}
export interface Template { id: number; code: string | null; name: string; items: TemplateItem[] }
export interface PasteRow {
  line: number; discipline: string; kind: ItemKind; title: string; start: string | null; end: string | null;
  owner: string; weight: number | null; error: string | null;
}
export interface PasteResult { rows: PasteRow[]; errors: number; created: number }

/**
 * Full set of editable fields: PATCH replaces them all (a Gantt drag sends the item back with new dates).
 * anchor null = fixed dates; anchor + offset = relative (server computes the dates); anchor without offsets =
 * [기준일에 연결] from the given dates. reason: required when the end moves past the baseline end.
 */
export interface ItemInput {
  disciplineId: number | null;
  parentId: number | null;
  kind: ItemKind;
  title: string;
  startDate: string | null;
  endDate: string | null;
  anchor: Anchor | null;
  offsetDays: number | null;
  offsetMonths: number | null;
  durationDays: number | null;
  ownerUserId: number | null;
  partnerName: string | null;
  weight: number | null;
  progress: number;
  predecessorIds: number[];
  version?: number;
  reason?: string | null;
  submission?: boolean; // milestones only; omitted = keep
}

export const STATUS_LABEL: Record<ItemStatus, string> = {
  DONE: '완료', DELAYED: '지연', IN_PROGRESS: '진행', PLANNED: '예정',
};

export const ANCHOR_LABEL: Record<Anchor, string> = {
  CONTRACT: '계약일', KICKOFF: '착수일', SUBMISSION: '입찰(제출)일', COMPLETION: '완료일', REVIEW: '심의일',
  SITE_BRIEFING: '현장설명일', NOTICE: '적격자 통보일', CONSTRUCTION_START: '착공일',
};
export const ANCHORS = Object.keys(ANCHOR_LABEL) as Anchor[];

/** "입찰(제출)일 −90일", "계약일 +4개월"; null for a fixed item. */
export function offsetLabel(i: Pick<ScheduleItem, 'anchor' | 'offsetDays' | 'offsetMonths'>): string | null {
  if (!i.anchor) return null;
  const n = i.offsetMonths ?? i.offsetDays ?? 0;
  return `${ANCHOR_LABEL[i.anchor]} ${n < 0 ? '−' : '+'}${Math.abs(n)}${i.offsetMonths != null ? '개월' : '일'}`;
}

/** Mirrors the server's rule, only to ask for the reason up front (the server still checks). */
export const needsReason = (item: ScheduleItem, newEnd: string | null) =>
  !!item.baselineEnd && !!newEnd && newEnd > item.baselineEnd && (!item.endDate || newEnd > item.endDate);

const send = (method: string, body?: unknown) => ({ method, body: body === undefined ? undefined : JSON.stringify(body) });

export const getDisciplines = () => api<Discipline[]>('/api/disciplines');
export const createDiscipline = (name: string) => api<Discipline>('/api/disciplines', send('POST', { name }));
export const updateDiscipline = (id: number, patch: Partial<Pick<Discipline, 'name' | 'sortOrder' | 'active'>>) =>
  api<Discipline>(`/api/disciplines/${id}`, send('PATCH', patch));

const base = (projectId: number) => `/api/projects/${projectId}/schedule`;
export const getSchedule = (projectId: number) => api<Schedule>(base(projectId));
export const createItem = (projectId: number, input: ItemInput) =>
  api<ScheduleItem>(`${base(projectId)}/items`, send('POST', input));
export const updateItem = (projectId: number, id: number, input: ItemInput) =>
  api<ScheduleItem>(`${base(projectId)}/items/${id}`, send('PATCH', input));
export const completeItem = (projectId: number, id: number, actualEnd: string) =>
  api<ScheduleItem>(`${base(projectId)}/items/${id}/complete`, send('POST', { actualEnd }));
export const reopenItem = (projectId: number, id: number) =>
  api<ScheduleItem>(`${base(projectId)}/items/${id}/complete`, send('DELETE'));
export const deleteItem = (projectId: number, id: number) => api<void>(`${base(projectId)}/items/${id}`, send('DELETE'));

export const setBase = (projectId: number, value: ScheduleBase) =>
  api<Schedule>(`${base(projectId)}/base`, send('PUT', { base: value }));
export const setAnchor = (projectId: number, anchor: Anchor, date: string, preview: boolean, note?: string | null) =>
  api<AnchorChange>(`${base(projectId)}/anchors/${anchor}?preview=${preview}`, send('PUT', { date, note }));
export const confirmBaseline = (projectId: number, reason: string | null) =>
  api<Schedule>(`${base(projectId)}/baseline`, send('POST', { reason }));
export const getDelays = (projectId: number) => api<DelayRow[]>(`${base(projectId)}/delays`);
export const getTemplates = () => api<Template[]>('/api/schedule-templates');
type TemplateItemInput = Pick<TemplateItem, 'title' | 'anchor' | 'offsetDays' | 'offsetMonths' | 'durationDays' | 'submission'>;
export const createTemplateItem = (templateId: number,
  input: TemplateItemInput & Pick<TemplateItem, 'kind' | 'disciplineCode'>) =>
  api<TemplateItem>(`/api/schedule-templates/${templateId}/items`, send('POST', input));
export const updateTemplateItem = (templateId: number, id: number, input: TemplateItemInput) =>
  api<TemplateItem>(`/api/schedule-templates/${templateId}/items/${id}`, send('PATCH', input));
export const deleteTemplateItem = (templateId: number, id: number) =>
  api<void>(`/api/schedule-templates/${templateId}/items/${id}`, send('DELETE'));
export const applyTemplate = (projectId: number, templateId: number) =>
  api<Schedule>(`${base(projectId)}/apply-template`, send('POST', { templateId }));
export const pasteItems = (projectId: number, text: string, confirm: boolean) =>
  api<PasteResult>(`${base(projectId)}/paste`, send('POST', { text, confirm }));

/** The item as an edit payload, its predecessors included. */
export const toInput = (item: ScheduleItem, deps: Dependency[]): ItemInput => ({
  disciplineId: item.disciplineId,
  parentId: item.parentId,
  kind: item.kind,
  title: item.title,
  startDate: item.startDate,
  endDate: item.endDate,
  anchor: item.anchor,
  offsetDays: item.offsetDays,
  offsetMonths: item.offsetMonths,
  durationDays: item.durationDays,
  ownerUserId: item.ownerUserId,
  partnerName: item.partnerName,
  weight: item.weight,
  progress: item.progress,
  predecessorIds: deps.filter((d) => d.successorId === item.id).map((d) => d.predecessorId),
  version: item.version,
  submission: item.submission,
});
