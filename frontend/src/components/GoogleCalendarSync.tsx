import { useEffect, useState } from 'react';
import { isMaster } from '../api/client';
import {
  disconnectGoogle, getGoogleCalendar, googleConnectUrl, setCompanyGoogleSync, setGoogleTargets, type GoogleCalendarStatus,
} from '../api/googleCalendar';
import { Alert } from './ui/Alert';
import { Button } from './ui/Button';
import { cn, focusRing } from './ui/cn';

// 내 Google 캘린더 (설계 §6.4): connect / disconnect, what to send, last sync and error. Hidden when the server has no
// Google keys. A company master also sees the company-wide switch here.

const RESULT: Record<string, string> = {
  connected: 'Google 캘린더에 연결했습니다. 1분 안에 일정이 들어갑니다.',
  denied: 'Google 연결을 취소했습니다.',
  failed: 'Google 연결에 실패했습니다. 잠시 후 다시 시도해 주세요.',
};
const when = (s: string | null) => (s ? new Date(s).toLocaleString('ko-KR') : '—');
const checkbox = cn('size-5 shrink-0 accent-brand', focusRing);
const checkLabel = 'flex min-h-11 items-center gap-2 text-[15px] text-ink';

export default function GoogleCalendarSync() {
  const [status, setStatus] = useState<GoogleCalendarStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice] = useState(() => RESULT[new URLSearchParams(window.location.search).get('google') ?? ''] ?? null);

  const load = () => getGoogleCalendar().then(setStatus, (e) => setError(e.message));
  useEffect(() => { load(); }, []);
  if (!status) return null;

  const run = (f: () => Promise<unknown>) => { setError(null); f().then(load, (e) => setError(e.message)); };
  const connect = () => { setError(null); googleConnectUrl().then(({ url }) => { window.location.href = url; }, (e) => setError(e.message)); };
  const disconnect = () => {
    if (!window.confirm('Google 캘린더 연결을 해제할까요?')) return;
    const del = window.confirm('Google에 만든 "인허가 일정" 캘린더도 지울까요?\n[확인] 지움 · [취소] 남겨 둠');
    run(() => disconnectGoogle(del));
  };

  return (
    <section className="rounded border border-line bg-white p-6">
      <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1">
        <h2 className="text-[19px] font-bold text-ink">내 Google 캘린더</h2>
        {isMaster() && (
          <label className={checkLabel}>
            <input type="checkbox" className={checkbox} checked={status.companyEnabled} onChange={(e) => run(() => setCompanyGoogleSync(e.target.checked))} />
            회사 전체에서 Google 연결 허용
          </label>
        )}
      </div>
      <p className="mt-1 break-keep text-[15px] text-body">
        내가 참석하는 이벤트와 내가 담당인 마일스톤을 Google의 "인허가 일정" 캘린더로 보냅니다(앱 → Google 한 방향).
        Google에서 고친 내용은 앱으로 돌아오지 않고 다음 동기화 때 덮어씁니다.
      </p>
      {notice && <Alert className="mt-4">{notice}</Alert>}
      {error && <Alert tone="danger" className="mt-4">{error}</Alert>}

      {!status.companyEnabled && !status.connected ? (
        <p className="mt-4 text-[15px] text-body">회사에서 Google 연결을 허용하지 않았습니다.</p>
      ) : !status.connected ? (
        <Button onClick={connect} className="mt-4">Google 캘린더 연결</Button>
      ) : (
        <div className="mt-4 space-y-2 text-[15px] text-body">
          <p><span className="font-bold text-ok">연결됨</span> · 마지막 동기화 <span className="tabular-nums">{when(status.lastSyncedAt)}</span></p>
          {status.lastError && <Alert tone="danger">동기화 오류: {status.lastError}</Alert>}
          <div className="flex flex-wrap gap-x-6">
            <label className={checkLabel}>
              <input type="checkbox" className={checkbox} checked={status.projectMilestones}
                onChange={(e) => run(() => setGoogleTargets(e.target.checked, status.tasks))} />
              내 프로젝트의 제출·보고 마일스톤
            </label>
            <label className={checkLabel}>
              <input type="checkbox" className={checkbox} checked={status.tasks}
                onChange={(e) => run(() => setGoogleTargets(status.projectMilestones, e.target.checked))} />
              내 담당 작업(TASK) 막대
            </label>
          </div>
          <Button variant="danger" size="sm" onClick={disconnect}>연결 해제</Button>
        </div>
      )}
    </section>
  );
}
