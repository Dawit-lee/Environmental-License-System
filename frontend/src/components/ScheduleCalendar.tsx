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

const TRIP = '#0F6E56';
const EVENT = '#1F4E79';

/** FullCalendar's all-day end is exclusive; ours is the last day. */
export function toCalendarEvents(data: CalendarData, opts: { showDeadlines: boolean; showProject: boolean }): EventInput[] {
  const prefix = (projectName: string) => (opts.showProject ? `[${projectName}] ` : '');
  return [
    ...data.events.map((e): EventInput => ({
      id: `e${e.id}`,
      title: `${prefix(e.projectName)}${EVENT_KIND_LABEL[e.kind]} · ${e.title}`,
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
      start: m.date,
      allDay: true,
      display: 'list-item',
      color: '#4B5563',
      extendedProps: { entry: { type: 'milestone', milestone: m } satisfies CalendarEntry },
    })),
    ...(opts.showDeadlines ? data.deadlines : []).map((d): EventInput => ({
      id: `d${d.id}`,
      title: `${prefix(d.projectName)}기한 · ${d.title}`,
      start: d.dueDate,
      allDay: true,
      display: 'list-item',
      color: '#B45309',
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
    <div className="schedule-calendar rounded-xl border border-[#E5E3DC] bg-white p-4">
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
        eventClick={(info) => onOpen(info.event.extendedProps.entry as CalendarEntry)}
      />
    </div>
  );
}
