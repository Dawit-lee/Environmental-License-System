import { useEffect, useState } from 'react';
import type { Discipline } from '../api/schedule';
import { absolute, createShareLink, getShareLinks, revokeShareLink, webcal, type CreatedLink, type ShareLink } from '../api/share';
import { Alert, Modal } from './ScheduleTools';

// ---------------------------------------------------------------------------
// ShareDialog — 일정 › [공유] (설계 §6.3): read-only links for partners. PM·EM·프로젝트 관리자 only (the server
// answers 403 to anyone else). The address is shown once, right after creation; afterwards only revoke.
// ---------------------------------------------------------------------------

const btn = 'rounded-lg border border-[#E5E3DC] px-3 py-1.5 text-xs font-medium text-gray-700 hover:border-[#1F4E79] hover:text-[#1F4E79] disabled:opacity-40';
const primary = 'rounded-lg bg-[#1F4E79] px-3.5 py-1.5 text-xs font-semibold text-white hover:bg-[#173a5c] disabled:opacity-40';
const day = (s: string | null) => (s ? new Date(s).toLocaleString('ko-KR', { dateStyle: 'short', timeStyle: 'short' }) : '-');

export default function ShareDialog({ projectId, disciplines, onClose }: {
  projectId: number; disciplines: Discipline[]; onClose: () => void;
}) {
  const [links, setLinks] = useState<ShareLink[]>([]);
  const [all, setAll] = useState(true);
  const [chosen, setChosen] = useState<number[]>([]);
  const [includeEvents, setIncludeEvents] = useState(true);
  const [days, setDays] = useState(90);
  const [created, setCreated] = useState<CreatedLink | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = () => getShareLinks(projectId).then(setLinks, (e: Error) => setError(e.message));
  useEffect(() => { load(); }, [projectId]); // eslint-disable-line react-hooks/exhaustive-deps

  const names = (ids: number[] | null) =>
    ids ? ids.map((id) => disciplines.find((d) => d.id === id)?.name ?? `#${id}`).join(', ') : '전체(공통 포함)';

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
    if (!window.confirm('이 링크를 폐기할까요? 받은 사람은 더 이상 볼 수 없습니다.')) return;
    try {
      await revokeShareLink(projectId, id);
      await load();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  return (
    <Modal title="협력사 공유 링크" onClose={onClose} wide>
      <p className="rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-900">
        링크를 받은 사람은 누구나 볼 수 있습니다. 만료일과 폐기로 관리하세요.
        <br /><span className="text-xs">보이는 내용: 제목·날짜·장소·분야·협력사명. 지연 사유, 담당자, 메모, 체크리스트, 상대 기관, 인허가 기한은 보이지 않습니다.</span>
      </p>
      <Alert error={error} />

      {created && (
        <div className="space-y-2 rounded-lg border border-[#1F4E79] p-3 text-sm">
          <p className="font-semibold text-[#1F4E79]">새 링크 — 지금만 볼 수 있습니다. 복사해 두세요.</p>
          {[['웹 보기', absolute(created.path)], ['달력 구독', webcal(created.icsPath)]].map(([label, url]) => (
            <div key={label} className="flex items-center gap-2">
              <span className="w-16 shrink-0 text-xs text-gray-500">{label}</span>
              <input readOnly value={url} onFocus={(e) => e.target.select()}
                className="flex-1 rounded border border-[#E5E3DC] px-2 py-1 font-mono text-xs" />
              <button type="button" className={btn} onClick={() => navigator.clipboard?.writeText(url)}>복사</button>
            </div>
          ))}
        </div>
      )}

      <fieldset className="space-y-2 rounded-lg border border-[#E5E3DC] p-3 text-sm">
        <legend className="px-1 text-xs font-medium text-gray-600">새 링크</legend>
        <label className="flex items-center gap-1.5">
          <input type="checkbox" checked={all} onChange={(e) => setAll(e.target.checked)} /> 모든 분야(공통 포함)
        </label>
        {!all && (
          <div className="flex flex-wrap gap-x-3 gap-y-1 pl-5">
            {disciplines.filter((d) => d.active).map((d) => (
              <label key={d.id} className="flex items-center gap-1 text-xs">
                <input type="checkbox" checked={chosen.includes(d.id)}
                  onChange={(e) => setChosen(e.target.checked ? [...chosen, d.id] : chosen.filter((x) => x !== d.id))} />
                {d.name}
              </label>
            ))}
          </div>
        )}
        <label className="flex items-center gap-1.5">
          <input type="checkbox" checked={includeEvents} onChange={(e) => setIncludeEvents(e.target.checked)} /> 이벤트 포함
        </label>
        <label className="flex items-center gap-1.5">
          만료
          <input type="number" min={1} max={365} value={days} onChange={(e) => setDays(Number(e.target.value))}
            className="w-20 rounded border border-[#E5E3DC] px-2 py-1 text-sm" />
          일 후 (최대 365일)
        </label>
        <button type="button" className={primary} disabled={busy || (!all && !chosen.length) || days < 1 || days > 365}
          onClick={create}>링크 만들기</button>
      </fieldset>

      <table className="w-full text-left text-xs">
        <thead className="bg-[#F8F7F2] text-gray-500">
          <tr><th className="px-2 py-1.5">분야</th><th className="px-2 py-1.5">이벤트</th><th className="px-2 py-1.5">만든 사람</th>
            <th className="px-2 py-1.5">만료</th><th className="px-2 py-1.5">마지막 접근</th><th className="px-2 py-1.5" /></tr>
        </thead>
        <tbody>
          {links.map((l) => (
            <tr key={l.id} className={`border-t border-[#E5E3DC] ${l.active ? '' : 'text-gray-400'}`}>
              <td className="px-2 py-1.5">{names(l.disciplineIds)}</td>
              <td className="px-2 py-1.5">{l.includeEvents ? '포함' : '-'}</td>
              <td className="px-2 py-1.5">{l.createdByName ?? '-'}</td>
              <td className="px-2 py-1.5">{day(l.expiresAt)}</td>
              <td className="px-2 py-1.5">{day(l.lastAccessedAt)}</td>
              <td className="px-2 py-1.5 text-right">
                {l.active ? <button type="button" className={btn} onClick={() => revoke(l.id)}>폐기</button>
                  : l.revokedAt ? '폐기됨' : '만료됨'}
              </td>
            </tr>
          ))}
          {!links.length && <tr><td colSpan={6} className="px-2 py-4 text-center text-gray-500">만든 링크가 없습니다.</td></tr>}
        </tbody>
      </table>
    </Modal>
  );
}
