// Auth API client. In dev, Vite proxies /api to the backend (see vite.config.ts).

export interface Session {
  accessToken: string;
  refreshToken: string;
  user: { id: number; email: string; name: string; role: 'ADMIN' | 'USER' };
}

export async function login(email: string, password: string): Promise<Session> {
  let res: Response;
  try {
    res = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    });
  } catch {
    throw new Error('서버에 연결할 수 없습니다.');
  }
  const body = await res.json().catch(() => null);
  if (!res.ok) {
    throw new Error(body?.message ?? `로그인에 실패했습니다. (${res.status})`);
  }
  return body as Session;
}
