import { lazy, Suspense, useEffect, useMemo, useState } from 'react';
import { addDays, format, startOfWeek } from 'date-fns';
import { api, isMaster } from '../api/client';
import { EVENT_KIND_LABEL, EVENT_KINDS, eventWhen, getCalendar, type CalendarData, type CalendarFilter, type ProjectEvent } from '../api/events';
import { getDepartments, type Department } from '../api/org';
import { issueCalendarToken, webcal } from '../api/share';
import { EntryDetail } from '../components/EventPanels';
import GoogleCalendarSync from '../components/GoogleCalendarSync';
import type { CalendarEntry } from '../components/ScheduleCalendar';
import type { Project } from '../types';
import { Link } from 'react-router';
import { EmptyState, primaryBtn } from '../components/Onboarding';

const ScheduleCalendar = lazy(() => import('../components/ScheduleCalendar'));

// ---------------------------------------------------------------------------
// CompanyCalendarPage — 회사 달력 (설계 §6.2): events, milestones and (toggle) 인허가 기한 of every project in scope
// (company ADMIN = whole company, others = projects they are a member of). Filters: 프로젝트·부서·팀·종류, "출장만".
// Below: who goes where this week and next (출장·심의·보고회).
// ---------------------------------------------------------------------------

const EMPTY: CalendarData = { events: [], milestones: [], deadlines: [] };
const PEOPLE_KINDS = new Set(['TRIP', 'REVIEW', 'CLIENT_REPORT']);
const select = 'rounded border border-line bg-white px-2 py-1.5 text-xs';

export default function CompanyCalendarPage() {
  const [projects, setProjects] = useState<Project[] | null>(null); // null until loaded, so the empty state does not flash
  const [depts, setDepts] = useState<Department[]>([]);
  const [filter, setFilter] = useState<CalendarFilter>({});
  const [showDeadlines, setShowDeadlines] = useState(false);
  const [range, setRange] = useState<[string, string] | null>(null);
  const [data, setData] = useState<CalendarData>(EMPTY);
  const [weeks, setWeeks] = useState<ProjectEvent[]>([]);
  const [open, setOpen] = useState<CalendarEntry | null>(null);
  const [error, setError] = useState<string | null>(null);

  const monday = startOfWeek(new Date(), { weekStartsOn: 1 });
  const nextMonday = addDays(monday, 7);
  const ymd = (d: Date) => format(d, 'yyyy-MM-dd');

  useEffect(() => {
    api<Project[]>(isMaster() ? '/api/projects?scope=company' : '/api/projects').then(setProjects, () => {});
    getDepartments().then(setDepts, () => {});
  }, []);
  useEffect(() => {
    if (!range) return;
    let stale = false; // an older range/filter answering late must not overwrite the current one
    getCalendar(range[0], range[1], filter).then((d) => { if (!stale) setData(d); }, (e: Error) => { if (!stale) setError(e.message); });
    return () => { stale = true; };
  }, [range, filter]);
  useEffect(() => {
    let stale = false;
    getCalendar(ymd(monday), ymd(addDays(nextMonday, 6)), filter).then((d) => { if (!stale) setWeeks(d.events); }, () => {});
    return () => { stale = true; };
  }, [filter]);

  // every department has a 1팀: with no department chosen, show "부서 / 팀"
  const teams = depts.filter((d) => !filter.dept || String(d.id) === filter.dept)
    .flatMap((d) => d.teams.map((t) => ({ ...t, name: filter.dept ? t.name : `${d.name} / ${t.name}` })));
  const set = (patch: CalendarFilter) => setFilter((f) => ({ ...f, ...patch }));
  const tripsOnly = filter.kind === 'TRIP';

  return (
    <div className="min-h-full bg-paper">
      <div className="mx-auto max-w-6xl space-y-4 px-4 py-8 sm:px-6">
        <div>
          <h1 className="text-2xl font-bold text-brand">회사 달력</h1>
          <p className="mt-1 text-sm text-mute">
            {isMaster() ? '회사 전체 프로젝트' : '내가 멤버인 프로젝트'}의 이벤트·출장·마일스톤입니다. 항목을 누르면 상세가 열립니다.
          </p>
        </div>
        {projects?.length === 0 && (
          <EmptyState text="프로젝트의 회의·출장·마일스톤을 한 달력에 모읍니다. 프로젝트를 만들고 일정에 이벤트를 넣으면 여기에 나옵니다.">
            <Link to="/projects/new" className={primaryBtn}>새 프로젝트 만들기</Link>
          </EmptyState>
        )}
        <MySubscription />

        <div className="flex flex-wrap items-center gap-2">
          <select aria-label="프로젝트" className={select} value={filter.project ?? ''} onChange={(e) => set({ project: e.target.value })}>
            <option value="">프로젝트 전체</option>
            {projects?.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
          <select aria-label="부서" className={select} value={filter.dept ?? ''} onChange={(e) => set({ dept: e.target.value, team: '' })}>
            <option value="">부서 전체</option>
            {depts.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
          </select>
          <select aria-label="팀" className={select} value={filter.team ?? ''} onChange={(e) => set({ team: e.target.value })}>
            <option value="">팀 전체</option>
            {teams.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
          </select>
          <select aria-label="종류" className={select} value={filter.kind ?? ''} onChange={(e) => set({ kind: e.target.value })}>
            <option value="">종류 전체</option>
            {EVENT_KINDS.map((k) => <option key={k} value={k}>{EVENT_KIND_LABEL[k]}</option>)}
          </select>
          <button type="button" onClick={() => set({ kind: tripsOnly ? '' : 'TRIP' })}
            className={`rounded border px-3 py-1.5 text-xs font-medium ${tripsOnly ? 'border-ok bg-ok text-white' : 'border-line bg-white text-body hover:border-ok'}`}>
            출장만
          </button>
          <label className="ml-2 flex items-center gap-1.5 text-xs text-body">
            <input type="checkbox" checked={showDeadlines} disabled={!!filter.kind} onChange={(e) => setShowDeadlines(e.target.checked)} />
            인허가 기한 표시
          </label>
        </div>

        {error && <p role="alert" className="rounded bg-red-50 px-3 py-2 text-sm text-danger">{error}</p>}
        <Suspense fallback={<div className="h-[640px] rounded border border-line bg-white" />}>
          <ScheduleCalendar data={data} showDeadlines={showDeadlines} showProject
            onRange={(from, to) => setRange([from, to])} onOpen={setOpen} />
        </Suspense>

        <PeopleWeeks events={weeks.filter((e) => filter.kind || PEOPLE_KINDS.has(e.kind))} nextMonday={ymd(nextMonday)} />
        <GoogleCalendarSync />
      </div>
      {open && <EntryDetail entry={open} onClose={() => setOpen(null)} />}
    </div>
  );
}

/** One row per attendee: this week's and next week's events, one line each (출장·심의·보고회 unless a kind is chosen). */
function PeopleWeeks({ events, nextMonday }: { events: ProjectEvent[]; nextMonday: string }) {
  const rows = useMemo(() => {
    const byPerson = new Map<string, { thisWeek: ProjectEvent[]; nextWeek: ProjectEvent[] }>();
    for (const e of events) {
      for (const a of e.attendees) {
        const name = a.name ?? `${a.externalName} (외부)`;
        const row = byPerson.get(name) ?? { thisWeek: [], nextWeek: [] };
        // a trip that spans the weekend shows in both weeks
        if (e.startsAt.slice(0, 10) < nextMonday) row.thisWeek.push(e);
        if (e.endsAt.slice(0, 10) >= nextMonday) row.nextWeek.push(e);
        byPerson.set(name, row);
      }
    }
    return [...byPerson.entries()].sort(([a], [b]) => a.localeCompare(b, 'ko'));
  }, [events, nextMonday]);
  const cell = (list: ProjectEvent[]) => {
    const text = list.map((e) => `${eventWhen(e)} ${EVENT_KIND_LABEL[e.kind]} · ${e.counterpartOrg ?? e.location ?? e.title}`).join(', ');
    return <td className="max-w-0 truncate px-3 py-2.5 text-body" title={text}>{text || '—'}</td>;
  };
  return (
    <section className="rounded border border-line bg-white p-5 shadow-sm">
      <h2 className="text-lg font-semibold text-ink">사람별 이번 주·다음 주</h2>
      <div className="mt-3 overflow-x-auto">
        <table className="w-full min-w-[640px] table-fixed whitespace-nowrap text-left text-sm">
          <thead className="bg-paper text-xs text-body">
            <tr>
              <th className="w-40 px-3 py-2.5 font-medium">사람</th>
              <th className="px-3 py-2.5 font-medium">이번 주</th>
              <th className="px-3 py-2.5 font-medium">다음 주</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-line">
            {rows.length === 0 && <tr><td colSpan={3} className="whitespace-normal px-3 py-6 text-center text-mute">이번 주·다음 주에 참석자가 지정된 일정이 없습니다. 프로젝트 › 일정 › 달력에서 출장·회의를 넣고 참석자를 지정하세요.</td></tr>}
            {rows.map(([name, r]) => (
              <tr key={name}>
                <td className="truncate px-3 py-2.5 font-medium text-ink" title={name}>{name}</td>
                {cell(r.thisWeek)}
                {cell(r.nextWeek)}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

/** 개인 구독 주소 (설계 §6.4 대안): my attended events and my milestones as .ics. A new address kills the old one. */
function MySubscription() {
  const [url, setUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const issue = async () => {
    if (!window.confirm('새 구독 주소를 만들까요? 이전에 만든 주소는 더 이상 동작하지 않습니다.')) return;
    try {
      setUrl(webcal((await issueCalendarToken()).icsPath));
    } catch (e) {
      setError((e as Error).message);
    }
  };
  return (
    <div className="flex flex-wrap items-center gap-2 text-xs text-body">
      <button type="button" onClick={issue}
        className="rounded border border-line bg-white px-3 py-1.5 font-medium text-body hover:border-brand">내 달력 구독 주소</button>
      {url
        ? <input readOnly value={url} onFocus={(e) => e.target.select()} className="min-w-0 flex-1 rounded border border-line px-2 py-1 font-mono" />
        : <span>내가 참석하는 이벤트와 내 담당 마일스톤을 Google·Outlook 달력에서 구독합니다. 반영은 달력 앱 주기(보통 수 시간)를 따릅니다.</span>}
      {error && <span role="alert" className="text-danger">{error}</span>}
    </div>
  );
}
