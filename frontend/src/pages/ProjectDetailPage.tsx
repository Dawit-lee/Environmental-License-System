import { useEffect, useMemo, useState } from 'react';
import { Link, NavLink, Navigate, useParams } from 'react-router';
import type { FacilityType, PermitCategory, PermitItem, PermitStatus, Project } from '../types';
import { api } from '../api/client';
import { latestDerivation, type DerivationWarning } from '../api/derivation';
import PermitDetailDrawer from '../components/PermitDetailDrawer';
import ReviewQueuePage from './ReviewQueuePage';
import LawAmendmentsPage from './LawAmendmentsPage';
import CalendarPage from './CalendarPage';

// ---------------------------------------------------------------------------
// ProjectDetailPage — the project hub: summary + lifecycle stage + routed tabs
// (/projects/:id/:tab). 인허가 tab = permit table; each row opens the drawer.
// ---------------------------------------------------------------------------

const FACILITY_LABEL: Record<FacilityType, string> = {
  INCINERATION: '소각시설',
  LANDFILL: '매립시설',
  BIOGAS: '통합바이오가스',
  SEWAGE: '하수처리',
  WASTEWATER: '폐수처리',
};

const FACILITY_BADGE: Record<FacilityType, string> = {
  INCINERATION: 'bg-red-100 text-red-700',
  LANDFILL: 'bg-gray-200 text-gray-700',
  BIOGAS: 'bg-emerald-100 text-emerald-700',
  SEWAGE: 'bg-sky-100 text-sky-700',
  WASTEWATER: 'bg-violet-100 text-violet-700',
};

const CATEGORY_LABEL: Record<PermitCategory, string> = {
  LOCATION: '입지',
  FACILITY: '시설',
  CONSTRUCTION: '건축',
  ELECTRICAL: '전기',
  ENVIRONMENT: '환경평가',
};

const CATEGORY_BADGE: Record<PermitCategory, string> = {
  LOCATION: 'bg-amber-100 text-amber-800',
  FACILITY: 'bg-sky-100 text-sky-800',
  CONSTRUCTION: 'bg-violet-100 text-violet-800',
  ELECTRICAL: 'bg-emerald-100 text-emerald-800',
  ENVIRONMENT: 'bg-rose-100 text-rose-800',
};

const STATUS_LABEL: Record<PermitStatus, string> = {
  NOT_APPLIED: '미신청',
  IN_PROGRESS: '진행중',
  COMPLETED: '취득완료',
  NEAR_EXPIRY: '만료임박',
  EXPIRED: '만료',
};

const STATUS_BADGE: Record<PermitStatus, string> = {
  NOT_APPLIED: 'bg-gray-200 text-gray-600',
  IN_PROGRESS: 'bg-[#1F4E79]/10 text-[#1F4E79]',
  COMPLETED: 'bg-[#0F6E56]/10 text-[#0F6E56]',
  NEAR_EXPIRY: 'bg-[#B45309]/10 text-[#B45309]',
  EXPIRED: 'bg-[#991B1B]/10 text-[#991B1B]',
};

function dDayClasses(dDay: number | null): string {
  if (dDay === null) return 'text-gray-400';
  if (dDay <= 7) return 'text-[#991B1B] font-semibold';
  if (dDay <= 30) return 'text-[#B45309] font-semibold';
  if (dDay <= 60) return 'text-amber-500 font-medium';
  return 'text-gray-500';
}

function dDayLabel(dDay: number | null): string {
  if (dDay === null) return '—';
  if (dDay === 0) return 'D-DAY';
  return dDay > 0 ? `D-${dDay}` : `D+${Math.abs(dDay)}`;
}

const CATEGORY_TAB_ORDER: PermitCategory[] = [
  'LOCATION', 'FACILITY', 'CONSTRUCTION', 'ELECTRICAL', 'ENVIRONMENT',
];

// Planned tabs (not built yet): overview(개요, compliance rate), obligations(의무사항, obligation tasks),
// documents(문서, reports), settings(설정), operations(운영, measured values).
const TABS = [
  { key: 'permits', label: '인허가' },
  { key: 'review', label: 'AI 검토' },
  { key: 'laws', label: '법령 영향' },
  { key: 'schedule', label: '일정' },
] as const;

const STAGES = ['입지', '설계', '착공', '준공', '운영'] as const;

/** shortcut: rough stage from permit status + planned dates; replace when projects store a real stage. */
function lifecycleStage(project: Project, permits: PermitItem[], today = new Date().toISOString().slice(0, 10)): number {
  const done = (cats: PermitCategory[]) =>
    permits.filter((p) => cats.includes(p.category)).every((p) => p.status === 'COMPLETED');
  if (!done(['LOCATION', 'ENVIRONMENT'])) return 0;
  if (!project.startDate || today < project.startDate) return 1;
  if (!project.expectedEndDate || today < project.expectedEndDate) return 2;
  if (!permits.every((p) => p.status === 'COMPLETED')) return 3;
  return 4;
}

export default function ProjectDetailPage() {
  const { id, tab = 'permits' } = useParams();
  const [activeCategory, setActiveCategory] = useState<'ALL' | PermitCategory>('ALL');
  const [statusFilter, setStatusFilter] = useState<'ALL' | PermitStatus>('ALL');
  const [selected, setSelected] = useState<PermitItem | null>(null);
  const [project, setProject] = useState<Project | null>(null);
  const [permits, setPermits] = useState<PermitItem[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [ruleWarnings, setRuleWarnings] = useState<DerivationWarning[]>([]);

  useEffect(() => {
    // approved items the current rules no longer find applicable; no derivation yet → 404, nothing to show
    latestDerivation(Number(id)).then((d) => setRuleWarnings(d.warnings ?? []), () => setRuleWarnings([]));
  }, [id]);

  useEffect(() => {
    setError(null);
    Promise.all([api<Project>(`/api/projects/${id}`), api<PermitItem[]>(`/api/projects/${id}/permits`)]).then(
      ([p, list]) => {
        setProject(p);
        setPermits(list);
      },
      (e: Error) => setError(e.message),
    );
  }, [id]);

  const stats = useMemo(() => {
    const total = permits.length;
    const completed = permits.filter((p) => p.status === 'COMPLETED').length;
    const nearExpiry = permits.filter(
      (p) => p.status === 'NEAR_EXPIRY' || (p.dDay !== null && p.dDay <= 30 && p.status !== 'COMPLETED'),
    ).length;
    const notApplied = permits.filter((p) => p.status === 'NOT_APPLIED').length;
    return { total, completed, nearExpiry, notApplied };
  }, [permits]);

  const visible = useMemo(
    () =>
      permits.filter(
        (p) =>
          (activeCategory === 'ALL' || p.category === activeCategory) &&
          (statusFilter === 'ALL' || p.status === statusFilter),
      ),
    [permits, activeCategory, statusFilter],
  );

  if (error || !project) {
    return (
      <div className="min-h-full bg-[#F8F7F2]">
        <div className="mx-auto max-w-7xl px-4 py-8 sm:px-6">
          <Link to="/projects" className="text-sm text-gray-500 transition hover:text-[#1F4E79] hover:underline">
            ← 프로젝트 목록
          </Link>
          <p className={`mt-16 text-center text-sm ${error ? 'text-[#991B1B]' : 'text-gray-400'}`}>
            {error ?? '불러오는 중…'}
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-full bg-[#F8F7F2]">
      <div className="mx-auto max-w-7xl px-4 py-8 sm:px-6">
        {/* Breadcrumb */}
        <nav className="text-sm text-gray-500">
          <Link to="/projects" className="transition hover:text-[#1F4E79] hover:underline">
            프로젝트 목록
          </Link>
          <span className="mx-2 text-gray-300">›</span>
          <span className="text-gray-700">{project.name}</span>
        </nav>

        {/* Summary card */}
        <section className="mt-4 rounded-xl border border-[#E5E3DC] bg-white p-6 shadow-sm">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <span
                className={`inline-block rounded-full px-2.5 py-0.5 text-xs font-medium ${FACILITY_BADGE[project.facilityType]}`}
              >
                {FACILITY_LABEL[project.facilityType]}
              </span>
              <h1 className="mt-2 text-2xl font-bold text-gray-900">{project.name}</h1>
              <p className="mt-1 text-sm text-gray-500">{project.location}</p>
            </div>
            <dl className="grid grid-cols-2 gap-x-6 gap-y-1 text-sm">
              <dt className="text-gray-400">시설용량</dt>
              <dd className="text-gray-700">{project.capacity ?? '—'} {project.capacityUnit}</dd>
              <dt className="text-gray-400">착공예정일</dt>
              <dd className="text-gray-700">{project.startDate ?? '—'}</dd>
              <dt className="text-gray-400">준공예정일</dt>
              <dd className="text-gray-700">{project.expectedEndDate ?? '—'}</dd>
            </dl>
          </div>

          {ruleWarnings.map((w) => (
            <p key={w.rule_key} className="mt-4 rounded-lg bg-amber-50 px-3 py-2 text-sm text-[#B45309]">
              {w.permit_name} — {w.message}
            </p>
          ))}

          {/* Stats row */}
          <div className="mt-6 grid grid-cols-2 gap-3 sm:grid-cols-4">
            <Stat label="전체 인허가" value={stats.total} tone="text-[#1F4E79]" />
            <Stat label="취득완료" value={stats.completed} tone="text-[#0F6E56]" />
            <Stat label="만료임박" value={stats.nearExpiry} tone="text-[#B45309]" />
            <Stat label="미신청" value={stats.notApplied} tone="text-[#991B1B]" />
          </div>

          <StageBar current={lifecycleStage(project, permits)} />
        </section>

        <nav className="mt-6 flex gap-1 overflow-x-auto border-b border-[#E5E3DC]" aria-label="프로젝트 메뉴">
          {TABS.map((t) => (
            <NavLink
              key={t.key}
              to={`/projects/${id}/${t.key}`}
              className={`-mb-px whitespace-nowrap border-b-2 px-4 py-2.5 text-sm font-medium transition ${
                tab === t.key ? 'border-[#1F4E79] text-[#1F4E79]' : 'border-transparent text-gray-500 hover:text-gray-800'
              }`}
            >
              {t.label}
            </NavLink>
          ))}
        </nav>

        <div className="mt-6">
          {tab === 'review' && <ReviewQueuePage key={id} projectId={project.id} />}
          {tab === 'laws' && <LawAmendmentsPage key={id} projectId={project.id} />}
          {tab === 'schedule' && <CalendarPage key={id} projectId={project.id} />}
          {!TABS.some((t) => t.key === tab) && <Navigate to={`/projects/${id}/permits`} replace />}
        </div>

        {tab === 'permits' && (<>

        {/* Category chips + status filter */}
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap gap-2">
            <Tab label="전체" active={activeCategory === 'ALL'} onClick={() => setActiveCategory('ALL')} />
            {CATEGORY_TAB_ORDER.map((cat) => (
              <Tab
                key={cat}
                label={CATEGORY_LABEL[cat]}
                active={activeCategory === cat}
                onClick={() => setActiveCategory(cat)}
              />
            ))}
          </div>
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value as 'ALL' | PermitStatus)}
            className="rounded-lg border border-[#E5E3DC] bg-white px-3 py-2 text-sm text-gray-700 outline-none transition focus:border-[#1F4E79] focus:ring-2 focus:ring-[#1F4E79]/20"
          >
            <option value="ALL">상태 전체</option>
            {Object.entries(STATUS_LABEL).map(([value, label]) => (
              <option key={value} value={value}>{label}</option>
            ))}
          </select>
        </div>

        {/* Permit table */}
        <div className="mt-4 overflow-x-auto rounded-xl border border-[#E5E3DC] bg-white shadow-sm">
          <table className="w-full min-w-[860px] text-left text-sm">
            <thead className="bg-[#F8F7F2] text-xs uppercase text-gray-500">
              <tr>
                <th className="px-4 py-3 font-medium">인허가명</th>
                <th className="px-4 py-3 font-medium">카테고리</th>
                <th className="px-4 py-3 font-medium">관련 법규</th>
                <th className="px-4 py-3 font-medium">승인기관</th>
                <th className="px-4 py-3 font-medium">만료일</th>
                <th className="px-4 py-3 font-medium">D-day</th>
                <th className="px-4 py-3 font-medium">상태</th>
                <th className="px-4 py-3 text-right font-medium">액션</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-[#E5E3DC]">
              {visible.map((p) => (
                <tr key={p.id} className="transition hover:bg-[#F8F7F2]">
                  <td className="px-4 py-3 font-medium text-gray-800">{p.name}</td>
                  <td className="px-4 py-3">
                    <span className={`inline-block rounded-full px-2.5 py-0.5 text-xs font-medium ${CATEGORY_BADGE[p.category]}`}>
                      {CATEGORY_LABEL[p.category]}
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    <a href={p.regulationUrl ?? undefined} target="_blank" rel="noreferrer"
                      className="inline-flex items-center gap-1 text-[#1F4E79] transition hover:underline focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/30">
                      {p.regulation}<span aria-hidden>↗</span>
                    </a>
                  </td>
                  <td className="px-4 py-3">
                    <a href={p.authorityUrl ?? undefined} target="_blank" rel="noreferrer"
                      className="inline-flex items-center gap-1 text-gray-700 transition hover:text-[#1F4E79] hover:underline focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/30">
                      {p.authority}<span aria-hidden>↗</span>
                    </a>
                  </td>
                  <td className="px-4 py-3 text-gray-600">{p.expiryDate ?? '—'}</td>
                  <td className={`px-4 py-3 ${dDayClasses(p.dDay)}`}>{dDayLabel(p.dDay)}</td>
                  <td className="px-4 py-3">
                    <span className={`inline-block rounded-full px-2.5 py-0.5 text-xs font-medium ${STATUS_BADGE[p.status]}`}>
                      {STATUS_LABEL[p.status]}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-right">
                    <button
                      type="button"
                      onClick={() => setSelected(p)}
                      className="rounded-lg border border-[#E5E3DC] px-3 py-1.5 text-xs font-medium text-gray-700 transition hover:border-[#1F4E79] hover:text-[#1F4E79] focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/30"
                    >
                      상세보기
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        </>)}
      </div>

      {/* Floating add button */}
      {tab === 'permits' && <button
        type="button"
        // TODO: open "인허가 추가" flow / POST /api/projects/:id/permits
        className="fixed bottom-6 right-6 z-30 rounded-full bg-[#1F4E79] px-5 py-3 text-sm font-semibold text-white shadow-lg transition hover:bg-[#173a5c] focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/40"
      >
        + 인허가 추가
      </button>}

      <PermitDetailDrawer permit={selected} onClose={() => setSelected(null)} />
    </div>
  );
}

// --- Local presentational helpers ------------------------------------------

function Stat({ label, value, tone }: { label: string; value: number; tone: string }) {
  return (
    <div className="rounded-lg bg-[#F8F7F2] p-4 text-center">
      <div className={`text-2xl font-bold ${tone}`}>{value}</div>
      <div className="mt-1 text-xs text-gray-500">{label}</div>
    </div>
  );
}

function Tab({ label, active, onClick }: { label: string; active: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`rounded-full px-3.5 py-1.5 text-sm font-medium transition focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/30 ${
        active ? 'bg-[#1F4E79] text-white' : 'bg-white text-gray-600 hover:bg-[#E5E3DC]'
      }`}
    >
      {label}
    </button>
  );
}

function StageBar({ current }: { current: number }) {
  return (
    <ol className="mt-6 flex items-center gap-1 text-xs" aria-label="생애주기 단계">
      {STAGES.map((label, i) => (
        <li key={label} className="flex flex-1 items-center gap-1">
          <span
            aria-current={i === current ? 'step' : undefined}
            className={`flex-1 rounded-full px-2 py-1.5 text-center font-medium ${
              i === current ? 'bg-[#1F4E79] text-white' : i < current ? 'bg-[#1F4E79]/10 text-[#1F4E79]' : 'bg-[#F8F7F2] text-gray-400'
            }`}
          >
            {label}
          </span>
          {i < STAGES.length - 1 && <span aria-hidden className="text-gray-300">→</span>}
        </li>
      ))}
    </ol>
  );
}
