import { useEffect, useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';
import type { Session } from '../api/auth';
import { getInvite, joinCompany, signupCompany } from '../api/signup';

// ---------------------------------------------------------------------------
// SignupPage — /signup (tabs: new company | invite code) and /join/:code.
// New company → its first admin, logged in at once. Invite → join request; a company admin approves.
// ---------------------------------------------------------------------------

const inputClasses =
  'w-full rounded-lg border border-[#E5E3DC] px-3 py-2.5 text-sm outline-none transition focus:border-[#1F4E79] focus:ring-2 focus:ring-[#1F4E79]/20';

function Field({ label, ...props }: { label: string } & React.InputHTMLAttributes<HTMLInputElement>) {
  return (
    <label className="mt-4 block first:mt-0">
      <span className="mb-1.5 block text-sm font-medium text-gray-700">{label}</span>
      <input {...props} required className={inputClasses} />
    </label>
  );
}

export default function SignupPage({ onLogin }: { onLogin: (session: Session) => void }) {
  const { code: linkCode } = useParams();
  const [tab, setTab] = useState<'company' | 'invite'>(linkCode ? 'invite' : 'company');
  const [code, setCode] = useState(linkCode ?? '');
  const [companyName, setCompanyName] = useState('');
  const [inviteCompany, setInviteCompany] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [done, setDone] = useState('');
  const [submitting, setSubmitting] = useState(false);

  // show which company the code belongs to
  useEffect(() => {
    setInviteCompany(null);
    if (tab !== 'invite' || code.trim().length < 10) return;
    getInvite(code.trim()).then((r) => setInviteCompany(r.companyName), (e) => setError((e as Error).message));
  }, [tab, code]);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (password.length < 8) {
      setError('비밀번호는 8자 이상이어야 합니다.');
      return;
    }
    setSubmitting(true);
    setError('');
    try {
      const who = { name: name.trim(), email: email.trim(), password };
      if (tab === 'company') onLogin(await signupCompany({ companyName: companyName.trim(), ...who }));
      else setDone((await joinCompany({ code: code.trim(), ...who })).message);
    } catch (err) {
      setError((err as Error).message);
    }
    setSubmitting(false);
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-[#F8F7F2] px-4" style={{ fontFamily: 'system-ui, sans-serif' }}>
      <div className="w-full max-w-sm">
        <div className="mb-6 text-center">
          <div className="text-3xl">🌿</div>
          <h1 className="mt-2 text-xl font-bold text-[#1F4E79]">회원가입</h1>
        </div>

        {done ? (
          <div role="status" className="rounded-xl border border-[#E5E3DC] bg-white p-6 text-sm text-gray-700 shadow-sm">
            <p>{done}</p>
            <Link to="/login" className="mt-4 inline-block font-medium text-[#1F4E79] hover:underline">로그인 화면으로</Link>
          </div>
        ) : (
          <form onSubmit={handleSubmit} className="rounded-xl border border-[#E5E3DC] bg-white p-6 shadow-sm">
            <div role="tablist" className="mb-5 grid grid-cols-2 gap-1 rounded-lg bg-[#F8F7F2] p-1">
              {([['company', '새 회사 만들기'], ['invite', '초대 코드로 가입']] as const).map(([t, label]) => (
                <button
                  key={t}
                  type="button"
                  role="tab"
                  aria-selected={tab === t}
                  onClick={() => { setTab(t); setError(''); }}
                  className={`rounded-md px-2 py-1.5 text-sm font-medium transition ${tab === t ? 'bg-white text-[#1F4E79] shadow-sm' : 'text-gray-500'}`}
                >
                  {label}
                </button>
              ))}
            </div>

            {tab === 'company' ? (
              <>
                <Field label="회사명" value={companyName} onChange={(e) => setCompanyName(e.target.value)} autoFocus />
                <p className="mt-1.5 text-xs text-gray-500">처음 만든 사람이 회사 관리자가 됩니다.</p>
              </>
            ) : (
              <>
                <Field label="초대 코드" value={code} onChange={(e) => { setCode(e.target.value.toUpperCase()); setError(''); }}
                  placeholder="관리자에게 받은 10자리 코드" autoFocus={!linkCode} />
                {inviteCompany && <p className="mt-1.5 text-xs text-gray-600">가입할 회사: <b>{inviteCompany}</b></p>}
              </>
            )}
            <div className="mt-4">
              <Field label="이름" value={name} onChange={(e) => setName(e.target.value)} autoFocus={!!linkCode} />
              <Field label="이메일" type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="회사 이메일" />
              <Field label="비밀번호 (8자 이상)" type="password" value={password} onChange={(e) => { setPassword(e.target.value); setError(''); }} />
            </div>

            {error && <p role="alert" className="mt-3 text-sm text-[#991B1B]">{error}</p>}

            <button
              type="submit"
              disabled={submitting}
              className="mt-5 w-full rounded-lg bg-[#1F4E79] px-4 py-2.5 text-sm font-semibold text-white transition hover:bg-[#173a5c] focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/40 disabled:opacity-60"
            >
              {submitting ? '처리 중…' : tab === 'company' ? '회사 만들고 시작하기' : '가입 신청'}
            </button>
            <p className="mt-4 text-center text-sm text-gray-500">
              이미 계정이 있나요? <Link to="/login" className="font-medium text-[#1F4E79] hover:underline">로그인</Link>
            </p>
          </form>
        )}
      </div>
    </div>
  );
}
