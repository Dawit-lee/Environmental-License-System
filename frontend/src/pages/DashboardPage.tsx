import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router';
import type { PermitItem, PermitStatus, Project } from '../types';
import { api } from '../api/client';
import { getDeadlines, ymd, type Deadline } from '../api/notifications';
import { openSiteImplementationCount } from '../api/revisions';
import { getNewLawImpactCount } from '../api/lawImpacts';

// ---------------------------------------------------------------------------
// DashboardPage — portfolio-wide overview: summary cards, near-expiry table,
// per-project status bars, 90-day deadline timeline, pending-work counts,
// status donut (pure CSS conic-gradient) and recent activity.
// ---------------------------------------------------------------------------

function dDayClasses(dDay: number): string {
  if (dDay <= 7) return 'text-[#991B1B] font-semibold';
  if (dDay <= 30) return 'text-[#B45309] font-semibold';
  if (dDay <= 60) return 'text-amber-500 font-medium';
  return 'text-gray-500';
}

// Adjacent pairs pass the dataviz CVD / normal-vision separation check; every chart also shows a text legend.
const STATUSES: { status: PermitStatus; label: string; color: string }[] = [
  { status: 'COMPLETED', label: '취득완료', color: '#0F6E56' },
  { status: 'IN_PROGRESS', label: '진행중', color: '#3B82C4' },
  { status: 'NOT_APPLIED', label: '미신청', color: '#D1D5DB' },
  { status: 'NEAR_EXPIRY', label: '만료임박', color: '#E8A33D' },
  { status: 'EXPIRED', label: '만료', color: '#991B1B' },
];

const isOpen = (s: { status: string }) => s.status === 'PENDING' || s.status === 'SUBMITTED';

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
  const [reviewOpen, setReviewOpen] = useState(0); // AI suggestions + revisions awaiting review
  const [lawNew, setLawNew] = useState(0); // law amendments not yet reviewed
  useEffect(() => {
    // shortcut: one permits call per project; add a summary endpoint if project counts grow large.
    api<Project[]>('/api/projects')
      .then(async (ps) => {
        const lists = await Promise.all(ps.map((p) => api<PermitItem[]>(`/api/projects/${p.id}/permits`)));
        setProjects(ps);
        setPermits(lists.flat());
        const sugg = await Promise.all(ps.map((p) => api<{ kind: string; status: string }[]>(`/api/projects/${p.id}/suggestions`)));
        // same rows the review queue shows: obligations and version-diff revisions
        setReviewOpen(sugg.flat().filter((x) => (x.kind === 'OBLIGATION' || x.kind === 'OBLIGATION_REVISION') && isOpen(x)).length);
      })
      .catch((e: Error) => setLoadError(e.message));
  }, []);

  const slices = STATUSES.map((s) => ({ ...s, value: permits.filter((p) => p.status === s.status).length }));
  const activity = useMemo(() => recentActivity(projects, permits), [projects, permits]);

  // Deadlines due within 90 days (API returns them sorted by date); the table shows the first 30.
  const [deadlines, setDeadlines] = useState<Deadline[]>([]);
  const nearExpiry = deadlines.filter((d) => d.dDay <= 30);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    const today = new Date();
    const to = new Date(today);
    to.setDate(to.getDate() + 90);
    getDeadlines(ymd(today), ymd(to)).then(setDeadlines, (e: Error) => setError(e.message));
    openSiteImplementationCount().then((r) => setSiteOpen(r.count), () => {});
    getNewLawImpactCount().then((r) => setLawNew(r.count), () => {});
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
        <div className="mt-6 grid grid-cols-2 gap-4 lg:grid-cols-4">
          <SummaryCard label="전체 프로젝트" value={projects.length} tone="text-[#1F4E79]" />
          <SummaryCard label="전체 인허가" value={total} tone="text-gray-800" />
          <SummaryCard label="만료임박 (D-30 이내)" value={nearExpiry.length} tone="text-[#B45309]" />
          <SummaryCard label="미신청" value={slices.find((s) => s.status === 'NOT_APPLIED')!.value} tone="text-[#991B1B]" />
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
          <section className="lg:col-span-2 rounded-xl border border-[#E5E3DC] bg-white p-5 shadow-sm">
            <h2 className="text-lg font-semibold text-gray-900">프로젝트별 인허가 진행</h2>
            <ProjectStatusBars projects={projects} permits={permits} />
          </section>
          <section className="rounded-xl border border-[#E5E3DC] bg-white p-5 shadow-sm">
            <h2 className="text-lg font-semibold text-gray-900">처리 대기</h2>
            <ul className="mt-3 divide-y divide-[#E5E3DC]">
              <PendingRow to="/review" label="허가조건 검토 대기" value={reviewOpen} />
              <PendingRow to="/laws" label="법령 개정 확인 필요" value={lawNew} />
              <PendingRow to="/projects" label="현장 반영 대기" value={siteOpen} />
            </ul>
          </section>
        </div>

        <div className="mt-6 grid grid-cols-1 gap-6 lg:grid-cols-3">
          <section className="lg:col-span-2 rounded-xl border border-[#E5E3DC] bg-white p-5 shadow-sm">
            <h2 className="text-lg font-semibold text-gray-900">향후 90일 기한 (주별)</h2>
            <DeadlineTimeline deadlines={deadlines} />
          </section>
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

        </div>

        {/* Recent activity */}
        <div className="mt-6">
          <section className="rounded-xl border border-[#E5E3DC] bg-white p-5 shadow-sm">
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

// Horizontal 100% stacked bar per project: share of permits in each status.
function ProjectStatusBars({ projects, permits }: { projects: Project[]; permits: PermitItem[] }) {
  if (projects.length === 0) return <p className="py-6 text-center text-sm text-gray-400">프로젝트가 없습니다.</p>;
  return (
    <>
      <ul className="mt-3 flex flex-wrap gap-x-4 gap-y-1 text-xs text-gray-600">
        {STATUSES.map((s) => (
          <li key={s.status} className="flex items-center gap-1.5 whitespace-nowrap">
            <span className="h-2.5 w-2.5 rounded-sm" style={{ backgroundColor: s.color }} />
            {s.label}
          </li>
        ))}
      </ul>
      <ul className="mt-4 space-y-3">
        {projects.map((p) => {
          const mine = permits.filter((x) => x.projectId === p.id);
          const done = mine.filter((x) => x.status === 'COMPLETED').length;
          return (
            <li key={p.id} className="grid grid-cols-[minmax(0,10rem)_1fr_auto] items-center gap-3 text-sm">
              <Link to={`/projects/${p.id}`} className="truncate text-gray-700 hover:text-[#1F4E79] hover:underline" title={p.name}>
                {p.name}
              </Link>
              <div className="flex h-3 gap-0.5 overflow-hidden rounded bg-[#F3F2EC]">
                {STATUSES.map((s) => {
                  const n = mine.filter((x) => x.status === s.status).length;
                  return n === 0 ? null : (
                    <div key={s.status} className="h-full first:rounded-l last:rounded-r" title={`${s.label} ${n}건`}
                      style={{ width: `${(n / mine.length) * 100}%`, backgroundColor: s.color }} />
                  );
                })}
              </div>
              <span className="whitespace-nowrap text-xs text-gray-500">취득 {done}/{mine.length}</span>
            </li>
          );
        })}
      </ul>
    </>
  );
}

// Column per week (13 weeks from this week's Monday); height = deadlines due that week.
function DeadlineTimeline({ deadlines }: { deadlines: Deadline[] }) {
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

function PendingRow({ to, label, value }: { to: string; label: string; value: number }) {
  return (
    <li>
      <Link to={to} className="flex items-center justify-between py-3 text-sm transition hover:text-[#1F4E79]">
        <span className="text-gray-600">{label}</span>
        <span className={`text-2xl font-bold ${value > 0 ? 'text-[#1F4E79]' : 'text-gray-300'}`}>{value}</span>
      </Link>
    </li>
  );
}
