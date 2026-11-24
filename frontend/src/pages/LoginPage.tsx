import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { login, type Session } from '../api/auth';
import { Button } from '../components/ui/Button';
import { Input } from '../components/ui/Field';

// Public demo build only (VITE_DEMO_LOGIN=true): a read-only account whose password is public on purpose.
const DEMO = import.meta.env.VITE_DEMO_LOGIN === 'true' ? { email: 'demo@envpermit.local', password: 'demo1234!' } : null;

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
    await signIn(email.trim(), password);
  };

  const signIn = async (id: string, pw: string) => {
    setSubmitting(true);
    try {
      onLogin(await login(id, pw));
    } catch (err) {
      setError((err as Error).message);
      setSubmitting(false);
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-paper px-4">
      <div className="w-full max-w-sm">
        <div className="mb-6 text-center">
          <h1 className="text-2xl font-bold text-ink">환경시설 인허가 관리</h1>
          <p className="mt-1 text-[15px] text-body">로그인 후 이용하실 수 있습니다</p>
        </div>

        <form
          onSubmit={handleSubmit}
          className="flex flex-col gap-4 rounded border border-line bg-white p-6"
        >
          <Input
            label="이메일"
            type="email"
            value={email}
            onChange={(e) => { setEmail(e.target.value); setError(''); }}
            placeholder="you@example.com"
            autoFocus
          />

          <Input
            label="비밀번호"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => { setPassword(e.target.value); setError(''); }}
            placeholder="••••••••"
          />

          {error && <p role="alert" className="text-[15px] text-danger">{error}</p>}

          <Button type="submit" disabled={submitting} className="w-full">
            {submitting ? '로그인 중…' : '로그인'}
          </Button>

          {DEMO && (
            <>
              <Button
                variant="secondary"
                disabled={submitting}
                onClick={() => { setError(''); void signIn(DEMO.email, DEMO.password); }}
                className="-mt-2 w-full"
              >
                데모 계정으로 둘러보기
              </Button>
              <p className="-mt-2 text-center text-[13px] text-mute">
                ID {DEMO.email} · PW {DEMO.password} (조회만 가능)
              </p>
            </>
          )}

          <p className="text-center text-[15px] text-body">
            계정이 없나요? <Link to="/signup" className="font-bold text-brand-text underline-offset-2 hover:underline">회원가입</Link>
          </p>
        </form>
      </div>
    </div>
  );
}
