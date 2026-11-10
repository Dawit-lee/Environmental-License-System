// operations-compliance/01: one task per obligation due date, closed with evidence documents.
import { api } from './client';
import type { PermitDocument } from '../types';

export interface Task {
  id: number;
  projectId: number;
  kind: 'OBLIGATION' | 'CORRECTIVE';
  obligationId: number | null;
  permitItemId: number | null;
  title: string;
  dueDate: string;
  assigneeId: number | null;
  assigneeName: string | null;
  status: 'OPEN' | 'DONE' | 'CANCELLED';
  note: string | null;
  doneByName: string | null;
  doneAt: string | null;
  /** finished after its due date */
  late: boolean;
  cancelledReason: 'OBLIGATION_CLOSED' | 'REVISION_REMOVED' | 'MANUAL' | null;
  version: number;
  evidenceCount: number;
  /** live measured values entered for this task; they count like evidence */
  measurementCount: number;
}

export interface TaskEvidence {
  documentId: number;
  permitItemId: number;
  fileName: string;
  addedByName: string | null;
  addedAt: string;
}

export interface TaskMeasurement {
  id: number;
  pointName: string;
  pollutantName: string;
  measuredOn: string;
  value: number;
  unit: string;
  belowDetection: boolean;
  level: 'NORMAL' | 'NEAR' | 'EXCEEDED' | 'NO_LIMIT';
}

export interface TaskEvent {
  type: 'CREATED' | 'ASSIGNED' | 'DUE_CHANGED' | 'EVIDENCE_ADDED' | 'EVIDENCE_REMOVED' | 'COMPLETED' | 'CANCELLED';
  data: Record<string, unknown> | null;
  at: string;
  byName: string | null;
}

export interface TaskDetail {
  task: Task;
  evidence: TaskEvidence[];
  measurements: TaskMeasurement[];
  events: TaskEvent[];
}

const today = () => new Date().toLocaleDateString('sv-SE');

/** 열림 / 기한 경과 / 완료 / 지연 완료 / 취소. Overdue is computed, not stored. */
export function taskStatusLabel(t: Task): string {
  if (t.status === 'OPEN') return t.dueDate < today() ? '기한 경과' : '열림';
  if (t.status === 'DONE') return t.late ? '지연 완료' : '완료';
  return '취소';
}
export const isOverdue = (t: Task) => t.status === 'OPEN' && t.dueDate < today();

/** One obligation's cycles, oldest first. */
export const listObligationTasks = (pid: number, obligationId: number) =>
  api<Task[]>(`/api/projects/${pid}/tasks?obligationId=${obligationId}`);
export const getTask = (id: number) => api<TaskDetail>(`/api/tasks/${id}`);
export const addEvidence = (id: number, documentId: number) =>
  api<TaskDetail>(`/api/tasks/${id}/evidence`, { method: 'POST', body: JSON.stringify({ documentId }) });
export const removeEvidence = (id: number, documentId: number) =>
  api<TaskDetail>(`/api/tasks/${id}/evidence/${documentId}`, { method: 'DELETE' });
export const completeTask = (id: number, body: { note?: string; nextDueDate?: string; version: number }) =>
  api<TaskDetail>(`/api/tasks/${id}/complete`, { method: 'POST', body: JSON.stringify(body) });

const docs = (pid: number, permitId: number) => `/api/projects/${pid}/permits/${permitId}/documents`;
export const listPermitDocuments = (pid: number, permitId: number) => api<PermitDocument[]>(docs(pid, permitId));
/** Evidence uploaded from a task is stored as a document of the task's permit item. */
export function uploadPermitDocument(pid: number, permitId: number, file: File) {
  const form = new FormData();
  form.append('file', file);
  return api<PermitDocument>(docs(pid, permitId), { method: 'POST', body: form });
}
export const documentPath = (pid: number, permitId: number, documentId: number) => `${docs(pid, permitId)}/${documentId}`;
