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

/** Logged-in user's id (null when signed out). */
export const sessionUserId = (): number | null => session()?.user.id ?? null;
/** Company master (ADMIN) may change company settings. */
export const isMaster = (): boolean => session()?.user.role === 'ADMIN';

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

// Sends with the bearer token; on 401 refreshes once, then gives up and goes to /login.
async function authFetch(path: string, init: RequestInit = {}): Promise<Response> {
  let s = session();
  // FormData sets its own multipart Content-Type (with boundary)
  const type: Record<string, string> = init.body instanceof FormData ? {} : { 'Content-Type': 'application/json' };
  const send = (token?: string) =>
    fetch(path, {
      ...init,
      headers: { ...type, ...init.headers, Authorization: `Bearer ${token}` },
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
  return res;
}

function failure(status: number, body: { message?: string } | null): ApiError {
  const message =
    status === 403 ? '이 프로젝트에 접근할 권한이 없습니다.' : body?.message ?? `요청에 실패했습니다. (${status})`;
  return new ApiError(status, message);
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const res = await authFetch(path, init);
  const body = await res.json().catch(() => null);
  if (!res.ok) throw failure(res.status, body);
  return body as T;
}

/** For file downloads. */
export async function apiBlob(path: string): Promise<Blob> {
  const res = await authFetch(path);
  if (!res.ok) throw failure(res.status, await res.json().catch(() => null));
  return res.blob();
}
