import { useMemo, useState } from 'react';

// ---------------------------------------------------------------------------
// CalendarPage — month grid of permit due-dates with color-coded dot badges
// and a click-to-open day popover. Pure React + Tailwind, no chart/date libs.
// ---------------------------------------------------------------------------

type DotTone = 'red' | 'orange' | 'yellow' | 'green';

interface CalendarPermit {
  id: number;
  date: string; // YYYY-MM-DD
  projectName: string;
  permitName: string;
  tone: DotTone; // red: D-7, orange: D-30, yellow: D-60, green: 취득완료
}

const TONE_DOT: Record<DotTone, string> = {
  red: 'bg-[#991B1B]',
  orange: 'bg-[#B45309]',
  yellow: 'bg-amber-400',
  green: 'bg-[#0F6E56]',
};

const TONE_LABEL: Record<DotTone, string> = {
  red: 'D-7 이내',
  orange: 'D-30 이내',
  yellow: 'D-60 이내',
  green: '취득완료',
};

const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];

// TODO: replace with GET /api/calendar?month=YYYY-MM
const PERMITS: CalendarPermit[] = [
  { id: 1, date: '2026-05-25', projectName: '부산 하수처리장 증설공사', permitName: '소방시설 착공신고', tone: 'orange' },
  { id: 2, date: '2026-05-12', projectName: '광주 통합바이오가스화시설', permitName: '개발행위허가', tone: 'green' },
  { id: 3, date: '2026-06-18', projectName: '부산 하수처리장 증설공사', permitName: '구조안전 확인', tone: 'orange' },
  { id: 4, date: '2026-06-20', projectName: '부산 하수처리장 증설공사', permitName: '건축허가', tone: 'orange' },
  { id: 5, date: '2026-06-25', projectName: '부산 하수처리장 증설공사', permitName: '전기사업 공사계획 신고', tone: 'red' },
  { id: 6, date: '2026-06-25', projectName: '청주 자원회수시설 신설', permitName: '대기배출시설 설치허가', tone: 'red' },
  { id: 7, date: '2026-07-15', projectName: '부산 하수처리장 증설공사', permitName: '소방시설 착공신고', tone: 'yellow' },
  { id: 8, date: '2026-05-30', projectName: '안산 산업단지 폐수처리장', permitName: '수질오염물질 배출시설 설치허가', tone: 'yellow' },
];

export default function CalendarPage() {
  // Default to the current project timeline month.
  const [year, setYear] = useState(2026);
  const [month, setMonth] = useState(5); // 1-based
  const [openDay, setOpenDay] = useState<string | null>(null);

  const byDate = useMemo(() => {
    const map: Record<string, CalendarPermit[]> = {};
    for (const p of PERMITS) {
      (map[p.date] ??= []).push(p);
    }
    return map;
  }, []);

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
    <div className="min-h-full bg-[#F8F7F2]">
      <div className="mx-auto max-w-5xl px-4 py-8 sm:px-6">
        <h1 className="text-2xl font-bold text-[#1F4E79]">인허가 캘린더</h1>

        {/* Legend */}
        <div className="mt-4 flex flex-wrap items-center gap-4 rounded-lg border border-[#E5E3DC] bg-white px-4 py-2.5 text-sm">
          {(Object.keys(TONE_LABEL) as DotTone[]).map((tone) => (
            <span key={tone} className="flex items-center gap-2 text-gray-600">
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
            className="rounded-lg px-3 py-1.5 text-lg text-gray-600 transition hover:bg-white focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/30"
          >
            ‹
          </button>
          <h2 className="text-lg font-semibold text-gray-900">
            {year}년 {month}월
          </h2>
          <button
            type="button"
            onClick={nextMonth}
            aria-label="다음 달"
            className="rounded-lg px-3 py-1.5 text-lg text-gray-600 transition hover:bg-white focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/30"
          >
            ›
          </button>
        </div>

        {/* Calendar grid */}
        <div className="mt-4 overflow-hidden rounded-xl border border-[#E5E3DC] bg-white shadow-sm">
          <div className="grid grid-cols-7 border-b border-[#E5E3DC] bg-[#F8F7F2] text-center text-xs font-medium text-gray-500">
            {WEEKDAYS.map((w, i) => (
              <div key={w} className={`py-2 ${i === 0 ? 'text-[#991B1B]' : i === 6 ? 'text-[#1F4E79]' : ''}`}>
                {w}
              </div>
            ))}
          </div>
          <div className="grid grid-cols-7">
            {cells.map((day, idx) => {
              if (day === null) {
                return <div key={`b-${idx}`} className="min-h-[88px] border-b border-r border-[#E5E3DC] bg-[#F8F7F2]/40" />;
              }
              const key = dateKey(day);
              const items = byDate[key] ?? [];
              const isOpen = openDay === key;
              return (
                <div key={key} className="relative min-h-[88px] border-b border-r border-[#E5E3DC] p-1.5">
                  <button
                    type="button"
                    onClick={() => setOpenDay(isOpen ? null : key)}
                    disabled={items.length === 0}
                    className={`flex h-full w-full flex-col items-start rounded-md p-1 text-left transition focus:outline-none ${
                      items.length > 0
                        ? 'cursor-pointer hover:bg-[#F8F7F2] focus:ring-2 focus:ring-[#1F4E79]/30'
                        : 'cursor-default'
                    } ${isOpen ? 'bg-[#1F4E79]/5 ring-2 ring-[#1F4E79]/30' : ''}`}
                  >
                    <span className="text-sm text-gray-700">{day}</span>
                    {items.length > 0 && (
                      <span className="mt-1 flex flex-wrap gap-1">
                        {items.map((it) => (
                          <span key={it.id} className={`h-2 w-2 rounded-full ${TONE_DOT[it.tone]}`} />
                        ))}
                      </span>
                    )}
                  </button>

                  {/* Popover */}
                  {isOpen && items.length > 0 && (
                    <div className="absolute left-1/2 top-full z-20 mt-1 w-64 -translate-x-1/2 rounded-lg border border-[#E5E3DC] bg-white p-3 shadow-lg">
                      <div className="mb-2 flex items-center justify-between">
                        <span className="text-sm font-semibold text-gray-900">{month}월 {day}일</span>
                        <button
                          type="button"
                          onClick={() => setOpenDay(null)}
                          aria-label="닫기"
                          className="text-gray-400 transition hover:text-gray-700"
                        >
                          ✕
                        </button>
                      </div>
                      <ul className="space-y-2">
                        {items.map((it) => (
                          <li key={it.id} className="flex items-start gap-2 text-sm">
                            <span className={`mt-1.5 h-2 w-2 shrink-0 rounded-full ${TONE_DOT[it.tone]}`} />
                            <div>
                              <p className="font-medium text-gray-800">{it.permitName}</p>
                              <p className="text-xs text-gray-500">{it.projectName}</p>
                            </div>
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
