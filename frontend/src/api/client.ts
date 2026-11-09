// Authenticated JSON fetch. Uses the stored session; on 401 tries one refresh,
// and if that fails clears the session and sends the user to /login.
import type { Session } from './auth';

const STORAGE_KEY = 'permit_session';

function session(): Session | null {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) ?? 'null');
  } catch {
    return null;
  }
}

async function refresh(s: Session): Promise<Session | null> {
  const res = await fetch('/api/auth/refresh', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken: s.refreshToken }),
  }).catch(() => null);
  if (!res?.ok) return null;
  const next = (await res.json()) as Session;
  localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
  return next;
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  let s = session();
  const send = (token?: string) =>
    fetch(path, {
      ...init,
      headers: { 'Content-Type': 'application/json', ...init.headers, Authorization: `Bearer ${token}` },
    });

  let res = await send(s?.accessToken).catch(() => {
    throw new ApiError(0, '서버에 연결할 수 없습니다.');
  });
  if (res.status === 401 && s) {
    s = await refresh(s);
    if (s) res = await send(s.accessToken);
  }
  if (res.status === 401) {
    localStorage.removeItem(STORAGE_KEY);
    window.location.href = '/login';
  }
  const body = await res.json().catch(() => null);
  if (!res.ok) {
    const message =
      res.status === 403 ? '이 프로젝트에 접근할 권한이 없습니다.' : body?.message ?? `요청에 실패했습니다. (${res.status})`;
    throw new ApiError(res.status, message);
  }
  return body as T;
}
