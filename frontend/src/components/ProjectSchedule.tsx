import { lazy, Suspense, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';
import { api } from '../api/client';
import {
  ANCHOR_LABEL, ANCHORS, completeItem, reopenItem, createItem, deleteItem, getDisciplines, getSchedule,
  needsReason, offsetLabel, STATUS_LABEL, toInput, updateItem,
  type Anchor, type Discipline, type ItemInput, type Schedule, type ScheduleItem,
} from '../api/schedule';
import CalendarPage from '../pages/CalendarPage';
import { getProjectEvents, type ProjectEvent } from '../api/events';
import ProjectCalendar from './ProjectCalendar';
import type { Scale } from './ScheduleGantt';
import { BaseDatesDialog, BaselineDialog, DelayTable, PasteDialog, TemplateDialog } from './ScheduleTools';
import ReadOnlySchedule from './ReadOnlySchedule';
import ShareDialog from './ShareDialog';
import { usePrompt } from './PromptDialog';
import { HELP, Help, Tip } from './Onboarding';
import { useDialogFocus, useEscape } from './useEscape';
import { EmptyBlock } from './ListParts';
import { Alert } from './ui/Alert';
import { Button } from './ui/Button';
import { Select } from './ui/Field';
import { SummaryStrip } from './ui/SummaryStrip';
import { Tabs } from './ui/Tabs';
import { cn, focusRing } from './ui/cn';

// the Gantt library is large; only this tab loads it
const ScheduleGantt = lazy(() => import('./ScheduleGantt'));

// ---------------------------------------------------------------------------
// ProjectSchedule — 프로젝트 › 일정 tab: view switch [간트] [달력] [지연 현황] [인허가 기한] [인쇄본], a summary line (기준 회차,
// 공정률, 지연 N건) and the base-date / baseline / template / paste tools. Clicking a row opens the item form;
// dragging a bar saves (a slip past the baseline asks for the reason first).
// ---------------------------------------------------------------------------

type View = 'gantt' | 'calendar' | 'delays' | 'deadlines' | 'print';
interface Member { userId: number; name: string }

// DESIGN.md: toolbar buttons 40px tertiary; form inputs 48px with the input border
const btnBase = cn('inline-flex h-10 shrink-0 items-center rounded border bg-white px-3 text-[15px] disabled:cursor-not-allowed disabled:border-transparent disabled:bg-line disabled:text-disabled', focusRing);
const btn = cn(btnBase, 'border-input text-ink hover:bg-paper');
const dangerBtn = cn(btnBase, 'ml-auto border-danger text-danger hover:bg-danger-bg');
const control = cn('rounded border border-input bg-white text-ink placeholder:text-disabled focus:border-brand disabled:bg-line disabled:text-disabled', focusRing);
const field = cn(control, 'h-12 w-full px-4 text-base');
/** An item linked to a base date, offsets left out: the server computes them back from its dates. */
const UNLINK_OFFSETS = { offsetDays: null, offsetMonths: null, durationDays: null };
/** 일·주·월 segmented control: the picked one is filled blue. */
const seg = (on: boolean) => cn('h-10 min-w-11 px-3 text-[15px]', focusRing, on ? 'bg-brand font-bold text-white' : 'text-ink hover:bg-paper');
const VIEWS: { key: View; label: string; help?: string }[] = [
  { key: 'gantt', label: '간트' }, { key: 'calendar', label: '달력' }, { key: 'delays', label: '지연 현황', help: HELP.delays.text },
  { key: 'deadlines', label: '인허가 기한', help: HELP.deadlines.text }, { key: 'print', label: '인쇄본', help: HELP.print.text },
];

export default function ProjectSchedule({ projectId }: { projectId: number }) {
  const [ask, promptUi] = usePrompt();
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
  type Tool = 'base' | 'baseline' | 'template' | 'paste' | 'share';
  // ?tool=template etc.: the 시작하기 checklist opens the dialog directly
  const [tool, setTool] = useState<Tool | null>(() => {
    const t = new URLSearchParams(window.location.search).get('tool');
    return t === 'base' || t === 'baseline' || t === 'template' || t === 'paste' || t === 'share' ? t : null;
  });

  // ?item=<id> (a notification link): open that item once it is loaded
  const [params] = useSearchParams();
  const openId = Number(params.get('item')) || 0;
  const opened = useRef(0);
  useEffect(() => {
    const item = openId !== opened.current ? schedule?.items.find((i) => i.id === openId) : undefined;
    if (item) { opened.current = openId; setEditing(item); }
  }, [openId, schedule]);

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
  const overdue = schedule?.items.filter((i) => i.status === 'DELAYED').length ?? 0;
  const dated = visible.filter((i) => i.endDate);
  const submission = schedule?.base === 'SUBMISSION' ? schedule.anchors.find((a) => a.anchor === 'SUBMISSION')?.date ?? null : null;

  const move = async (id: number, dates: { startDate: string; endDate: string }) => {
    const item = schedule?.items.find((i) => i.id === id);
    if (!item || (item.startDate === dates.startDate && item.endDate === dates.endDate)) return;
    let reason: string | null = null;
    if (needsReason(item, dates.endDate)) {
      reason = (await ask(`기준(${item.baselineEnd})보다 늦어집니다. 연기 사유를 입력하세요.`))?.trim() || null;
      if (!reason) return load();
    }
    setError(null);
    try {
      // a relative item stays on its base date: no offsets = computed back from the dragged dates
      const relative = item.anchor ? UNLINK_OFFSETS : {};
      await updateItem(projectId, id, { ...toInput(item, schedule!.dependencies), ...dates, ...relative, reason });
    } catch (e) {
      setError((e as Error).message);
    }
    await load(); // on failure this snaps the bar back
  };

  return (
    <div className="space-y-6">
      {promptUi}
      <Tabs label="일정 보기" value={view} onChange={(k) => setView(k as View)}
        tabs={VIEWS.map((v) => ({ key: v.key, label: <span title={v.help}>{v.label}</span> }))} />
      {view !== 'deadlines' && schedule && (
        <>
          <SummaryStrip items={[
            { label: <>기준 회차{!schedule.baselineNo && <Help k="unbaselined" />}</>, value: schedule.baselineNo ? `${schedule.baselineNo}차` : '미확정' },
            ...(schedule.progress ? [{
              label: <>공정률 (예정 / 진행){schedule.progress.weightTotal !== 100 && <span className="text-danger"> · 보할 합 {schedule.progress.weightTotal}</span>}</>,
              value: `${schedule.progress.planned} / ${schedule.progress.actual}`, unit: '%',
            }] : []),
            { label: '항목', value: schedule.items.length, unit: '건' },
            { label: <>기한 경과<Help k="overdue" /></>, value: overdue, unit: '건', tone: overdue ? 'danger' as const : undefined, alert: overdue > 0 },
            ...(schedule.baselineNo > 0 ? [{
              label: <>일정 지연<Help k="slip" /></>, value: schedule.delayCount, unit: '건', tone: schedule.delayCount ? 'warning' as const : undefined,
            }] : []),
          ]} />
          {/* 도구 막대: 기준·템플릿·붙여넣기·공유 */}
          <div className="flex flex-wrap items-center gap-2">
            <Tip k="base" align="left"><button type="button" className={btn} onClick={() => setTool('base')}>기준일</button></Tip>
            <Tip k="baseline" align="left"><button type="button" className={btn} disabled={!schedule.items.length} onClick={() => setTool('baseline')}>
              {schedule.baselineNo ? '기준 재확정' : '기준 확정'}</button></Tip>
            <Tip k="template"><button type="button" className={btn} onClick={() => setTool('template')}>템플릿</button></Tip>
            <Tip k="paste"><button type="button" className={btn} onClick={() => setTool('paste')}>붙여넣기</button></Tip>
            <Tip k="share"><button type="button" className={btn} onClick={() => setTool('share')}>공유</button></Tip>
          </div>
        </>
      )}

      {view === 'delays' && <DelayTable key={loads} projectId={projectId} baselined={!!schedule?.baselineNo} />}

      {view === 'deadlines' && <CalendarPage projectId={projectId} />}
      {view === 'print' && schedule && (
        <>
          <div className="flex flex-wrap items-end gap-3">
            <Select label="분야" className="w-full sm:w-44" value={filter.discipline} onChange={(e) => setFilter({ ...filter, discipline: e.target.value })}>
              <option value="">전체</option>
              <option value="0">공통(미지정)</option>
              {disciplines.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </Select>
            <Button variant="tertiary" onClick={() => window.print()}>인쇄 · PDF</Button>
          </div>
          <ReadOnlySchedule title="공정표"
            subtitle={`${schedule.today} 기준${filter.discipline ? ` · ${filter.discipline === '0' ? '공통' : disciplines.find((d) => String(d.id) === filter.discipline)?.name ?? ''}` : ''}`}
            items={schedule.items.filter((i) => i.endDate && (!filter.discipline || String(i.disciplineId ?? 0) === filter.discipline))
              .map((i) => ({ ...i, startDate: i.startDate!, endDate: i.endDate! }))}
            events={events.filter((e) => !filter.discipline || String(e.disciplineId ?? 0) === filter.discipline)} />
        </>
      )}
      {view === 'calendar' && (
        <ProjectCalendar projectId={projectId} disciplines={disciplines} members={members} onChanged={load}
          onOpenItem={(id) => setEditing(schedule?.items.find((i) => i.id === id) ?? null)} />
      )}

      {view === 'gantt' && (
        <>
          {/* 필터 줄: 분야·담당·기한 경과 + 오른쪽 축척·항목 추가 */}
          <div className="flex flex-wrap items-end gap-3">
            <Select label="분야" className="w-full sm:w-44" value={filter.discipline} onChange={(e) => setFilter({ ...filter, discipline: e.target.value })}>
              <option value="">전체</option>
              <option value="0">공통(미지정)</option>
              {disciplines.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </Select>
            <Select label="담당" className="w-full sm:w-44" value={filter.owner} onChange={(e) => setFilter({ ...filter, owner: e.target.value })}>
              <option value="">전체</option>
              <option value="0">미지정</option>
              {members.map((m) => <option key={m.userId} value={m.userId}>{m.name}</option>)}
            </Select>
            <label className="flex h-12 items-center gap-2 text-[15px] text-ink">
              <input type="checkbox" checked={filter.delayedOnly} className={cn('size-5 accent-brand', focusRing)}
                onChange={(e) => setFilter({ ...filter, delayedOnly: e.target.checked })} />
              기한 경과만
            </label>
            <div className="flex w-full items-center gap-2 sm:ml-auto sm:w-auto">
              <div role="group" aria-label="간트 축척" className="inline-flex overflow-hidden rounded border border-input bg-white">
                <button type="button" aria-pressed={scale === 'day'} className={seg(scale === 'day')} onClick={() => setScale('day')}>일</button>
                <button type="button" aria-pressed={scale === 'week'} className={cn(seg(scale === 'week'), 'border-x border-input')} onClick={() => setScale('week')}>주</button>
                <button type="button" aria-pressed={scale === 'month'} className={seg(scale === 'month')} onClick={() => setScale('month')}>월</button>
              </div>
              <Button size="sm" className="ml-auto sm:ml-0" onClick={() => setEditing('new')}>+ 항목 추가</Button>
            </div>
          </div>

          {error && <Alert tone="danger">{error}</Alert>}

          {dated.length < visible.length && (
            <p className="break-keep text-[15px] text-body">날짜 미정 {visible.length - dated.length}건: 기준일이 정해지면 간트에 나타납니다. [기준일]에서 날짜를 넣으세요.</p>
          )}
          {schedule && dated.length > 0 && (
            <Suspense fallback={<div className="h-[600px] rounded border border-line bg-white" />}>
            <ScheduleGantt key={`${scale}:${loads}`} items={dated} dependencies={schedule.dependencies} events={events}
              disciplines={disciplines} today={schedule.today} scale={scale} submission={submission}
              onOpen={(id) => setEditing(schedule.items.find((i) => i.id === id) ?? null)} onMove={move} />
            </Suspense>
          )}
          {schedule && visible.length === 0 && schedule.items.length > 0 && (
            <EmptyBlock title="조건에 맞는 항목이 없습니다." action={(
              <Button variant="tertiary" onClick={() => setFilter({ discipline: '', owner: '', delayedOnly: false })}>필터 모두 지우기</Button>
            )} />
          )}
          {schedule && schedule.items.length === 0 && (
            <EmptyBlock title="등록된 일정 항목이 없습니다." description="분야별 작업과 마일스톤을 간트로 관리합니다. 템플릿으로 시작하면 자주 쓰는 공정이 한 번에 들어갑니다." action={(
              <div className="flex flex-wrap justify-center gap-2">
                <Button variant="tertiary" onClick={() => setEditing('new')}>항목 직접 추가</Button>
                <Button variant="tertiary" onClick={() => setTool('paste')}>엑셀 붙여넣기</Button>
                <Button onClick={() => setTool('template')}>템플릿으로 시작</Button>
              </div>
            )} />
          )}
        </>
      )}

      {tool === 'base' && schedule && (
        <BaseDatesDialog projectId={projectId} schedule={schedule} onClose={() => setTool(null)} onChanged={load} />
      )}
      {tool === 'baseline' && schedule && (
        <BaselineDialog projectId={projectId} schedule={schedule} onClose={() => setTool(null)} onDone={() => { setTool(null); load(); }} />
      )}
      {tool === 'template' && schedule && (
        <TemplateDialog projectId={projectId} schedule={schedule} disciplines={disciplines} onClose={() => setTool(null)} onChanged={() => { setTool(null); load(); }} />
      )}
      {tool === 'paste' && (
        <PasteDialog projectId={projectId} onClose={() => setTool(null)} onChanged={() => { setTool(null); load(); }} />
      )}
      {tool === 'share' && <ShareDialog projectId={projectId} disciplines={disciplines} onClose={() => setTool(null)} />}
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
  useEscape(onClose);
  const box = useRef<HTMLElement>(null);
  useDialogFocus(box);
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
  // open when the item already uses an advanced field, so nothing set is hidden
  const [advancedOpen] = useState(() => !!item && (!!item.anchor || item.progress > 0 || item.weight != null
    || !!item.partnerName || item.parentId != null || schedule.dependencies.some((d) => d.successorId === item.id)));
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
  // DESIGN.md 입력: label above the control (15px 700)
  const row = 'flex flex-col gap-2';

  return (
    <div className="fixed inset-0 z-50">
      <div onClick={onClose} className="absolute inset-0 bg-black/40" />
      <aside role="dialog" aria-modal="true" ref={box} tabIndex={-1} aria-label="일정 항목"
        className="absolute right-0 top-0 flex h-full w-full max-w-[480px] flex-col bg-white shadow-xl">
        <div className="flex items-start justify-between gap-4 border-b border-line px-6 py-4">
          <div className="min-w-0">
            <p className="truncate text-[15px] text-body">
              {item ? `${STATUS_LABEL[item.status]}${item.delayDays > 0 ? ` ${item.delayDays}일` : ''}` : '새 항목'}
              {item?.baselineEnd && ` · 원래 ${item.baselineEnd} → 지금 ${item.endDate ?? '미정'}`}
              {!!item?.slipDays && item.slipDays > 0 && <span className="text-danger"> (+{item.slipDays}일)</span>}
            </p>
            <h2 className="mt-1 truncate text-[19px] font-bold text-ink">{item?.title ?? '일정 항목 추가'}</h2>
          </div>
          <button type="button" onClick={onClose} aria-label="닫기" className={cn('-mr-3 -mt-2 flex size-11 shrink-0 items-center justify-center rounded text-2xl leading-none text-body hover:bg-paper', focusRing)}>×</button>
        </div>

        <form className="flex-1 space-y-5 overflow-y-auto px-6 py-6" onSubmit={(e) => {
          e.preventDefault();
          run(() => item ? updateItem(projectId, item.id, form) : createItem(projectId, form));
        }}>
          {error && <Alert tone="danger">{error}</Alert>}
          <label className={row}><span className="text-[15px] font-bold text-ink">항목명</span>
            <input required maxLength={200} className={field} value={form.title} onChange={(e) => set({ title: e.target.value })} /></label>
          <label className={row}><span className="text-[15px] font-bold text-ink">종류</span>
            <select className={field} value={form.kind} onChange={(e) => set({ kind: e.target.value as ItemInput['kind'] })}>
              <option value="TASK">작업(기간)</option><option value="MILESTONE">마일스톤(하루)</option>
            </select></label>
          {form.kind === 'MILESTONE' && (
            <div className={row}><span className="text-[15px] font-bold text-ink">제출·보고</span>
              <label className="flex min-h-11 items-center gap-2 text-[15px] text-ink">
                <input type="checkbox" className={cn('size-5 shrink-0 accent-brand', focusRing)} checked={!!form.submission} onChange={(e) => set({ submission: e.target.checked })} />
                발주처 제출·보고 마일스톤 (Google 동기화 대상)
              </label></div>
          )}
          <label className={row}><span className="text-[15px] font-bold text-ink">분야</span>
            <select className={field} value={form.disciplineId ?? ''} onChange={(e) => set({ disciplineId: num(e.target.value) })}>
              <option value="">공통(미지정)</option>
              {disciplines.filter((d) => d.active || d.id === form.disciplineId)
                .map((d) => <option key={d.id} value={d.id}>{d.name}{d.active ? '' : ' (비활성)'}</option>)}
            </select></label>
          {/* exact days for linked items too: a picked date drops the offsets, the server computes them back (as a drag does) */}
          {form.kind === 'TASK' && (
            <label className={row}><span className="text-[15px] font-bold text-ink">시작</span>
              <input type="date" required className={field} value={form.startDate ?? ''}
                onChange={(e) => set({ startDate: e.target.value, ...(relative ? UNLINK_OFFSETS : {}) })} /></label>
          )}
          <label className={row}><span className="text-[15px] font-bold text-ink">{form.kind === 'TASK' ? '종료' : '날짜'}</span>
            <input type="date" required className={field} value={form.endDate ?? ''}
              onChange={(e) => set({ endDate: e.target.value, ...(relative ? UNLINK_OFFSETS : {}) })} /></label>
          <label className={row}><span className="text-[15px] font-bold text-ink">담당</span>
            <select className={field} value={form.ownerUserId ?? ''} onChange={(e) => set({ ownerUserId: num(e.target.value) })}>
              <option value="">미지정</option>
              {members.map((m) => <option key={m.userId} value={m.userId}>{m.name}</option>)}
            </select></label>
          {item?.baselineEnd && (
            <label className={row}><span className="text-[15px] font-bold text-ink">연기 사유</span>
              <input maxLength={500} className={field} placeholder="기준보다 늦출 때 필수" value={form.reason ?? ''}
                onChange={(e) => set({ reason: e.target.value })} /></label>
          )}
          {item?.delayReason && <p className="truncate text-[15px] text-body" title={item.delayReason}>마지막 연기 사유: {item.delayReason}</p>}
          {/* 기본 칸만 먼저: 기준일 연결·진행률·선행은 접어 둔다 */}
          <details open={advancedOpen} className="rounded border border-line px-4 py-3">
            <summary className={cn('min-h-6 cursor-pointer rounded-sm text-[15px] font-bold text-ink', focusRing)}>고급 (기준일 연결 · 진행률 · 선행 항목)</summary>
            <div className="mt-4 space-y-5">
          <label className={row}><span className="text-[15px] font-bold text-ink">묶음</span>
            <select className={field} value={form.parentId ?? ''} disabled={hasChildren} onChange={(e) => set({ parentId: num(e.target.value) })}>
              <option value="">없음</option>
              {others.filter((i) => i.parentId === null).map((i) => <option key={i.id} value={i.id}>{i.title}</option>)}
            </select></label>
          <label className={row}><span className="text-[15px] font-bold text-ink">날짜 기준</span>
            <select className={field} value={form.anchor ?? ''}
              onChange={(e) => set({ anchor: (e.target.value || null) as Anchor | null, offsetDays: null, offsetMonths: null, durationDays: null })}>
              <option value="">고정일</option>
              {ANCHORS.map((a) => <option key={a} value={a}>{ANCHOR_LABEL[a]} ({dateOf(a) ?? '미정'})</option>)}
            </select></label>
          {form.anchor && (
            <div className={row}><span className="text-[15px] font-bold text-ink">{form.kind === 'TASK' ? '종료일까지' : '기준일로부터'}</span>
              <div className="flex gap-2">
                <input type="number" aria-label="기준일로부터" className={field} placeholder="비우면 아래 날짜로 계산" value={offset ?? ''}
                  onChange={(e) => {
                    const v = e.target.value === '' ? null : Number(e.target.value);
                    set(unit === 'month' ? { offsetMonths: v, offsetDays: null } : { offsetDays: v, offsetMonths: null });
                  }} />
                <select aria-label="단위" className={cn(control, 'h-12 w-28 shrink-0 px-3 text-base')} value={unit} onChange={(e) => {
                  const u = e.target.value as 'day' | 'month';
                  setUnit(u);
                  set(u === 'month' ? { offsetMonths: offset, offsetDays: null } : { offsetDays: offset, offsetMonths: null });
                }}><option value="day">일</option><option value="month">개월</option></select>
              </div>
            </div>
          )}
          {relative && form.kind === 'TASK' && (
            <label className={row}><span className="text-[15px] font-bold text-ink">기간(일)</span>
              <input type="number" min={1} required className={field} value={form.durationDays ?? ''}
                onChange={(e) => set({ durationDays: num(e.target.value) })} /></label>
          )}
          {relative && (
            <p className="break-keep text-[15px] text-body">
              {offsetLabel({ anchor: form.anchor, offsetDays: form.offsetDays, offsetMonths: form.offsetMonths })} · 기준일이 바뀌면 함께 이동합니다.
              {' '}위에서 날짜를 고르면 그 날짜에 맞춰 다시 계산합니다.
            </p>
          )}
          <label className={row}><span className="text-[15px] font-bold text-ink">협력사</span>
            <input maxLength={100} className={field} placeholder="예: 가온구조" value={form.partnerName ?? ''}
              onChange={(e) => set({ partnerName: e.target.value || null })} /></label>
          <label className={row}><span className="text-[15px] font-bold text-ink">진행 %</span>
            <input type="number" min={0} max={100} className={field} value={form.progress}
              onChange={(e) => set({ progress: Number(e.target.value) })} /></label>
          <label className={row}><span className="text-[15px] font-bold text-ink">보할 %</span>
            <input type="number" min={0} max={100} step="0.01" className={field} placeholder="공정률 계산용(선택)" value={form.weight ?? ''}
              onChange={(e) => set({ weight: num(e.target.value) })} /></label>
          <div className={row}><span className="text-[15px] font-bold text-ink">먼저 끝낼 항목</span>
            <div className="max-h-44 space-y-1 overflow-y-auto rounded border border-input px-3 py-2">
              {others.length === 0 && <p className="text-[15px] text-body">다른 항목이 없습니다.</p>}
              {others.map((i) => (
                <label key={i.id} className="flex min-h-10 items-center gap-2 truncate text-[15px] text-ink" title={i.title}>
                  <input type="checkbox" className={cn('size-5 shrink-0 accent-brand', focusRing)} checked={form.predecessorIds.includes(i.id)} onChange={(e) => set({
                    predecessorIds: e.target.checked ? [...form.predecessorIds, i.id] : form.predecessorIds.filter((p) => p !== i.id),
                  })} />
                  <span className="truncate">{i.title}</span>
                </label>
              ))}
            </div>
          </div>
            </div>
          </details>
          <div className="sticky bottom-0 -mx-6 flex justify-end border-t border-line bg-white px-6 py-3">
            <Button type="submit" disabled={busy}>저장</Button>
          </div>
        </form>

        {item && (
          <div className="flex flex-wrap items-center gap-2 border-t border-line px-6 py-4">
            {item.actualEnd ? (
              <div className="flex w-full flex-wrap items-center gap-2">
                <span className="text-[15px] text-body">완료 · 실적 종료일 {item.actualEnd}</span>
                <button type="button" disabled={busy} className={btn}
                  onClick={() => window.confirm(`'${item.title}'의 완료를 취소할까요?`) && run(() => reopenItem(projectId, item.id))}>완료 취소</button>
              </div>
            ) : (
              <div className="flex w-full flex-wrap items-center gap-2">
                <span className="text-[15px] font-bold text-ink">실적 종료일</span>
                <input type="date" aria-label="실적 종료일" max={schedule.today} value={actualEnd}
                  onChange={(e) => setActualEnd(e.target.value)} className={cn(control, 'h-10 px-3 text-[15px]')} />
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
            <button type="button" disabled={busy} className={dangerBtn}
              onClick={() => window.confirm(`'${item.title}'${hasChildren ? '과 하위 항목' : ''}을 삭제할까요? 이력은 남습니다.`)
                && run(() => deleteItem(projectId, item.id))}>삭제</button>
          </div>
        )}
      </aside>
    </div>
  );
}
