import { useEffect, useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';
import type { Session } from '../api/auth';
import { getInvite, joinCompany, signupCompany } from '../api/signup';
import { Button } from '../components/ui/Button';
import { Input } from '../components/ui/Field';
import { Tabs } from '../components/ui/Tabs';

// ---------------------------------------------------------------------------
// SignupPage — /signup (tabs: new company | invite code) and /join/:code.
// New company → its first admin, logged in at once. Invite → join request; a company admin approves.
// ---------------------------------------------------------------------------

/** Every signup field is required. */
const Field = (props: Parameters<typeof Input>[0]) => <Input {...props} required />;

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
    <div className="flex min-h-screen items-center justify-center bg-paper px-4">
      <div className="w-full max-w-sm">
        <div className="mb-6 text-center">
          <h1 className="text-2xl font-bold text-ink">회원가입</h1>
        </div>

        {done ? (
          <div role="status" className="rounded border border-line bg-white p-6 text-base text-body">
            <p>{done}</p>
            <Link to="/login" className="mt-4 inline-block font-bold text-brand-text hover:underline">로그인 화면으로</Link>
          </div>
        ) : (
          <form onSubmit={handleSubmit} className="flex flex-col gap-4 rounded border border-line bg-white p-6">
            <Tabs
              label="가입 방법"
              tabs={[{ key: 'company', label: '새 회사 만들기' }, { key: 'invite', label: '초대 코드로 가입' }]}
              value={tab}
              onChange={(t) => { setTab(t as typeof tab); setError(''); }}
            />

            {tab === 'company' ? (
              <Field label="회사명" value={companyName} onChange={(e) => setCompanyName(e.target.value)} hint="처음 만든 사람이 회사 관리자가 됩니다." autoFocus />
            ) : (
              <Field label="초대 코드" value={code} onChange={(e) => { setCode(e.target.value.toUpperCase()); setError(''); }}
                placeholder="관리자에게 받은 10자리 코드" autoFocus={!linkCode}
                hint={inviteCompany ? <>가입할 회사: <b>{inviteCompany}</b></> : undefined} />
            )}
            <Field label="이름" value={name} onChange={(e) => setName(e.target.value)} autoFocus={!!linkCode} />
            <Field label="이메일" type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="회사 이메일" />
            <Field label="비밀번호 (8자 이상)" type="password" autoComplete="new-password" value={password} onChange={(e) => { setPassword(e.target.value); setError(''); }} />

            {error && <p role="alert" className="text-[15px] text-danger">{error}</p>}

            <Button type="submit" disabled={submitting} className="w-full">
              {submitting ? '처리 중…' : tab === 'company' ? '회사 만들고 시작하기' : '가입 신청'}
            </Button>
            <p className="text-center text-[15px] text-body">
              이미 계정이 있나요? <Link to="/login" className="font-bold text-brand-text underline-offset-2 hover:underline">로그인</Link>
            </p>
          </form>
        )}
      </div>
    </div>
  );
}
