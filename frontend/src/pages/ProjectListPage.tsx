import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import type { FacilityType, Project, ProjectStatus } from '../types';
import { FACILITY_LABEL, PROJECT_STATUS, filterProjects } from './listFilters';
import { Badge } from '../components/ui/Badge';
import { Select, Input } from '../components/ui/Field';
import { Table } from '../components/ui/Table';
import { Alert } from '../components/ui/Alert';
import { cn, focusRing } from '../components/ui/cn';
import { EmptyBlock, FilterChips, SkeletonRows, type ActiveFilter } from '../components/ListParts';
import { api, sessionUserId } from '../api/client';
import { Button } from '../components/ui/Button';
import { getCompanyUsers, getDepartments, type Department } from '../api/org';
import { PageHeader } from '../components/ui/PageHeader';

// ---------------------------------------------------------------------------
// ProjectListPage — company-wide project table: search, facility/status/department/team filters,
// sortable columns, one line per row. Defaults to my team.
// ---------------------------------------------------------------------------

type SortKey = 'name' | 'facility' | 'team' | 'pm' | 'em' | 'status' | 'progress' | 'nearExpiry' | 'location';

const progressOf = (p: Project) => (p.permitCount ? p.acquiredCount / p.permitCount : 0);
const teamOf = (p: Project) => (p.teamName ? `${p.departmentName} / ${p.teamName}` : '');

const SORT_VALUE: Record<SortKey, (p: Project) => string | number> = {
  name: (p) => p.name,
  facility: (p) => FACILITY_LABEL[p.facilityType],
  team: teamOf,
  pm: (p) => p.pmName ?? '',
  em: (p) => p.emName ?? '',
  status: (p) => PROJECT_STATUS[p.status].text,
  progress: progressOf,
  nearExpiry: (p) => p.nearExpiryCount,
  location: (p) => p.location,
};

/** Less important columns: hidden under 1280 px so the key ones fit without scrolling. */
const WIDE = 'hidden xl:table-cell';

// DESIGN.md 목록 화면: name first (link), status badge second; numbers right-aligned.
const COLUMNS: { key: SortKey; label: string; className?: string }[] = [
  { key: 'name', label: '프로젝트명' },
  { key: 'status', label: '상태' },
  { key: 'facility', label: '시설유형' },
  { key: 'team', label: '부서/팀', className: WIDE },
  { key: 'pm', label: 'PM' },
  { key: 'em', label: 'EM', className: WIDE },
  { key: 'progress', label: '취득 진행', className: 'text-right' },
  { key: 'nearExpiry', label: '만료임박', className: 'text-right' },
  { key: 'location', label: '위치', className: WIDE },
];

/** scope filter value: 'ME' (projects I'm a member of), 'MINE' (my team), 'ALL', 'd:<departmentId>' or 't:<teamId>' */
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
  const [myIds, setMyIds] = useState<Set<number>>(new Set());
  const [error, setError] = useState<string | null>(null);
  const [loads, setLoads] = useState(0); // 다시 시도

  useEffect(() => {
    setError(null);
    Promise.all([api<Project[]>('/api/projects?scope=company'), getDepartments(), getCompanyUsers(), api<Project[]>('/api/projects')]).then(
      ([ps, ds, us, mine]) => {
        const team = us.find((u) => u.id === sessionUserId())?.teamId ?? null;
        setProjects(ps);
        setDepts(ds);
        setMyTeam(team);
        setMyIds(new Set(mine.map((p) => p.id)));
        setScope((s) => s ?? (mine.length ? 'ME' : 'ALL'));
      },
      (e: Error) => setError(e.message),
    );
  }, [loads]);

  const inScope = useMemo(() => {
    if (scope === 'ME') return (p: Project) => myIds.has(p.id);
    if (scope === 'MINE') return (p: Project) => p.teamId === myTeam;
    if (scope?.startsWith('t:')) return (p: Project) => p.teamId === Number(scope.slice(2));
    if (scope?.startsWith('d:')) {
      const teams = new Set(depts.find((d) => d.id === Number(scope.slice(2)))?.teams.map((t) => t.id));
      return (p: Project) => p.teamId !== null && teams.has(p.teamId);
    }
    return () => true;
  }, [scope, myTeam, myIds, depts]);

  const rows = useMemo(() => {
    const value = SORT_VALUE[sort.key];
    return filterProjects(projects ?? [], { query, facility: facilityFilter, status: statusFilter, inScope }).sort((a, b) => {
      const x = value(a);
      const y = value(b);
      const c = typeof x === 'number' && typeof y === 'number' ? x - y : String(x).localeCompare(String(y), 'ko');
      return sort.asc ? c : -c;
    });
  }, [projects, query, facilityFilter, statusFilter, inScope, sort]);

  const toggleSort = (key: SortKey) => setSort((s) => ({ key, asc: s.key === key ? !s.asc : true }));

  const scopeLabel = (s: Scope) => {
    if (s === 'ME') return '내 프로젝트';
    if (s === 'MINE') return '내 팀';
    const d = s.startsWith('d:') ? depts.find((x) => x.id === Number(s.slice(2))) : depts.find((x) => x.teams.some((t) => t.id === Number(s.slice(2))));
    if (!d) return s;
    return s.startsWith('d:') ? `${d.name} 전체` : `${d.name} / ${d.teams.find((t) => t.id === Number(s.slice(2)))?.name}`;
  };
  const active: ActiveFilter[] = [
    query.trim() && { key: 'q', label: `검색: ${query.trim()}`, clear: () => setQuery('') },
    scope && scope !== 'ALL' && { key: 'scope', label: `부서/팀: ${scopeLabel(scope)}`, clear: () => setScope('ALL') },
    facilityFilter !== 'ALL' && { key: 'facility', label: `시설유형: ${FACILITY_LABEL[facilityFilter]}`, clear: () => setFacilityFilter('ALL') },
    statusFilter !== 'ALL' && { key: 'status', label: `상태: ${PROJECT_STATUS[statusFilter].text}`, clear: () => setStatusFilter('ALL') },
  ].filter((f): f is ActiveFilter => Boolean(f));
  const clearAll = () => { setQuery(''); setScope('ALL'); setFacilityFilter('ALL'); setStatusFilter('ALL'); };

  const head = (
    <thead>
      <tr>
        {COLUMNS.map((c) => {
          const on = sort.key === c.key;
          return (
            <th key={c.key} aria-sort={on ? (sort.asc ? 'ascending' : 'descending') : 'none'} className={c.className}>
              <button type="button" onClick={() => toggleSort(c.key)} className={cn('group inline-flex items-center gap-1 rounded-sm font-bold hover:text-brand-text', focusRing)}>
                {c.label}
                <span aria-hidden className={on ? 'text-brand-text' : 'invisible group-hover:visible text-body'}>{on && !sort.asc ? '▼' : '▲'}</span>
              </button>
            </th>
          );
        })}
      </tr>
    </thead>
  );

  return (
    <div className="py-8">
      <PageHeader
        title="프로젝트 목록"
        description="회사의 프로젝트와 인허가 취득 진행을 한눈에 봅니다."
        actions={<Button onClick={() => navigate('/projects/new')}>+ 새 프로젝트</Button>}
      />

      {/* 필터 줄: 검색 + 자주 쓰는 필터를 한 줄에 */}
      <div className="mt-8 grid gap-3 sm:grid-cols-2 lg:grid-cols-[minmax(220px,2fr)_repeat(3,minmax(0,1fr))]">
        <Input label="검색" type="search" value={query} onChange={(e) => setQuery(e.target.value)} placeholder="프로젝트명 또는 위치" />
        <Select label="부서/팀" value={scope ?? 'ME'} onChange={(e) => setScope(e.target.value)}>
          <option value="ME">내 프로젝트</option>
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
        </Select>
        <Select label="시설유형" value={facilityFilter} onChange={(e) => setFacilityFilter(e.target.value as 'ALL' | FacilityType)}>
          <option value="ALL">전체</option>
          {Object.entries(FACILITY_LABEL).map(([value, label]) => (
            <option key={value} value={value}>{label}</option>
          ))}
        </Select>
        <Select label="상태" value={statusFilter} onChange={(e) => setStatusFilter(e.target.value as 'ALL' | ProjectStatus)}>
          <option value="ALL">전체</option>
          {Object.entries(PROJECT_STATUS).map(([value, s]) => (
            <option key={value} value={value}>{s.text}</option>
          ))}
        </Select>
      </div>
      <FilterChips filters={active} onClearAll={clearAll} />

      <div className="mt-6">
        {error ? (
          <Alert tone="danger" title="프로젝트 목록을 불러오지 못했습니다.">
            <p>{error}</p>
            <Button variant="tertiary" size="sm" className="mt-3" onClick={() => setLoads((n) => n + 1)}>다시 시도</Button>
          </Alert>
        ) : projects === null ? (
          <Table density="compact" caption="프로젝트 목록 불러오는 중" className="min-w-[720px] whitespace-nowrap">
            {head}
            <SkeletonRows cols={COLUMNS.length} />
          </Table>
        ) : projects.length === 0 ? (
          <EmptyBlock
            title="등록된 프로젝트가 없습니다."
            description="프로젝트를 만들면 필요한 인허가가 자동으로 채워집니다."
            action={<Button onClick={() => navigate('/projects/new')}>새 프로젝트 만들기</Button>}
          />
        ) : rows.length === 0 ? (
          <EmptyBlock title="조건에 맞는 항목이 없습니다." action={<Button variant="tertiary" onClick={clearAll}>필터 모두 지우기</Button>} />
        ) : (
          <>
            {/* 도구 막대: 결과 건수 */}
            <p className="mb-2 text-[15px] text-body" aria-live="polite"><span className="tabular-nums font-bold text-ink">{rows.length}</span>건</p>
            <Table density="compact" caption="프로젝트 목록" className="min-w-[720px] whitespace-nowrap xl:min-w-[1080px]">
              {head}
              <tbody>
                {rows.map((p) => {
                  const progress = Math.round(progressOf(p) * 100);
                  const status = PROJECT_STATUS[p.status];
                  return (
                    <tr key={p.id} onClick={() => navigate(`/projects/${p.id}`)} className="cursor-pointer">
                      <td className="max-w-[220px] xl:max-w-[260px]">
                        <Link
                          to={`/projects/${p.id}`}
                          onClick={(e) => e.stopPropagation()}
                          title={p.name}
                          className={cn('block truncate rounded-sm font-bold text-brand-text hover:underline', focusRing)}
                        >
                          {p.name}
                        </Link>
                      </td>
                      <td><Badge tone={status.tone}>{status.text}</Badge></td>
                      <td className="text-body">{FACILITY_LABEL[p.facilityType]}</td>
                      <Cell text={teamOf(p)} max={`max-w-[200px] ${WIDE}`} />
                      <Cell text={p.pmName ?? ''} max="max-w-[100px]" />
                      <Cell text={p.emName ?? ''} max={`max-w-[100px] ${WIDE}`} />
                      <td className="text-right">
                        <div className="inline-flex items-center gap-2" title={`${progress}%`}>
                          <div className="h-1.5 w-16 overflow-hidden rounded-sm bg-line-soft" aria-hidden>
                            <div className="h-full bg-ok" style={{ width: `${progress}%` }} />
                          </div>
                          <span className="w-12 tabular-nums text-body">{p.acquiredCount}/{p.permitCount}</span>
                        </div>
                      </td>
                      <td className="text-right">
                        {p.nearExpiryCount ? <Badge tone="warning" className="tabular-nums">{p.nearExpiryCount}건</Badge> : <span className="text-mute">—</span>}
                      </td>
                      <Cell text={p.location} max={`max-w-[180px] ${WIDE}`} muted />
                    </tr>
                  );
                })}
              </tbody>
            </Table>
          </>
        )}
      </div>
    </div>
  );
}

/** One-line cell: long text is cut with … and shown in full on hover. */
function Cell({ text, max, muted }: { text: string; max: string; muted?: boolean }) {
  return (
    <td className={`${max} ${muted ? 'text-mute' : 'text-body'}`}>
      <span className="block truncate" title={text || undefined}>{text || '—'}</span>
    </td>
  );
}
