import FullCalendar from '@fullcalendar/react';
import dayGridPlugin from '@fullcalendar/daygrid';
import listPlugin from '@fullcalendar/list';
import koLocale from '@fullcalendar/core/locales/ko';
import type { DatesSetArg, EventInput } from '@fullcalendar/core';
import { addDays, format, parseISO } from 'date-fns';
import type { CalendarData, CalendarDeadline, CalendarMilestone, ProjectEvent } from '../api/events';
import { EVENT_KIND_LABEL } from '../api/events';
import './ScheduleCalendar.css';

// ---------------------------------------------------------------------------
// ScheduleCalendar — the only place that knows FullCalendar (standard MIT plugins: daygrid + list, Korean locale).
// It maps calendar data to FullCalendar events and reports the visible range and clicks; fetching and the detail
// panel live outside. Trips and other multi-day events draw as range bars in the month grid.
// ---------------------------------------------------------------------------

export type CalendarEntry =
  | { type: 'event'; event: ProjectEvent }
  | { type: 'milestone'; milestone: CalendarMilestone }
  | { type: 'deadline'; deadline: CalendarDeadline };

const TRIP = 'var(--color-ok)';
const EVENT = 'var(--color-brand)';
const MILESTONE = 'var(--color-body)';
const DEADLINE = 'var(--color-warn)';

/** Colour key under the toolbar; every entry also says its kind in words (출장 · …, ◆, 기한 · …), never colour alone. */
export const calendarLegend = (showDeadlines: boolean) => [
  { key: 'event', label: '이벤트(회의·심의 등)', color: EVENT, mark: 'bar' },
  { key: 'trip', label: '출장', color: TRIP, mark: 'bar' },
  { key: 'milestone', label: '◆ 마일스톤', color: MILESTONE, mark: 'none' },
  ...(showDeadlines ? [{ key: 'deadline', label: '기한 · 인허가 기한', color: DEADLINE, mark: 'dot' }] : []),
] as const;

/** FullCalendar's all-day end is exclusive; ours is the last day. */
export function toCalendarEvents(data: CalendarData, opts: { showDeadlines: boolean; showProject: boolean }): EventInput[] {
  // a short project tag in the cell, the whole name in the hover text (title attribute, see eventDidMount)
  // shortcut: the tag is the name's first word ("가람시 하수처리장…" → "가람시"); use Project.shortName once the calendar API returns it
  const prefix = (projectName: string) => (opts.showProject ? `[${projectName.split(' ')[0]}] ` : '');
  const full = (projectName: string, text: string) => (opts.showProject ? `[${projectName}] ${text}` : text);
  return [
    ...data.events.map((e): EventInput => ({
      id: `e${e.id}`,
      title: `${prefix(e.projectName)}${EVENT_KIND_LABEL[e.kind]} · ${e.title}`,
      full: full(e.projectName, `${EVENT_KIND_LABEL[e.kind]} · ${e.title}`),
      start: e.allDay ? e.startsAt.slice(0, 10) : e.startsAt,
      end: e.allDay ? format(addDays(parseISO(e.endsAt.slice(0, 10)), 1), 'yyyy-MM-dd') : e.endsAt,
      allDay: e.allDay,
      color: e.kind === 'TRIP' ? TRIP : EVENT,
      display: 'block',
      extendedProps: { entry: { type: 'event', event: e } satisfies CalendarEntry },
    })),
    ...data.milestones.map((m): EventInput => ({
      id: `m${m.id}`,
      title: `${prefix(m.projectName)}◆ ${m.title}${m.done ? ' (완료)' : ''}`,
      full: full(m.projectName, `◆ ${m.title}${m.done ? ' (완료)' : ''}`),
      start: m.date,
      allDay: true,
      display: 'list-item',
      classNames: ['is-milestone'], // the ◆ in the title marks it; no list dot
      color: MILESTONE,
      extendedProps: { entry: { type: 'milestone', milestone: m } satisfies CalendarEntry },
    })),
    ...(opts.showDeadlines ? data.deadlines : []).map((d): EventInput => ({
      id: `d${d.id}`,
      title: `${prefix(d.projectName)}기한 · ${d.title}`,
      full: full(d.projectName, `기한 · ${d.title}`),
      start: d.dueDate,
      allDay: true,
      display: 'list-item',
      color: DEADLINE,
      extendedProps: { entry: { type: 'deadline', deadline: d } satisfies CalendarEntry },
    })),
  ];
}

export default function ScheduleCalendar({ data, showDeadlines, showProject, onRange, onOpen }: {
  data: CalendarData;
  showDeadlines: boolean;
  showProject: boolean;
  /** visible range, inclusive YYYY-MM-DD */
  onRange: (from: string, to: string) => void;
  onOpen: (entry: CalendarEntry) => void;
}) {
  return (
    <div className="schedule-calendar rounded border border-line bg-white p-4">
      <ul aria-label="달력 범례" className="mb-3 flex flex-wrap gap-x-5 gap-y-1 text-[15px] text-body">
        {calendarLegend(showDeadlines).map((l) => (
          <li key={l.key} className="flex items-center gap-2">
            {l.mark !== 'none' && (
              <span aria-hidden="true" className={l.mark === 'bar' ? 'h-3 w-5 rounded-sm' : 'size-2.5 rounded-full'} style={{ background: l.color }} />
            )}
            {l.label}
          </li>
        ))}
      </ul>
      <FullCalendar
        plugins={[dayGridPlugin, listPlugin]}
        locale={koLocale}
        initialView="dayGridMonth"
        headerToolbar={{ left: 'prev,next today', center: 'title', right: 'dayGridMonth,listWeek' }}
        buttonText={{ month: '월', list: '주 목록' }}
        height="auto"
        dayMaxEvents={4}
        events={toCalendarEvents(data, { showDeadlines, showProject })}
        datesSet={(arg: DatesSetArg) => onRange(format(arg.start, 'yyyy-MM-dd'), format(addDays(arg.end, -1), 'yyyy-MM-dd'))}
        // cells wrap to two lines (ScheduleCalendar.css); the whole text is on hover
        eventDidMount={(info) => { info.el.title = info.event.extendedProps.full as string; }}
        eventClick={(info) => onOpen(info.event.extendedProps.entry as CalendarEntry)}
      />
    </div>
  );
}
