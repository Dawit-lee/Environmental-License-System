import { useState, type FormEvent } from 'react';
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

  const handleKakaoLogin = () => {
    // TODO: replace with real Kakao OAuth.
    //   1) redirect to https://kauth.kakao.com/oauth/authorize
    //      ?client_id=${KAKAO_REST_API_KEY}&redirect_uri=${REDIRECT_URI}&response_type=code
    //   2) handle the code callback → exchange for token → fetch profile
    setError('카카오 로그인은 아직 준비 중입니다.');
  };

  const inputClasses =
    'w-full rounded-lg border border-[#E5E3DC] px-3 py-2.5 text-sm outline-none transition focus:border-[#1F4E79] focus:ring-2 focus:ring-[#1F4E79]/20';

  return (
    <div className="flex min-h-screen items-center justify-center bg-[#F8F7F2] px-4" style={{ fontFamily: 'system-ui, sans-serif' }}>
      <div className="w-full max-w-sm">
        <div className="mb-6 text-center">
          <div className="text-3xl">🌿</div>
          <h1 className="mt-2 text-xl font-bold text-[#1F4E79]">환경시설 인허가 관리</h1>
          <p className="mt-1 text-sm text-gray-500">로그인 후 이용하실 수 있습니다</p>
        </div>

        <form
          onSubmit={handleSubmit}
          className="rounded-xl border border-[#E5E3DC] bg-white p-6 shadow-sm"
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

          {error && <p role="alert" className="mt-3 text-sm text-[#991B1B]">{error}</p>}

          <button
            type="submit"
            disabled={submitting}
            className="mt-5 w-full rounded-lg bg-[#1F4E79] px-4 py-2.5 text-sm font-semibold text-white transition hover:bg-[#173a5c] focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/40 disabled:opacity-60"
          >
            {submitting ? '로그인 중…' : '로그인'}
          </button>

          {/* Divider */}
          <div className="my-4 flex items-center gap-3">
            <span className="h-px flex-1 bg-[#E5E3DC]" />
            <span className="text-xs text-gray-400">또는</span>
            <span className="h-px flex-1 bg-[#E5E3DC]" />
          </div>

          {/* Kakao OAuth (mock) */}
          <button
            type="button"
            onClick={handleKakaoLogin}
            className="flex w-full items-center justify-center gap-2 rounded-lg bg-[#FEE500] px-4 py-2.5 text-sm font-semibold text-[#191600] transition hover:brightness-95 focus:outline-none focus:ring-2 focus:ring-[#FEE500]/60"
          >
            <span aria-hidden className="text-base leading-none">💬</span>
            카카오 로그인
          </button>

        </form>
      </div>
    </div>
  );
}
