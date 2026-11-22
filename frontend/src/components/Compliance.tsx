import { useEffect, useState, type ReactNode } from 'react';
import { DUE_SOON_DAYS } from '../terms';
import { useNavigate } from 'react-router';
import { api } from '../api/client';

// ---------------------------------------------------------------------------
// Compliance — 규제 대응 현황 (operations-compliance F4): 기한 내 이행률 and the counts shown beside it.
// CompanyCompliance goes on the 대시보드, ProjectCompliance on the project 개요 tab. The server computes every
// number (ComplianceRate); this file only formats them and prints the formula next to them.
// ---------------------------------------------------------------------------

interface Counts {
  due: number; onTime: number; late: number; percent: number | null; // null = nothing fell due
  overdue: number; openCorrective: number; dueSoon: number;
}
interface Metrics { tasks: Counts; exceeded: number; near: number; nearExpiry: number; siteOpen: number }
// operations: the 운영 module is on (any shown project, for Summary); off → measurement numbers are hidden
interface ProjectRow { projectId: number; name: string; teamId: number | null; teamName: string | null; operations: boolean; metrics: Metrics }
interface Summary {
  from: string; to: string; scope: 'COMPANY' | 'MINE'; projectCount: number; operations: boolean;
  teams: { id: number; name: string }[]; total: Metrics; projects: ProjectRow[];
}
interface Month { month: string; due: number; onTime: number; percent: number | null }
interface PermitRow { permitItemId: number | null; name: string | null; due: number; onTime: number; late: number; percent: number | null }
interface ProjectSummary { from: string; to: string; operations: boolean; total: Metrics; permits: PermitRow[] }

const card = 'rounded border border-line bg-white p-5 shadow-sm';
const th = 'px-3 py-2.5 font-medium';
const td = 'px-3 py-2.5';
const pct = (p: number | null) => (p === null ? '해당 없음' : `${p}%`);
const alarm = (n: number) => (n > 0 ? 'text-danger' : 'text-body'); // colour only for 지연·초과
const query = (o: Record<string, string | number | null>) => {
  const q = Object.entries(o).filter(([, v]) => v !== null && v !== '').map(([k, v]) => `${k}=${v}`).join('&');
  return q ? `?${q}` : '';
};

function Metric({ label, value, sub, tone = 'text-ink' }: { label: string; value: ReactNode; sub: string; tone?: string }) {
  return (
    <div className={card}>
      <div className={`whitespace-nowrap text-3xl font-bold ${tone}`}>{value}</div>
      <div className="mt-1 truncate text-sm text-body" title={label}>{label}</div>
      <div className="mt-0.5 truncate text-xs text-mute" title={sub}>{sub}</div>
    </div>
  );
}

/** The four headline numbers, the counts kept apart from the rate, and the formula in words. */
function MetricCards({ m, company, operations }: { m: Metrics; company: boolean; operations: boolean }) {
  const t = m.tasks;
  return (
    <>
      <div className={`mt-4 grid grid-cols-2 gap-4 ${operations ? 'lg:grid-cols-4' : 'lg:grid-cols-3'}`}>
        <Metric label="기한 내 이행률" value={pct(t.percent)} tone={t.percent === null ? 'text-mute' : 'text-brand'}
          sub={t.percent === null ? '기간 안에 기한이 지난 업무 없음' : `기한 내 완료 ${t.onTime}건 / 기한 도래 ${t.due}건`} />
        <Metric label="기한 경과" value={t.overdue} tone={alarm(t.overdue)} sub="열린 업무 중 기한이 지난 것" />
        {operations && <Metric label="미조치 초과" value={t.openCorrective} tone={alarm(t.openCorrective)} sub="열린 조치 업무" />}
        <Metric label="기한 임박" value={t.dueSoon} sub={`열린 업무 중 오늘 ~ ${DUE_SOON_DAYS}일 뒤`} />
      </div>
      <p className="mt-3 text-sm text-body">
        지연 완료 {t.late}건 · {operations && `측정 초과 ${m.exceeded}건 · 측정 근접 ${m.near}건 · `}허가 만료 임박 {m.nearExpiry}건 · 현장 반영 대기 {m.siteOpen}건
      </p>
      <p className="mt-1 text-xs text-mute">
        산식: 이행률 = 기간 안에 기한이 지난 업무(의무 + 조치, 취소 제외) 중 기한 안에 완료한 건수 ÷ 기한이 지난 업무 건수.
        지연 완료와 기한 경과는 분자에서 빠집니다. 기한이 지난 업무가 없으면 100%가 아니라 "해당 없음"입니다.
        {company && ' 회사 전체는 프로젝트 이행률의 평균이 아니라 건수를 합산해 계산합니다.'}
        {operations
          ? ' 기한 경과·미조치 초과·기한 임박·만료 임박·현장 반영은 기간과 무관한 오늘 기준이고, 측정 초과·근접은 기간 안의 측정일 기준입니다.'
          : ' 기한 경과·기한 임박·만료 임박·현장 반영은 기간과 무관한 오늘 기준입니다.'}
      </p>
    </>
  );
}

function Period({ from, to, onChange }: { from: string; to: string; onChange: (from: string, to: string) => void }) {
  const input = 'rounded border border-line bg-white px-2 py-1 text-sm text-body';
  return (
    <label className="flex items-center gap-1.5 whitespace-nowrap text-sm text-body">
      기간
      <input type="date" aria-label="기간 시작" className={input} value={from} max={to}
        onChange={(e) => e.target.value && onChange(e.target.value, to)} />
      ~
      <input type="date" aria-label="기간 끝" className={input} value={to} min={from}
        onChange={(e) => e.target.value && onChange(from, e.target.value)} />
    </label>
  );
}

// Column per month, one axis (0–100%); the percent is printed above each bar, "–" when nothing fell due.
function MonthlyTrend({ months }: { months: Month[] }) {
  return (
    <div className="mt-4 overflow-x-auto">
      <div className="min-w-[320px]">
        <div className="flex h-40 items-end gap-1.5 border-b border-line">
          {months.map((m) => (
            <div key={m.month} className="flex h-full min-w-0 flex-1 flex-col justify-end"
              title={`${m.month} — ${m.percent === null ? '기한이 지난 업무 없음' : `기한 내 완료 ${m.onTime}건 / 기한 도래 ${m.due}건 (${m.percent}%)`}`}>
              <span className="mb-1 whitespace-nowrap text-center text-xs text-body">{m.percent === null ? '–' : `${m.percent}%`}</span>
              <div className="rounded-t bg-[#3B82C4]" style={{ height: `${(m.percent ?? 0) * 0.8}%` }} />
            </div>
          ))}
        </div>
        <div className="mt-1.5 flex gap-1.5 text-[11px] text-mute">
          {months.map((m) => (
            <span key={m.month} className="min-w-0 flex-1 whitespace-nowrap text-center">
              {Number(m.month.slice(5))}월<br />{m.onTime}/{m.due}
            </span>
          ))}
        </div>
      </div>
      <p className="mt-2 text-xs text-mute">막대 = 월별 기한 내 이행률(0~100%), 아래 숫자 = 기한 내 완료 / 기한 도래 건수</p>
    </div>
  );
}

/** 대시보드: scope line, period + team filter, cards, project table and monthly trend. stageOf: 생애주기 단계 label. */
export function CompanyCompliance({ stageOf }: { stageOf: (projectId: number) => string }) {
  const navigate = useNavigate();
  const [filter, setFilter] = useState<{ from: string; to: string; teamId: number | null }>({ from: '', to: '', teamId: null });
  const [data, setData] = useState<Summary | null>(null);
  const [months, setMonths] = useState<Month[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const q = query(filter);
    Promise.all([api<Summary>(`/api/compliance/summary${q}`), api<Month[]>(`/api/compliance/trend${q}`)]).then(
      ([s, t]) => { setData(s); setMonths(t); setError(null); },
      (e: Error) => setError(e.message),
    );
  }, [filter]);

  if (!data) return error ? <p className="mt-6 text-sm text-danger">{error}</p> : null;
  return (
    <section className="mt-6">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <h2 className="text-lg font-semibold text-ink">규제 대응 현황</h2>
        <span className="whitespace-nowrap text-sm text-body">
          집계 범위: {data.scope === 'COMPANY' ? `회사 전체 (프로젝트 ${data.projectCount}개)` : `내 프로젝트 ${data.projectCount}개`}
        </span>
        <div className="ml-auto flex flex-wrap items-center gap-3">
          <select aria-label="팀" className="rounded border border-line bg-white px-2 py-1 text-sm text-body"
            value={filter.teamId ?? ''} onChange={(e) => setFilter({ ...filter, teamId: e.target.value ? Number(e.target.value) : null })}>
            <option value="">전체 팀</option>
            {data.teams.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
          </select>
          <Period from={data.from} to={data.to} onChange={(from, to) => setFilter({ ...filter, from, to })} />
        </div>
      </div>
      {error && <p className="mt-2 text-sm text-danger">{error}</p>}
      <MetricCards m={data.total} company operations={data.operations} />

      <div className="mt-6 space-y-6">
        <div className={card}>
          <h3 className="font-semibold text-ink">프로젝트별 현황</h3>
          <div className="mt-3 overflow-x-auto">
            <table className="w-full min-w-[640px] whitespace-nowrap text-left text-sm">
              <thead className="bg-paper text-xs text-body">
                <tr>
                  <th className={th}>프로젝트</th>
                  <th className={th}>팀</th>
                  <th className={th}>단계</th>
                  <th className={`${th} text-right`}>이행률</th>
                  <th className={`${th} text-right`}>기한 경과</th>
                  {data.operations && <th className={`${th} text-right`} title="미조치 초과: 열린 조치 업무">초과</th>}
                  <th className={`${th} text-right`}>만료 임박</th>
                  <th className={`${th} text-right`}>현장 반영</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {data.projects.length === 0 && (
                  <tr><td colSpan={data.operations ? 8 : 7} className="px-3 py-6 text-center text-mute">집계할 프로젝트가 없습니다.</td></tr>
                )}
                {data.projects.map((p) => {
                  const t = p.metrics.tasks;
                  return (
                    <tr key={p.projectId} tabIndex={0} className="cursor-pointer transition hover:bg-paper"
                      onClick={() => navigate(`/projects/${p.projectId}`)}
                      onKeyDown={(e) => e.key === 'Enter' && navigate(`/projects/${p.projectId}`)}>
                      <td className={`${td} w-full max-w-0 truncate font-medium text-ink`} title={p.name}>{p.name}</td>
                      <td className={`${td} max-w-[180px] truncate text-body`} title={p.teamName ?? ''}>{p.teamName ?? '—'}</td>
                      <td className={`${td} text-body`}>{stageOf(p.projectId)}</td>
                      <td className={`${td} text-right ${t.percent === null ? 'text-mute' : 'text-ink'}`}
                        title={t.percent === null ? '기간 안에 기한이 지난 업무 없음' : `기한 내 완료 ${t.onTime}건 / 기한 도래 ${t.due}건`}>
                        {pct(t.percent)}
                      </td>
                      <td className={`${td} text-right ${alarm(t.overdue)}`}>{t.overdue}</td>
                      {data.operations && (p.operations
                        ? <td className={`${td} text-right ${alarm(t.openCorrective)}`} title={`기간 내 측정 초과 ${p.metrics.exceeded}건`}>{t.openCorrective}</td>
                        : <td className={`${td} text-right text-mute`} title="운영단계 관리 꺼짐">—</td>)}
                      <td className={`${td} text-right text-body`}>{p.metrics.nearExpiry}</td>
                      <td className={`${td} text-right text-body`}>{p.metrics.siteOpen}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
        <div className={card}>
          <h3 className="font-semibold text-ink">월별 기한 내 이행률</h3>
          <MonthlyTrend months={months} />
        </div>
      </div>
    </section>
  );
}

/** 프로젝트 › 개요: the same cards for one project and the rate per permit item. Hidden when the user may not see it. */
export function ProjectCompliance({ projectId }: { projectId: number }) {
  const [period, setPeriod] = useState({ from: '', to: '' });
  const [data, setData] = useState<ProjectSummary | null>(null);

  useEffect(() => {
    api<ProjectSummary>(`/api/projects/${projectId}/compliance/summary${query(period)}`).then(setData, () => setData(null));
  }, [projectId, period]);

  if (!data) return null;
  return (
    <section>
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <h2 className="text-lg font-semibold text-ink">규제 대응 현황</h2>
        <div className="ml-auto"><Period from={data.from} to={data.to} onChange={(from, to) => setPeriod({ from, to })} /></div>
      </div>
      <MetricCards m={data.total} company={false} operations={data.operations} />
      <div className={`${card} mt-6`}>
        <h3 className="font-semibold text-ink">인허가 항목별 이행률</h3>
        <div className="mt-3 overflow-x-auto">
          <table className="w-full min-w-[480px] whitespace-nowrap text-left text-sm">
            <thead className="bg-paper text-xs text-body">
              <tr>
                <th className={th}>인허가 항목</th>
                <th className={`${th} text-right`}>기한 도래</th>
                <th className={`${th} text-right`}>기한 내 완료</th>
                <th className={`${th} text-right`}>지연 완료</th>
                <th className={`${th} text-right`}>이행률</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {data.permits.length === 0 && (
                <tr><td colSpan={5} className="px-3 py-6 text-center text-mute">기간 안에 기한이 지난 업무가 없습니다.</td></tr>
              )}
              {data.permits.map((p) => {
                const name = p.name ?? '인허가 항목 미지정';
                return (
                  <tr key={p.permitItemId ?? 0}>
                    <td className={`${td} w-full max-w-0 truncate font-medium text-ink`} title={name}>{name}</td>
                    <td className={`${td} text-right text-body`}>{p.due}</td>
                    <td className={`${td} text-right text-body`}>{p.onTime}</td>
                    <td className={`${td} text-right text-body`}>{p.late}</td>
                    <td className={`${td} text-right text-ink`}>{pct(p.percent)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>
    </section>
  );
}
