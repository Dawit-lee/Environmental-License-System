import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router';
import type { PermitStatus } from '../types';
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

interface StatusSlice {
  status: PermitStatus;
  label: string;
  value: number;
  color: string;
}

const STATUS_SLICES: StatusSlice[] = [
  { status: 'COMPLETED', label: '취득완료', value: 36, color: '#0F6E56' },
  { status: 'IN_PROGRESS', label: '진행중', value: 14, color: '#1F4E79' },
  { status: 'NOT_APPLIED', label: '미신청', value: 9, color: '#9CA3AF' },
  { status: 'NEAR_EXPIRY', label: '만료임박', value: 5, color: '#B45309' },
  { status: 'EXPIRED', label: '만료', value: 1, color: '#991B1B' },
];

const ACTIVITY = [
  { id: 1, date: '2026-05-28', text: '청주 자원회수시설 신설 — 대기배출시설 설치허가 신청서 접수' },
  { id: 2, date: '2026-05-27', text: '부산 하수처리장 증설공사 — 폐기물처리시설 설치승인 취득완료' },
  { id: 3, date: '2026-05-25', text: '부산 하수처리장 증설공사 — 소방시설 착공신고 상태가 만료임박으로 변경' },
  { id: 4, date: '2026-05-22', text: '안산 산업단지 폐수처리장 — 프로젝트가 보류로 변경' },
  { id: 5, date: '2026-05-20', text: '광주 통합바이오가스화시설 — 전체 인허가 취득완료' },
];

export default function DashboardPage() {
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

  const total = STATUS_SLICES.reduce((sum, s) => sum + s.value, 0);

  // Build conic-gradient stops for the donut.
  const gradient = useMemo(() => {
    let acc = 0;
    const stops = STATUS_SLICES.map((s) => {
      const start = (acc / total) * 360;
      acc += s.value;
      const end = (acc / total) * 360;
      return `${s.color} ${start}deg ${end}deg`;
    });
    return `conic-gradient(${stops.join(', ')})`;
  }, [total]);

  return (
    <div className="min-h-full bg-[#F8F7F2]">
      <div className="mx-auto max-w-6xl px-4 py-8 sm:px-6">
        <h1 className="text-2xl font-bold text-[#1F4E79]">대시보드</h1>

        {/* Summary cards */}
        <div className="mt-6 grid grid-cols-2 gap-4 lg:grid-cols-5">
          <SummaryCard label="전체 프로젝트" value={4} tone="text-[#1F4E79]" />
          <SummaryCard label="전체 인허가" value={total} tone="text-gray-800" />
          <SummaryCard label="만료임박 (D-30 이내)" value={nearExpiry.length} tone="text-[#B45309]" />
          <SummaryCard label="미신청" value={9} tone="text-[#991B1B]" />
          <SummaryCard label="현장 반영 대기 (건)" value={siteOpen} tone="text-[#0F6E56]" />
        </div>

        <div className="mt-6 grid grid-cols-1 gap-6 lg:grid-cols-3">
          {/* Near-expiry table */}
          <section className="lg:col-span-2 rounded-xl border border-[#E5E3DC] bg-white p-5 shadow-sm">
            <h2 className="text-lg font-semibold text-gray-900">기한 임박 (D-30 이내)</h2>
            <div className="mt-3 overflow-x-auto">
              <table className="w-full min-w-[480px] text-left text-sm">
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
                      <td className="px-3 py-2.5 font-medium text-gray-800">{row.title}</td>
                      <td className="px-3 py-2.5 text-gray-600">{row.dueDate}</td>
                      <td className={`px-3 py-2.5 ${dDayClasses(row.dDay)}`}>{row.dDay === 0 ? 'D-day' : `D-${row.dDay}`}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>

          {/* Donut */}
          <section className="rounded-xl border border-[#E5E3DC] bg-white p-5 shadow-sm">
            <h2 className="text-lg font-semibold text-gray-900">인허가 상태 분포</h2>
            <div className="mt-4 flex items-center gap-5">
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
                {STATUS_SLICES.map((s) => (
                  <li key={s.status} className="flex items-center gap-2">
                    <span className="h-2.5 w-2.5 rounded-full" style={{ backgroundColor: s.color }} />
                    <span className="text-gray-600">{s.label}</span>
                    <span className="ml-auto font-medium text-gray-800">{s.value}</span>
                  </li>
                ))}
              </ul>
            </div>
          </section>
        </div>

        {/* Recent activity */}
        <section className="mt-6 rounded-xl border border-[#E5E3DC] bg-white p-5 shadow-sm">
          <h2 className="text-lg font-semibold text-gray-900">최근 활동</h2>
          <ul className="mt-3 divide-y divide-[#E5E3DC]">
            {ACTIVITY.map((a) => (
              <li key={a.id} className="flex gap-3 py-2.5 text-sm">
                <span className="shrink-0 text-gray-400">{a.date}</span>
                <span className="text-gray-700">{a.text}</span>
              </li>
            ))}
          </ul>
        </section>
      </div>
    </div>
  );
}

// --- Local presentational helpers ------------------------------------------

function SummaryCard({ label, value, tone }: { label: string; value: number; tone: string }) {
  return (
    <div className="rounded-xl border border-[#E5E3DC] bg-white p-5 shadow-sm transition hover:shadow-md">
      <div className={`text-3xl font-bold ${tone}`}>{value}</div>
      <div className="mt-1 text-sm text-gray-500">{label}</div>
    </div>
  );
}
