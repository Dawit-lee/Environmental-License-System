import { useMemo, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router';
import type { FacilityType, PermitCategory } from '../types';

// ---------------------------------------------------------------------------
// NewProjectPage — two-step wizard.
//   Step 1: capture project attributes.
//   Step 2: preview the permits auto-derived from those attributes.
// No API calls — derivation is mocked locally.
// ---------------------------------------------------------------------------

const FACILITY_OPTIONS: { value: FacilityType; label: string }[] = [
  { value: 'INCINERATION', label: '소각시설' },
  { value: 'LANDFILL', label: '매립시설' },
  { value: 'BIOGAS', label: '통합바이오가스' },
  { value: 'SEWAGE', label: '하수처리' },
  { value: 'WASTEWATER', label: '폐수처리' },
];

const WASTE_OPTIONS = ['생활폐기물', '사업장폐기물', '음식물류폐기물', '하수슬러지'];

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

interface DerivedPermit {
  id: number;
  name: string;
  category: PermitCategory;
  reason: string;
  regulation: string;
  regulationUrl: string;
  approvalTiming: string;
}

// TODO: replace with POST /api/projects/derive-permits using the form payload.
const DERIVED_PERMITS: DerivedPermit[] = [
  {
    id: 1,
    name: '폐기물처리시설 설치승인',
    category: 'FACILITY',
    reason: '시설용량 100톤/일 이상으로 설치승인 대상',
    regulation: '폐기물관리법 제29조',
    regulationUrl: 'https://www.law.go.kr/법령/폐기물관리법',
    approvalTiming: '착공 전',
  },
  {
    id: 2,
    name: '국토이용계획 변경',
    category: 'LOCATION',
    reason: '계획관리지역 내 환경기초시설 입지에 따른 용도 변경 필요',
    regulation: '국토의 계획 및 이용에 관한 법률 제30조',
    regulationUrl: 'https://www.law.go.kr/법령/국토의계획및이용에관한법률',
    approvalTiming: '설계 단계',
  },
  {
    id: 3,
    name: '개발행위허가',
    category: 'LOCATION',
    reason: '부지조성을 위한 토지형질변경 면적 5,000㎡ 이상',
    regulation: '국토의 계획 및 이용에 관한 법률 제56조',
    regulationUrl: 'https://www.law.go.kr/법령/국토의계획및이용에관한법률',
    approvalTiming: '착공 전',
  },
  {
    id: 4,
    name: '환경영향평가 협의',
    category: 'ENVIRONMENT',
    reason: '소각용량 100톤/일 이상 시설로 환경영향평가 대상사업',
    regulation: '환경영향평가법 제22조',
    regulationUrl: 'https://www.law.go.kr/법령/환경영향평가법',
    approvalTiming: '인허가 신청 전',
  },
  {
    id: 5,
    name: '건축허가',
    category: 'CONSTRUCTION',
    reason: '관리동·처리동 등 건축물 신축에 따른 허가 대상',
    regulation: '건축법 제11조',
    regulationUrl: 'https://www.law.go.kr/법령/건축법',
    approvalTiming: '착공 30일 전',
  },
  {
    id: 6,
    name: '구조안전 확인',
    category: 'CONSTRUCTION',
    reason: '지상 2층 이상 건축물로 구조안전 및 내진설계 확인 대상',
    regulation: '건축법 제48조',
    regulationUrl: 'https://www.law.go.kr/법령/건축법',
    approvalTiming: '건축허가 동시',
  },
  {
    id: 7,
    name: '전기사업 공사계획 신고',
    category: 'ELECTRICAL',
    reason: '수전설비 용량 1,000kW 이상 전기설비 설치',
    regulation: '전기사업법 제61조',
    regulationUrl: 'https://www.law.go.kr/법령/전기사업법',
    approvalTiming: '전기공사 착공 전',
  },
  {
    id: 8,
    name: '대기배출시설 설치허가',
    category: 'FACILITY',
    reason: '소각 과정에서 발생하는 특정대기유해물질 배출시설 해당',
    regulation: '대기환경보전법 제23조',
    regulationUrl: 'https://www.law.go.kr/법령/대기환경보전법',
    approvalTiming: '가동 전',
  },
];

const CATEGORY_TAB_ORDER: PermitCategory[] = [
  'LOCATION',
  'FACILITY',
  'CONSTRUCTION',
  'ELECTRICAL',
  'ENVIRONMENT',
];

export default function NewProjectPage() {
  const navigate = useNavigate();
  const [step, setStep] = useState<1 | 2>(1);

  // ---- Step 1 form state -------------------------------------------------
  const [name, setName] = useState('');
  const [facilityType, setFacilityType] = useState<FacilityType>('INCINERATION');
  const [location, setLocation] = useState('');
  const [wasteTypes, setWasteTypes] = useState<string[]>([]);
  const [capacity, setCapacity] = useState('');
  const [startDate, setStartDate] = useState('');
  const [floorsBelow, setFloorsBelow] = useState('');
  const [floorsAbove, setFloorsAbove] = useState('');
  const [includesElectrical, setIncludesElectrical] = useState(true);

  // ---- Step 2 state ------------------------------------------------------
  const [activeCategory, setActiveCategory] = useState<'ALL' | PermitCategory>('ALL');
  const [included, setIncluded] = useState<Record<number, boolean>>(
    () => Object.fromEntries(DERIVED_PERMITS.map((p) => [p.id, true])),
  );

  const canProceed = name.trim().length > 0;

  const toggleWaste = (waste: string) =>
    setWasteTypes((prev) =>
      prev.includes(waste) ? prev.filter((w) => w !== waste) : [...prev, waste],
    );

  const counts = useMemo(() => {
    const byCat = CATEGORY_TAB_ORDER.reduce<Record<string, number>>((acc, cat) => {
      acc[cat] = DERIVED_PERMITS.filter((p) => p.category === cat).length;
      return acc;
    }, {});
    return { all: DERIVED_PERMITS.length, ...byCat };
  }, []);

  const visiblePermits =
    activeCategory === 'ALL'
      ? DERIVED_PERMITS
      : DERIVED_PERMITS.filter((p) => p.category === activeCategory);

  const includedCount = Object.values(included).filter(Boolean).length;

  const handleCreate = () => {
    // TODO: POST /api/projects with form payload + selected permit ids.
    navigate('/projects');
  };

  return (
    <div className="min-h-full bg-[#F8F7F2]">
      <div className="mx-auto max-w-5xl px-4 py-8 sm:px-6">
        {/* Step indicator */}
        <ol className="mb-8 flex items-center gap-4 text-sm">
          <StepDot index={1} label="프로젝트 정보 입력" active={step >= 1} current={step === 1} />
          <li className="h-px flex-1 bg-[#E5E3DC]" aria-hidden />
          <StepDot index={2} label="인허가 자동 도출" active={step >= 2} current={step === 2} />
        </ol>

        {step === 1 ? (
          <section className="rounded-xl border border-[#E5E3DC] bg-white p-6 shadow-sm sm:p-8">
            <h1 className="text-2xl font-bold text-[#1F4E79]">프로젝트 정보 입력</h1>
            <p className="mt-1 text-sm text-gray-500">
              사업 속성을 입력하면 다음 단계에서 필요한 인허가가 자동으로 도출됩니다.
            </p>

            <div className="mt-6 grid grid-cols-1 gap-6 md:grid-cols-2">
              {/* 프로젝트명 */}
              <Field label="프로젝트명" required className="md:col-span-2">
                <input
                  type="text"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="예: 부산 하수처리장 증설공사"
                  className="w-full rounded-lg border border-[#E5E3DC] px-3 py-2 text-sm outline-none transition focus:border-[#1F4E79] focus:ring-2 focus:ring-[#1F4E79]/20"
                />
              </Field>

              {/* 시설유형 */}
              <Field label="시설유형">
                <select
                  value={facilityType}
                  onChange={(e) => setFacilityType(e.target.value as FacilityType)}
                  className="w-full rounded-lg border border-[#E5E3DC] bg-white px-3 py-2 text-sm outline-none transition focus:border-[#1F4E79] focus:ring-2 focus:ring-[#1F4E79]/20"
                >
                  {FACILITY_OPTIONS.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </Field>

              {/* 사업부지 위치 */}
              <Field label="사업부지 위치">
                <input
                  type="text"
                  value={location}
                  onChange={(e) => setLocation(e.target.value)}
                  placeholder="지번 또는 도로명 주소"
                  className="w-full rounded-lg border border-[#E5E3DC] px-3 py-2 text-sm outline-none transition focus:border-[#1F4E79] focus:ring-2 focus:ring-[#1F4E79]/20"
                />
              </Field>

              {/* 처리대상폐기물 */}
              <Field label="처리대상폐기물" className="md:col-span-2">
                <div className="flex flex-wrap gap-2">
                  {WASTE_OPTIONS.map((waste) => {
                    const checked = wasteTypes.includes(waste);
                    return (
                      <label
                        key={waste}
                        className={`flex cursor-pointer items-center gap-2 rounded-lg border px-3 py-2 text-sm transition hover:border-[#1F4E79] focus-within:ring-2 focus-within:ring-[#1F4E79]/20 ${
                          checked
                            ? 'border-[#1F4E79] bg-[#1F4E79]/5 text-[#1F4E79]'
                            : 'border-[#E5E3DC] text-gray-700'
                        }`}
                      >
                        <input
                          type="checkbox"
                          checked={checked}
                          onChange={() => toggleWaste(waste)}
                          className="h-4 w-4 accent-[#1F4E79]"
                        />
                        {waste}
                      </label>
                    );
                  })}
                </div>
              </Field>

              {/* 시설용량 */}
              <Field label="시설용량">
                <div className="flex items-center gap-2">
                  <input
                    type="number"
                    value={capacity}
                    onChange={(e) => setCapacity(e.target.value)}
                    placeholder="0"
                    className="w-full rounded-lg border border-[#E5E3DC] px-3 py-2 text-sm outline-none transition focus:border-[#1F4E79] focus:ring-2 focus:ring-[#1F4E79]/20"
                  />
                  <span className="shrink-0 text-sm text-gray-500">톤/일</span>
                </div>
                <p className="mt-1 text-xs text-[#B45309]">100톤/일 이상 시 설치승인 대상</p>
              </Field>

              {/* 착공예정일 */}
              <Field label="착공예정일">
                <input
                  type="date"
                  value={startDate}
                  onChange={(e) => setStartDate(e.target.value)}
                  className="w-full rounded-lg border border-[#E5E3DC] px-3 py-2 text-sm outline-none transition focus:border-[#1F4E79] focus:ring-2 focus:ring-[#1F4E79]/20"
                />
              </Field>

              {/* 공사규모 */}
              <Field label="공사규모" className="md:col-span-2">
                <div className="flex flex-wrap items-center gap-4">
                  <div className="flex items-center gap-2">
                    <span className="text-sm text-gray-500">지하</span>
                    <input
                      type="number"
                      value={floorsBelow}
                      onChange={(e) => setFloorsBelow(e.target.value)}
                      placeholder="0"
                      className="w-20 rounded-lg border border-[#E5E3DC] px-3 py-2 text-sm outline-none transition focus:border-[#1F4E79] focus:ring-2 focus:ring-[#1F4E79]/20"
                    />
                    <span className="text-sm text-gray-500">층</span>
                  </div>
                  <div className="flex items-center gap-2">
                    <span className="text-sm text-gray-500">지상</span>
                    <input
                      type="number"
                      value={floorsAbove}
                      onChange={(e) => setFloorsAbove(e.target.value)}
                      placeholder="0"
                      className="w-20 rounded-lg border border-[#E5E3DC] px-3 py-2 text-sm outline-none transition focus:border-[#1F4E79] focus:ring-2 focus:ring-[#1F4E79]/20"
                    />
                    <span className="text-sm text-gray-500">층</span>
                  </div>
                  <label className="flex cursor-pointer items-center gap-2 text-sm text-gray-700">
                    <input
                      type="checkbox"
                      checked={includesElectrical}
                      onChange={(e) => setIncludesElectrical(e.target.checked)}
                      className="h-4 w-4 accent-[#1F4E79]"
                    />
                    전기공사 포함
                  </label>
                </div>
              </Field>
            </div>

            <div className="mt-8 flex justify-end">
              <button
                type="button"
                disabled={!canProceed}
                onClick={() => setStep(2)}
                className="rounded-lg bg-[#1F4E79] px-5 py-2.5 text-sm font-semibold text-white transition hover:bg-[#173a5c] focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/40 disabled:cursor-not-allowed disabled:opacity-40"
              >
                다음: 인허가 자동 도출 →
              </button>
            </div>
          </section>
        ) : (
          <section className="rounded-xl border border-[#E5E3DC] bg-white p-6 shadow-sm sm:p-8">
            <h1 className="text-2xl font-bold text-[#1F4E79]">인허가 자동 도출</h1>
            <p className="mt-1 text-sm text-gray-500">
              입력하신 정보를 바탕으로 필요한 인허가를 분석했습니다
            </p>

            {/* Summary bar */}
            <div className="mt-5 flex flex-wrap items-center justify-between gap-3 rounded-lg bg-[#1F4E79]/5 px-4 py-3">
              <p className="text-sm font-semibold text-[#1F4E79]">
                총 {counts.all}개 인허가가 도출되었습니다
              </p>
              <p className="text-sm text-gray-600">
                포함 <span className="font-semibold text-[#0F6E56]">{includedCount}</span>개 선택됨
              </p>
            </div>

            {/* Category tabs */}
            <div className="mt-5 flex flex-wrap gap-2">
              <CategoryTab
                label={`전체(${counts.all})`}
                active={activeCategory === 'ALL'}
                onClick={() => setActiveCategory('ALL')}
              />
              {CATEGORY_TAB_ORDER.map((cat) => (
                <CategoryTab
                  key={cat}
                  label={`${CATEGORY_LABEL[cat]}(${counts[cat]})`}
                  active={activeCategory === cat}
                  onClick={() => setActiveCategory(cat)}
                />
              ))}
            </div>

            {/* Table */}
            <div className="mt-4 overflow-x-auto rounded-lg border border-[#E5E3DC]">
              <table className="w-full min-w-[760px] text-left text-sm">
                <thead className="bg-[#F8F7F2] text-xs uppercase text-gray-500">
                  <tr>
                    <th className="px-4 py-3 font-medium">인허가명</th>
                    <th className="px-4 py-3 font-medium">카테고리</th>
                    <th className="px-4 py-3 font-medium">해당 사유</th>
                    <th className="px-4 py-3 font-medium">관련 법규</th>
                    <th className="px-4 py-3 font-medium">승인 시점</th>
                    <th className="px-4 py-3 text-center font-medium">포함 여부</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[#E5E3DC]">
                  {visiblePermits.map((p) => (
                    <tr key={p.id} className="transition hover:bg-[#F8F7F2]">
                      <td className="px-4 py-3 font-medium text-gray-800">{p.name}</td>
                      <td className="px-4 py-3">
                        <span
                          className={`inline-block rounded-full px-2.5 py-0.5 text-xs font-medium ${CATEGORY_BADGE[p.category]}`}
                        >
                          {CATEGORY_LABEL[p.category]}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-gray-600">{p.reason}</td>
                      <td className="px-4 py-3">
                        <a
                          href={p.regulationUrl}
                          target="_blank"
                          rel="noreferrer"
                          className="text-[#1F4E79] underline-offset-2 transition hover:underline focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/30"
                        >
                          {p.regulation}
                        </a>
                      </td>
                      <td className="px-4 py-3 text-gray-600">{p.approvalTiming}</td>
                      <td className="px-4 py-3 text-center">
                        <Toggle
                          checked={included[p.id]}
                          onChange={(v) => setIncluded((prev) => ({ ...prev, [p.id]: v }))}
                        />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div className="mt-8 flex items-center justify-between">
              <button
                type="button"
                onClick={() => setStep(1)}
                className="rounded-lg border border-[#E5E3DC] px-5 py-2.5 text-sm font-semibold text-gray-700 transition hover:bg-[#F8F7F2] focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/20"
              >
                ← 이전
              </button>
              <button
                type="button"
                onClick={handleCreate}
                className="rounded-lg bg-[#1F4E79] px-5 py-2.5 text-sm font-semibold text-white transition hover:bg-[#173a5c] focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/40"
              >
                프로젝트 생성
              </button>
            </div>
          </section>
        )}
      </div>
    </div>
  );
}

// --- Local presentational helpers ------------------------------------------

function StepDot({
  index,
  label,
  active,
  current,
}: {
  index: number;
  label: string;
  active: boolean;
  current: boolean;
}) {
  return (
    <li className="flex items-center gap-2">
      <span
        className={`flex h-7 w-7 items-center justify-center rounded-full text-sm font-semibold transition ${
          active ? 'bg-[#1F4E79] text-white' : 'bg-[#E5E3DC] text-gray-500'
        }`}
      >
        {index}
      </span>
      <span
        className={`font-medium ${current ? 'text-[#1F4E79]' : active ? 'text-gray-700' : 'text-gray-400'}`}
      >
        {label}
      </span>
    </li>
  );
}

function Field({
  label,
  required,
  className,
  children,
}: {
  label: string;
  required?: boolean;
  className?: string;
  children: ReactNode;
}) {
  return (
    <div className={className}>
      <label className="mb-1.5 block text-sm font-medium text-gray-700">
        {label}
        {required && <span className="ml-0.5 text-[#991B1B]">*</span>}
      </label>
      {children}
    </div>
  );
}

function CategoryTab({
  label,
  active,
  onClick,
}: {
  label: string;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`rounded-full px-3.5 py-1.5 text-sm font-medium transition focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/30 ${
        active
          ? 'bg-[#1F4E79] text-white'
          : 'bg-[#F8F7F2] text-gray-600 hover:bg-[#E5E3DC]'
      }`}
    >
      {label}
    </button>
  );
}

function Toggle({
  checked,
  onChange,
}: {
  checked: boolean;
  onChange: (value: boolean) => void;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      onClick={() => onChange(!checked)}
      className={`relative inline-flex h-6 w-11 items-center rounded-full transition focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/40 ${
        checked ? 'bg-[#1F4E79]' : 'bg-gray-300'
      }`}
    >
      <span
        className={`inline-block h-4 w-4 transform rounded-full bg-white shadow transition ${
          checked ? 'translate-x-6' : 'translate-x-1'
        }`}
      />
    </button>
  );
}
