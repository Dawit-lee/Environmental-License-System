import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router';
import type { PermitItem, Project } from '../types';
import { api } from '../api/client';
import { getDeadlines, ymd, type Deadline } from '../api/notifications';
import { openSiteImplementationCount } from '../api/revisions';
import { getNewLawImpactCount } from '../api/lawImpacts';
import {
  ActivityList, DeadlineTimeline, STAGES, STATUSES, StatusDonut, UpcomingDeadlines, lifecycleStage, recentActivity, summaryItems,
} from '../components/dashboard';
import { CompanyCompliance } from '../components/Compliance';
import { DelayedByDiscipline, WeekEvents, type DelayedCount } from '../components/ScheduleWidgets';
import { GettingStarted } from '../components/Onboarding';
import { isDueSoon } from '../terms';
import { PageHeader } from '../components/ui/PageHeader';
import { SummaryStrip } from '../components/ui/SummaryStrip';
import { Alert } from '../components/ui/Alert';

// ---------------------------------------------------------------------------
// DashboardPage (DESIGN.md 5 대시보드): summary strip → 30-day deadline table with
// AI-review / law-amendment boxes beside it → compliance, schedule and the charts below.
// ---------------------------------------------------------------------------

interface DashboardSummary {
  projects: Project[];
  permits: PermitItem[];
  reviewOpen: number;
  delayedByDiscipline: DelayedCount[];
}

const box = 'rounded border border-line bg-white';
const boxTitle = 'border-b border-line px-5 py-4 text-[19px] font-bold text-ink';

export default function DashboardPage() {
  const [projects, setProjects] = useState<Project[]>([]);
  const [permits, setPermits] = useState<PermitItem[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false); // the checklist waits for real counts, so it does not flash every step open
  const [reviewOpen, setReviewOpen] = useState(0); // AI suggestions + revisions awaiting review
  const [lawNew, setLawNew] = useState(0); // law amendments not yet reviewed
  const [siteOpen, setSiteOpen] = useState(0); // ④ approved revisions not yet carried out on site
  const [delayed, setDelayed] = useState<DelayedCount[] | null>(null);
  useEffect(() => {
    // one summary call instead of permits/suggestions/schedule per project
    api<DashboardSummary>('/api/dashboard')
      .then((d) => {
        setProjects(d.projects);
        setPermits(d.permits);
        setReviewOpen(d.reviewOpen);
        setDelayed(d.delayedByDiscipline);
        setLoaded(true);
      })
      .catch((e: Error) => setLoadError(e.message));
  }, []);

  const activity = useMemo(() => recentActivity(projects, permits), [projects, permits]);

  // Open deadlines from 180 days back (still-open overdue items) to 90 days ahead; the API sorts them by date.
  const [deadlines, setDeadlines] = useState<Deadline[]>([]);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    const from = new Date();
    from.setDate(from.getDate() - 180);
    const to = new Date();
    to.setDate(to.getDate() + 90);
    getDeadlines(ymd(from), ymd(to)).then(setDeadlines, (e: Error) => setError(e.message));
    openSiteImplementationCount().then((r) => setSiteOpen(r.count), () => {});
    getNewLawImpactCount().then((r) => setLawNew(r.count), () => {});
  }, []);
  const upcoming = deadlines.filter((d) => d.dDay >= 0); // the 90-day chart counts only what is still ahead

  const today = new Date();
  return (
    <div className="min-h-full bg-paper">
      <div className="space-y-6 py-8">
        <PageHeader title="대시보드"
          description={`${today.getFullYear()}년 ${today.getMonth() + 1}월 ${today.getDate()}일 기준 · 내가 참여한 프로젝트 ${projects.length}건`} />
        {loadError && <Alert tone="danger">{loadError}</Alert>}

        <SummaryStrip items={summaryItems({
          projects: projects.length,
          permits: permits.length,
          overdue: deadlines.filter((d) => d.dDay < 0).length,
          dueSoon: deadlines.filter((d) => isDueSoon(d.dDay)).length,
          notApplied: permits.filter((p) => p.status === 'NOT_APPLIED').length,
          reviewOpen,
        })} />

        {!loadError && loaded && <GettingStarted projects={projects} permits={permits} />}

        <div className="grid grid-cols-1 items-start gap-6 lg:grid-cols-3">
          <div className="min-w-0 lg:col-span-2"><UpcomingDeadlines deadlines={deadlines} error={error} /></div>
          <aside className="flex min-w-0 flex-col gap-4" aria-label="처리 대기">
            <section className={`${box} flex flex-col gap-3 p-5`}>
              <h2 className="text-[19px] font-bold text-ink">AI 검토 대기</h2>
              <p className="text-[15px] leading-relaxed text-body">허가서에서 찾은 조건은 사람이 승인해야 의무사항과 기한에 들어갑니다.</p>
              <p className="text-[15px] text-body">검토할 항목 <strong className="text-2xl font-bold tabular-nums text-ink">{reviewOpen}</strong> 건</p>
              <Link to="/inbox?kind=REVIEW"
                className="flex h-12 items-center justify-center rounded bg-brand text-[17px] font-bold text-white hover:bg-brand-hover">
                검토하러 가기
              </Link>
              <Link to="/inbox?kind=SITE" className="flex justify-between text-[15px] text-body hover:text-brand-text hover:underline">
                <span>현장 반영 대기</span><span className="font-bold tabular-nums text-ink">{siteOpen}건</span>
              </Link>
            </section>
            <Alert tone="info" title="법령 개정 알림">
              <p className="mt-1">{lawNew > 0 ? `확인이 필요한 법령 개정이 ${lawNew}건 있습니다.` : '확인할 법령 개정이 없습니다.'}</p>
              <Link to="/inbox?kind=LAW" className="mt-1 inline-block text-brand-text hover:underline">개정 내용 보기 ›</Link>
            </Alert>
          </aside>
        </div>

        <div className="grid grid-cols-1 items-start gap-6 lg:grid-cols-3">
          <section className={`${box} min-w-0 lg:col-span-2`}>
            <h2 className={boxTitle}>프로젝트별 인허가 진행</h2>
            <div className="px-5 pb-5"><ProjectStatusBars projects={projects} permits={permits} /></div>
          </section>
          <DelayedByDiscipline counts={delayed} />
        </div>

        {/* 규제 대응 현황 (F4): 프로젝트별 현황 표 + 월별 기한 내 이행률; the stage is the same rough one the project page shows */}
        <CompanyCompliance stageOf={(id) => {
          const p = projects.find((x) => x.id === id);
          return p ? STAGES[lifecycleStage(p, permits.filter((x) => x.projectId === id))] : '—';
        }} />

        <div className="grid grid-cols-1 items-start gap-6 lg:grid-cols-3">
          <div className="min-w-0 lg:col-span-2"><WeekEvents /></div>
          <section className={box}>
            <h2 className={boxTitle}>최근 활동</h2>
            <div className="px-5 pb-2"><ActivityList activity={activity} /></div>
          </section>
        </div>

        <div className="grid grid-cols-1 items-start gap-6 lg:grid-cols-3">
          <section className={`${box} min-w-0 lg:col-span-2`}>
            <h2 className={boxTitle}>향후 90일 기한 (주별)</h2>
            <div className="px-5 pb-5"><DeadlineTimeline deadlines={upcoming} /></div>
          </section>
          <section className={box}>
            <h2 className={boxTitle}>인허가 상태 분포</h2>
            <div className="px-5 pb-5"><StatusDonut permits={permits} /></div>
          </section>
        </div>
      </div>
    </div>
  );
}

// --- Local presentational helpers ------------------------------------------

// Horizontal 100% stacked bar per project: share of permits in each status.
function ProjectStatusBars({ projects, permits }: { projects: Project[]; permits: PermitItem[] }) {
  if (projects.length === 0) return <p className="py-6 text-center text-sm text-mute">프로젝트가 없습니다.</p>;
  return (
    <>
      <ul className="mt-3 flex flex-wrap gap-x-4 gap-y-1 text-xs text-body">
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
              <Link to={`/projects/${p.id}/permits`} className="truncate text-body hover:text-brand hover:underline" title={p.name}>
                {p.name}
              </Link>
              <div className="flex h-3 gap-0.5 overflow-hidden rounded bg-line-soft">
                {STATUSES.map((s) => {
                  const n = mine.filter((x) => x.status === s.status).length;
                  return n === 0 ? null : (
                    <div key={s.status} className="h-full first:rounded-l last:rounded-r" title={`${s.label} ${n}건`} role="img" aria-label={`${s.label} ${n}건`}
                      style={{ width: `${(n / mine.length) * 100}%`, backgroundColor: s.color }} />
                  );
                })}
              </div>
              <span className="whitespace-nowrap text-xs text-mute">취득 {done}/{mine.length}</span>
            </li>
          );
        })}
      </ul>
    </>
  );
}

