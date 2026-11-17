import { afterEach, expect, it, vi } from 'vitest';
import { api } from './client';

afterEach(() => vi.unstubAllGlobals());

it('shares one refresh across concurrent 401s and retries each request', async () => {
  const store = new Map([['permit_session', JSON.stringify({ accessToken: 'old', refreshToken: 'r', user: { id: 1 } })]]);
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => store.set(k, v),
    removeItem: (k: string) => store.delete(k),
  });
  vi.stubGlobal('window', { location: { href: '' } });
  let refreshes = 0;
  const retried: string[] = [];
  vi.stubGlobal('fetch', async (path: string, init: RequestInit) => {
    if (path === '/api/auth/refresh') {
      refreshes++;
      await new Promise((r) => setTimeout(r, 10));
      return new Response(JSON.stringify({ accessToken: 'new', refreshToken: 'r2', user: { id: 1 } }));
    }
    const auth = (init.headers as Record<string, string>).Authorization;
    if (auth === 'Bearer old') return new Response(null, { status: 401 });
    retried.push(path);
    return new Response(JSON.stringify({ path }));
  });

  const results = await Promise.all(['/a', '/b', '/c'].map((p) => api<{ path: string }>(p)));

  expect(refreshes).toBe(1);
  expect(retried.sort()).toEqual(['/a', '/b', '/c']);
  expect(results.map((r) => r.path)).toEqual(['/a', '/b', '/c']);
});
