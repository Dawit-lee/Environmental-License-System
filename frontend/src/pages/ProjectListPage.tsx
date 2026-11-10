import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import type { FacilityType, Project, ProjectStatus } from '../types';
import { api, sessionUserId } from '../api/client';
import { getCompanyUsers, getDepartments, type Department } from '../api/org';

// ---------------------------------------------------------------------------
// ProjectListPage — company-wide project table: search, facility/status/department/team filters,
// sortable columns, one line per row. Defaults to my team.
// ---------------------------------------------------------------------------

const FACILITY_LABEL: Record<FacilityType, string> = {
  INCINERATION: '소각시설',
  LANDFILL: '매립시설',
  BIOGAS: '통합바이오가스',
  SEWAGE: '하수처리',
  WASTEWATER: '폐수처리',
};

// Badge colors by facility type per design system.
const FACILITY_BADGE: Record<FacilityType, string> = {
  INCINERATION: 'bg-red-100 text-red-700',
  LANDFILL: 'bg-gray-200 text-gray-700',
  BIOGAS: 'bg-emerald-100 text-emerald-700',
  SEWAGE: 'bg-sky-100 text-sky-700',
  WASTEWATER: 'bg-violet-100 text-violet-700',
};

const STATUS_LABEL: Record<ProjectStatus, string> = {
  IN_PROGRESS: '진행중',
  COMPLETED: '완료',
  ON_HOLD: '보류',
};

const STATUS_BADGE: Record<ProjectStatus, string> = {
  IN_PROGRESS: 'bg-[#1F4E79]/10 text-[#1F4E79]',
  COMPLETED: 'bg-[#0F6E56]/10 text-[#0F6E56]',
  ON_HOLD: 'bg-gray-200 text-gray-600',
};

type SortKey = 'name' | 'facility' | 'team' | 'pm' | 'em' | 'status' | 'progress' | 'nearExpiry' | 'location';

const progressOf = (p: Project) => (p.permitCount ? p.acquiredCount / p.permitCount : 0);
const teamOf = (p: Project) => (p.teamName ? `${p.departmentName} / ${p.teamName}` : '');

const SORT_VALUE: Record<SortKey, (p: Project) => string | number> = {
  name: (p) => p.name,
  facility: (p) => FACILITY_LABEL[p.facilityType],
  team: teamOf,
  pm: (p) => p.pmName ?? '',
  em: (p) => p.emName ?? '',
  status: (p) => STATUS_LABEL[p.status],
  progress: progressOf,
  nearExpiry: (p) => p.nearExpiryCount,
  location: (p) => p.location,
};

const COLUMNS: { key: SortKey; label: string; className?: string }[] = [
  { key: 'name', label: '프로젝트명' },
  { key: 'facility', label: '시설유형' },
  { key: 'team', label: '부서/팀' },
  { key: 'pm', label: 'PM' },
  { key: 'em', label: 'EM' },
  { key: 'status', label: '상태' },
  { key: 'progress', label: '취득 진행' },
  { key: 'nearExpiry', label: '만료임박', className: 'text-right' },
  { key: 'location', label: '위치' },
];

/** scope filter value: 'MINE' (my team), 'ALL', 'd:<departmentId>' or 't:<teamId>' */
type Scope = string;

export default function ProjectListPage() {
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [facilityFilter, setFacilityFilter] = useState<'ALL' | FacilityType>('ALL');
  const [statusFilter, setStatusFilter] = useState<'ALL' | ProjectStatus>('ALL');
  const [scope, setScope] = useState<Scope | null>(null);
  const [sort, setSort] = useState<{ key: SortKey; asc: boolean }>({ key: 'name', asc: true });
  const [projects, setProjects] = useState<Project[] | null>(null);
  const [depts, setDepts] = useState<Department[]>([]);
  const [myTeam, setMyTeam] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([api<Project[]>('/api/projects?scope=company'), getDepartments(), getCompanyUsers()]).then(
      ([ps, ds, us]) => {
        const team = us.find((u) => u.id === sessionUserId())?.teamId ?? null;
        setProjects(ps);
        setDepts(ds);
        setMyTeam(team);
        setScope((s) => s ?? (team ? 'MINE' : 'ALL'));
      },
      (e: Error) => setError(e.message),
    );
  }, []);

  const inScope = useMemo(() => {
    if (scope === 'MINE') return (p: Project) => p.teamId === myTeam;
    if (scope?.startsWith('t:')) return (p: Project) => p.teamId === Number(scope.slice(2));
    if (scope?.startsWith('d:')) {
      const teams = new Set(depts.find((d) => d.id === Number(scope.slice(2)))?.teams.map((t) => t.id));
      return (p: Project) => p.teamId !== null && teams.has(p.teamId);
    }
    return () => true;
  }, [scope, myTeam, depts]);

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    const value = SORT_VALUE[sort.key];
    return (projects ?? [])
      .filter(
        (p) =>
          inScope(p) &&
          (q === '' || p.name.toLowerCase().includes(q) || p.location.toLowerCase().includes(q)) &&
          (facilityFilter === 'ALL' || p.facilityType === facilityFilter) &&
          (statusFilter === 'ALL' || p.status === statusFilter),
      )
      .sort((a, b) => {
        const x = value(a);
        const y = value(b);
        const c = typeof x === 'number' && typeof y === 'number' ? x - y : String(x).localeCompare(String(y), 'ko');
        return sort.asc ? c : -c;
      });
  }, [projects, query, facilityFilter, statusFilter, inScope, sort]);

  const toggleSort = (key: SortKey) => setSort((s) => ({ key, asc: s.key === key ? !s.asc : true }));

  const selectClasses =
    'rounded-lg border border-[#E5E3DC] bg-white px-3 py-2 text-sm text-gray-700 outline-none transition focus:border-[#1F4E79] focus:ring-2 focus:ring-[#1F4E79]/20';

  return (
    <div className="min-h-full bg-[#F8F7F2]">
      <div className="mx-auto max-w-7xl px-4 py-8 sm:px-6">
        {/* Header */}
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h1 className="text-2xl font-bold text-[#1F4E79]">프로젝트 목록</h1>
          <button
            type="button"
            onClick={() => navigate('/projects/new')}
            className="rounded-lg bg-[#1F4E79] px-4 py-2.5 text-sm font-semibold text-white transition hover:bg-[#173a5c] focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/40"
          >
            + 새 프로젝트
          </button>
        </div>

        {/* Search + filters */}
        <div className="mt-6 flex flex-wrap gap-3">
          <input
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="프로젝트명 또는 위치 검색"
            className="min-w-[220px] flex-1 rounded-lg border border-[#E5E3DC] bg-white px-3 py-2 text-sm outline-none transition focus:border-[#1F4E79] focus:ring-2 focus:ring-[#1F4E79]/20"
          />
          <select aria-label="부서/팀" value={scope ?? 'ALL'} onChange={(e) => setScope(e.target.value)} className={selectClasses}>
            {myTeam !== null && <option value="MINE">내 팀</option>}
            <option value="ALL">부서/팀 전체</option>
            {depts.map((d) => (
              <optgroup key={d.id} label={d.name}>
                <option value={`d:${d.id}`}>{d.name} 전체</option>
                {d.teams.map((t) => (
                  <option key={t.id} value={`t:${t.id}`}>{d.name} / {t.name}</option>
                ))}
              </optgroup>
            ))}
          </select>
          <select
            aria-label="시설유형"
            value={facilityFilter}
            onChange={(e) => setFacilityFilter(e.target.value as 'ALL' | FacilityType)}
            className={selectClasses}
          >
            <option value="ALL">시설유형 전체</option>
            {Object.entries(FACILITY_LABEL).map(([value, label]) => (
              <option key={value} value={value}>{label}</option>
            ))}
          </select>
          <select
            aria-label="상태"
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value as 'ALL' | ProjectStatus)}
            className={selectClasses}
          >
            <option value="ALL">상태 전체</option>
            {Object.entries(STATUS_LABEL).map(([value, label]) => (
              <option key={value} value={value}>{label}</option>
            ))}
          </select>
        </div>

        {error ? (
          <p className="mt-16 text-center text-sm text-[#991B1B]">{error}</p>
        ) : projects === null ? (
          <p className="mt-16 text-center text-sm text-gray-400">불러오는 중…</p>
        ) : projects.length === 0 ? (
          <div className="mt-16 flex flex-col items-center justify-center text-center">
            <div className="flex h-24 w-24 items-center justify-center rounded-full bg-[#E5E3DC] text-4xl">📁</div>
            <p className="mt-4 text-sm text-gray-500">등록된 프로젝트가 없습니다</p>
            <button
              type="button"
              onClick={() => navigate('/projects/new')}
              className="mt-4 rounded-lg bg-[#1F4E79] px-4 py-2.5 text-sm font-semibold text-white transition hover:bg-[#173a5c] focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/40"
            >
              첫 프로젝트 추가하기
            </button>
          </div>
        ) : (
          <div className="mt-6 overflow-x-auto rounded-xl border border-[#E5E3DC] bg-white shadow-sm">
            <table className="w-full min-w-[1080px] whitespace-nowrap text-left text-sm">
              <thead className="bg-[#F8F7F2] text-xs text-gray-500">
                <tr>
                  {COLUMNS.map((c) => (
                    <th
                      key={c.key}
                      aria-sort={sort.key === c.key ? (sort.asc ? 'ascending' : 'descending') : 'none'}
                      className={`px-4 py-3 font-medium ${c.className ?? ''}`}
                    >
                      <button type="button" onClick={() => toggleSort(c.key)} className="inline-flex items-center gap-1 hover:text-gray-800">
                        {c.label}
                        <span aria-hidden className={sort.key === c.key ? 'text-[#1F4E79]' : 'text-gray-300'}>
                          {sort.key === c.key && !sort.asc ? '▼' : '▲'}
                        </span>
                      </button>
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-[#E5E3DC]">
                {rows.map((p) => {
                  const progress = Math.round(progressOf(p) * 100);
                  return (
                    <tr
                      key={p.id}
                      onClick={() => navigate(`/projects/${p.id}`)}
                      className="cursor-pointer transition hover:bg-[#F8F7F2]"
                    >
                      <td className="max-w-[260px] px-4 py-3">
                        <Link
                          to={`/projects/${p.id}`}
                          onClick={(e) => e.stopPropagation()}
                          title={p.name}
                          className="block truncate font-medium text-gray-800 hover:text-[#1F4E79] hover:underline"
                        >
                          {p.name}
                        </Link>
                      </td>
                      <td className="px-4 py-3">
                        <span className={`inline-block rounded-full px-2.5 py-0.5 text-xs font-medium ${FACILITY_BADGE[p.facilityType]}`}>
                          {FACILITY_LABEL[p.facilityType]}
                        </span>
                      </td>
                      <Cell text={teamOf(p)} max="max-w-[200px]" />
                      <Cell text={p.pmName ?? ''} max="max-w-[100px]" />
                      <Cell text={p.emName ?? ''} max="max-w-[100px]" />
                      <td className="px-4 py-3">
                        <span className={`inline-block rounded-full px-2.5 py-0.5 text-xs font-medium ${STATUS_BADGE[p.status]}`}>
                          {STATUS_LABEL[p.status]}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex items-center gap-2" title={`${progress}%`}>
                          <div className="h-2 w-24 overflow-hidden rounded-full bg-[#E5E3DC]">
                            <div className="h-full rounded-full bg-[#0F6E56]" style={{ width: `${progress}%` }} />
                          </div>
                          <span className="text-xs text-gray-500">{p.acquiredCount}/{p.permitCount}</span>
                        </div>
                      </td>
                      <td className={`px-4 py-3 text-right ${p.nearExpiryCount ? 'font-semibold text-[#991B1B]' : 'text-gray-400'}`}>
                        {p.nearExpiryCount || '—'}
                      </td>
                      <Cell text={p.location} max="max-w-[240px]" muted />
                    </tr>
                  );
                })}
              </tbody>
            </table>
            {rows.length === 0 && <p className="py-10 text-center text-sm text-gray-400">조건에 맞는 프로젝트가 없습니다</p>}
          </div>
        )}
      </div>
    </div>
  );
}

/** One-line cell: long text is cut with … and shown in full on hover. */
function Cell({ text, max, muted }: { text: string; max: string; muted?: boolean }) {
  return (
    <td className={`${max} px-4 py-3 ${muted ? 'text-gray-500' : 'text-gray-700'}`}>
      <span className="block truncate" title={text || undefined}>{text || '—'}</span>
    </td>
  );
}
