// Signup (public) and join-request approval (company admin).
import { api } from './client';
import type { Session } from './auth';

async function publicJson<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response;
  try {
    res = await fetch(path, { ...init, headers: { 'Content-Type': 'application/json' } });
  } catch {
    throw new Error('서버에 연결할 수 없습니다.');
  }
  const body = await res.json().catch(() => null);
  if (!res.ok) {
    // 400 = bean validation, whose message is Spring's English summary
    throw new Error(
      res.status === 400 ? '입력값을 확인해주세요. (비밀번호 8자 이상, 올바른 이메일)'
        : body?.message ?? `요청에 실패했습니다. (${res.status})`,
    );
  }
  return body as T;
}

const post = (body: object): RequestInit => ({ method: 'POST', body: JSON.stringify(body) });

export const signupCompany = (b: { companyName: string; name: string; email: string; password: string }) =>
  publicJson<Session>('/api/auth/signup', post(b));
export const getInvite = (code: string) =>
  publicJson<{ companyName: string }>(`/api/auth/invite/${encodeURIComponent(code)}`);
export const joinCompany = (b: { code: string; name: string; email: string; password: string }) =>
  publicJson<{ message: string }>('/api/auth/join', post(b));

export interface JoinRequest {
  id: number;
  name: string;
  email: string;
  createdAt: string;
}

export const getInviteCode = () => api<{ code: string }>('/api/company/invite-code');
export const regenerateInviteCode = () => api<{ code: string }>('/api/company/invite-code', { method: 'POST' });
export const getJoinRequests = () => api<JoinRequest[]>('/api/join-requests');
export const approveJoinRequest = (id: number) => api<void>(`/api/join-requests/${id}/approve`, { method: 'POST' });
export const rejectJoinRequest = (id: number, reason: string) =>
  api<void>(`/api/join-requests/${id}/reject`, { method: 'POST', body: JSON.stringify({ reason }) });
