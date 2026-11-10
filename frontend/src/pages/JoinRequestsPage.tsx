import { useEffect, useState } from 'react';
import {
  approveJoinRequest,
  getInviteCode,
  getJoinRequests,
  regenerateInviteCode,
  rejectJoinRequest,
  type JoinRequest,
} from '../api/signup';

// ---------------------------------------------------------------------------
// JoinRequestsPage — company admin: invite link/code (copy, regenerate) and pending join requests.
// ---------------------------------------------------------------------------

const btn = 'rounded-lg border border-[#E5E3DC] px-3 py-1.5 text-sm font-medium transition hover:bg-[#F8F7F2] disabled:opacity-60';

export default function JoinRequestsPage() {
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
      const input = prompt(`${r.name}(${r.email}) 님의 신청을 거절합니다. 사유(선택):`);
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
    <div className="mx-auto max-w-3xl space-y-6 p-4 sm:p-6">
      <h1 className="text-xl font-bold text-gray-900">가입 요청</h1>
      {error && <p role="alert" className="text-sm text-[#991B1B]">{error}</p>}

      <section className="rounded-xl border border-[#E5E3DC] bg-white p-5">
        <h2 className="text-sm font-semibold text-gray-900">초대 링크</h2>
        <p className="mt-1 text-xs text-gray-500">링크나 코드를 동료에게 보내세요. 신청이 오면 메일과 알림으로 알려드립니다.</p>
        {code && (
          <div className="mt-3 space-y-2">
            <div className="flex flex-wrap items-center gap-2">
              <input readOnly value={link} aria-label="초대 링크" onFocus={(e) => e.target.select()}
                className="min-w-0 flex-1 rounded-lg border border-[#E5E3DC] bg-[#F8F7F2] px-3 py-1.5 text-sm" />
              <button type="button" className={btn} onClick={() => copy(link, 'link')}>{copied === 'link' ? '복사됨 ✓' : '링크 복사'}</button>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-sm text-gray-600">코드</span>
              <code className="rounded bg-[#F8F7F2] px-2 py-1 font-mono text-sm tracking-wider">{code}</code>
              <button type="button" className={btn} onClick={() => copy(code, 'code')}>{copied === 'code' ? '복사됨 ✓' : '코드 복사'}</button>
              <button type="button" className={`${btn} text-[#991B1B]`} onClick={regenerate}>재발급</button>
            </div>
          </div>
        )}
      </section>

      <section className="rounded-xl border border-[#E5E3DC] bg-white">
        <h2 className="border-b border-[#E5E3DC] px-5 py-3 text-sm font-semibold text-gray-900">
          대기 중 {items ? `${items.length}건` : ''}
        </h2>
        {items?.length === 0 && <p className="px-5 py-6 text-sm text-gray-500">대기 중인 가입 요청이 없습니다. (14일이 지나면 자동 만료)</p>}
        <ul>
          {items?.map((r) => (
            <li key={r.id} className="flex flex-wrap items-center gap-3 border-b border-[#E5E3DC] px-5 py-3 last:border-0">
              <div className="min-w-0 flex-1">
                <p className="text-sm font-medium text-gray-900">{r.name}</p>
                <p className="truncate text-xs text-gray-500">{r.email} · {new Date(r.createdAt).toLocaleString('ko-KR')}</p>
              </div>
              <button type="button" disabled={busy === r.id} onClick={() => decide(r, true)}
                className="rounded-lg bg-[#1F4E79] px-3 py-1.5 text-sm font-semibold text-white transition hover:bg-[#173a5c] disabled:opacity-60">
                승인
              </button>
              <button type="button" disabled={busy === r.id} onClick={() => decide(r, false)} className={`${btn} text-[#991B1B]`}>
                거절
              </button>
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
