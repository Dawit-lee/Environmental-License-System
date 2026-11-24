import { useState } from 'react';
import { Link } from 'react-router';
import { Badge, dueBadge } from './ui/Badge';
import { Table } from './ui/Table';
import type { SummaryItem } from './ui/SummaryStrip';
import { Help } from './Onboarding';
import { DUE_SOON_DAYS } from '../terms';
import type { PermitCategory, PermitItem, PermitStatus, Project } from '../types';
import { ymd, type Deadline } from '../api/notifications';

// ---------------------------------------------------------------------------
// Dashboard pieces shared by the portfolio dashboard (DashboardPage) and the project 개요 tab (ProjectOverview).
// ---------------------------------------------------------------------------

export function dDayClasses(dDay: number): string {
  if (dDay <= 7) return 'text-danger font-semibold';
  if (dDay <= 30) return 'text-warn font-semibold';
  if (dDay <= 60) return 'text-amber-500 font-medium';
  return 'text-mute';
}

// Colours are the DESIGN.md state tokens; colour is never alone — every chart also shows a text legend with counts.
export const STATUSES: { status: PermitStatus; label: string; color: string }[] = [
  { status: 'COMPLETED', label: '취득완료', color: 'var(--color-ok)' },
  { status: 'IN_PROGRESS', label: '진행중', color: 'var(--color-information)' },
  { status: 'NOT_APPLIED', label: '미신청', color: 'var(--color-line)' },
  { status: 'NEAR_EXPIRY', label: '만료임박', color: 'var(--color-warn-border)' },
  { status: 'EXPIRED', label: '만료', color: 'var(--color-danger)' },
];

export const STAGES = ['입지', '설계', '착공', '준공', '운영'] as const;

/** shortcut: rough stage from permit status + planned dates; replace when projects store a real stage. */
export function lifecycleStage(project: Project, permits: PermitItem[], today = new Date().toLocaleDateString('sv-SE')): number {
  const done = (cats: PermitCategory[]) =>
    permits.filter((p) => cats.includes(p.category)).every((p) => p.status === 'COMPLETED');
  if (!done(['LOCATION', 'ENVIRONMENT'])) return 0;
  if (!project.constructionStartDate || today < project.constructionStartDate) return 1;
  if (!project.expectedEndDate || today < project.expectedEndDate) return 2;
  if (!permits.every((p) => p.status === 'COMPLETED')) return 3;
  return 4;
}

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
  const gradient = total === 0 ? 'var(--color-line)' : `conic-gradient(${slices
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
          <span className="text-xl font-bold text-ink">{total}</span>
          <span className="text-[10px] text-mute">전체</span>
        </div>
      </div>
      <ul className="space-y-1.5 text-sm">
        {slices.map((s) => (
          <li key={s.status} className="flex items-center gap-2">
            <span className="h-2.5 w-2.5 rounded-full" style={{ backgroundColor: s.color }} />
            <span className="whitespace-nowrap text-body">{s.label}</span>
            <span className="ml-auto font-medium text-ink">{s.value}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

export function ActivityList({ activity }: { activity: Activity[] }) {
  return (
          <ul className="mt-3 divide-y divide-line">
      {activity.length === 0 && <li className="py-6 text-center text-sm text-mute">아직 기록된 신청·취득이 없습니다.</li>}
      {activity.map((a) => (
        <li key={a.key} className="flex gap-3 py-2.5 text-sm">
          <span className="shrink-0 text-mute">{a.date}</span>
          <span className="truncate text-body" title={a.text}>{a.text}</span>
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
      <div className="flex h-36 items-end gap-1.5 border-b border-line">
        {weeks.map((w, i) => (
          <div key={i} className="flex h-full flex-1 flex-col justify-end"
            title={`${w.start.getMonth() + 1}/${w.start.getDate()} 주 — ${w.items.length}건${w.items.map((d) => `\n· ${d.projectName}: ${d.title}`).join('')}`}>
            {w.items.length > 0 && <span className="mb-1 text-center text-xs text-body">{w.items.length}</span>}
            <div className="rounded-t bg-information" style={{ height: `${(w.items.length / max) * 80}%` }} />
          </div>
        ))}
      </div>
      <div className="mt-1.5 flex gap-1.5 text-[11px] text-mute">
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
      <Link to={to} className="flex min-h-12 items-center justify-between gap-3 py-2 text-[15px] text-body hover:text-brand-text hover:underline">
        <span>{label}</span>
        <span className="whitespace-nowrap tabular-nums"><strong className="text-2xl font-bold text-ink">{value}</strong> 건</span>
      </Link>
    </li>
  );
}

/**
 * 「30일 안에 닥치는 기한」: overdue first, then the next DUE_SOON_DAYS days, with a project filter.
 * `deadlines` comes sorted by date from /api/deadlines.
 */
const SHOWN = 10;

export function UpcomingDeadlines({ deadlines, error }: { deadlines: Deadline[]; error?: string | null }) {
  const [projectId, setProjectId] = useState('');
  const [all, setAll] = useState(false);
  const due = deadlines.filter((d) => d.dDay <= DUE_SOON_DAYS);
  const projects = [...new Map(due.map((d) => [d.projectId, d.projectName])).entries()];
  const matched = projectId ? due.filter((d) => String(d.projectId) === projectId) : due;
  const rows = all ? matched : matched.slice(0, SHOWN);
  return (
    <section aria-labelledby="due-title" className="min-w-0 rounded border border-line bg-white">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-line px-5 py-4">
        <h2 id="due-title" className="text-[19px] font-bold text-ink">{DUE_SOON_DAYS}일 안에 닥치는 기한</h2>
        <label className="flex items-center gap-2 text-[15px] text-body">
          프로젝트
          <select value={projectId} onChange={(e) => setProjectId(e.target.value)}
            className="h-10 max-w-48 rounded border border-input bg-white px-2 text-[15px] text-ink">
            <option value="">전체</option>
            {projects.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
          </select>
        </label>
      </div>
      {/* the section is the box: drop the table's own border so it is not drawn twice */}
      <div className="[&>div]:rounded-none [&>div]:border-0">
        <Table caption="기한이 지났거나 30일 안에 닥치는 항목" className="min-w-[560px]">
          <thead>
            <tr><th scope="col">상태</th><th scope="col">기한</th><th scope="col">항목</th><th scope="col">프로젝트</th><th scope="col">담당</th></tr>
          </thead>
          <tbody>
            {error && <tr><td colSpan={5} className="text-center text-danger">{error}</td></tr>}
            {!error && rows.length === 0 && (
              <tr><td colSpan={5} className="whitespace-normal text-left text-mute">{DUE_SOON_DAYS}일 안에 닥치는 기한이 없습니다.</td></tr>
            )}
            {rows.map((d) => {
              const b = dueBadge(d.dDay);
              return (
                <tr key={d.id}>
                  <td><Badge tone={b.tone}>{b.text}</Badge></td>
                  <td className="whitespace-nowrap tabular-nums">{d.dueDate}</td>
                  <td className="w-full max-w-0 truncate" title={d.title}>
                    <Link to={deadlineLink(d)} className="text-ink hover:text-brand-text hover:underline">{d.title}</Link>
                  </td>
                  <td className="max-w-48 truncate text-body" title={d.projectName}>{d.projectName}</td>
                  <td className="whitespace-nowrap text-body">{d.assigneeName ?? '전체 멤버'}</td>
                </tr>
              );
            })}
          </tbody>
        </Table>
      </div>
      {matched.length > SHOWN && (
        <div className="border-t border-line px-5 py-3">
          <button type="button" onClick={() => setAll(!all)} aria-expanded={all}
            className="text-[15px] font-medium text-brand-text hover:underline">
            {all ? '접기' : `나머지 ${matched.length - SHOWN}건 더 보기`}
          </button>
        </div>
      )}
    </section>
  );
}

/** Summary strip cells; only 기한 경과 gets the red ground, and only when it is not zero. */
export function summaryItems(c: { projects: number; permits: number; overdue: number; dueSoon: number; notApplied: number; reviewOpen: number }): SummaryItem[] {
  return [
    { label: '진행 중 프로젝트', value: c.projects, unit: '건' },
    { label: '관리 중 인허가', value: c.permits, unit: '건' },
    { label: <>기한 경과<Help k="overdue" /></>, value: c.overdue, unit: '건', tone: c.overdue > 0 ? 'danger' : undefined, alert: c.overdue > 0 },
    { label: <>기한 임박<Help k="dueSoon" /></>, value: c.dueSoon, unit: '건', tone: c.dueSoon > 0 ? 'warning' : undefined },
    { label: '미신청 인허가', value: c.notApplied, unit: '건' },
    { label: 'AI 검토 대기', value: c.reviewOpen, unit: '건', tone: c.reviewOpen > 0 ? 'info' : undefined },
  ];
}

/** 프로젝트 › 개요 strip: permit counts by status; 만료 임박 turns amber only when it is not zero. */
export function permitSummaryItems(permits: PermitItem[]): SummaryItem[] {
  const n = (s: PermitStatus) => permits.filter((p) => p.status === s).length;
  const near = n('NEAR_EXPIRY');
  return [
    { label: '전체 인허가', value: permits.length, unit: '건' },
    { label: '취득 완료', value: n('COMPLETED'), unit: '건' },
    { label: '만료 임박', value: near, unit: '건', tone: near > 0 ? 'warning' : undefined },
    { label: '미신청', value: n('NOT_APPLIED'), unit: '건' },
  ];
}
