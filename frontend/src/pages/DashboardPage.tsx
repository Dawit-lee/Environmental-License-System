import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router';
import type { PermitItem, PermitStatus, Project } from '../types';
import { api } from '../api/client';
import { getDeadlines, ymd, type Deadline } from '../api/notifications';
import { openSiteImplementationCount } from '../api/revisions';

// ---------------------------------------------------------------------------
// DashboardPage — portfolio-wide overview: summary cards, near-expiry table,
// status donut (pure CSS conic-gradient) and recent activity.
// ---------------------------------------------------------------------------

function dDayClasses(dDay: number): string {
  if (dDay <= 7) return 'text-[#991B1B] font-semibold';
  if (dDay <= 30) return 'text-[#B45309] font-semibold';
  if (dDay <= 60) return 'text-amber-500 font-medium';
  return 'text-gray-500';
}

const STATUSES: { status: PermitStatus; label: string; color: string }[] = [
  { status: 'COMPLETED', label: '취득완료', color: '#0F6E56' },
  { status: 'IN_PROGRESS', label: '진행중', color: '#1F4E79' },
  { status: 'NOT_APPLIED', label: '미신청', color: '#9CA3AF' },
  { status: 'NEAR_EXPIRY', label: '만료임박', color: '#B45309' },
  { status: 'EXPIRED', label: '만료', color: '#991B1B' },
];

interface Activity {
  key: string;
  date: string;
  text: string;
}

// Recent activity = latest application / acquisition dates recorded on permits.
function recentActivity(projects: Project[], permits: PermitItem[]): Activity[] {
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

export default function DashboardPage() {
  const [projects, setProjects] = useState<Project[]>([]);
  const [permits, setPermits] = useState<PermitItem[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  useEffect(() => {
    // shortcut: one permits call per project; add a summary endpoint if project counts grow large.
    api<Project[]>('/api/projects')
      .then(async (ps) => {
        const lists = await Promise.all(ps.map((p) => api<PermitItem[]>(`/api/projects/${p.id}/permits`)));
        setProjects(ps);
        setPermits(lists.flat());
      })
      .catch((e: Error) => setLoadError(e.message));
  }, []);

  const slices = STATUSES.map((s) => ({ ...s, value: permits.filter((p) => p.status === s.status).length }));
  const activity = useMemo(() => recentActivity(projects, permits), [projects, permits]);

  // Deadlines due within 30 days (API returns them sorted by date).
  const [nearExpiry, setNearExpiry] = useState<Deadline[]>([]);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    const today = new Date();
    const to = new Date(today);
    to.setDate(to.getDate() + 30);
    getDeadlines(ymd(today), ymd(to)).then(setNearExpiry, (e: Error) => setError(e.message));
    openSiteImplementationCount().then((r) => setSiteOpen(r.count), () => {});
  }, []);
  const [siteOpen, setSiteOpen] = useState(0); // ④ approved revisions not yet carried out on site

  const total = permits.length;

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
    <div className="min-h-full bg-[#F8F7F2]">
      <div className="mx-auto max-w-6xl px-4 py-8 sm:px-6">
        <h1 className="text-2xl font-bold text-[#1F4E79]">대시보드</h1>
        {loadError && <p className="mt-2 text-sm text-[#991B1B]">{loadError}</p>}

        {/* Summary cards */}
        <div className="mt-6 grid grid-cols-2 gap-4 lg:grid-cols-5">
          <SummaryCard label="전체 프로젝트" value={projects.length} tone="text-[#1F4E79]" />
          <SummaryCard label="전체 인허가" value={total} tone="text-gray-800" />
          <SummaryCard label="만료임박 (D-30 이내)" value={nearExpiry.length} tone="text-[#B45309]" />
          <SummaryCard label="미신청" value={slices.find((s) => s.status === 'NOT_APPLIED')!.value} tone="text-[#991B1B]" />
          <SummaryCard label="현장 반영 대기 (건)" value={siteOpen} tone="text-[#0F6E56]" />
        </div>

        {/* Near-expiry table — full width so each row stays on one line */}
        <section className="mt-6 rounded-xl border border-[#E5E3DC] bg-white p-5 shadow-sm">
            <h2 className="text-lg font-semibold text-gray-900">기한 임박 (D-30 이내)</h2>
            <div className="mt-3 overflow-x-auto">
              <table className="w-full min-w-[480px] whitespace-nowrap text-left text-sm">
                <thead className="bg-[#F8F7F2] text-xs uppercase text-gray-500">
                  <tr>
                    <th className="px-3 py-2.5 font-medium">프로젝트명</th>
                    <th className="px-3 py-2.5 font-medium">항목</th>
                    <th className="px-3 py-2.5 font-medium">기한</th>
                    <th className="px-3 py-2.5 font-medium">D-day</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[#E5E3DC]">
                  {error && (
                    <tr><td colSpan={4} className="px-3 py-6 text-center text-[#991B1B]">{error}</td></tr>
                  )}
                  {!error && nearExpiry.length === 0 && (
                    <tr><td colSpan={4} className="px-3 py-6 text-center text-gray-400">30일 안에 다가오는 기한이 없습니다.</td></tr>
                  )}
                  {nearExpiry.map((row) => (
                    <tr key={row.id} className="transition hover:bg-[#F8F7F2]">
                      <td className="px-3 py-2.5">
                        <Link to={`/projects/${row.projectId}`} className="text-gray-700 transition hover:text-[#1F4E79] hover:underline">
                          {row.projectName}
                        </Link>
                      </td>
                      <td className="w-full max-w-0 truncate px-3 py-2.5 font-medium text-gray-800" title={row.title}>{row.title}</td>
                      <td className="px-3 py-2.5 text-gray-600">{row.dueDate}</td>
                      <td className={`px-3 py-2.5 ${dDayClasses(row.dDay)}`}>{row.dDay === 0 ? 'D-day' : `D-${row.dDay}`}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>

        <div className="mt-6 grid grid-cols-1 gap-6 lg:grid-cols-3">
          {/* Donut */}
          <section className="rounded-xl border border-[#E5E3DC] bg-white p-5 shadow-sm">
            <h2 className="text-lg font-semibold text-gray-900">인허가 상태 분포</h2>
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
          </section>

          {/* Recent activity */}
          <section className="lg:col-span-2 rounded-xl border border-[#E5E3DC] bg-white p-5 shadow-sm">
          <h2 className="text-lg font-semibold text-gray-900">최근 활동</h2>
          <ul className="mt-3 divide-y divide-[#E5E3DC]">
            {activity.length === 0 && <li className="py-6 text-center text-sm text-gray-400">아직 기록된 신청·취득이 없습니다.</li>}
            {activity.map((a) => (
              <li key={a.key} className="flex gap-3 py-2.5 text-sm">
                <span className="shrink-0 text-gray-400">{a.date}</span>
                <span className="truncate text-gray-700" title={a.text}>{a.text}</span>
              </li>
            ))}
          </ul>
          </section>
        </div>
      </div>
    </div>
  );
}

// --- Local presentational helpers ------------------------------------------

function SummaryCard({ label, value, tone }: { label: string; value: number; tone: string }) {
  return (
    <div className="rounded-xl border border-[#E5E3DC] bg-white p-5 shadow-sm transition hover:shadow-md">
      <div className={`text-3xl font-bold ${tone}`}>{value}</div>
      <div className="mt-1 truncate text-sm text-gray-500" title={label}>{label}</div>
    </div>
  );
}
