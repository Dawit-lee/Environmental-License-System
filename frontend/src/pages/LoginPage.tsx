import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { login, type Session } from '../api/auth';

// ---------------------------------------------------------------------------
// LoginPage — POST /api/auth/login. The parent (PermitApp) persists the session.
// ---------------------------------------------------------------------------

export default function LoginPage({ onLogin }: { onLogin: (session: Session) => void }) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (email.trim() === '' || password.trim() === '') {
      setError('이메일과 비밀번호를 입력해주세요.');
      return;
    }
    setSubmitting(true);
    try {
      onLogin(await login(email.trim(), password));
    } catch (err) {
      setError((err as Error).message);
      setSubmitting(false);
    }
  };

  const inputClasses =
    'w-full rounded-lg border border-line px-3 py-2.5 text-sm outline-none transition focus:border-brand focus:ring-2 focus:ring-brand/20';

  return (
    <div className="flex min-h-screen items-center justify-center bg-paper px-4" style={{ fontFamily: 'system-ui, sans-serif' }}>
      <div className="w-full max-w-sm">
        <div className="mb-6 text-center">
          <div className="text-3xl">🌿</div>
          <h1 className="mt-2 text-xl font-bold text-brand">환경시설 인허가 관리</h1>
          <p className="mt-1 text-sm text-gray-500">로그인 후 이용하실 수 있습니다</p>
        </div>

        <form
          onSubmit={handleSubmit}
          className="rounded-xl border border-line bg-white p-6 shadow-sm"
        >
          <label className="mb-1.5 block text-sm font-medium text-gray-700">이메일</label>
          <input
            type="email"
            value={email}
            onChange={(e) => { setEmail(e.target.value); setError(''); }}
            placeholder="you@example.com"
            className={inputClasses}
            autoFocus
          />

          <label className="mb-1.5 mt-4 block text-sm font-medium text-gray-700">비밀번호</label>
          <input
            type="password"
            value={password}
            onChange={(e) => { setPassword(e.target.value); setError(''); }}
            placeholder="••••••••"
            className={inputClasses}
          />

          {error && <p role="alert" className="mt-3 text-sm text-danger">{error}</p>}

          <button
            type="submit"
            disabled={submitting}
            className="mt-5 w-full rounded-lg bg-brand px-4 py-2.5 text-sm font-semibold text-white transition hover:bg-brand-hover focus:outline-none focus:ring-2 focus:ring-brand/40 disabled:opacity-60"
          >
            {submitting ? '로그인 중…' : '로그인'}
          </button>

          <p className="mt-4 text-center text-sm text-gray-500">
            계정이 없나요? <Link to="/signup" className="font-medium text-brand hover:underline">회원가입</Link>
          </p>
        </form>
      </div>
    </div>
  );
}
