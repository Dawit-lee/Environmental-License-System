import { lazy, Suspense, useEffect, useMemo, useState } from 'react';
import { api } from '../api/client';
import {
  completeItem, createItem, deleteItem, getDisciplines, getSchedule, STATUS_LABEL, toInput, updateItem,
  type Discipline, type ItemInput, type Schedule, type ScheduleItem,
} from '../api/schedule';
import CalendarPage from '../pages/CalendarPage';
import { getProjectEvents, type ProjectEvent } from '../api/events';
import ProjectCalendar from './ProjectCalendar';
import type { Scale } from './ScheduleGantt';

// the Gantt library is large; only this tab loads it
const ScheduleGantt = lazy(() => import('./ScheduleGantt'));

// ---------------------------------------------------------------------------
// ProjectSchedule — 프로젝트 › 일정 tab: view switch [간트] [인허가 기한]. The Gantt shows per-discipline items
// (fixed dates; anchors/baselines come with ticket 03). Clicking a row opens the item form; dragging a bar saves.
// ---------------------------------------------------------------------------

type View = 'gantt' | 'calendar' | 'deadlines';
interface Member { userId: number; name: string }

const btn = 'rounded-lg border border-[#E5E3DC] px-3 py-1.5 text-xs font-medium text-gray-700 hover:border-[#1F4E79] hover:text-[#1F4E79] disabled:opacity-40';
const field = 'w-full rounded-lg border border-[#E5E3DC] px-2.5 py-1.5 text-sm outline-none focus:border-[#1F4E79]';
const seg = (on: boolean) => `px-3 py-1.5 text-xs font-medium ${on ? 'bg-[#1F4E79] text-white' : 'text-gray-600 hover:bg-[#F8F7F2]'}`;

export default function ProjectSchedule({ projectId }: { projectId: number }) {
  const [view, setView] = useState<View>('gantt');
  const [schedule, setSchedule] = useState<Schedule | null>(null);
  const [disciplines, setDisciplines] = useState<Discipline[]>([]);
  const [members, setMembers] = useState<Member[]>([]);
  const [loads, setLoads] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [scale, setScale] = useState<Scale>('week');
  const [filter, setFilter] = useState({ discipline: '', owner: '', delayedOnly: false });
  const [editing, setEditing] = useState<ScheduleItem | 'new' | null>(null);
  const [events, setEvents] = useState<ProjectEvent[]>([]); // the Gantt's 이벤트 row

  // together: the Gantt copies its data once per key (loads)
  const load = () =>
    Promise.all([getSchedule(projectId), getProjectEvents(projectId).catch(() => [])]).then(([s, ev]) => {
      setSchedule(s);
      setEvents(ev);
      setLoads((n) => n + 1);
    }, (e: Error) => setError(e.message));
  useEffect(() => {
    load();
    getDisciplines().then(setDisciplines, () => {});
    api<Member[]>(`/api/projects/${projectId}/members`).then(setMembers, () => {});
  }, [projectId]);

  const visible = useMemo(() => (schedule?.items ?? []).filter((i) =>
    (!filter.discipline || String(i.disciplineId ?? 0) === filter.discipline)
    && (!filter.owner || String(i.ownerUserId ?? 0) === filter.owner)
    && (!filter.delayedOnly || i.status === 'DELAYED')), [schedule, filter]);
  const delayed = schedule?.items.filter((i) => i.status === 'DELAYED').length ?? 0;

  const move = async (id: number, dates: { startDate: string; endDate: string }) => {
    const item = schedule?.items.find((i) => i.id === id);
    if (!item || (item.startDate === dates.startDate && item.endDate === dates.endDate)) return;
    setError(null);
    try {
      await updateItem(projectId, id, { ...toInput(item, schedule!.dependencies), ...dates });
    } catch (e) {
      setError((e as Error).message);
    }
    await load(); // on failure this snaps the bar back
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <div className="inline-flex overflow-hidden rounded-lg border border-[#E5E3DC] bg-white">
          <button type="button" className={seg(view === 'gantt')} onClick={() => setView('gantt')}>간트</button>
          <button type="button" className={seg(view === 'calendar')} onClick={() => setView('calendar')}>달력</button>
          <button type="button" className={seg(view === 'deadlines')} onClick={() => setView('deadlines')}>인허가 기한</button>
        </div>
        {view === 'gantt' && schedule && (
          <p className="text-sm text-gray-500">
            항목 {schedule.items.length}건 · <span className={delayed ? 'text-[#991B1B]' : ''}>지연 {delayed}건</span>
          </p>
        )}
      </div>

      {view === 'deadlines' && <CalendarPage projectId={projectId} />}
      {view === 'calendar' && (
        <ProjectCalendar projectId={projectId} disciplines={disciplines} members={members} onChanged={load}
          onOpenItem={(id) => setEditing(schedule?.items.find((i) => i.id === id) ?? null)} />
      )}

      {view === 'gantt' && (
        <>
          <div className="flex flex-wrap items-center gap-2">
            <select aria-label="분야 필터" className="rounded-lg border border-[#E5E3DC] bg-white px-2 py-1.5 text-xs"
              value={filter.discipline} onChange={(e) => setFilter({ ...filter, discipline: e.target.value })}>
              <option value="">분야 전체</option>
              <option value="0">공통(미지정)</option>
              {disciplines.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </select>
            <select aria-label="담당 필터" className="rounded-lg border border-[#E5E3DC] bg-white px-2 py-1.5 text-xs"
              value={filter.owner} onChange={(e) => setFilter({ ...filter, owner: e.target.value })}>
              <option value="">담당 전체</option>
              <option value="0">미지정</option>
              {members.map((m) => <option key={m.userId} value={m.userId}>{m.name}</option>)}
            </select>
            <label className="flex items-center gap-1.5 text-xs text-gray-600">
              <input type="checkbox" checked={filter.delayedOnly}
                onChange={(e) => setFilter({ ...filter, delayedOnly: e.target.checked })} />
              지연만
            </label>
            <div className="ml-auto flex items-center gap-2">
              <div className="inline-flex overflow-hidden rounded-lg border border-[#E5E3DC] bg-white">
                <button type="button" className={seg(scale === 'week')} onClick={() => setScale('week')}>주</button>
                <button type="button" className={seg(scale === 'month')} onClick={() => setScale('month')}>월</button>
              </div>
              <button type="button" onClick={() => setEditing('new')}
                className="rounded-lg bg-[#1F4E79] px-3.5 py-1.5 text-xs font-semibold text-white hover:bg-[#173a5c]">+ 항목</button>
            </div>
          </div>

          {error && <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-[#991B1B]">{error}</p>}

          {schedule && visible.length > 0 && (
            <Suspense fallback={<div className="h-[600px] rounded-xl border border-[#E5E3DC] bg-white" />}>
            <ScheduleGantt key={`${scale}:${loads}`} items={visible} dependencies={schedule.dependencies} events={events}
              disciplines={disciplines} today={schedule.today} scale={scale}
              onOpen={(id) => setEditing(schedule.items.find((i) => i.id === id) ?? null)} onMove={move} />
            </Suspense>
          )}
          {schedule && visible.length === 0 && (
            <p className="rounded-xl border border-dashed border-[#E5E3DC] bg-white px-6 py-12 text-center text-sm text-gray-500">
              {schedule.items.length ? '조건에 맞는 항목이 없습니다.' : '일정 항목이 없습니다. [+ 항목]으로 분야별 작업·마일스톤을 추가하세요.'}
            </p>
          )}
        </>
      )}

      {editing && schedule && (
        <ItemDrawer projectId={projectId} item={editing === 'new' ? null : editing} schedule={schedule}
          disciplines={disciplines} members={members}
          onClose={() => setEditing(null)} onSaved={() => { setEditing(null); load(); }} />
      )}
    </div>
  );
}

function ItemDrawer({ projectId, item, schedule, disciplines, members, onClose, onSaved }: {
  projectId: number;
  item: ScheduleItem | null;
  schedule: Schedule;
  disciplines: Discipline[];
  members: Member[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const [form, setForm] = useState<ItemInput>(() => item ? toInput(item, schedule.dependencies) : {
    disciplineId: null, parentId: null, kind: 'TASK', title: '', startDate: schedule.today, endDate: schedule.today,
    ownerUserId: null, partnerName: null, progress: 0, predecessorIds: [],
  });
  const [actualEnd, setActualEnd] = useState(schedule.today);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const set = (patch: Partial<ItemInput>) => setForm((f) => ({ ...f, ...patch }));
  const others = schedule.items.filter((i) => i.id !== item?.id);
  const hasChildren = !!item && schedule.items.some((i) => i.parentId === item.id);

  const run = async (action: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    try {
      await action();
      onSaved();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  const num = (v: string) => (v ? Number(v) : null);
  const row = 'grid grid-cols-[88px_1fr] items-center gap-3';

  return (
    <div className="fixed inset-0 z-50">
      <div onClick={onClose} className="absolute inset-0 bg-black/40" />
      <aside role="dialog" aria-modal="true" aria-label="일정 항목"
        className="absolute right-0 top-0 flex h-full w-full max-w-[480px] flex-col bg-white shadow-xl">
        <div className="flex items-start justify-between gap-4 border-b border-[#E5E3DC] px-6 py-4">
          <div className="min-w-0">
            <p className="text-xs text-gray-500">
              {item ? `${STATUS_LABEL[item.status]}${item.delayDays > 0 ? ` · 지연 ${item.delayDays}일` : ''}` : '새 항목'}
            </p>
            <h2 className="mt-1 truncate text-lg font-semibold text-gray-900">{item?.title ?? '일정 항목 추가'}</h2>
          </div>
          <button type="button" onClick={onClose} aria-label="닫기" className="text-xl leading-none text-gray-400 hover:text-gray-700">×</button>
        </div>

        <form className="flex-1 space-y-3 overflow-y-auto px-6 py-5" onSubmit={(e) => {
          e.preventDefault();
          run(() => item ? updateItem(projectId, item.id, form) : createItem(projectId, form));
        }}>
          {error && <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-[#991B1B]">{error}</p>}
          <label className={row}><span className="text-sm text-gray-500">항목명</span>
            <input required maxLength={200} className={field} value={form.title} onChange={(e) => set({ title: e.target.value })} /></label>
          <label className={row}><span className="text-sm text-gray-500">종류</span>
            <select className={field} value={form.kind} onChange={(e) => set({ kind: e.target.value as ItemInput['kind'] })}>
              <option value="TASK">작업(기간)</option><option value="MILESTONE">마일스톤(하루)</option>
            </select></label>
          <label className={row}><span className="text-sm text-gray-500">분야</span>
            <select className={field} value={form.disciplineId ?? ''} onChange={(e) => set({ disciplineId: num(e.target.value) })}>
              <option value="">공통(미지정)</option>
              {disciplines.filter((d) => d.active || d.id === form.disciplineId)
                .map((d) => <option key={d.id} value={d.id}>{d.name}{d.active ? '' : ' (비활성)'}</option>)}
            </select></label>
          <label className={row}><span className="text-sm text-gray-500">묶음</span>
            <select className={field} value={form.parentId ?? ''} disabled={hasChildren} onChange={(e) => set({ parentId: num(e.target.value) })}>
              <option value="">없음</option>
              {others.filter((i) => i.parentId === null).map((i) => <option key={i.id} value={i.id}>{i.title}</option>)}
            </select></label>
          {form.kind === 'TASK' && (
            <label className={row}><span className="text-sm text-gray-500">시작</span>
              <input type="date" required className={field} value={form.startDate ?? ''} onChange={(e) => set({ startDate: e.target.value })} /></label>
          )}
          <label className={row}><span className="text-sm text-gray-500">{form.kind === 'TASK' ? '종료' : '날짜'}</span>
            <input type="date" required className={field} value={form.endDate} onChange={(e) => set({ endDate: e.target.value })} /></label>
          <label className={row}><span className="text-sm text-gray-500">담당</span>
            <select className={field} value={form.ownerUserId ?? ''} onChange={(e) => set({ ownerUserId: num(e.target.value) })}>
              <option value="">미지정</option>
              {members.map((m) => <option key={m.userId} value={m.userId}>{m.name}</option>)}
            </select></label>
          <label className={row}><span className="text-sm text-gray-500">협력사</span>
            <input maxLength={100} className={field} placeholder="예: 가온구조" value={form.partnerName ?? ''}
              onChange={(e) => set({ partnerName: e.target.value || null })} /></label>
          <label className={row}><span className="text-sm text-gray-500">진행 %</span>
            <input type="number" min={0} max={100} className={field} value={form.progress}
              onChange={(e) => set({ progress: Number(e.target.value) })} /></label>
          <div className={`${row} items-start`}><span className="pt-1 text-sm text-gray-500">선행 (FS)</span>
            <div className="max-h-36 space-y-1 overflow-y-auto rounded-lg border border-[#E5E3DC] px-2.5 py-1.5">
              {others.length === 0 && <p className="text-xs text-gray-400">다른 항목이 없습니다.</p>}
              {others.map((i) => (
                <label key={i.id} className="flex items-center gap-2 truncate text-sm" title={i.title}>
                  <input type="checkbox" checked={form.predecessorIds.includes(i.id)} onChange={(e) => set({
                    predecessorIds: e.target.checked ? [...form.predecessorIds, i.id] : form.predecessorIds.filter((p) => p !== i.id),
                  })} />
                  <span className="truncate">{i.title}</span>
                </label>
              ))}
            </div>
          </div>
          <div className="flex justify-end pt-2">
            <button type="submit" disabled={busy}
              className="rounded-lg bg-[#1F4E79] px-4 py-2 text-sm font-semibold text-white hover:bg-[#173a5c] disabled:opacity-40">저장</button>
          </div>
        </form>

        {item && (
          <div className="space-y-3 border-t border-[#E5E3DC] px-6 py-4">
            {item.actualEnd ? (
              <p className="text-sm text-gray-600">완료 · 실적 종료일 {item.actualEnd}</p>
            ) : (
              <div className="flex items-center gap-2">
                <span className="text-sm text-gray-500">실적 종료일</span>
                <input type="date" aria-label="실적 종료일" max={schedule.today} value={actualEnd}
                  onChange={(e) => setActualEnd(e.target.value)} className="rounded-lg border border-[#E5E3DC] px-2 py-1.5 text-sm" />
                <button type="button" disabled={busy || !actualEnd} className={btn}
                  onClick={() => run(() => completeItem(projectId, item.id, actualEnd))}>완료 처리</button>
              </div>
            )}
            <button type="button" disabled={busy} className={`${btn} hover:!border-[#991B1B] hover:!text-[#991B1B]`}
              onClick={() => window.confirm(`'${item.title}'${hasChildren ? '과 하위 항목' : ''}을 삭제할까요? 이력은 남습니다.`)
                && run(() => deleteItem(projectId, item.id))}>삭제</button>
          </div>
        )}
      </aside>
    </div>
  );
}
