import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router';
import { getDeadlines, ymd, type Deadline } from '../api/notifications';
import { focusRing } from '../components/ui/cn';

// ---------------------------------------------------------------------------
// CalendarPage — month grid of permit due-dates; each day lists up to 3 titles with a color-coded dot
// and a click-to-open day popover. Pure React + Tailwind, no chart/date libs.
// ---------------------------------------------------------------------------

type DotTone = 'red' | 'orange' | 'yellow' | 'green' | 'gray';

function tone(dDay: number): DotTone {
  if (dDay < 0) return 'gray';
  if (dDay <= 7) return 'red';
  if (dDay <= 30) return 'orange';
  if (dDay <= 60) return 'yellow';
  return 'green';
}

const TONE_DOT: Record<DotTone, string> = {
  red: 'bg-danger',
  orange: 'bg-warn',
  yellow: 'bg-amber-400',
  green: 'bg-ok',
  gray: 'bg-line',
};

const TONE_LABEL: Record<DotTone, string> = {
  red: 'D-7 이내',
  orange: 'D-30 이내',
  yellow: 'D-60 이내',
  green: '60일 넘게 남음',
  gray: '지남',
};

const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];

/** With `projectId` (project tab) only that project's deadlines are shown. */
export default function CalendarPage({ projectId }: { projectId?: number }) {
  const [year, setYear] = useState(() => new Date().getFullYear());
  const [month, setMonth] = useState(() => new Date().getMonth() + 1); // 1-based
  const [openDay, setOpenDay] = useState<string | null>(null);
  const [deadlines, setDeadlines] = useState<Deadline[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setError(null);
    getDeadlines(ymd(new Date(year, month - 1, 1)), ymd(new Date(year, month, 0)))
      // 인허가 기한 only: schedule items and events have the 달력 view next to this one
      .then((l) => setDeadlines(l.filter((d) => (!projectId || d.projectId === projectId)
        && d.kind !== 'SCHEDULE_ITEM' && d.kind !== 'PROJECT_EVENT')), (e: Error) => setError(e.message));
  }, [year, month, projectId]);

  const byDate = useMemo(() => {
    const map: Record<string, Deadline[]> = {};
    for (const d of deadlines) {
      (map[d.dueDate] ??= []).push(d);
    }
    return map;
  }, [deadlines]);

  // Build the grid cells (leading blanks + days of month).
  const cells = useMemo(() => {
    const firstDay = new Date(year, month - 1, 1).getDay();
    const daysInMonth = new Date(year, month, 0).getDate();
    const result: (number | null)[] = [];
    for (let i = 0; i < firstDay; i++) result.push(null);
    for (let d = 1; d <= daysInMonth; d++) result.push(d);
    return result;
  }, [year, month]);

  const dateKey = (day: number) =>
    `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;

  const prevMonth = () => {
    setOpenDay(null);
    if (month === 1) { setYear((y) => y - 1); setMonth(12); }
    else setMonth((m) => m - 1);
  };
  const nextMonth = () => {
    setOpenDay(null);
    if (month === 12) { setYear((y) => y + 1); setMonth(1); }
    else setMonth((m) => m + 1);
  };

  return (
    <div className="min-h-full bg-paper">
      <div className={projectId ? '' : 'py-8'}>
        {!projectId && <h1 className="text-2xl font-bold text-ink">인허가 캘린더</h1>}

        {/* Legend */}
        <div className="mt-4 flex flex-wrap items-center gap-4 rounded border border-line bg-white px-4 py-3 text-[15px]">
          {(Object.keys(TONE_LABEL) as DotTone[]).map((tone) => (
            <span key={tone} className="flex items-center gap-2 text-body">
              <span className={`h-2.5 w-2.5 rounded-full ${TONE_DOT[tone]}`} />
              {TONE_LABEL[tone]}
            </span>
          ))}
        </div>

        {/* Month navigation */}
        <div className="mt-6 flex items-center justify-center gap-6">
          <button
            type="button"
            onClick={prevMonth}
            aria-label="이전 달"
            className={`flex size-11 items-center justify-center rounded text-2xl text-body hover:bg-white ${focusRing}`}
          >
            ‹
          </button>
          <h2 className="text-[19px] font-bold tabular-nums text-ink">
            {year}년 {month}월
          </h2>
          <button
            type="button"
            onClick={nextMonth}
            aria-label="다음 달"
            className={`flex size-11 items-center justify-center rounded text-2xl text-body hover:bg-white ${focusRing}`}
          >
            ›
          </button>
        </div>

        {error && <p role="alert" className="mt-4 text-center text-[15px] text-danger">{error}</p>}

        {/* Calendar grid */}
        <div className="mt-4 overflow-hidden rounded border border-line bg-white">
          <div className="grid grid-cols-7 border-b border-line bg-paper text-center text-[15px] font-bold text-ink">
            {WEEKDAYS.map((w, i) => (
              <div key={w} className={`py-2 ${i === 0 ? 'text-danger' : i === 6 ? 'text-brand-text' : ''}`}>
                {w}
              </div>
            ))}
          </div>
          <div className="grid grid-cols-7">
            {cells.map((day, idx) => {
              if (day === null) {
                return <div key={`b-${idx}`} className="min-h-[104px] border-b border-r border-line-soft bg-paper" />;
              }
              const key = dateKey(day);
              const items = byDate[key] ?? [];
              const isOpen = openDay === key;
              return (
                <div key={key} className="relative min-h-[104px] border-b border-r border-line-soft p-1.5">
                  <button
                    type="button"
                    onClick={() => setOpenDay(isOpen ? null : key)}
                    disabled={items.length === 0}
                    className={`flex h-full w-full flex-col items-start rounded p-1 text-left ${focusRing} ${
                      items.length > 0
                        ? 'cursor-pointer hover:bg-paper'
                        : 'cursor-default'
                    } ${isOpen ? 'bg-brand-subtle' : ''}`}
                  >
                    <span className="text-[15px] tabular-nums text-ink">{day}</span>
                    {items.length > 0 && (
                      <span className="mt-1 flex w-full min-w-0 flex-col gap-0.5">
                        {items.slice(0, 3).map((it) => (
                          <span key={it.id} className="flex w-full min-w-0 items-start gap-1 text-[13px] leading-[18px] text-body" title={`${it.projectName} — ${it.title}`}>
                            <span className={`mt-[6px] h-1.5 w-1.5 shrink-0 rounded-full ${TONE_DOT[tone(it.dDay)]}`} />
                            <span className="line-clamp-2 break-all">{it.title}</span>
                          </span>
                        ))}
                        {items.length > 3 && <span className="text-[13px] text-body">+{items.length - 3}건 더</span>}
                      </span>
                    )}
                  </button>

                  {/* Popover */}
                  {isOpen && items.length > 0 && (
                    <div className="absolute left-1/2 top-full z-20 mt-1 w-64 -translate-x-1/2 rounded border border-line bg-white p-3 shadow-lg">
                      <div className="mb-2 flex items-center justify-between">
                        <span className="text-[15px] font-bold text-ink">{month}월 {day}일</span>
                        <button
                          type="button"
                          onClick={() => setOpenDay(null)}
                          aria-label="닫기"
                          className="text-mute transition hover:text-body"
                        >
                          ✕
                        </button>
                      </div>
                      <ul className="space-y-2">
                        {items.map((it) => (
                          <li key={it.id}>
                            <Link to={`/projects/${it.projectId}`} className={`flex items-start gap-2 rounded text-[15px] hover:bg-paper ${focusRing}`}>
                              <span className={`mt-1.5 h-2 w-2 shrink-0 rounded-full ${TONE_DOT[tone(it.dDay)]}`} />
                              <div>
                                <p className="font-bold text-ink">{it.title}</p>
                                <p className="text-[13px] text-body">{it.projectName}</p>
                              </div>
                            </Link>
                          </li>
                        ))}
                      </ul>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}
