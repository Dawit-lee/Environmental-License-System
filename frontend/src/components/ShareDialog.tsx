import { useEffect, useState } from 'react';
import { addDays, format } from 'date-fns';
import type { Discipline } from '../api/schedule';
import { absolute, createShareLink, getShareLinks, revokeShareLink, webcal, type CreatedLink, type ShareLink } from '../api/share';
import { Alert, Modal, Section, Summary, btn, primary, segBtn } from './ScheduleTools';

// ---------------------------------------------------------------------------
// ShareDialog — 일정 › [공유] (설계 §6.3): read-only links for partners. PM·EM·프로젝트 관리자 only (the server
// answers 403 to anyone else). The address is shown once, right after creation; afterwards only revoke.
// ---------------------------------------------------------------------------

const EXPIRY = [30, 90, 180];
const when = (s: string | null) => (s ? new Date(s).toLocaleString('ko-KR', { dateStyle: 'short', timeStyle: 'short' }) : '—');
const pill = (on: boolean) => `rounded border px-3 py-1 text-xs ${on ? 'border-brand bg-[#EAF0F6] font-medium text-brand' : 'border-line bg-white text-body hover:border-[#B9C6D3]'}`;

export default function ShareDialog({ projectId, disciplines, onClose }: {
  projectId: number; disciplines: Discipline[]; onClose: () => void;
}) {
  const [links, setLinks] = useState<ShareLink[]>([]);
  const [all, setAll] = useState(true);
  const [chosen, setChosen] = useState<number[]>([]);
  const [includeEvents, setIncludeEvents] = useState(true);
  const [days, setDays] = useState(90);
  const [created, setCreated] = useState<CreatedLink | null>(null);
  const [confirming, setConfirming] = useState<number | null>(null);
  const [copied, setCopied] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = () => getShareLinks(projectId).then(setLinks, (e: Error) => setError(e.message));
  useEffect(() => { load(); }, [projectId]); // eslint-disable-line react-hooks/exhaustive-deps

  const active = disciplines.filter((d) => d.active);
  const names = (ids: number[] | null) =>
    ids ? ids.map((id) => disciplines.find((d) => d.id === id)?.name ?? `#${id}`).join(', ') : '전체';
  const pick = (id: number) => {
    if (all) { setAll(false); setChosen([id]); return; }
    setChosen(chosen.includes(id) ? chosen.filter((x) => x !== id) : [...chosen, id]);
  };

  const create = async () => {
    setBusy(true);
    setError(null);
    try {
      setCreated(await createShareLink(projectId, { disciplineIds: all ? null : chosen, includeEvents, expiresInDays: days }));
      await load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const revoke = async (id: number) => {
    setConfirming(null);
    try {
      await revokeShareLink(projectId, id);
      await load();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const copy = async (url: string) => {
    try {
      await navigator.clipboard.writeText(url);
      setCopied(url);
    } catch {
      setError('클립보드에 복사하지 못했습니다. 주소를 직접 선택해 복사하세요.');
    }
  };

  const using = links.filter((l) => l.active).length;
  return (
    <Modal title="협력사 공유" subtitle="링크를 가진 사람은 로그인 없이 일정을 봅니다." onClose={onClose} size="lg"
      footer={<>
        <Summary />
        <button type="button" className={btn} onClick={onClose}>닫기</button>
        <button type="button" className={primary} disabled={busy || (!all && !chosen.length)} onClick={create}>링크 만들기</button>
      </>}>
      <Alert error={error} />

      {created && (
        <div className="space-y-2.5 rounded border border-brand p-3.5 text-sm">
          <p className="font-semibold text-brand">링크를 만들었습니다. 이 주소는 지금만 보이니 복사해 두세요.</p>
          {[['웹 보기', absolute(created.path)], ['달력 구독', webcal(created.icsPath)]].map(([label, url]) => (
            <div key={label} className="grid grid-cols-[64px_minmax(0,1fr)_auto] items-center gap-2">
              <span className="text-xs text-mute">{label}</span>
              <input readOnly value={url} aria-label={`${label} 주소`} onFocus={(e) => e.target.select()}
                className="min-w-0 rounded border border-line px-2 py-1 font-mono text-xs" />
              <button type="button" className={btn} onClick={() => copy(url)}>{copied === url ? '복사됨' : '복사'}</button>
            </div>
          ))}
        </div>
      )}

      <div className="grid gap-4 md:grid-cols-2">
        <Section title="새 링크">
          <div className="space-y-3 text-sm">
            <div className="flex gap-3">
              <span className="w-14 shrink-0 pt-1 text-mute">분야</span>
              <div className="flex flex-wrap gap-1.5">
                <button type="button" aria-pressed={all} className={pill(all)} onClick={() => setAll(true)}>전체</button>
                {active.map((d) => (
                  <button key={d.id} type="button" aria-pressed={!all && chosen.includes(d.id)} className={pill(!all && chosen.includes(d.id))}
                    onClick={() => pick(d.id)}>{d.name}</button>
                ))}
              </div>
            </div>
            <label className="flex items-center gap-3">
              <span className="w-14 shrink-0 text-mute">이벤트</span>
              <input type="checkbox" checked={includeEvents} onChange={(e) => setIncludeEvents(e.target.checked)} />
              <span className="text-body">회의·현장 방문 일정도 보여 주기</span>
            </label>
            <div className="flex flex-wrap items-center gap-3">
              <span className="w-14 shrink-0 text-mute">만료</span>
              <div className="inline-flex overflow-hidden rounded border border-line bg-white">
                {EXPIRY.map((n) => <button key={n} type="button" aria-pressed={days === n} className={segBtn(days === n)} onClick={() => setDays(n)}>{n}일</button>)}
              </div>
              <span className="text-xs tabular-nums text-mute">{format(addDays(new Date(), days), 'yy.MM.dd')}까지</span>
            </div>
          </div>
        </Section>
        <div className="space-y-1.5 self-start rounded bg-paper px-3.5 py-3 text-xs leading-relaxed">
          <div className="grid grid-cols-[56px_minmax(0,1fr)] gap-2"><span className="text-mute">보임</span><span className="text-body">제목 · 날짜 · 장소 · 분야 · 협력사명</span></div>
          <div className="grid grid-cols-[56px_minmax(0,1fr)] gap-2"><span className="text-mute">안 보임</span><span className="text-body">담당자 · 지연 사유 · 메모 · 체크리스트 · 상대 기관 · 인허가 기한</span></div>
          <div className="grid grid-cols-[56px_minmax(0,1fr)] gap-2"><span className="text-mute">범위</span>
            <span className="text-body">{all ? '전체(공통 포함)' : names(chosen) || '분야를 고르세요'}{includeEvents ? ' · 이벤트 포함' : ''}</span></div>
        </div>
      </div>

      <Section title="만든 링크" hint={`${using}개 사용 중`}>
        <div className="overflow-x-auto rounded border border-line">
          <table className="w-full whitespace-nowrap text-left text-sm">
            <thead className="bg-paper text-xs text-body">
              <tr><th className="px-3 py-2 font-medium">분야</th><th className="px-3 py-2 font-medium">이벤트</th><th className="px-3 py-2 font-medium">만든 사람</th>
                <th className="px-3 py-2 font-medium">만료</th><th className="px-3 py-2 font-medium">마지막 접근</th><th className="px-3 py-2" /></tr>
            </thead>
            <tbody className="divide-y divide-line-soft">
              {links.map((l) => (
                <tr key={l.id} className={`${l.active ? '' : 'text-mute'} ${created?.link.id === l.id ? 'bg-[#EAF0F6]' : ''}`}>
                  <td className="max-w-[220px] truncate px-3 py-2" title={names(l.disciplineIds)}>{names(l.disciplineIds)}</td>
                  <td className="px-3 py-2">{l.includeEvents ? '포함' : '—'}</td>
                  <td className="px-3 py-2">{l.createdByName ?? '—'}</td>
                  <td className="px-3 py-2 tabular-nums">{when(l.expiresAt)}</td>
                  <td className="px-3 py-2 tabular-nums text-mute">{when(l.lastAccessedAt)}</td>
                  <td className="px-3 py-2 text-right">
                    {!l.active ? (l.revokedAt ? '폐기됨' : '만료됨')
                      : confirming === l.id ? (
                        <span className="inline-flex items-center gap-2">
                          <span className="text-xs text-danger">받은 사람이 더는 못 봅니다</span>
                          <button type="button" className="rounded bg-danger px-3 py-1.5 text-xs font-semibold text-white hover:bg-danger-hover" onClick={() => revoke(l.id)}>폐기</button>
                          <button type="button" className={btn} onClick={() => setConfirming(null)}>취소</button>
                        </span>
                      ) : <button type="button" className={btn} onClick={() => setConfirming(l.id)}>폐기</button>}
                  </td>
                </tr>
              ))}
              {!links.length && <tr><td colSpan={6} className="px-3 py-6 text-center text-mute">만든 링크가 없습니다.</td></tr>}
            </tbody>
          </table>
        </div>
      </Section>
    </Modal>
  );
}
