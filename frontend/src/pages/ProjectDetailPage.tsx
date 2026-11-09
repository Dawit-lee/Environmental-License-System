import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router';
import type { FacilityType, PermitCategory, PermitItem, PermitStatus } from '../types';
import PermitDetailDrawer from '../components/PermitDetailDrawer';

// ---------------------------------------------------------------------------
// ProjectDetailPage — project summary + permit table with status/category
// filters. Each row opens the PermitDetailDrawer.
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

const PROJECT = {
  id: 1,
  name: '부산 하수처리장 증설공사',
  facilityType: 'SEWAGE' as FacilityType,
  location: '부산광역시 강서구 명지동 1234',
  capacity: 150,
  capacityUnit: '톤/일',
  startDate: '2026-03-15',
  expectedEndDate: '2027-12-31',
};

// TODO: replace with GET /api/projects/:id/permits.
const PERMITS: PermitItem[] = [
  {
    id: 101, projectId: 1, name: '폐기물처리시설 설치승인', category: 'FACILITY',
    regulation: '폐기물관리법 제29조', regulationUrl: 'https://www.law.go.kr/법령/폐기물관리법',
    authority: '낙동강유역환경청', authorityUrl: 'https://www.me.go.kr',
    reason: '시설용량 100톤/일 이상으로 폐기물관리법 제29조에 따른 설치승인 대상입니다.',
    approvalDate: '착공 전', applicationDate: '2026-02-01', acquisitionDate: '2026-03-01',
    expiryDate: '2031-03-01', status: 'COMPLETED', nextAction: '추가 조치가 필요하지 않습니다.',
    nextActionUrl: 'https://www.me.go.kr', dDay: 640,
  },
  {
    id: 102, projectId: 1, name: '건축허가', category: 'CONSTRUCTION',
    regulation: '건축법 제11조', regulationUrl: 'https://www.law.go.kr/법령/건축법',
    authority: '세움터', authorityUrl: 'https://www.eais.go.kr',
    reason: '30일 이내에 세움터에 건축허가를 신청해야 합니다.',
    approvalDate: '착공 30일 전', applicationDate: '2026-06-15', acquisitionDate: null,
    expiryDate: '2026-06-20', status: 'IN_PROGRESS', nextAction: '30일 이내에 세움터에 건축허가를 신청해야 합니다.',
    nextActionUrl: 'https://www.eais.go.kr', dDay: 22,
  },
  {
    id: 103, projectId: 1, name: '개발행위허가', category: 'LOCATION',
    regulation: '국토의 계획 및 이용에 관한 법률 제56조', regulationUrl: 'https://www.law.go.kr/법령/국토의계획및이용에관한법률',
    authority: '부산광역시 강서구청', authorityUrl: 'https://www.gangseo.go.kr',
    reason: '토지형질변경 면적 5,000㎡ 이상으로 개발행위허가 대상입니다.',
    approvalDate: '착공 전', applicationDate: '2026-01-20', acquisitionDate: '2026-02-25',
    expiryDate: '2028-02-25', status: 'COMPLETED', nextAction: '추가 조치가 필요하지 않습니다.',
    nextActionUrl: 'https://www.gangseo.go.kr', dDay: 270,
  },
  {
    id: 104, projectId: 1, name: '환경영향평가 협의', category: 'ENVIRONMENT',
    regulation: '환경영향평가법 제22조', regulationUrl: 'https://www.law.go.kr/법령/환경영향평가법',
    authority: '낙동강유역환경청', authorityUrl: 'https://www.me.go.kr',
    reason: '처리용량 100톤/일 이상 시설로 환경영향평가 대상사업입니다.',
    approvalDate: '인허가 신청 전', applicationDate: '2025-11-10', acquisitionDate: '2026-01-15',
    expiryDate: null, status: 'COMPLETED', nextAction: '추가 조치가 필요하지 않습니다.',
    nextActionUrl: 'https://www.me.go.kr', dDay: null,
  },
  {
    id: 105, projectId: 1, name: '전기사업 공사계획 신고', category: 'ELECTRICAL',
    regulation: '전기사업법 제61조', regulationUrl: 'https://www.law.go.kr/법령/전기사업법',
    authority: '한국전기안전공사', authorityUrl: 'https://www.kesco.or.kr',
    reason: '수전설비 용량 1,000kW 이상으로 공사계획 신고 대상입니다.',
    approvalDate: '전기공사 착공 전', applicationDate: '2026-06-25', acquisitionDate: null,
    expiryDate: '2026-06-25', status: 'NOT_APPLIED', nextAction: '전기공사 착공 전 공사계획을 신고해야 합니다.',
    nextActionUrl: 'https://www.kesco.or.kr', dDay: 5,
  },
  {
    id: 106, projectId: 1, name: '대기배출시설 설치허가', category: 'FACILITY',
    regulation: '대기환경보전법 제23조', regulationUrl: 'https://www.law.go.kr/법령/대기환경보전법',
    authority: '낙동강유역환경청', authorityUrl: 'https://www.me.go.kr',
    reason: '특정대기유해물질 배출시설에 해당하여 설치허가가 필요합니다.',
    approvalDate: '가동 전', applicationDate: '2026-07-01', acquisitionDate: null,
    expiryDate: '2026-07-20', status: 'IN_PROGRESS', nextAction: '배출시설 설치명세서를 제출해야 합니다.',
    nextActionUrl: 'https://www.me.go.kr', dDay: 50,
  },
  {
    id: 107, projectId: 1, name: '수질오염물질 배출시설 설치허가', category: 'FACILITY',
    regulation: '물환경보전법 제33조', regulationUrl: 'https://www.law.go.kr/법령/물환경보전법',
    authority: '부산광역시청', authorityUrl: 'https://www.busan.go.kr',
    reason: '폐수 배출시설 설치에 따른 허가 대상입니다.',
    approvalDate: '가동 전', applicationDate: '2026-08-01', acquisitionDate: null,
    expiryDate: '2026-08-10', status: 'NOT_APPLIED', nextAction: '배출시설 및 방지시설 설치계획을 제출해야 합니다.',
    nextActionUrl: 'https://www.busan.go.kr', dDay: 73,
  },
  {
    id: 108, projectId: 1, name: '구조안전 확인', category: 'CONSTRUCTION',
    regulation: '건축법 제48조', regulationUrl: 'https://www.law.go.kr/법령/건축법',
    authority: '세움터', authorityUrl: 'https://www.eais.go.kr',
    reason: '지상 2층 이상 건축물로 구조안전 및 내진설계 확인 대상입니다.',
    approvalDate: '건축허가 동시', applicationDate: '2026-06-15', acquisitionDate: null,
    expiryDate: '2026-06-18', status: 'NOT_APPLIED', nextAction: '구조안전확인서를 건축허가와 함께 제출해야 합니다.',
    nextActionUrl: 'https://www.eais.go.kr', dDay: 20,
  },
  {
    id: 109, projectId: 1, name: '국토이용계획 변경', category: 'LOCATION',
    regulation: '국토의 계획 및 이용에 관한 법률 제30조', regulationUrl: 'https://www.law.go.kr/법령/국토의계획및이용에관한법률',
    authority: '부산광역시청', authorityUrl: 'https://www.busan.go.kr',
    reason: '계획관리지역 내 환경기초시설 입지에 따른 용도 변경이 필요합니다.',
    approvalDate: '설계 단계', applicationDate: '2025-10-05', acquisitionDate: '2025-12-20',
    expiryDate: null, status: 'COMPLETED', nextAction: '추가 조치가 필요하지 않습니다.',
    nextActionUrl: 'https://www.busan.go.kr', dDay: null,
  },
  {
    id: 110, projectId: 1, name: '소방시설 착공신고', category: 'CONSTRUCTION',
    regulation: '소방시설공사업법 제13조', regulationUrl: 'https://www.law.go.kr/법령/소방시설공사업법',
    authority: '부산소방재난본부', authorityUrl: 'https://www.busan119.go.kr',
    reason: '연면적 기준 초과로 소방시설 착공신고 대상입니다.',
    approvalDate: '착공 전', applicationDate: '2026-05-25', acquisitionDate: null,
    expiryDate: '2026-07-15', status: 'NEAR_EXPIRY', nextAction: '소방시설 착공신고서를 제출해야 합니다.',
    nextActionUrl: 'https://www.busan119.go.kr', dDay: 47,
  },
];

const CATEGORY_TAB_ORDER: PermitCategory[] = [
  'LOCATION', 'FACILITY', 'CONSTRUCTION', 'ELECTRICAL', 'ENVIRONMENT',
];

export default function ProjectDetailPage() {
  const { id } = useParams();
  const [activeCategory, setActiveCategory] = useState<'ALL' | PermitCategory>('ALL');
  const [statusFilter, setStatusFilter] = useState<'ALL' | PermitStatus>('ALL');
  const [selected, setSelected] = useState<PermitItem | null>(null);

  const stats = useMemo(() => {
    const total = PERMITS.length;
    const completed = PERMITS.filter((p) => p.status === 'COMPLETED').length;
    const nearExpiry = PERMITS.filter(
      (p) => p.status === 'NEAR_EXPIRY' || (p.dDay !== null && p.dDay <= 30 && p.status !== 'COMPLETED'),
    ).length;
    const notApplied = PERMITS.filter((p) => p.status === 'NOT_APPLIED').length;
    return { total, completed, nearExpiry, notApplied };
  }, []);

  const visible = useMemo(
    () =>
      PERMITS.filter(
        (p) =>
          (activeCategory === 'ALL' || p.category === activeCategory) &&
          (statusFilter === 'ALL' || p.status === statusFilter),
      ),
    [activeCategory, statusFilter],
  );

  return (
    <div className="min-h-full bg-[#F8F7F2]">
      <div className="mx-auto max-w-6xl px-4 py-8 sm:px-6">
        {/* Breadcrumb */}
        <nav className="text-sm text-gray-500">
          <Link to="/projects" className="transition hover:text-[#1F4E79] hover:underline">
            프로젝트 목록
          </Link>
          <span className="mx-2 text-gray-300">›</span>
          <span className="text-gray-700">{PROJECT.name}</span>
        </nav>

        {/* Summary card */}
        <section className="mt-4 rounded-xl border border-[#E5E3DC] bg-white p-6 shadow-sm">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <span
                className={`inline-block rounded-full px-2.5 py-0.5 text-xs font-medium ${FACILITY_BADGE[PROJECT.facilityType]}`}
              >
                {FACILITY_LABEL[PROJECT.facilityType]}
              </span>
              <h1 className="mt-2 text-2xl font-bold text-gray-900">{PROJECT.name}</h1>
              <p className="mt-1 text-sm text-gray-500">{PROJECT.location}</p>
            </div>
            <dl className="grid grid-cols-2 gap-x-6 gap-y-1 text-sm">
              <dt className="text-gray-400">시설용량</dt>
              <dd className="text-gray-700">{PROJECT.capacity} {PROJECT.capacityUnit}</dd>
              <dt className="text-gray-400">착공예정일</dt>
              <dd className="text-gray-700">{PROJECT.startDate}</dd>
              <dt className="text-gray-400">준공예정일</dt>
              <dd className="text-gray-700">{PROJECT.expectedEndDate}</dd>
            </dl>
          </div>

          {/* Stats row */}
          <div className="mt-6 grid grid-cols-2 gap-3 sm:grid-cols-4">
            <Stat label="전체 인허가" value={stats.total} tone="text-[#1F4E79]" />
            <Stat label="취득완료" value={stats.completed} tone="text-[#0F6E56]" />
            <Stat label="만료임박" value={stats.nearExpiry} tone="text-[#B45309]" />
            <Stat label="미신청" value={stats.notApplied} tone="text-[#991B1B]" />
          </div>
        </section>

        {/* Tabs + status filter */}
        <div className="mt-6 flex flex-wrap items-center justify-between gap-3">
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
                    <a href={p.regulationUrl} target="_blank" rel="noreferrer"
                      className="inline-flex items-center gap-1 text-[#1F4E79] transition hover:underline focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/30">
                      {p.regulation}<span aria-hidden>↗</span>
                    </a>
                  </td>
                  <td className="px-4 py-3">
                    <a href={p.authorityUrl} target="_blank" rel="noreferrer"
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
      </div>

      {/* Floating add button */}
      <button
        type="button"
        // TODO: open "인허가 추가" flow / POST /api/projects/:id/permits
        className="fixed bottom-6 right-6 z-30 rounded-full bg-[#1F4E79] px-5 py-3 text-sm font-semibold text-white shadow-lg transition hover:bg-[#173a5c] focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/40"
      >
        + 인허가 추가
      </button>

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
