import { api, ApiError } from './client';

// 내 Google 캘린더 한 방향 동기화 (design-schedule/06). The server answers 404 when the feature is not configured.
export interface GoogleCalendarStatus {
  companyEnabled: boolean;
  connected: boolean;
  connectedAt: string | null;
  lastSyncedAt: string | null;
  lastError: string | null;
  projectMilestones: boolean;
  tasks: boolean;
}

/** null = feature off on this server (hide the panel). */
export const getGoogleCalendar = () =>
  api<GoogleCalendarStatus>('/api/me/google-calendar').catch((e) => {
    if (e instanceof ApiError && e.status === 404) return null;
    throw e;
  });
export const googleConnectUrl = () => api<{ url: string }>('/api/me/google-calendar/connect');
export const setGoogleTargets = (projectMilestones: boolean, tasks: boolean) =>
  api<GoogleCalendarStatus>('/api/me/google-calendar', { method: 'PUT', body: JSON.stringify({ projectMilestones, tasks }) });
export const disconnectGoogle = (deleteCalendar: boolean) =>
  api<void>(`/api/me/google-calendar?deleteCalendar=${deleteCalendar}`, { method: 'DELETE' });
export const setCompanyGoogleSync = (enabled: boolean) =>
  api<unknown>('/api/company/google-sync', { method: 'PUT', body: JSON.stringify({ enabled }) });
