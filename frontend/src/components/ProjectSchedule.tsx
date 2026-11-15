import { lazy, Suspense, useEffect, useMemo, useState } from 'react';
import { api } from '../api/client';
import {
  ANCHOR_LABEL, ANCHORS, completeItem, confirmBaseline, createItem, deleteItem, getDisciplines, getSchedule,
  needsReason, offsetLabel, STATUS_LABEL, toInput, updateItem,
  type Anchor, type Discipline, type ItemInput, type Schedule, type ScheduleItem,
} from '../api/schedule';
import CalendarPage from '../pages/CalendarPage';
import { getProjectEvents, type ProjectEvent } from '../api/events';
import ProjectCalendar from './ProjectCalendar';
import type { Scale } from './ScheduleGantt';
import { BaseDatesDialog, DelayTable, PasteDialog, TemplateDialog } from './ScheduleTools';

// the Gantt library is large; only this tab loads it
const ScheduleGantt = lazy(() => import('./ScheduleGantt'));

// ---------------------------------------------------------------------------
// ProjectSchedule — 프로젝트 › 일정 tab: view switch [간트] [지연 현황] [인허가 기한], a summary line (기준 회차,
// 공정률, 지연 N건) and the base-date / baseline / template / paste tools. Clicking a row opens the item form;
// dragging a bar saves (a slip past the baseline asks for the reason first).
// ---------------------------------------------------------------------------

type View = 'gantt' | 'calendar' | 'delays' | 'deadlines';
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
  const [tool, setTool] = useState<'base' | 'template' | 'paste' | null>(null);

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
  const dated = visible.filter((i) => i.endDate);
  const submission = schedule?.base === 'SUBMISSION' ? schedule.anchors.find((a) => a.anchor === 'SUBMISSION')?.date ?? null : null;

  const move = async (id: number, dates: { startDate: string; endDate: string }) => {
    const item = schedule?.items.find((i) => i.id === id);
    if (!item || (item.startDate === dates.startDate && item.endDate === dates.endDate)) return;
    let reason: string | null = null;
    if (needsReason(item, dates.endDate)) {
      reason = window.prompt(`기준(${item.baselineEnd})보다 늦어집니다. 연기 사유를 입력하세요.`)?.trim() || null;
      if (!reason) return load();
    }
    setError(null);
    try {
      // a relative item stays on its base date: no offsets = computed back from the dragged dates
      const relative = item.anchor ? { offsetDays: null, offsetMonths: null, durationDays: null } : {};
      await updateItem(projectId, id, { ...toInput(item, schedule!.dependencies), ...dates, ...relative, reason });
    } catch (e) {
      setError((e as Error).message);
    }
    await load(); // on failure this snaps the bar back
  };

  const baseline = async () => {
    if (!schedule) return;
    let reason: string | null = null;
    if (schedule.baselineNo === 0) {
      if (!window.confirm('현재 계획을 기준 계획으로 확정할까요? 이후 기준보다 늦추면 사유가 필요합니다.')) return;
    } else {
      reason = window.prompt(`기준 ${schedule.baselineNo}차를 현재 계획으로 다시 확정합니다(PM·프로젝트 어드민). 사유를 입력하세요.`)?.trim() || null;
      if (!reason) return;
    }
    setError(null);
    try {
      await confirmBaseline(projectId, reason);
    } catch (e) {
      setError((e as Error).message);
    }
    await load();
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <div className="inline-flex overflow-hidden rounded-lg border border-[#E5E3DC] bg-white">
          <button type="button" className={seg(view === 'gantt')} onClick={() => setView('gantt')}>간트</button>
          <button type="button" className={seg(view === 'calendar')} onClick={() => setView('calendar')}>달력</button>
          <button type="button" className={seg(view === 'delays')} onClick={() => setView('delays')}>지연 현황</button>
          <button type="button" className={seg(view === 'deadlines')} onClick={() => setView('deadlines')}>인허가 기한</button>
        </div>
        {view !== 'deadlines' && schedule && (
          <p className="truncate text-sm text-gray-500">
            {schedule.baselineNo ? `기준 ${schedule.baselineNo}차` : '기준 미확정'}
            {schedule.progress && <> · 예정 {schedule.progress.planned}% / 진행 {schedule.progress.actual}%
              {schedule.progress.weightTotal !== 100 && <span className="text-[#991B1B]"> (보할 합 {schedule.progress.weightTotal})</span>}</>}
            {' · '}항목 {schedule.items.length}건 · <span className={schedule.delayCount ? 'text-[#991B1B]' : ''}>지연 {schedule.delayCount}건</span>
          </p>
        )}
        {view !== 'deadlines' && schedule && (
          <div className="ml-auto flex items-center gap-2">
            <button type="button" className={btn} onClick={() => setTool('base')}>기준일</button>
            <button type="button" className={btn} disabled={!schedule.items.length} onClick={baseline}>
              {schedule.baselineNo ? '기준 재확정' : '기준 확정'}</button>
            <button type="button" className={btn} onClick={() => setTool('template')}>템플릿</button>
            <button type="button" className={btn} onClick={() => setTool('paste')}>붙여넣기</button>
          </div>
        )}
      </div>

      {view === 'delays' && <DelayTable key={loads} projectId={projectId} />}

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

          {dated.length < visible.length && (
            <p className="text-sm text-gray-500">날짜 미정 {visible.length - dated.length}건: 기준일이 정해지면 간트에 나타납니다. [기준일]에서 날짜를 넣으세요.</p>
          )}
          {schedule && dated.length > 0 && (
            <Suspense fallback={<div className="h-[600px] rounded-xl border border-[#E5E3DC] bg-white" />}>
            <ScheduleGantt key={`${scale}:${loads}`} items={dated} dependencies={schedule.dependencies} events={events}
              disciplines={disciplines} today={schedule.today} scale={scale} submission={submission}
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

      {tool === 'base' && schedule && (
        <BaseDatesDialog projectId={projectId} schedule={schedule} onClose={() => setTool(null)} onChanged={load} />
      )}
      {tool === 'template' && (
        <TemplateDialog projectId={projectId} onClose={() => setTool(null)} onChanged={() => { setTool(null); load(); }} />
      )}
      {tool === 'paste' && (
        <PasteDialog projectId={projectId} onClose={() => setTool(null)} onChanged={() => { setTool(null); load(); }} />
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
  const dateOf = (a: Anchor) => schedule.anchors.find((x) => x.anchor === a)?.date ?? null;
  // a new item is relative to the main base date when that date is known (설계 §2.3)
  const [form, setForm] = useState<ItemInput>(() => item ? { ...toInput(item, schedule.dependencies), reason: '' } : {
    disciplineId: null, parentId: null, kind: 'TASK', title: '', startDate: schedule.today, endDate: schedule.today,
    anchor: dateOf(schedule.base) ? schedule.base : null, offsetDays: null, offsetMonths: null, durationDays: null,
    ownerUserId: null, partnerName: null, weight: null, progress: 0, predecessorIds: [],
  });
  const [unit, setUnit] = useState<'day' | 'month'>(item?.offsetMonths != null ? 'month' : 'day');
  const offset = form.offsetMonths ?? form.offsetDays;
  const relative = !!form.anchor && offset != null;
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
            <p className="truncate text-xs text-gray-500">
              {item ? `${STATUS_LABEL[item.status]}${item.delayDays > 0 ? ` · 지연 ${item.delayDays}일` : ''}` : '새 항목'}
              {item?.baselineEnd && ` · 원래 ${item.baselineEnd} → 지금 ${item.endDate ?? '미정'}`}
              {!!item?.slipDays && item.slipDays > 0 && <span className="text-[#991B1B]"> (+{item.slipDays}일)</span>}
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
          <label className={row}><span className="text-sm text-gray-500">날짜 기준</span>
            <select className={field} value={form.anchor ?? ''}
              onChange={(e) => set({ anchor: (e.target.value || null) as Anchor | null, offsetDays: null, offsetMonths: null, durationDays: null })}>
              <option value="">고정일</option>
              {ANCHORS.map((a) => <option key={a} value={a}>{ANCHOR_LABEL[a]} ({dateOf(a) ?? '미정'})</option>)}
            </select></label>
          {form.anchor && (
            <div className={row}><span className="text-sm text-gray-500">{form.kind === 'TASK' ? '종료 오프셋' : '오프셋'}</span>
              <div className="flex gap-2">
                <input type="number" aria-label="오프셋" className={field} placeholder="비우면 아래 날짜로 계산" value={offset ?? ''}
                  onChange={(e) => {
                    const v = e.target.value === '' ? null : Number(e.target.value);
                    set(unit === 'month' ? { offsetMonths: v, offsetDays: null } : { offsetDays: v, offsetMonths: null });
                  }} />
                <select aria-label="오프셋 단위" className="rounded-lg border border-[#E5E3DC] px-2 text-sm" value={unit} onChange={(e) => {
                  const u = e.target.value as 'day' | 'month';
                  setUnit(u);
                  set(u === 'month' ? { offsetMonths: offset, offsetDays: null } : { offsetDays: offset, offsetMonths: null });
                }}><option value="day">일</option><option value="month">개월</option></select>
              </div>
            </div>
          )}
          {relative && form.kind === 'TASK' && (
            <label className={row}><span className="text-sm text-gray-500">기간(일)</span>
              <input type="number" min={1} required className={field} value={form.durationDays ?? ''}
                onChange={(e) => set({ durationDays: num(e.target.value) })} /></label>
          )}
          {relative ? (
            <p className="text-xs text-gray-500">
              {offsetLabel({ anchor: form.anchor, offsetDays: form.offsetDays, offsetMonths: form.offsetMonths })} · 기준일이 바뀌면 함께 이동합니다.
              {item?.endDate && ` 지금 ${item.kind === 'TASK' ? `${item.startDate} ~ ` : ''}${item.endDate}`}
            </p>
          ) : (<>
            {form.kind === 'TASK' && (
              <label className={row}><span className="text-sm text-gray-500">시작</span>
                <input type="date" required className={field} value={form.startDate ?? ''} onChange={(e) => set({ startDate: e.target.value })} /></label>
            )}
            <label className={row}><span className="text-sm text-gray-500">{form.kind === 'TASK' ? '종료' : '날짜'}</span>
              <input type="date" required className={field} value={form.endDate ?? ''} onChange={(e) => set({ endDate: e.target.value })} /></label>
          </>)}
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
          <label className={row}><span className="text-sm text-gray-500">보할 %</span>
            <input type="number" min={0} max={100} step="0.01" className={field} placeholder="공정률 계산용(선택)" value={form.weight ?? ''}
              onChange={(e) => set({ weight: num(e.target.value) })} /></label>
          {item?.baselineEnd && (
            <label className={row}><span className="text-sm text-gray-500">연기 사유</span>
              <input maxLength={500} className={field} placeholder="기준보다 늦출 때 필수" value={form.reason ?? ''}
                onChange={(e) => set({ reason: e.target.value })} /></label>
          )}
          {item?.delayReason && <p className="truncate text-xs text-gray-500" title={item.delayReason}>마지막 연기 사유: {item.delayReason}</p>}
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
            {item.anchor && item.endDate && (
              <button type="button" disabled={busy} className={btn} title="기준일과 상관없이 지금 날짜로 고정합니다"
                onClick={() => run(() => updateItem(projectId, item.id, {
                  ...toInput(item, schedule.dependencies), anchor: null, offsetDays: null, offsetMonths: null, durationDays: null,
                }))}>날짜 고정</button>
            )}
            {!item.anchor && (
              <button type="button" disabled={busy || !dateOf(schedule.base)} className={btn}
                title={dateOf(schedule.base) ? `${ANCHOR_LABEL[schedule.base]}로부터 오프셋을 계산합니다` : `${ANCHOR_LABEL[schedule.base]}이 정해지지 않았습니다`}
                onClick={() => run(() => updateItem(projectId, item.id, { ...toInput(item, schedule.dependencies), anchor: schedule.base }))}>
                {ANCHOR_LABEL[schedule.base]}에 연결</button>
            )}{' '}
            <button type="button" disabled={busy} className={`${btn} hover:!border-[#991B1B] hover:!text-[#991B1B]`}
              onClick={() => window.confirm(`'${item.title}'${hasChildren ? '과 하위 항목' : ''}을 삭제할까요? 이력은 남습니다.`)
                && run(() => deleteItem(projectId, item.id))}>삭제</button>
          </div>
        )}
      </aside>
    </div>
  );
}
