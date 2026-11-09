import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import type { FacilityType, Project, ProjectStatus } from '../types';
import { api } from '../api/client';

// ---------------------------------------------------------------------------
// ProjectListPage — searchable, filterable card grid of all projects.
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

export default function ProjectListPage() {
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [facilityFilter, setFacilityFilter] = useState<'ALL' | FacilityType>('ALL');
  const [statusFilter, setStatusFilter] = useState<'ALL' | ProjectStatus>('ALL');
  const [projects, setProjects] = useState<Project[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<Project[]>('/api/projects').then(setProjects, (e: Error) => setError(e.message));
  }, []);

  const filtered = useMemo(
    () =>
      (projects ?? []).filter((p) => {
        const matchesQuery =
          query.trim() === '' ||
          p.name.toLowerCase().includes(query.toLowerCase()) ||
          p.location.toLowerCase().includes(query.toLowerCase());
        const matchesFacility = facilityFilter === 'ALL' || p.facilityType === facilityFilter;
        const matchesStatus = statusFilter === 'ALL' || p.status === statusFilter;
        return matchesQuery && matchesFacility && matchesStatus;
      }),
    [projects, query, facilityFilter, statusFilter],
  );

  const selectClasses =
    'rounded-lg border border-[#E5E3DC] bg-white px-3 py-2 text-sm text-gray-700 outline-none transition focus:border-[#1F4E79] focus:ring-2 focus:ring-[#1F4E79]/20';

  return (
    <div className="min-h-full bg-[#F8F7F2]">
      <div className="mx-auto max-w-6xl px-4 py-8 sm:px-6">
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
          <select
            value={facilityFilter}
            onChange={(e) => setFacilityFilter(e.target.value as 'ALL' | FacilityType)}
            className={selectClasses}
          >
            <option value="ALL">시설유형 전체</option>
            {Object.entries(FACILITY_LABEL).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value as 'ALL' | ProjectStatus)}
            className={selectClasses}
          >
            <option value="ALL">상태 전체</option>
            {Object.entries(STATUS_LABEL).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </div>

        {/* Grid or empty state */}
        {error ? (
          <p className="mt-16 text-center text-sm text-[#991B1B]">{error}</p>
        ) : projects === null ? (
          <p className="mt-16 text-center text-sm text-gray-400">불러오는 중…</p>
        ) : filtered.length === 0 ? (
          <div className="mt-16 flex flex-col items-center justify-center text-center">
            <div className="flex h-24 w-24 items-center justify-center rounded-full bg-[#E5E3DC] text-4xl">
              📁
            </div>
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
          <div className="mt-6 grid grid-cols-1 gap-5 md:grid-cols-2 lg:grid-cols-3">
            {filtered.map((p) => {
              const progress = p.permitCount ? Math.round((p.acquiredCount / p.permitCount) * 100) : 0;
              return (
                <button
                  key={p.id}
                  type="button"
                  onClick={() => navigate(`/projects/${p.id}`)}
                  className="flex flex-col rounded-xl border border-[#E5E3DC] bg-white p-5 text-left shadow-sm transition hover:-translate-y-0.5 hover:border-[#1F4E79]/40 hover:shadow-md focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/30"
                >
                  <div className="flex items-center justify-between">
                    <span
                      className={`inline-block rounded-full px-2.5 py-0.5 text-xs font-medium ${FACILITY_BADGE[p.facilityType]}`}
                    >
                      {FACILITY_LABEL[p.facilityType]}
                    </span>
                    <span
                      className={`inline-block rounded-full px-2.5 py-0.5 text-xs font-medium ${STATUS_BADGE[p.status]}`}
                    >
                      {STATUS_LABEL[p.status]}
                    </span>
                  </div>

                  <h2 className="mt-3 text-base font-bold text-gray-900">{p.name}</h2>
                  <p className="mt-1 text-sm text-gray-500">{p.location}</p>

                  {/* Progress */}
                  <div className="mt-4">
                    <div className="flex items-center justify-between text-xs text-gray-500">
                      <span>
                        {p.acquiredCount} / {p.permitCount} 취득완료
                      </span>
                      <span>{progress}%</span>
                    </div>
                    <div className="mt-1.5 h-2 w-full overflow-hidden rounded-full bg-[#E5E3DC]">
                      <div
                        className="h-full rounded-full bg-[#0F6E56] transition-all"
                        style={{ width: `${progress}%` }}
                      />
                    </div>
                  </div>

                  {p.nearExpiryCount > 0 && (
                    <span className="mt-3 inline-flex w-fit items-center gap-1 rounded-full bg-[#991B1B]/10 px-2.5 py-1 text-xs font-medium text-[#991B1B]">
                      ⚠ 만료임박 {p.nearExpiryCount}건
                    </span>
                  )}
                </button>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
