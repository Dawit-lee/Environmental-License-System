import { useEffect, useState } from 'react';
import { completeObligation, listProjectObligations, type Obligation } from '../api/revisions';
import type { ApiError } from '../api/client';
import type { PermitItem } from '../types';

// ---------------------------------------------------------------------------
// ProjectObligations — 프로젝트 › 의무사항: approved obligations of every permit, nearest due date first.
// Planned (operations-compliance spec F1): per-due-date obligation tasks with assignee and evidence go here.
// ---------------------------------------------------------------------------

const FREQ: Record<Obligation['frequency'], string> = {
  ONCE: '1회', MONTHLY: '매월', QUARTERLY: '분기', SEMIANNUAL: '반기', ANNUAL: '매년', OTHER: '기타',
};

const today = () => new Date().toISOString().slice(0, 10);
const dDay = (date: string) => Math.round((Date.parse(date) - Date.parse(today())) / 86_400_000);

export default function ProjectObligations({ projectId, permits, onOpenPermit }: {
  projectId: number; permits: PermitItem[]; onOpenPermit: (p: PermitItem) => void;
}) {
  const [items, setItems] = useState<Obligation[] | null>(null);
  const [showClosed, setShowClosed] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<number | null>(null);

  const load = () => listProjectObligations(projectId).then(setItems, (e: Error) => setError(e.message));
  useEffect(() => {
    load();
  }, [projectId]);

  const complete = async (o: Obligation) => {
    setBusy(o.id);
    setError(null);
    try {
      await completeObligation(o.id).catch((e: ApiError) => {
        if (e.status !== 422) throw e;
        // OTHER frequency or no date yet: the server asks for the next due date
        const next = window.prompt('다음 기한일 (YYYY-MM-DD)')?.trim();
        if (next) return completeObligation(o.id, next);
      });
      await load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  if (!items) return <p className="text-sm text-gray-400">{error ?? '불러오는 중…'}</p>;

  const permitById = new Map(permits.map((p) => [p.id, p]));
  const rows = items.filter((o) => showClosed || o.status === 'ACTIVE');
  const closedCount = items.length - items.filter((o) => o.status === 'ACTIVE').length;

  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-gray-500">AI 검토에서 승인된 의무사항입니다. 이행하면 다음 기한으로 넘어갑니다.</p>
        {closedCount > 0 && (
          <label className="flex items-center gap-2 text-sm text-gray-600">
            <input type="checkbox" checked={showClosed} onChange={(e) => setShowClosed(e.target.checked)} className="accent-[#1F4E79]" />
            종료된 의무사항 보기 ({closedCount})
          </label>
        )}
      </div>
      {error && <p role="alert" className="mt-3 rounded-lg bg-red-50 px-3 py-2 text-sm text-[#991B1B]">{error}</p>}

      <div className="mt-4 overflow-x-auto rounded-xl border border-[#E5E3DC] bg-white shadow-sm">
        <table className="w-full min-w-[860px] whitespace-nowrap text-left text-sm">
          <thead className="bg-[#F8F7F2] text-xs text-gray-500">
            <tr>
              <th className="px-4 py-3 font-medium">의무사항</th>
              <th className="px-4 py-3 font-medium">인허가</th>
              <th className="px-4 py-3 font-medium">주기</th>
              <th className="px-4 py-3 font-medium">다음 기한</th>
              <th className="px-4 py-3 font-medium">D-day</th>
              <th className="px-4 py-3 font-medium">상태</th>
              <th className="px-4 py-3 text-right font-medium">이행</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[#E5E3DC]">
            {rows.map((o) => {
              const permit = permitById.get(o.permitItemId);
              const d = o.nextDueDate ? dDay(o.nextDueDate) : null;
              return (
                <tr key={o.id} className="hover:bg-[#F8F7F2]">
                  <td className="max-w-[320px] px-4 py-2.5">
                    <span className="block truncate font-medium text-gray-800" title={o.summary}>{o.summary}</span>
                  </td>
                  <td className="max-w-[220px] px-4 py-2.5">
                    {permit ? (
                      <button type="button" onClick={() => onOpenPermit(permit)} title={permit.name}
                        className="block max-w-full truncate text-[#1F4E79] hover:underline">{permit.name}</button>
                    ) : '—'}
                  </td>
                  <td className="px-4 py-2.5 text-gray-600">{FREQ[o.frequency]}</td>
                  <td className="px-4 py-2.5 text-gray-600">{o.nextDueDate ?? '—'}</td>
                  <td className={`px-4 py-2.5 ${d === null ? 'text-gray-400' : d < 0 ? 'font-semibold text-[#991B1B]' : d <= 30 ? 'font-semibold text-[#B45309]' : 'text-gray-500'}`}>
                    {d === null || o.status === 'CLOSED' ? '—' : d === 0 ? 'D-DAY' : d > 0 ? `D-${d}` : `D+${-d} 지남`}
                  </td>
                  <td className="px-4 py-2.5 text-gray-600">{o.status === 'ACTIVE' ? '진행' : '종료'}</td>
                  <td className="px-4 py-2.5 text-right">
                    {o.status === 'ACTIVE' && (
                      <button type="button" disabled={busy === o.id} onClick={() => complete(o)}
                        className="rounded-lg border border-[#E5E3DC] px-3 py-1 text-xs font-medium text-gray-700 hover:border-[#1F4E79] hover:text-[#1F4E79] disabled:opacity-40">
                        이행 완료
                      </button>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {rows.length === 0 && (
          <p className="py-10 text-center text-sm text-gray-400">승인된 의무사항이 없습니다. 허가증을 올리고 AI 검토에서 승인하면 여기에 나옵니다.</p>
        )}
      </div>
    </div>
  );
}
