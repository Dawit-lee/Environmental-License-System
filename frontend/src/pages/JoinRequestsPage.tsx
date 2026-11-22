import { useEffect, useState } from 'react';
import {
  approveJoinRequest,
  getInviteCode,
  getJoinRequests,
  regenerateInviteCode,
  rejectJoinRequest,
  type JoinRequest,
} from '../api/signup';
import { usePrompt } from '../components/PromptDialog';

// ---------------------------------------------------------------------------
// JoinRequestsPage — company admin: invite link/code (copy, regenerate) and pending join requests.
// ---------------------------------------------------------------------------

const btn = 'rounded border border-line px-3 py-1.5 text-sm font-medium transition hover:bg-paper disabled:opacity-60';

export default function JoinRequestsPage() {
  const [ask, promptUi] = usePrompt();
  const [code, setCode] = useState<string | null>(null);
  const [items, setItems] = useState<JoinRequest[] | null>(null);
  const [copied, setCopied] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState<number | null>(null);

  const load = () => getJoinRequests().then(setItems, (e) => setError(e.message));
  useEffect(() => {
    getInviteCode().then((r) => setCode(r.code), (e) => setError(e.message));
    load();
  }, []);

  const link = code ? `${window.location.origin}/join/${code}` : '';
  const copy = (text: string, what: string) =>
    navigator.clipboard.writeText(text).then(() => setCopied(what), () => setError('복사하지 못했습니다. 직접 선택해 복사해 주세요.'));

  const regenerate = async () => {
    if (!confirm('새 코드를 만들면 지금 코드와 링크는 더 이상 쓸 수 없습니다. 계속할까요?')) return;
    try {
      setCode((await regenerateInviteCode()).code);
      setCopied('');
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const decide = async (r: JoinRequest, approve: boolean) => {
    let reason = '';
    if (!approve) {
      const input = await ask(`${r.name}(${r.email}) 님의 신청을 거절합니다. 사유(선택)`);
      if (input === null) return;
      reason = input;
    }
    setBusy(r.id);
    setError('');
    try {
      await (approve ? approveJoinRequest(r.id) : rejectJoinRequest(r.id, reason));
    } catch (e) {
      setError((e as Error).message);
    }
    setBusy(null);
    load();
  };

  return (
    <div className="min-h-full bg-paper">
    <div className="mx-auto max-w-7xl space-y-6 px-4 py-8 sm:px-6">
      <h1 className="text-2xl font-bold text-brand">가입 요청</h1>
      {promptUi}
      {error && <p role="alert" className="text-sm text-danger">{error}</p>}

      <section className="rounded border border-line bg-white p-5">
        <h2 className="text-sm font-semibold text-ink">초대 링크</h2>
        <p className="mt-1 text-xs text-mute">링크나 코드를 동료에게 보내세요. 신청이 오면 메일과 알림으로 알려드립니다.</p>
        {code && (
          <div className="mt-3 space-y-2">
            <div className="flex flex-wrap items-center gap-2">
              <input readOnly value={link} aria-label="초대 링크" onFocus={(e) => e.target.select()}
                className="min-w-0 flex-1 rounded border border-line bg-paper px-3 py-1.5 text-sm" />
              <button type="button" className={btn} onClick={() => copy(link, 'link')}>{copied === 'link' ? '복사됨 ✓' : '링크 복사'}</button>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-sm text-body">코드</span>
              <code className="rounded bg-paper px-2 py-1 font-mono text-sm tracking-wider">{code}</code>
              <button type="button" className={btn} onClick={() => copy(code, 'code')}>{copied === 'code' ? '복사됨 ✓' : '코드 복사'}</button>
              <button type="button" className={`${btn} text-danger`} onClick={regenerate}>재발급</button>
            </div>
          </div>
        )}
      </section>

      <section className="rounded border border-line bg-white">
        <h2 className="border-b border-line px-5 py-3 text-sm font-semibold text-ink">
          대기 중 {items ? `${items.length}건` : ''}
        </h2>
        {items?.length === 0 && <p className="px-5 py-6 text-sm text-mute">대기 중인 가입 요청이 없습니다. (14일이 지나면 자동 만료)</p>}
        <ul>
          {items?.map((r) => (
            <li key={r.id} className="flex flex-wrap items-center gap-3 border-b border-line px-5 py-3 last:border-0">
              <div className="min-w-0 flex-1">
                <p className="text-sm font-medium text-ink">{r.name}</p>
                <p className="truncate text-xs text-mute">{r.email} · {new Date(r.createdAt).toLocaleString('ko-KR')}</p>
              </div>
              <button type="button" disabled={busy === r.id} onClick={() => decide(r, true)}
                className="rounded bg-brand px-3 py-1.5 text-sm font-semibold text-white transition hover:bg-brand-hover disabled:opacity-60">
                승인
              </button>
              <button type="button" disabled={busy === r.id} onClick={() => decide(r, false)} className={`${btn} text-danger`}>
                거절
              </button>
            </li>
          ))}
        </ul>
      </section>
    </div>
    </div>
  );
}
