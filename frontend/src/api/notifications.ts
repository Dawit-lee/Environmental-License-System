// Deadlines (dashboard, calendar) and in-app notifications (header bell).
import { api } from './client';

export interface Deadline {
  id: number;
  kind: string; // PERMIT_EXPIRY, OBLIGATION, …
  targetId: number;
  projectId: number;
  projectName: string;
  title: string;
  dueDate: string; // YYYY-MM-DD
  dDay: number;
}

export interface AppNotification {
  id: number;
  message: string;
  link: string | null;
  dueDate: string | null;
  offsetDays: number | null;
  createdAt: string;
  readAt: string | null;
}

/** Local YYYY-MM-DD. */
export function ymd(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

export const getDeadlines = (from: string, to: string) =>
  api<Deadline[]>(`/api/deadlines?from=${from}&to=${to}`);
export const getNotifications = () => api<AppNotification[]>('/api/notifications');
export const markRead = (id: number) => api<void>(`/api/notifications/${id}/read`, { method: 'POST' });
export const markAllRead = () => api<void>('/api/notifications/read-all', { method: 'POST' });
