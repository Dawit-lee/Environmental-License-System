import { useEffect, useMemo, useState } from 'react';
import { Link, NavLink, Navigate, useParams } from 'react-router';
import type { PermitCategory, PermitItem, PermitStatus, Project } from '../types';
import { CATEGORY_LABEL, FACILITY_LABEL, PERMIT_STATUS, filterPermits } from './listFilters';
import { Badge, dueBadge } from '../components/ui/Badge';
import { Button } from '../components/ui/Button';
import { Select } from '../components/ui/Field';
import { Table } from '../components/ui/Table';
import { cn, focusRing } from '../components/ui/cn';
import { EmptyBlock, FilterChips, type ActiveFilter } from '../components/ListParts';
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
import { Tip } from '../components/Onboarding';
import { Alert } from '../components/ui/Alert';

// ---------------------------------------------------------------------------
// ProjectDetailPage — the project hub: summary + lifecycle stage + routed tabs
// (/projects/:id/:tab). 인허가 tab = permit table; each row opens the drawer.
// ---------------------------------------------------------------------------

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

  const visible = useMemo(() => filterPermits(permits, { category: activeCategory, status: statusFilter }), [permits, activeCategory, statusFilter]);

  const permitFilters: ActiveFilter[] = [
    activeCategory !== 'ALL' && { key: 'category', label: `분야: ${CATEGORY_LABEL[activeCategory]}`, clear: () => setActiveCategory('ALL') },
    statusFilter !== 'ALL' && { key: 'status', label: `상태: ${PERMIT_STATUS[statusFilter].text}`, clear: () => setStatusFilter('ALL') },
  ].filter((f): f is ActiveFilter => Boolean(f));
  const clearPermitFilters = () => { setActiveCategory('ALL'); setStatusFilter('ALL'); };

  if (error || !project) {
    return (
      <div className="min-h-full bg-paper">
        <div className="py-8">
          <Link to="/projects" className="text-sm text-mute transition hover:text-brand hover:underline">
            ← 프로젝트 목록
          </Link>
          {error ? (
            <Alert tone="danger" title="프로젝트를 불러오지 못했습니다." className="mt-6">
              <p>{error}</p>
              <Button variant="tertiary" size="sm" className="mt-3" onClick={() => setReloads((n) => n + 1)}>다시 시도</Button>
            </Alert>
          ) : (
            <p className="mt-16 text-center text-sm text-mute">불러오는 중…</p>
          )}
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

        {/* 필터 줄: 분야·상태 + 오른쪽 내보내기 */}
        <div className="flex flex-wrap items-end gap-3">
          <Select label="분야" className="w-full sm:w-44" value={activeCategory} onChange={(e) => setActiveCategory(e.target.value as 'ALL' | PermitCategory)}>
            <option value="ALL">전체</option>
            {CATEGORY_TAB_ORDER.map((cat) => <option key={cat} value={cat}>{CATEGORY_LABEL[cat]}</option>)}
          </Select>
          <Select label="상태" className="w-full sm:w-44" value={statusFilter} onChange={(e) => setStatusFilter(e.target.value as 'ALL' | PermitStatus)}>
            <option value="ALL">전체</option>
            {Object.entries(PERMIT_STATUS).map(([value, s]) => <option key={value} value={value}>{s.text}</option>)}
          </Select>
          <div className="sm:ml-auto">
            <Tip k="report"><Button
              variant="tertiary"
              disabled={reporting}
              onClick={() => {
                setReporting(true);
                downloadReport(project.id).catch((e: Error) => window.alert(e.message)).finally(() => setReporting(false));
              }}
            >
              {reporting ? '만드는 중…' : '진행 현황 보고 내려받기'}
            </Button></Tip>
          </div>
        </div>
        <FilterChips filters={permitFilters} onClearAll={clearPermitFilters} />

        <div className="mt-6">
          {permits.length === 0 ? (
            <EmptyBlock
              title="등록된 인허가가 없습니다."
              description={project.member
                ? '시설 종류·용량·입지로 필요한 인허가를 찾습니다. 도출 결과에서 맞는 항목을 승인하면 여기에 나옵니다.'
                : '프로젝트 멤버가 인허가를 도출하면 여기에 나옵니다.'}
              action={project.member && (
                <Link to={`/projects/${project.id}/settings`} className={cn('inline-flex h-12 items-center rounded bg-brand px-4 text-[17px] font-bold text-white hover:bg-brand-hover', focusRing)}>
                  인허가 도출하기
                </Link>
              )}
            />
          ) : visible.length === 0 ? (
            <EmptyBlock title="조건에 맞는 항목이 없습니다." action={<Button variant="tertiary" onClick={clearPermitFilters}>필터 모두 지우기</Button>} />
          ) : (
            <>
              <p className="mb-2 text-[15px] text-body" aria-live="polite"><span className="tabular-nums font-bold text-ink">{visible.length}</span>건</p>
              <Table caption="인허가 목록" className="min-w-[640px] whitespace-nowrap xl:min-w-[960px]">
                <thead>
                  <tr>
                    <th>인허가명</th>
                    <th>상태</th>
                    <th>분야</th>
                    <th className={WIDE}>관련 법규</th>
                    <th className={WIDE}>승인기관</th>
                    <th className="text-right">만료일</th>
                    <th className="text-right">남은 기간</th>
                  </tr>
                </thead>
                <tbody>
                  {visible.map((p) => {
                    const status = PERMIT_STATUS[p.status];
                    const due = p.dDay === null ? null : dueBadge(p.dDay);
                    return (
                      <tr key={p.id} onClick={() => setSelected(p)} className={cn('cursor-pointer', selected?.id === p.id && 'bg-brand-subtle')}>
                        <td>
                          <button
                            type="button"
                            onClick={(e) => { e.stopPropagation(); setSelected(p); }}
                            className={cn('rounded-sm text-left font-bold text-brand-text hover:underline', focusRing)}
                          >
                            {p.name}
                          </button>
                        </td>
                        <td><Badge tone={status.tone}>{status.text}</Badge></td>
                        <td className="text-body">{CATEGORY_LABEL[p.category]}</td>
                        <td className={WIDE}>
                          <a href={p.regulationUrl ?? undefined} target="_blank" rel="noreferrer" onClick={(e) => e.stopPropagation()}
                            className={cn('inline-flex items-center gap-1 rounded-sm text-brand-text hover:underline', focusRing)}>
                            {p.regulation}<span aria-hidden>↗</span>
                          </a>
                        </td>
                        <td className={WIDE}>
                          <a href={p.authorityUrl ?? undefined} target="_blank" rel="noreferrer" onClick={(e) => e.stopPropagation()}
                            className={cn('inline-flex items-center gap-1 rounded-sm text-body hover:text-brand-text hover:underline', focusRing)}>
                            {p.authority}<span aria-hidden>↗</span>
                          </a>
                        </td>
                        <td className="text-right tabular-nums text-body">{p.expiryDate ?? '—'}</td>
                        <td className="text-right">
                          {due ? <Badge tone={due.tone} className="tabular-nums">{due.text}</Badge> : <span className="text-mute">—</span>}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </Table>
            </>
          )}
        </div>
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
