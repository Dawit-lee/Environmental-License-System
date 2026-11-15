import { addDays, differenceInCalendarDays, eachMonthOfInterval, format, parseISO } from 'date-fns';
import type { SharedEvent, SharedItem } from '../api/share';

// ---------------------------------------------------------------------------
// ReadOnlySchedule — 인쇄본 and the partner share page: a plain bar chart (discipline › item, milestone ◆) and the
// event list. No Gantt library: it prints on paper as is (wraps in .print-area) and has nothing to drag.
// ---------------------------------------------------------------------------

const when = (e: SharedEvent) => {
  const day = (s: string) => s.slice(0, 10).replaceAll('-', '.');
  const time = (s: string) => s.slice(11, 16);
  const sameDay = e.startsAt.slice(0, 10) === e.endsAt.slice(0, 10);
  if (e.allDay) return sameDay ? day(e.startsAt) : `${day(e.startsAt)} ~ ${day(e.endsAt)}`;
  return sameDay ? `${day(e.startsAt)} ${time(e.startsAt)}~${time(e.endsAt)}` : `${day(e.startsAt)} ${time(e.startsAt)} ~ ${day(e.endsAt)} ${time(e.endsAt)}`;
};

export default function ReadOnlySchedule({ title, subtitle, items, events }: {
  title: string; subtitle?: string; items: SharedItem[]; events: SharedEvent[];
}) {
  const days = [...items.flatMap((i) => [i.startDate, i.endDate]), ...events.flatMap((e) => [e.startsAt.slice(0, 10), e.endsAt.slice(0, 10)])]
    .map((d) => parseISO(d));
  const groups = new Map<string, SharedItem[]>(); // server order: discipline sort order, then start
  for (const i of items) {
    const key = i.disciplineName ?? '공통';
    groups.set(key, [...(groups.get(key) ?? []), i]);
  }

  return (
    <div className="print-area space-y-5 rounded-xl border border-[#E5E3DC] bg-white p-5">
      <div>
        <h1 className="text-xl font-bold text-[#1F4E79]">{title}</h1>
        {subtitle && <p className="mt-1 text-xs text-gray-500">{subtitle}</p>}
      </div>
      {items.length > 0 && days.length > 0 && <Bars items={groups} from={new Date(Math.min(...days.map(Number)))}
        to={addDays(new Date(Math.max(...days.map(Number))), 1)} />}
      {!items.length && <p className="text-sm text-gray-500">공정 항목이 없습니다.</p>}
      {events.length > 0 && (
        <section>
          <h2 className="mb-2 text-sm font-semibold text-gray-900">이벤트</h2>
          <table className="w-full text-left text-sm">
            <thead className="bg-[#F8F7F2] text-xs text-gray-500">
              <tr><th className="px-2 py-1.5">일시</th><th className="px-2 py-1.5">제목</th><th className="px-2 py-1.5">장소</th><th className="px-2 py-1.5">분야</th></tr>
            </thead>
            <tbody>
              {events.map((e, n) => (
                <tr key={n} className="border-t border-[#E5E3DC]">
                  <td className="whitespace-nowrap px-2 py-1.5">{when(e)}</td>
                  <td className="px-2 py-1.5">{e.title}</td>
                  <td className="px-2 py-1.5">{e.location ?? ''}</td>
                  <td className="px-2 py-1.5">{e.disciplineName ?? '공통'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
    </div>
  );
}

function Bars({ items, from, to }: { items: Map<string, SharedItem[]>; from: Date; to: Date }) {
  const span = Math.max(1, differenceInCalendarDays(to, from));
  const pct = (d: Date) => `${(differenceInCalendarDays(d, from) / span) * 100}%`;
  const months = eachMonthOfInterval({ start: from, end: addDays(to, -1) });
  return (
    <div className="text-xs">
      <div className="flex border-b border-[#E5E3DC] pb-1 text-gray-500">
        <div className="w-56 shrink-0">항목 (협력사)</div>
        <div className="relative h-4 flex-1">
          {months.map((m) => (
            <span key={m.toISOString()} className="absolute border-l border-[#E5E3DC] pl-1"
              style={{ left: m < from ? '0%' : pct(m) }}>{format(m, 'yy.M')}</span>
          ))}
        </div>
      </div>
      {[...items.entries()].map(([group, rows]) => (
        <div key={group} className="break-inside-avoid">
          <div className="mt-2 font-semibold text-gray-800">{group}</div>
          {rows.map((r, n) => {
            const start = parseISO(r.startDate);
            const end = addDays(parseISO(r.endDate), 1);
            return (
              <div key={n} className="flex items-center border-b border-[#F1EFE8] py-0.5">
                <div className="w-56 shrink-0 truncate pr-2" title={r.title}>
                  {r.title}{r.partnerName && <span className="text-gray-500"> ({r.partnerName})</span>}
                </div>
                <div className="relative h-4 flex-1">
                  {r.kind === 'MILESTONE'
                    ? <span className="absolute -translate-x-1/2 text-[#1F4E79]" style={{ left: pct(start) }}
                        title={r.endDate}>◆ <span className="text-gray-600">{r.endDate.slice(5).replace('-', '/')}</span></span>
                    : <span className="absolute top-1 h-2 rounded bg-[#1F4E79] print:[print-color-adjust:exact]"
                        style={{ left: pct(start), width: `max(2px, calc(${pct(end)} - ${pct(start)}))` }}
                        title={`${r.startDate} ~ ${r.endDate}`} />}
                </div>
              </div>
            );
          })}
        </div>
      ))}
    </div>
  );
}
