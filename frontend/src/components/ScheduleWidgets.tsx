import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { addDays, format, startOfWeek } from 'date-fns';
import { EVENT_KIND_LABEL, eventWhen, getCalendar, getProjectEvents, type ProjectEvent } from '../api/events';
import { getSchedule, type ScheduleItem } from '../api/schedule';
import { Help } from './Onboarding';

// ---------------------------------------------------------------------------
// Schedule widgets (설계 §6.5): dashboard "이번 주 이벤트" and "지연 항목(분야별)", overview "일정" card.
// ---------------------------------------------------------------------------

const card = 'rounded-xl border border-[#E5E3DC] bg-white p-5 shadow-sm';
const ymd = (d: Date) => format(d, 'yyyy-MM-dd');

/** Events overlapping this week (Mon–Sun) in my calendar scope, one line each. */
export function WeekEvents() {
  const [events, setEvents] = useState<ProjectEvent[] | null>(null);
  useEffect(() => {
    const monday = startOfWeek(new Date(), { weekStartsOn: 1 });
    getCalendar(ymd(monday), ymd(addDays(monday, 6))).then((d) => setEvents(d.events), () => setEvents([]));
  }, []);
  return (
    <section className={card}>
      <div className="flex items-baseline justify-between">
        <h2 className="text-lg font-semibold text-gray-900">이번 주 이벤트</h2>
        <Link to="/calendar" className="text-xs text-[#1F4E79] hover:underline">회사 달력</Link>
      </div>
      <div className="mt-3 overflow-x-auto">
        <table className="w-full min-w-[560px] table-fixed whitespace-nowrap text-left text-sm">
          <thead className="bg-[#F8F7F2] text-xs text-gray-500">
            <tr>
              <th className="w-40 px-3 py-2.5 font-medium">일시</th>
              <th className="w-24 px-3 py-2.5 font-medium">종류</th>
              <th className="px-3 py-2.5 font-medium">제목</th>
              <th className="w-48 px-3 py-2.5 font-medium">프로젝트</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[#E5E3DC]">
            {events?.length === 0 && <tr><td colSpan={4} className="px-3 py-6 text-center text-gray-400">이번 주 이벤트가 없습니다.</td></tr>}
            {events?.map((e) => (
              <tr key={e.id} className="hover:bg-[#F8F7F2]">
                <td className="truncate px-3 py-2.5 text-gray-600">{eventWhen(e)}</td>
                <td className="truncate px-3 py-2.5 text-gray-600">{EVENT_KIND_LABEL[e.kind]}</td>
                <td className="truncate px-3 py-2.5 font-medium text-gray-800" title={e.title}>{e.title}</td>
                <td className="truncate px-3 py-2.5">
                  <Link to={`/projects/${e.projectId}/schedule`} className="text-gray-700 hover:text-[#1F4E79] hover:underline" title={e.projectName}>{e.projectName}</Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

export interface DelayedCount {
  discipline: string;
  count: number;
}

/** Delayed schedule items across my projects, counted per discipline (from GET /api/dashboard; null while loading). */
export function DelayedByDiscipline({ counts }: { counts: DelayedCount[] | null }) {
  return (
    <section className={card}>
      <h2 className="text-lg font-semibold text-gray-900">기한 경과 일정 (분야별)<Help k="overdue" align="right" /></h2>
      <ul className="mt-3 divide-y divide-[#E5E3DC] text-sm">
        {counts?.length === 0 && <li className="py-3 text-gray-400">기한이 지난 일정 항목이 없습니다.</li>}
        {counts?.map(({ discipline, count }) => (
          <li key={discipline} className="flex justify-between py-2.5">
            <span className="truncate text-gray-700">{discipline}</span>
            <span className="text-[#991B1B]">{count}건</span>
          </li>
        ))}
      </ul>
    </section>
  );
}

/** Overview card: next 3 milestones, delayed count, next event. Members only. */
export function ScheduleSummary({ projectId }: { projectId: number }) {
  const [items, setItems] = useState<ScheduleItem[]>([]);
  const [today, setToday] = useState(ymd(new Date()));
  const [next, setNext] = useState<ProjectEvent | null>(null);
  useEffect(() => {
    getSchedule(projectId).then((s) => { setItems(s.items); setToday(s.today); }, () => {});
    const now = format(new Date(), "yyyy-MM-dd'T'HH:mm");
    getProjectEvents(projectId).then((l) => setNext(l.find((e) => e.endsAt.slice(0, 16) >= now) ?? null), () => {});
  }, [projectId]);
  const milestones = items.filter((i) => i.kind === 'MILESTONE' && !i.actualEnd && i.endDate >= today)
    .sort((a, b) => a.endDate.localeCompare(b.endDate)).slice(0, 3);
  const delayed = items.filter((i) => i.status === 'DELAYED').length;
  const base = `/projects/${projectId}/schedule`;
  return (
    <section className={card}>
      <div className="flex items-baseline justify-between">
        <h2 className="text-lg font-semibold text-gray-900">일정</h2>
        <Link to={base} className={`text-sm ${delayed ? 'text-[#991B1B]' : 'text-gray-500'} hover:underline`}>기한 경과 {delayed}건</Link>
      </div>
      <p className="mt-3 text-xs text-gray-500">다음 마일스톤</p>
      <ul className="mt-1 space-y-1 text-sm">
        {milestones.length === 0 && <li className="text-gray-400">예정된 마일스톤이 없습니다.</li>}
        {milestones.map((m) => (
          <li key={m.id} className="flex justify-between gap-3">
            <span className="truncate text-gray-800" title={m.title}>{m.title}</span>
            <span className="shrink-0 text-gray-500">{m.endDate}</span>
          </li>
        ))}
      </ul>
      <p className="mt-3 text-xs text-gray-500">다음 이벤트</p>
      <p className="mt-1 truncate text-sm text-gray-800" title={next?.title}>
        {next ? `${eventWhen(next)} ${EVENT_KIND_LABEL[next.kind]} · ${next.title}` : <span className="text-gray-400">없음</span>}
      </p>
    </section>
  );
}
