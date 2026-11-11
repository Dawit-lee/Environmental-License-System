import { Link } from 'react-router';
import type { PermitItem, PermitStatus, Project } from '../types';
import { ymd, type Deadline } from '../api/notifications';

// ---------------------------------------------------------------------------
// Dashboard pieces shared by the portfolio dashboard (DashboardPage) and the project 개요 tab (ProjectOverview).
// ---------------------------------------------------------------------------

export function dDayClasses(dDay: number): string {
  if (dDay <= 7) return 'text-[#991B1B] font-semibold';
  if (dDay <= 30) return 'text-[#B45309] font-semibold';
  if (dDay <= 60) return 'text-amber-500 font-medium';
  return 'text-gray-500';
}

// Adjacent pairs pass the dataviz CVD / normal-vision separation check; every chart also shows a text legend.
export const STATUSES: { status: PermitStatus; label: string; color: string }[] = [
  { status: 'COMPLETED', label: '취득완료', color: '#0F6E56' },
  { status: 'IN_PROGRESS', label: '진행중', color: '#3B82C4' },
  { status: 'NOT_APPLIED', label: '미신청', color: '#D1D5DB' },
  { status: 'NEAR_EXPIRY', label: '만료임박', color: '#E8A33D' },
  { status: 'EXPIRED', label: '만료', color: '#991B1B' },
];

export const isOpen = (s: { status: string }) => s.status === 'PENDING' || s.status === 'SUBMITTED';

export interface Activity {
  key: string;
  date: string;
  text: string;
}

// Recent activity = latest application / acquisition dates recorded on permits.
export function recentActivity(projects: Project[], permits: PermitItem[]): Activity[] {
  const names = new Map(projects.map((p) => [p.id, p.name]));
  return permits
    .flatMap((p) => [
      p.applicationDate && { key: `a${p.id}`, date: p.applicationDate, text: `${names.get(p.projectId)} — ${p.name} 신청` },
      p.acquisitionDate && { key: `q${p.id}`, date: p.acquisitionDate, text: `${names.get(p.projectId)} — ${p.name} 취득완료` },
    ])
    .filter((a): a is Activity => !!a && a.date <= ymd(new Date())) // planned future dates are not activity
    .sort((a, b) => b.date.localeCompare(a.date))
    .slice(0, 5);
}

/** Deadline → the project tab that handles it. */
export const deadlineLink = (d: Deadline) => `/projects/${d.projectId}/${d.kind === 'OBLIGATION' || d.kind === 'CORRECTIVE_TASK' ? 'obligations' : 'schedule'}`;

export function StatusDonut({ permits }: { permits: PermitItem[] }) {
  const total = permits.length;
  const slices = STATUSES.map((s) => ({ ...s, value: permits.filter((p) => p.status === s.status).length }));
  // Build conic-gradient stops for the donut (grey ring when there are no permits yet).
  let acc = 0;
  const gradient = total === 0 ? '#E5E3DC' : `conic-gradient(${slices
    .map((s) => {
      const start = (acc / total) * 360;
      acc += s.value;
      return `${s.color} ${start}deg ${(acc / total) * 360}deg`;
    })
    .join(', ')})`;

  return (
            <div className="mt-4 flex flex-wrap items-center gap-5">
      <div
        className="relative h-32 w-32 shrink-0 rounded-full"
        style={{ background: gradient }}
        role="img"
        aria-label="인허가 상태 분포 도넛 차트"
      >
        <div className="absolute inset-[18%] flex flex-col items-center justify-center rounded-full bg-white">
          <span className="text-xl font-bold text-gray-800">{total}</span>
          <span className="text-[10px] text-gray-400">전체</span>
        </div>
      </div>
      <ul className="space-y-1.5 text-sm">
        {slices.map((s) => (
          <li key={s.status} className="flex items-center gap-2">
            <span className="h-2.5 w-2.5 rounded-full" style={{ backgroundColor: s.color }} />
            <span className="whitespace-nowrap text-gray-600">{s.label}</span>
            <span className="ml-auto font-medium text-gray-800">{s.value}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

export function ActivityList({ activity }: { activity: Activity[] }) {
  return (
          <ul className="mt-3 divide-y divide-[#E5E3DC]">
      {activity.length === 0 && <li className="py-6 text-center text-sm text-gray-400">아직 기록된 신청·취득이 없습니다.</li>}
      {activity.map((a) => (
        <li key={a.key} className="flex gap-3 py-2.5 text-sm">
          <span className="shrink-0 text-gray-400">{a.date}</span>
          <span className="truncate text-gray-700" title={a.text}>{a.text}</span>
        </li>
      ))}
    </ul>
  );
}

// Column per week (13 weeks from this week's Monday); height = deadlines due that week.
export function DeadlineTimeline({ deadlines }: { deadlines: Deadline[] }) {
  const monday = new Date();
  monday.setHours(0, 0, 0, 0);
  monday.setDate(monday.getDate() - ((monday.getDay() + 6) % 7));
  const weeks = Array.from({ length: 13 }, (_, i) => {
    const start = new Date(monday);
    start.setDate(start.getDate() + i * 7);
    const end = new Date(start);
    end.setDate(end.getDate() + 6);
    const items = deadlines.filter((d) => d.dueDate >= ymd(start) && d.dueDate <= ymd(end));
    return { start, items };
  });
  const max = Math.max(1, ...weeks.map((w) => w.items.length));
  return (
    <div className="mt-4">
      <div className="flex h-36 items-end gap-1.5 border-b border-[#E5E3DC]">
        {weeks.map((w, i) => (
          <div key={i} className="flex h-full flex-1 flex-col justify-end"
            title={`${w.start.getMonth() + 1}/${w.start.getDate()} 주 — ${w.items.length}건${w.items.map((d) => `\n· ${d.projectName}: ${d.title}`).join('')}`}>
            {w.items.length > 0 && <span className="mb-1 text-center text-xs text-gray-600">{w.items.length}</span>}
            <div className="rounded-t bg-[#3B82C4]" style={{ height: `${(w.items.length / max) * 80}%` }} />
          </div>
        ))}
      </div>
      <div className="mt-1.5 flex gap-1.5 text-[11px] text-gray-400">
        {weeks.map((w, i) => (
          <span key={i} className="flex-1 whitespace-nowrap text-center">
            {i % 2 === 0 ? `${w.start.getMonth() + 1}/${w.start.getDate()}` : ''}
          </span>
        ))}
      </div>
    </div>
  );
}

export function PendingRow({ to, label, value }: { to: string; label: string; value: number }) {
  return (
    <li>
      <Link to={to} className="flex items-center justify-between py-3 text-sm transition hover:text-[#1F4E79]">
        <span className="text-gray-600">{label}</span>
        <span className={`text-2xl font-bold ${value > 0 ? 'text-[#1F4E79]' : 'text-gray-300'}`}>{value}</span>
      </Link>
    </li>
  );
}
