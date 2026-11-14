// Design schedule (design-schedule/02): company disciplines and per-project schedule items.
import { api } from './client';

export interface Discipline { id: number; code: string | null; name: string; sortOrder: number; active: boolean }

export type ItemKind = 'TASK' | 'MILESTONE';
export type ItemStatus = 'DONE' | 'DELAYED' | 'IN_PROGRESS' | 'PLANNED';

export interface ScheduleItem {
  id: number;
  disciplineId: number | null;
  disciplineName: string | null;
  parentId: number | null;
  kind: ItemKind;
  title: string;
  startDate: string;
  endDate: string;
  actualEnd: string | null;
  ownerUserId: number | null;
  ownerName: string | null;
  partnerName: string | null;
  progress: number;
  version: number;
  status: ItemStatus;
  delayDays: number;
}

export interface Dependency { predecessorId: number; successorId: number }
export interface Schedule { today: string; items: ScheduleItem[]; dependencies: Dependency[] }

/** Full set of editable fields: PATCH replaces them all (a Gantt drag sends the item back with new dates). */
export interface ItemInput {
  disciplineId: number | null;
  parentId: number | null;
  kind: ItemKind;
  title: string;
  startDate: string | null;
  endDate: string;
  ownerUserId: number | null;
  partnerName: string | null;
  progress: number;
  predecessorIds: number[];
  version?: number;
}

export const STATUS_LABEL: Record<ItemStatus, string> = {
  DONE: '완료', DELAYED: '지연', IN_PROGRESS: '진행', PLANNED: '예정',
};

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
export const deleteItem = (projectId: number, id: number) => api<void>(`${base(projectId)}/items/${id}`, send('DELETE'));

/** The item as an edit payload, its predecessors included. */
export const toInput = (item: ScheduleItem, deps: Dependency[]): ItemInput => ({
  disciplineId: item.disciplineId,
  parentId: item.parentId,
  kind: item.kind,
  title: item.title,
  startDate: item.startDate,
  endDate: item.endDate,
  ownerUserId: item.ownerUserId,
  partnerName: item.partnerName,
  progress: item.progress,
  predecessorIds: deps.filter((d) => d.successorId === item.id).map((d) => d.predecessorId),
  version: item.version,
});
