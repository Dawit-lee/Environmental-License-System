import { useEffect, useState } from 'react';
import { sessionUserId } from '../api/client';
import { listProjectObligations, type Obligation } from '../api/revisions';
import { isOverdue, listProjectTasks, taskStatusLabel, type Task } from '../api/tasks';
import type { PermitItem } from '../types';
import ObligationTaskDrawer from './ObligationTaskDrawer';

// ---------------------------------------------------------------------------
// ProjectObligations — 프로젝트 › 의무사항: approved obligations of every permit, nearest due date first.
// Each row shows the current cycle's task (operations-compliance F1); the row opens the task detail.
// 조치 업무 (opened by a measured value over the limit, operations-compliance/06) are rows of the same table.
// ---------------------------------------------------------------------------

const FREQ: Record<Obligation['frequency'], string> = {
  ONCE: '1회', MONTHLY: '매월', QUARTERLY: '분기', SEMIANNUAL: '반기', ANNUAL: '매년', OTHER: '기타',
};

const today = () => new Date().toLocaleDateString('sv-SE');
/** One table line: an obligation with its current cycle, or a 조치 업무. */
interface Row {
  key: string;
  obligation?: Obligation;
  corrective?: Task;
  title: string;
  permitItemId: number | null;
  /** the open task, if any */
  task: Task | null;
  last: Task | null;
  closed: boolean;
  status: string;
}

const dDay = (date: string) => Math.round((Date.parse(date) - Date.parse(today())) / 86_400_000);

export default function ProjectObligations({ projectId, permits, onOpenPermit }: {
  projectId: number; permits: PermitItem[]; onOpenPermit: (p: PermitItem) => void;
}) {
  const [items, setItems] = useState<Obligation[] | null>(null);
  const [showClosed, setShowClosed] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [correctives, setCorrectives] = useState<Task[]>([]);
  const [openKey, setOpenKey] = useState<string | null>(null);
  const [kind, setKind] = useState<'all' | 'obligation' | 'corrective'>('all');
  const [who, setWho] = useState<'all' | 'me' | 'none'>('all');
  const [state, setState] = useState<'all' | 'open' | 'overdue' | 'done'>('all');

  const load = () => {
    listProjectObligations(projectId).then(setItems, (e: Error) => setError(e.message));
    listProjectTasks(projectId).then((l) => setCorrectives(l.filter((t) => t.kind === 'CORRECTIVE')), () => {});
  };
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
  const me = sessionUserId();
  const all: Row[] = [
    ...correctives.map((t): Row => ({
      key: `c${t.id}`, corrective: t, title: t.title, permitItemId: t.permitItemId, task: t.status === 'OPEN' ? t : null,
      last: t.status === 'OPEN' ? null : t, closed: t.status !== 'OPEN', status: taskStatusLabel(t),
    })),
    ...items.map((o): Row => ({
      key: `o${o.id}`, obligation: o, title: o.summary, permitItemId: o.permitItemId, task: o.openTask ?? null,
      last: o.lastTask ?? null, closed: o.status !== 'ACTIVE', status: status(o),
    })),
  ].sort((a, b) => (a.task?.dueDate ?? '9999').localeCompare(b.task?.dueDate ?? '9999')); // stable: undated keep their order
  const rows = all.filter((r) => {
    const t = r.task;
    if (kind !== 'all' && (kind === 'corrective') !== !!r.corrective) return false;
    if (who === 'me' && (t ?? r.last)?.assigneeId !== me) return false;
    if (who === 'none' && !(t && t.assigneeId === null)) return false;
    if (state === 'open') return !!t && !isOverdue(t);
    if (state === 'overdue') return !!t && isOverdue(t);
    if (state === 'done') return !t && r.last?.status === 'DONE'; // finished for good; earlier cycles are in the row's detail
    return showClosed || !r.closed;
  });
  const opened = all.find((r) => r.key === openKey);
  const select = 'rounded-lg border border-[#E5E3DC] bg-white px-2 py-1 text-sm text-gray-700';
  const closedCount = all.filter((r) => r.closed).length;

  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-gray-500">AI 검토에서 승인된 의무사항입니다. 행을 누르면 이번 회차 업무에 증빙을 붙이고 완료할 수 있습니다.</p>
        <div className="flex flex-wrap items-center gap-4 text-sm text-gray-600">
          <label className="flex items-center gap-2">
            종류
            <select value={kind} onChange={(e) => setKind(e.target.value as typeof kind)} className={select}>
              <option value="all">전체</option><option value="obligation">의무</option><option value="corrective">조치</option>
            </select>
          </label>
          <label className="flex items-center gap-2">
            담당
            <select value={who} onChange={(e) => setWho(e.target.value as typeof who)} className={select}>
              <option value="all">전체</option><option value="me">나</option><option value="none">미지정</option>
            </select>
          </label>
          <label className="flex items-center gap-2">
            상태
            <select value={state} onChange={(e) => setState(e.target.value as typeof state)} className={select}>
              <option value="all">전체</option><option value="open">열림</option><option value="overdue">기한 경과</option><option value="done">완료</option>
            </select>
          </label>
          {closedCount > 0 && state === 'all' && (
            <label className="flex items-center gap-2">
              <input type="checkbox" checked={showClosed} onChange={(e) => setShowClosed(e.target.checked)} className="accent-[#1F4E79]" />
              종료·완료된 항목 보기 ({closedCount})
            </label>
          )}
        </div>
      </div>
      {error && <p role="alert" className="mt-3 rounded-lg bg-red-50 px-3 py-2 text-sm text-[#991B1B]">{error}</p>}

      <div className="mt-4 overflow-x-auto rounded-xl border border-[#E5E3DC] bg-white shadow-sm">
        <table className="w-full min-w-[920px] whitespace-nowrap text-left text-sm">
          <thead className="bg-[#F8F7F2] text-xs text-gray-500">
            <tr>
              <th className="px-4 py-3 font-medium">종류</th>
              <th className="px-4 py-3 font-medium">의무사항 · 조치</th>
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
            {rows.map((r) => {
              const permit = r.permitItemId ? permitById.get(r.permitItemId) : undefined;
              const t = r.task;
              const d = t ? dDay(t.dueDate) : null;
              return (
                <tr key={r.key} onClick={() => setOpenKey(r.key)} className="cursor-pointer hover:bg-[#F8F7F2]">
                  <td className="px-4 py-2.5 text-gray-600">{r.corrective ? '조치' : '의무'}</td>
                  <td className="max-w-[320px] px-4 py-2.5">
                    <span className="block truncate font-medium text-gray-800" title={r.title}>{r.title}</span>
                  </td>
                  <td className="max-w-[220px] px-4 py-2.5">
                    {permit ? (
                      <button type="button" onClick={(e) => { e.stopPropagation(); onOpenPermit(permit); }} title={permit.name}
                        className="block max-w-full truncate text-[#1F4E79] hover:underline">{permit.name}</button>
                    ) : '—'}
                  </td>
                  <td className="px-4 py-2.5 text-gray-600">{r.obligation ? FREQ[r.obligation.frequency] : '—'}</td>
                  <td className="px-4 py-2.5 text-gray-600">{t?.dueDate ?? '—'}</td>
                  <td className={`px-4 py-2.5 ${d === null ? 'text-gray-400' : d < 0 ? 'font-semibold text-[#991B1B]' : 'text-gray-600'}`}>
                    {d === null ? '—' : d === 0 ? 'D-DAY' : d > 0 ? `D-${d}` : `D+${-d}`}
                  </td>
                  <td className="max-w-[120px] truncate px-4 py-2.5 text-gray-600" title={t?.assigneeName ?? ''}>{t ? t.assigneeName ?? '미지정' : '—'}</td>
                  <td className="px-4 py-2.5 text-gray-600">{t ? `${t.evidenceCount + t.measurementCount}건` : '—'}</td>
                  <td className={`px-4 py-2.5 ${t && isOverdue(t) ? 'font-semibold text-[#991B1B]' : 'text-gray-600'}`}>{r.status}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {rows.length === 0 && all.length > 0 && <p className="py-10 text-center text-sm text-gray-400">조건에 맞는 항목이 없습니다.</p>}
        {all.length === 0 && (
          <p className="py-10 text-center text-sm text-gray-400">승인된 의무사항이 없습니다. 허가증을 올리고 AI 검토에서 승인하면 여기에 나옵니다.</p>
        )}
      </div>
      {opened && (
        <ObligationTaskDrawer key={opened.key} projectId={projectId} obligation={opened.obligation} corrective={opened.corrective}
          permit={opened.permitItemId ? permitById.get(opened.permitItemId) : undefined}
          onClose={() => setOpenKey(null)} onChanged={load} />
      )}
    </div>
  );
}
