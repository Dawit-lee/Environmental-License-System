import { useMemo } from 'react';
import { Gantt, Willow, type IApi, type ITask, type ILink } from '@svar-ui/react-gantt';
import '@svar-ui/react-gantt/all.css';
import './ScheduleGantt.css';
import { addDays, differenceInCalendarDays, format, getDaysInMonth, parseISO, subDays } from 'date-fns';
import type { Dependency, Discipline, ScheduleItem } from '../api/schedule';

// ---------------------------------------------------------------------------
// ScheduleGantt — the only place that knows SVAR React Gantt (MIT edition; no PRO props such as markers or
// baselines). It just maps schedule items to Gantt rows: discipline group › item (› sub-item), bars, milestone
// diamonds, FS links, today column, "+N일" past the baseline (text, not PRO baseline bars) and a D-day row when the
// main base date is the bid date. Edits happen outside; a bar drag reports the new dates via onMove. Items without
// dates are not passed in.
// The chart keeps its own copy of the data after init: the parent re-keys it after every load, so a rejected
// drag snaps back to the server's dates.
// ---------------------------------------------------------------------------

export type Scale = 'week' | 'month';

/** Group rows have string ids, items keep their numeric id. */
const GROUP = 'd:';
const isGroup = (id: unknown) => typeof id === 'string' && id.startsWith(GROUP);

/** Gantt end is exclusive; ours is the last day. */
export function toGantt(items: ScheduleItem[], deps: Dependency[], disciplines: Discipline[]): { tasks: ITask[]; links: ILink[] } {
  const visible = new Set(items.map((i) => i.id));
  const order = new Map(disciplines.map((d) => [d.id, d.sortOrder]));
  const groups = new Map<number, ScheduleItem[]>(); // 0 = 공통 (no discipline)
  for (const i of items) {
    const key = i.disciplineId ?? 0;
    groups.set(key, [...(groups.get(key) ?? []), i]);
  }
  const tasks: ITask[] = [];
  [...groups.entries()]
    .sort(([a], [b]) => (order.get(a) ?? -1) - (order.get(b) ?? -1))
    .forEach(([key, rows]) => {
      const starts = rows.map((r) => parseISO(r.startDate!).getTime());
      const ends = rows.map((r) => parseISO(r.endDate!).getTime());
      tasks.push({
        id: GROUP + key, type: 'summary', open: true, text: rows[0].disciplineName ?? '공통',
        start: new Date(Math.min(...starts)), end: addDays(new Date(Math.max(...ends)), 1),
        owner: '', endText: '', delayDays: 0, // shown in the grid columns
      });
      for (const r of rows) {
        const start = parseISO(r.startDate!);
        tasks.push({
          id: r.id,
          parent: r.parentId && visible.has(r.parentId) ? r.parentId : GROUP + key,
          open: true,
          text: r.title,
          type: r.kind === 'MILESTONE' ? 'milestone' : 'task',
          start,
          end: r.kind === 'MILESTONE' ? start : addDays(parseISO(r.endDate!), 1),
          progress: r.progress,
          owner: r.ownerName ?? '',
          endText: r.endDate!.slice(2).replaceAll('-', '.'),
          delayDays: r.delayDays,
          slipDays: r.slipDays ?? 0,
          done: r.status === 'DONE',
        });
      }
    });
  const links: ILink[] = deps
    .filter((d) => visible.has(d.predecessorId) && visible.has(d.successorId))
    .map((d) => ({ id: `${d.predecessorId}-${d.successorId}`, type: 'e2s', source: d.predecessorId, target: d.successorId }));
  return { tasks, links };
}

/** Back from a dragged Gantt task to our inclusive dates. */
export function fromGantt(task: ITask, kind: ScheduleItem['kind']): { startDate: string; endDate: string } {
  const start = format(task.start as Date, 'yyyy-MM-dd');
  return { startDate: start, endDate: kind === 'MILESTONE' ? start : format(subDays(task.end as Date, 1), 'yyyy-MM-dd') };
}

const DelayCell = ({ row }: { row: ITask }) =>
  row.delayDays > 0 ? <span className="text-[#991B1B]">+{row.delayDays}일</span> : null;

/** Bar content: the title, and 기준 대비 연기 as plain text just right of the bar. */
const Bar = ({ data }: { data: ITask }) => {
  const slip = data.slipDays > 0 ? <span className="schedule-slip">+{data.slipDays}일</span> : null;
  return data.type === 'milestone'
    ? <div className="schedule-bar-out">{data.text}{slip && <> {slip}</>}</div>
    : <><div className="schedule-bar-text">{data.text}</div>{slip && <div className="schedule-bar-slip">{slip}</div>}</>;
};

/** "D-90" / "D-day" / "D+3" for the cell starting at d. */
export const dDay = (submission: Date, d: Date) => {
  const n = differenceInCalendarDays(submission, d);
  return n === 0 ? 'D-day' : n > 0 ? `D-${n}` : `D+${-n}`;
};

const COLUMNS = [
  { id: 'text', header: '항목', flexgrow: 1 },
  { id: 'owner', header: '담당', width: 72 },
  { id: 'endText', header: '종료', width: 72, align: 'center' as const },
  { id: 'delayDays', header: '지연', width: 56, align: 'right' as const, cell: DelayCell },
];

/**
 * The today line. SVAR's vertical markers are PRO, and highlightTime only tags whole cells of the smallest scale
 * (a week or a month here), so the tagged cell draws a line at today's day inside it: day index and cell length
 * as classes → CSS variables (see ScheduleGantt.css).
 */
export function todayClass(today: Date, cellStart: Date, unit: string): string {
  const days = unit === 'week' ? 7 : unit === 'month' ? getDaysInMonth(cellStart) : 0;
  const d = differenceInCalendarDays(today, cellStart);
  return days && d >= 0 && d < days ? `schedule-today schedule-day-${d} schedule-len-${days}` : '';
}
const TODAY_CSS = [
  ...Array.from({ length: 31 }, (_, i) => `.schedule-day-${i}{--today-day:${i}}`),
  ...[7, 28, 29, 30, 31].map((n) => `.schedule-len-${n}{--today-len:${n}}`),
].join('');

const SCALES: Record<Scale, { cellWidth: number; scales: { unit: string; step: number; format: (d: Date) => string }[] }> = {
  week: {
    cellWidth: 70, // per week
    scales: [
      { unit: 'month', step: 1, format: (d) => format(d, 'yyyy년 M월') },
      { unit: 'week', step: 1, format: (d) => format(d, 'M/d') },
    ],
  },
  month: {
    cellWidth: 90, // per month
    scales: [
      { unit: 'year', step: 1, format: (d) => format(d, 'yyyy년') },
      { unit: 'month', step: 1, format: (d) => format(d, 'M월') },
    ],
  },
};

export default function ScheduleGantt({ items, dependencies, disciplines, today, scale, submission, onOpen, onMove }: {
  items: ScheduleItem[];
  dependencies: Dependency[];
  disciplines: Discipline[];
  today: string;
  scale: Scale;
  submission: string | null; // 주 기준일 = 입찰(제출)일이면 그 날짜: the axis gets a D-day row
  onOpen: (id: number) => void;
  onMove: (id: number, dates: { startDate: string; endDate: string }) => void;
}) {
  const { tasks, links } = useMemo(() => toGantt(items, dependencies, disciplines), [items, dependencies, disciplines]);
  const todayDate = parseISO(today);
  const kinds = useMemo(() => new Map(items.map((i) => [i.id, i.kind])), [items]);
  const scales = submission
    ? [...SCALES[scale].scales, { unit: scale, step: 1, format: (d: Date) => dDay(parseISO(submission), d) }]
    : SCALES[scale].scales;

  const init = (api: IApi) => {
    // edits go through our own form and API; the Gantt only drags bars
    for (const action of ['show-editor', 'add-task', 'delete-task', 'copy-task', 'move-task', 'add-link', 'delete-link'] as const) {
      api.intercept(action, () => false);
    }
    api.intercept('drag-task', (ev) => !isGroup(ev.id));
    // never keep a selection, so clicking the same row again (after closing the form) opens it again
    api.intercept('select-task', (ev) => {
      if (!isGroup(ev.id)) onOpen(ev.id as number);
      return false;
    });
    api.on('update-task', (ev) => {
      if (ev.inProgress || isGroup(ev.id)) return;
      const task = api.getTask(ev.id);
      onMove(ev.id as number, fromGantt(task, kinds.get(ev.id as number) ?? 'TASK'));
    });
  };

  return (
    <div className="schedule-gantt h-[600px] overflow-hidden rounded-xl border border-[#E5E3DC] bg-white">
      <style>{TODAY_CSS}</style>
      <Willow fonts={false}>
        <Gantt
          init={init}
          tasks={tasks}
          links={links}
          columns={COLUMNS}
          scales={scales}
          taskTemplate={Bar}
          cellWidth={SCALES[scale].cellWidth}
          lengthUnit="day"
          cellHeight={34}
          scaleHeight={30}
          gridWidth={420}
          // only the smallest row: the upper header cells are rounded to whole lower cells
          highlightTime={(d, unit) => (unit === scale ? todayClass(todayDate, d, unit) : '')}
        />
      </Willow>
    </div>
  );
}
