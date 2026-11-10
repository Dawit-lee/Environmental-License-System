import { useEffect, useState } from 'react';
import { listProjectObligations, type Obligation } from '../api/revisions';
import { isOverdue, taskStatusLabel } from '../api/tasks';
import type { PermitItem } from '../types';
import ObligationTaskDrawer from './ObligationTaskDrawer';

// ---------------------------------------------------------------------------
// ProjectObligations — 프로젝트 › 의무사항: approved obligations of every permit, nearest due date first.
// Each row shows the current cycle's task (operations-compliance F1); the row opens the task detail.
// ---------------------------------------------------------------------------

const FREQ: Record<Obligation['frequency'], string> = {
  ONCE: '1회', MONTHLY: '매월', QUARTERLY: '분기', SEMIANNUAL: '반기', ANNUAL: '매년', OTHER: '기타',
};

const today = () => new Date().toLocaleDateString('sv-SE');
const dDay = (date: string) => Math.round((Date.parse(date) - Date.parse(today())) / 86_400_000);

export default function ProjectObligations({ projectId, permits, onOpenPermit }: {
  projectId: number; permits: PermitItem[]; onOpenPermit: (p: PermitItem) => void;
}) {
  const [items, setItems] = useState<Obligation[] | null>(null);
  const [showClosed, setShowClosed] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [openId, setOpenId] = useState<number | null>(null);

  const load = () => listProjectObligations(projectId).then(setItems, (e: Error) => setError(e.message));
  useEffect(() => {
    load();
  }, [projectId]);

  /** 열림 / 기한 경과 / 완료 / 지연 완료 of the current cycle; plain text, only overdue is coloured. */
  const status = (o: Obligation) =>
    o.openTask ? taskStatusLabel(o.openTask)
      : o.status === 'ACTIVE' ? '기한 미정'
        : o.lastTask ? taskStatusLabel(o.lastTask) : '종료';

  if (!items) return <p className="text-sm text-gray-400">{error ?? '불러오는 중…'}</p>;

  const permitById = new Map(permits.map((p) => [p.id, p]));
  const rows = items.filter((o) => showClosed || o.status === 'ACTIVE');
  const closedCount = items.length - items.filter((o) => o.status === 'ACTIVE').length;

  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-gray-500">AI 검토에서 승인된 의무사항입니다. 행을 누르면 이번 회차 업무에 증빙을 붙이고 완료할 수 있습니다.</p>
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
              <th className="px-4 py-3 font-medium">기한</th>
              <th className="px-4 py-3 font-medium">D-day</th>
              <th className="px-4 py-3 font-medium">담당</th>
              <th className="px-4 py-3 font-medium">증빙</th>
              <th className="px-4 py-3 font-medium">상태</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[#E5E3DC]">
            {rows.map((o) => {
              const permit = permitById.get(o.permitItemId);
              const t = o.openTask;
              const d = t ? dDay(t.dueDate) : null;
              return (
                <tr key={o.id} onClick={() => setOpenId(o.id)} className="cursor-pointer hover:bg-[#F8F7F2]">
                  <td className="max-w-[320px] px-4 py-2.5">
                    <span className="block truncate font-medium text-gray-800" title={o.summary}>{o.summary}</span>
                  </td>
                  <td className="max-w-[220px] px-4 py-2.5">
                    {permit ? (
                      <button type="button" onClick={(e) => { e.stopPropagation(); onOpenPermit(permit); }} title={permit.name}
                        className="block max-w-full truncate text-[#1F4E79] hover:underline">{permit.name}</button>
                    ) : '—'}
                  </td>
                  <td className="px-4 py-2.5 text-gray-600">{FREQ[o.frequency]}</td>
                  <td className="px-4 py-2.5 text-gray-600">{t?.dueDate ?? '—'}</td>
                  <td className={`px-4 py-2.5 ${d === null ? 'text-gray-400' : d < 0 ? 'font-semibold text-[#991B1B]' : 'text-gray-600'}`}>
                    {d === null ? '—' : d === 0 ? 'D-DAY' : d > 0 ? `D-${d}` : `D+${-d}`}
                  </td>
                  <td className="max-w-[120px] truncate px-4 py-2.5 text-gray-600" title={t?.assigneeName ?? ''}>{t ? t.assigneeName ?? '미지정' : '—'}</td>
                  <td className="px-4 py-2.5 text-gray-600">{t ? `${t.evidenceCount + t.measurementCount}건` : '—'}</td>
                  <td className={`px-4 py-2.5 ${t && isOverdue(t) ? 'font-semibold text-[#991B1B]' : 'text-gray-600'}`}>{status(o)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {rows.length === 0 && (
          <p className="py-10 text-center text-sm text-gray-400">승인된 의무사항이 없습니다. 허가증을 올리고 AI 검토에서 승인하면 여기에 나옵니다.</p>
        )}
      </div>
      {openId !== null && items.some((o) => o.id === openId) && (
        <ObligationTaskDrawer key={openId} projectId={projectId} obligation={items.find((o) => o.id === openId)!}
          permit={permitById.get(items.find((o) => o.id === openId)!.permitItemId)}
          onClose={() => setOpenId(null)} onChanged={load} />
      )}
    </div>
  );
}
