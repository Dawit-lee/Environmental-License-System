import { useEffect, useMemo, useState } from 'react';
import { Link, NavLink, Navigate, useParams } from 'react-router';
import type { FacilityType, PermitCategory, PermitItem, PermitStatus, Project } from '../types';
import { api, apiBlob } from '../api/client';
import { latestDerivation, type DerivationWarning } from '../api/derivation';
import PermitDetailDrawer from '../components/PermitDetailDrawer';
import ReviewQueuePage from './ReviewQueuePage';
import LawAmendmentsPage from './LawAmendmentsPage';
import ProjectSchedule from '../components/ProjectSchedule';
import NewProjectPage from './NewProjectPage';
import ProjectOverview from '../components/ProjectOverview';
import { STAGES, lifecycleStage } from '../components/dashboard';
import ProjectObligations from '../components/ProjectObligations';
import ProjectDocuments from '../components/ProjectDocuments';
import ProjectOperations from '../components/ProjectOperations';
import ProjectModules from '../components/ProjectModules';
import ProjectMembers from '../components/ProjectMembers';
import ProjectDelete from '../components/ProjectDelete';
import { EmptyState, Tip, primaryBtn } from '../components/Onboarding';
import { Alert } from '../components/ui/Alert';

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

const CATEGORY_LABEL: Record<PermitCategory, string> = {
  LOCATION: '입지',
  FACILITY: '시설',
  CONSTRUCTION: '건축',
  ELECTRICAL: '전기',
  ENVIRONMENT: '환경평가',
};

const STATUS_LABEL: Record<PermitStatus, string> = {
  NOT_APPLIED: '미신청',
  IN_PROGRESS: '진행중',
  COMPLETED: '취득완료',
  NEAR_EXPIRY: '만료임박',
  EXPIRED: '만료',
};

const STATUS_BADGE: Record<PermitStatus, string> = {
  NOT_APPLIED: 'text-body',
  IN_PROGRESS: 'text-brand',
  COMPLETED: 'text-ok',
  NEAR_EXPIRY: 'text-warn',
  EXPIRED: 'text-danger',
};

function dDayClasses(dDay: number | null): string {
  if (dDay === null) return 'text-mute';
  if (dDay <= 7) return 'text-danger font-semibold';
  if (dDay <= 30) return 'text-warn font-semibold';
  if (dDay <= 60) return 'text-amber-500 font-medium';
  return 'text-mute';
}

function dDayLabel(dDay: number | null): string {
  if (dDay === null) return '—';
  if (dDay === 0) return 'D-DAY';
  return dDay > 0 ? `D-${dDay}` : `D+${Math.abs(dDay)}`;
}

/** Less important columns: hidden under 1280 px (the drawer shows them) so the key ones fit without scrolling. */
const WIDE = 'hidden xl:table-cell';

const CATEGORY_TAB_ORDER: PermitCategory[] = [
  'LOCATION', 'FACILITY', 'CONSTRUCTION', 'ELECTRICAL', 'ENVIRONMENT',
];

const TABS = [
  { key: 'overview', label: '개요' },
  { key: 'permits', label: '인허가' },
  { key: 'obligations', label: '의무사항' },
  { key: 'review', label: 'AI 검토' },
  { key: 'laws', label: '법령 영향' },
  { key: 'schedule', label: '일정' },
  { key: 'operations', label: '운영' },
  { key: 'documents', label: '문서' },
  { key: 'settings', label: '설정' },
] as const;

/** 인허가 진행 현황 보고 1쪽 (design-schedule/08): built from current values on every click. */
async function downloadReport(projectId: number) {
  const url = URL.createObjectURL(await apiBlob(`/api/projects/${projectId}/permit-status-report.xlsx`));
  const a = document.createElement('a');
  a.href = url;
  a.download = `${new Date().toLocaleDateString('sv-SE').slice(2).replaceAll('-', '')} 인허가 진행 현황.xlsx`;
  a.click();
  URL.revokeObjectURL(url);
}

export default function ProjectDetailPage() {
  const { id, tab = 'overview' } = useParams();
  const [activeCategory, setActiveCategory] = useState<'ALL' | PermitCategory>('ALL');
  const [statusFilter, setStatusFilter] = useState<'ALL' | PermitStatus>('ALL');
  const [selected, setSelected] = useState<PermitItem | null>(null);
  const [project, setProject] = useState<Project | null>(null);
  const [permits, setPermits] = useState<PermitItem[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [ruleWarnings, setRuleWarnings] = useState<DerivationWarning[]>([]);
  const [reloads, setReloads] = useState(0); // 설정 saves → refresh the header
  const [reporting, setReporting] = useState(false); // the report takes ~2 s to build

  useEffect(() => {
    // approved items the current rules no longer find applicable; no derivation yet → 404, nothing to show
    let stale = false;
    latestDerivation(Number(id)).then((d) => { if (!stale) setRuleWarnings(d?.warnings ?? []); }, () => { if (!stale) setRuleWarnings([]); }); // 204: never derived
    return () => { stale = true; };
  }, [id]);

  useEffect(() => {
    let stale = false; // switching projects fast: ignore the previous project's late answer
    setError(null);
    Promise.all([api<Project>(`/api/projects/${id}`), api<PermitItem[]>(`/api/projects/${id}/permits`)]).then(
      ([p, list]) => {
        if (stale) return;
        setProject(p);
        setPermits(list);
      },
      (e: Error) => { if (!stale) setError(e.message); },
    );
    return () => { stale = true; };
  }, [id, reloads]);

  const stats = useMemo(() => {
    const total = permits.length;
    const completed = permits.filter((p) => p.status === 'COMPLETED').length;
    const nearExpiry = permits.filter((p) => p.status === 'NEAR_EXPIRY').length; // same rule as the 상태 분포
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
      <div className="min-h-full bg-paper">
        <div className="py-8">
          <Link to="/projects" className="text-sm text-mute transition hover:text-brand hover:underline">
            ← 프로젝트 목록
          </Link>
          <p className={`mt-16 text-center text-sm ${error ? 'text-danger' : 'text-mute'}`}>
            {error ?? '불러오는 중…'}
          </p>
        </div>
      </div>
    );
  }

  // the other tabs are member-only on the server (403), so a company viewer is not shown dead tabs
  const tabs = (project.member ? TABS : TABS.filter((t) => t.key === 'overview' || t.key === 'permits'))
    .filter((t) => t.key !== 'operations' || project.operationsEnabled); // opt-in module (설정 › 모듈)

  return (
    <div className="min-h-full bg-paper">
      <div className="py-8">
        {/* Breadcrumb */}
        <nav className="text-sm text-mute">
          <Link to="/projects" className="transition hover:text-brand hover:underline">
            프로젝트 목록
          </Link>
          <span className="mx-2 text-line">›</span>
          <span className="text-body">{project.name}</span>
        </nav>

        {/* Summary card: full on 개요, one line on the other tabs so the schedule and tables get the height */}
        {tab !== 'overview' ? (
          <section className="mt-4 rounded border border-line bg-white px-5 py-3 shadow-sm">
            <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
              <h1 className="truncate text-lg font-bold text-ink" title={project.name}>{project.name}</h1>
              <span className="text-sm text-mute">
                {FACILITY_LABEL[project.facilityType]} · {STAGES[lifecycleStage(project, permits)]} 단계
              </span>
              <span className="text-sm text-mute sm:ml-auto">
                인허가 {stats.total} · 취득완료 {stats.completed} · <span className={stats.nearExpiry ? 'text-warn' : ''}>만료임박 {stats.nearExpiry}</span> · 미신청 {stats.notApplied}
              </span>
            </div>
            <Notices project={project} warnings={ruleWarnings} />
          </section>
        ) : (
        <section className="mt-4 rounded border border-line bg-white p-6 shadow-sm">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <span className="text-sm text-mute">
                {FACILITY_LABEL[project.facilityType]}
              </span>
              <h1 className="mt-2 text-2xl font-bold text-ink">{project.name}</h1>
              <p className="mt-1 text-sm text-mute">{project.location}</p>
            </div>
            <dl className="grid grid-cols-2 gap-x-6 gap-y-1 text-sm">
              <dt className="text-mute">시설용량</dt>
              <dd className="text-body">{project.capacity ?? '—'} {project.capacityUnit}</dd>
              <dt className="text-mute">착수일</dt>
              <dd className="text-body">{project.startDate ?? '—'}</dd>
              <dt className="text-mute">착공예정일</dt>
              <dd className="text-body">{project.constructionStartDate ?? '—'}</dd>
              <dt className="text-mute">준공예정일</dt>
              <dd className="text-body">{project.expectedEndDate ?? '—'}</dd>
              <dt className="text-mute">부서/팀</dt>
              <dd className="text-body">{project.teamName ? `${project.departmentName} / ${project.teamName}` : '—'}</dd>
              <dt className="text-mute">PM / EM</dt>
              <dd className="text-body">{project.pmName ?? '—'} / {project.emName ?? '—'}</dd>
            </dl>
          </div>

          <Notices project={project} warnings={ruleWarnings} />

          {/* Stats row */}
          <div className="mt-6 grid grid-cols-2 gap-3 sm:grid-cols-4">
            <Stat label="전체 인허가" value={stats.total} tone="text-brand" />
            <Stat label="취득완료" value={stats.completed} tone="text-ok" />
            <Stat label="만료임박" value={stats.nearExpiry} tone="text-warn" />
            <Stat label="미신청" value={stats.notApplied} tone="text-danger" />
          </div>

          <StageBar current={lifecycleStage(project, permits)} />
        </section>
        )}

        <nav className="mt-6 flex gap-1 overflow-x-auto overflow-y-hidden border-b border-line" aria-label="프로젝트 메뉴">
          {tabs.map((t) => (
            <NavLink
              key={t.key}
              to={`/projects/${id}/${t.key}`}
              className={`-mb-px whitespace-nowrap border-b-2 px-4 py-2.5 text-sm font-medium transition ${
                tab === t.key ? 'border-brand text-brand' : 'border-transparent text-mute hover:text-ink'
              }`}
            >
              {t.label}
            </NavLink>
          ))}
        </nav>

        <div className="mt-6">
          {tab === 'overview' && <ProjectOverview project={project} permits={permits} />}
          {tab === 'review' && <ReviewQueuePage key={id} projectId={project.id} />}
          {tab === 'laws' && <LawAmendmentsPage key={id} projectId={project.id} />}
          {tab === 'schedule' && <ProjectSchedule key={id} projectId={project.id} />}
          {tab === 'obligations' && <ProjectObligations key={id} projectId={project.id} permits={permits} onOpenPermit={setSelected} />}
          {tab === 'operations' && <ProjectOperations key={id} projectId={project.id} permits={permits} />}
          {tab === 'documents' && <ProjectDocuments key={id} projectId={project.id} permits={permits} onOpenPermit={setSelected} />}
          {tab === 'settings' && (
            <>
              <NewProjectPage key={id} projectId={project.id} onSaved={() => setReloads((n) => n + 1)} />
              <ProjectModules project={project} onSaved={() => setReloads((n) => n + 1)} />
              <ProjectMembers key={`m${id}`} projectId={project.id} />
              <ProjectDelete project={project} />
            </>
          )}
          {!tabs.some((t) => t.key === tab) && <Navigate to={`/projects/${id}/overview`} replace />}
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
          <div className="flex items-center gap-2">
          <Tip k="report"><button
            type="button"
            disabled={reporting}
            onClick={() => {
              setReporting(true);
              downloadReport(project.id).catch((e: Error) => window.alert(e.message)).finally(() => setReporting(false));
            }}
            className="rounded border border-line bg-white px-3 py-2 text-sm text-body transition hover:bg-paper focus:outline-none focus:ring-2 focus:ring-brand/20 disabled:opacity-60"
          >
            {reporting ? '만드는 중…' : '진행 현황 보고 ⬇'}
          </button></Tip>
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value as 'ALL' | PermitStatus)}
            className="rounded border border-line bg-white px-3 py-2 text-sm text-body outline-none transition focus:border-brand focus:ring-2 focus:ring-brand/20"
          >
            <option value="ALL">상태 전체</option>
            {Object.entries(STATUS_LABEL).map(([value, label]) => (
              <option key={value} value={value}>{label}</option>
            ))}
          </select>
          </div>
        </div>

        {permits.length === 0 && (
          <div className="mt-4">
            <EmptyState text={project.member
              ? '시설 종류·용량·입지로 필요한 인허가를 규칙에 따라 찾습니다. 도출 결과에서 맞는 항목을 승인하면 여기에 나옵니다.'
              : '아직 등록된 인허가가 없습니다.'}>
              {project.member && <Link to={`/projects/${project.id}/settings`} className={primaryBtn}>인허가 도출하기</Link>}
            </EmptyState>
          </div>
        )}

        {/* Permit table */}
        {permits.length > 0 && <div className="mt-4 overflow-x-auto rounded border border-line bg-white shadow-sm">
          <table className="w-full min-w-[600px] whitespace-nowrap xl:min-w-[860px] text-left text-sm">
            <thead className="bg-paper text-xs uppercase text-body">
              <tr>
                <th className="px-4 py-3 font-medium">인허가명</th>
                <th className="px-4 py-3 font-medium">카테고리</th>
                <th className={`px-4 py-3 font-medium ${WIDE}`}>관련 법규</th>
                <th className={`px-4 py-3 font-medium ${WIDE}`}>승인기관</th>
                <th className="px-4 py-3 font-medium">만료일</th>
                <th className="px-4 py-3 font-medium">D-day</th>
                <th className="px-4 py-3 font-medium">상태</th>
                <th className="px-4 py-3 text-right font-medium">액션</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {visible.map((p) => (
                <tr key={p.id} className="transition hover:bg-paper">
                  <td className="px-4 py-3 font-medium text-ink">{p.name}</td>
                  <td className="px-4 py-3">
                    <span className="whitespace-nowrap text-body">
                      {CATEGORY_LABEL[p.category]}
                    </span>
                  </td>
                  <td className={`px-4 py-3 ${WIDE}`}>
                    <a href={p.regulationUrl ?? undefined} target="_blank" rel="noreferrer"
                      className="inline-flex items-center gap-1 text-brand transition hover:underline focus:outline-none focus:ring-2 focus:ring-brand/30">
                      {p.regulation}<span aria-hidden>↗</span>
                    </a>
                  </td>
                  <td className={`px-4 py-3 ${WIDE}`}>
                    <a href={p.authorityUrl ?? undefined} target="_blank" rel="noreferrer"
                      className="inline-flex items-center gap-1 text-body transition hover:text-brand hover:underline focus:outline-none focus:ring-2 focus:ring-brand/30">
                      {p.authority}<span aria-hidden>↗</span>
                    </a>
                  </td>
                  <td className="px-4 py-3 text-body">{p.expiryDate ?? '—'}</td>
                  <td className={`px-4 py-3 ${dDayClasses(p.dDay)}`}>{dDayLabel(p.dDay)}</td>
                  <td className="px-4 py-3">
                    <span className={`whitespace-nowrap ${STATUS_BADGE[p.status]}`}>
                      {STATUS_LABEL[p.status]}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-right">
                    <button
                      type="button"
                      onClick={() => setSelected(p)}
                      className="rounded border border-line px-3 py-1.5 text-xs font-medium text-body transition hover:border-brand hover:text-brand focus:outline-none focus:ring-2 focus:ring-brand/30"
                    >
                      상세보기
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>}
        </>)}
      </div>

      <PermitDetailDrawer
        permit={selected}
        onClose={() => setSelected(null)}
        onSaved={
          project.member
            ? (p) => {
                setSelected(p);
                setPermits((list) => list.map((x) => (x.id === p.id ? p : x)));
              }
            : undefined
        }
      />
    </div>
  );
}

// --- Local presentational helpers ------------------------------------------

function Notices({ project, warnings }: { project: Project; warnings: DerivationWarning[] }) {
  return (
    <>
      {!project.member && (
        <p className="mt-3 rounded bg-paper px-3 py-2 text-sm text-body">
          이 프로젝트의 멤버가 아니라 인허가 목록만 볼 수 있습니다. 수정하려면 프로젝트 어드민에게 멤버 추가를 요청하세요.
        </p>
      )}
      {warnings.map((w) => (
        <Alert key={w.rule_key} tone="warning" className="mt-3">
          {w.permit_name} — {w.message}
        </Alert>
      ))}
    </>
  );
}

function Stat({ label, value, tone }: { label: string; value: number; tone: string }) {
  return (
    <div className="rounded bg-paper p-4 text-center">
      <div className={`text-2xl font-bold ${tone}`}>{value}</div>
      <div className="mt-1 text-xs text-mute">{label}</div>
    </div>
  );
}

function Tab({ label, active, onClick }: { label: string; active: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`rounded px-3.5 py-1.5 text-sm font-medium transition focus:outline-none focus:ring-2 focus:ring-brand/30 ${
        active ? 'bg-brand text-white' : 'bg-white text-body hover:bg-line'
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
            className={`flex-1 rounded px-2 py-1.5 text-center font-medium ${
              i === current ? 'bg-brand text-white' : i < current ? 'bg-brand/10 text-brand' : 'bg-paper text-body'
            }`}
          >
            {label}
          </span>
          {i < STAGES.length - 1 && <span aria-hidden className="text-line">→</span>}
        </li>
      ))}
    </ol>
  );
}
