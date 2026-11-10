import { useMemo, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router';
import { api } from '../api/client';
import {
  derive,
  saveAttributes,
  type Attributes,
  type DerivationResult,
  type JudgementRow,
  type RuleCategory,
} from '../api/derivation';
import type { FacilityType } from '../types';

// ---------------------------------------------------------------------------
// NewProjectPage — two-step wizard.
//   Step 1: capture project attributes (field dictionary keys) and create the project.
//   Step 2: the rule engine's judgement (POST /api/projects/{id}/derivations).
//           APPLICABLE / ON_HOLD rows are now PENDING suggestions in the review pipeline.
// ---------------------------------------------------------------------------

const FACILITY_OPTIONS: { value: FacilityType; label: string }[] = [
  { value: 'INCINERATION', label: '소각시설' },
  { value: 'LANDFILL', label: '매립시설' },
  { value: 'BIOGAS', label: '통합바이오가스' },
  { value: 'SEWAGE', label: '하수처리' },
  { value: 'WASTEWATER', label: '폐수처리' },
];

const SIDO = ['서울', '부산', '대구', '인천', '광주', '대전', '울산', '세종', '경기', '강원', '충북', '충남', '전북', '전남', '경북', '경남', '제주'];
const PROPONENTS = [
  { value: 'MUNICIPAL', label: '지자체' },
  { value: 'REGIONAL', label: '광역' },
  { value: 'PRIVATE', label: '민간' },
];
const WASTE_OPTIONS = [
  { value: 'MUNICIPAL_SOLID', label: '생활폐기물' },
  { value: 'INDUSTRIAL', label: '사업장폐기물' },
  { value: 'FOOD', label: '음식물류폐기물' },
  { value: 'SEWAGE_SLUDGE', label: '하수슬러지' },
];

const CATEGORY_LABEL: Record<RuleCategory, string> = { SITE: '입지', FACILITY: '시설', GENERAL: '일반' };
const CATEGORY_BADGE: Record<RuleCategory, string> = {
  SITE: 'bg-amber-100 text-amber-800',
  FACILITY: 'bg-sky-100 text-sky-800',
  GENERAL: 'bg-violet-100 text-violet-800',
};
const CATEGORY_TAB_ORDER: RuleCategory[] = ['SITE', 'FACILITY', 'GENERAL'];

// How the "추가 정보 필요" panel asks for a missing field. Default: plain number.
const EXTRA_INPUT: Record<string, { unit: string; placeholder: string; scale?: number; options?: { value: string; label: string }[] }> = {
  site_area_m2: { unit: '㎡', placeholder: '예: 42000' },
  construction_cost_krw: { unit: '억원', placeholder: '예: 1800', scale: 1e8 },
  air_emission_tpy: { unit: '톤/년', placeholder: '예: 35' },
  wastewater_m3pd: { unit: '㎥/일', placeholder: '예: 120' },
  zoning: {
    unit: '',
    placeholder: '',
    options: [
      { value: 'URBAN', label: '도시지역' },
      { value: 'MGMT_CONSERVATION', label: '보전관리지역' },
      { value: 'MGMT_PRODUCTION', label: '생산관리지역' },
      { value: 'MGMT_PLANNING', label: '계획관리지역' },
      { value: 'AGRI_FOREST', label: '농림지역' },
      { value: 'NATURE_CONSERVATION', label: '자연환경보전지역' },
      { value: 'GREENBELT', label: '개발제한구역' },
    ],
  },
};

const law = (name: string) => `https://www.law.go.kr/법령/${name.replace(/\s/g, '')}`;
const input =
  'w-full rounded-lg border border-[#E5E3DC] bg-white px-3 py-2 text-sm outline-none transition focus:border-[#1F4E79] focus:ring-2 focus:ring-[#1F4E79]/20';

export default function NewProjectPage() {
  const navigate = useNavigate();
  const [step, setStep] = useState<1 | 2>(1);

  // ---- Step 1 form state -------------------------------------------------
  const [name, setName] = useState('');
  const [facilityType, setFacilityType] = useState<FacilityType>('INCINERATION');
  const [sido, setSido] = useState('');
  const [address, setAddress] = useState('');
  const [proponent, setProponent] = useState('MUNICIPAL');
  const [projectKind, setProjectKind] = useState<'NEW' | 'EXPANSION'>('NEW');
  const [existingCapacity, setExistingCapacity] = useState('');
  const [wasteTypes, setWasteTypes] = useState<string[]>([]);
  const [capacity, setCapacity] = useState('');
  const [startDate, setStartDate] = useState('');

  // ---- Step 2 state ------------------------------------------------------
  const [projectId, setProjectId] = useState<number | null>(null);
  const [extra, setExtra] = useState<Attributes>({});
  const [result, setResult] = useState<DerivationResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [activeCategory, setActiveCategory] = useState<'ALL' | RuleCategory>('ALL');
  const [showNotApplicable, setShowNotApplicable] = useState(false);

  const rows = result?.results ?? [];
  const derived = rows.filter((r) => r.determination === 'APPLICABLE' || r.determination === 'ON_HOLD');
  const notApplicable = rows.filter((r) => r.determination === 'NOT_APPLICABLE');

  // "추가 정보 필요": ON_HOLD rows grouped by the field they wait for
  const waiting = useMemo(() => {
    const acc: Record<string, { label: string; permits: string[] }> = {};
    for (const r of derived) {
      r.hold_info?.missing_fields.forEach((f, i) => {
        (acc[f] ??= { label: r.hold_info!.missing_inputs[i], permits: [] }).permits.push(r.permit_name);
      });
    }
    return acc;
  }, [derived]);

  const canProceed =
    name.trim().length > 0 &&
    facilityType === 'INCINERATION' &&
    sido !== '' &&
    address.trim() !== '' &&
    capacity !== '' &&
    (projectKind === 'NEW' || existingCapacity !== '');

  const baseAttributes = (): Attributes => ({
    facility_type: facilityType,
    capacity_tpd: Number(capacity),
    sido,
    address: address.trim(),
    proponent,
    project_kind: projectKind,
    ...(projectKind === 'EXPANSION' ? { existing_capacity_tpd: Number(existingCapacity) } : {}),
    ...(wasteTypes.length ? { waste_types: wasteTypes } : {}),
    ...(startDate ? { construction_start: startDate } : {}),
  });

  // shortcut: every run adds a fresh PENDING set; superseding old ones is the re-run policy (permit-derivation/02)
  const run = async (pid: number, attrs: Attributes) => {
    await saveAttributes(pid, attrs);
    setResult(await derive(pid));
  };

  const handleNext = async () => {
    setBusy(true);
    setError(null);
    try {
      const body = JSON.stringify({
        name: name.trim(),
        facilityType,
        location: `${sido} ${address.trim()}`,
        capacity: Number(capacity),
        capacityUnit: '톤/일',
        startDate: startDate || null,
      });
      const pid =
        projectId ??
        (await api<{ id: number }>('/api/projects', { method: 'POST', body })).id;
      if (projectId) await api(`/api/projects/${pid}`, { method: 'PUT', body });
      setProjectId(pid);
      await run(pid, { ...baseAttributes(), ...extra });
      setStep(2);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const fillExtra = async (field: string, value: string | number) => {
    if (!projectId) return;
    const next = { ...extra, [field]: value };
    setExtra(next);
    setBusy(true);
    setError(null);
    try {
      await run(projectId, { ...baseAttributes(), ...next });
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const toggleWaste = (waste: string) =>
    setWasteTypes((prev) =>
      prev.includes(waste) ? prev.filter((w) => w !== waste) : [...prev, waste],
    );

  const counts = useMemo(() => {
    const byCat = CATEGORY_TAB_ORDER.reduce<Record<string, number>>((acc, cat) => {
      acc[cat] = derived.filter((p) => p.category === cat).length;
      return acc;
    }, {});
    return { all: derived.length, ...byCat };
  }, [derived]);

  const visiblePermits =
    activeCategory === 'ALL' ? derived : derived.filter((p) => p.category === activeCategory);

  return (
    <div className="min-h-full bg-[#F8F7F2]">
      <div className="mx-auto max-w-5xl px-4 py-8 sm:px-6">
        {/* Step indicator */}
        <ol className="mb-8 flex items-center gap-4 text-sm">
          <StepDot index={1} label="프로젝트 정보 입력" active={step >= 1} current={step === 1} />
          <li className="h-px flex-1 bg-[#E5E3DC]" aria-hidden />
          <StepDot index={2} label="인허가 자동 도출" active={step >= 2} current={step === 2} />
        </ol>

        {error && (
          <p role="alert" className="mb-4 rounded-lg border border-[#991B1B]/30 bg-red-50 px-4 py-3 text-sm text-[#991B1B]">
            {error}
          </p>
        )}

        {step === 1 ? (
          <section className="rounded-xl border border-[#E5E3DC] bg-white p-6 shadow-sm sm:p-8">
            <h1 className="text-2xl font-bold text-[#1F4E79]">프로젝트 정보 입력</h1>
            <p className="mt-1 text-sm text-gray-500">
              사업 속성을 입력하면 다음 단계에서 필요한 인허가가 자동으로 도출됩니다.
            </p>

            <div className="mt-6 grid grid-cols-1 gap-6 md:grid-cols-2">
              <Field label="프로젝트명" required className="md:col-span-2">
                <input
                  type="text"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="예: 광명시 자원회수시설 설치사업"
                  className={input}
                />
              </Field>

              <Field label="시설유형" required>
                <select value={facilityType} onChange={(e) => setFacilityType(e.target.value as FacilityType)} className={input}>
                  {FACILITY_OPTIONS.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
                {facilityType !== 'INCINERATION' && (
                  <p className="mt-1 text-xs text-[#B45309]">자동 도출은 현재 소각시설만 지원합니다</p>
                )}
              </Field>

              <Field label="추진주체" required>
                <select value={proponent} onChange={(e) => setProponent(e.target.value)} className={input}>
                  {PROPONENTS.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </Field>

              <Field label="사업부지 시·도" required>
                <select value={sido} onChange={(e) => setSido(e.target.value)} className={input}>
                  <option value="">선택</option>
                  {SIDO.map((s) => (
                    <option key={s} value={s}>
                      {s}
                    </option>
                  ))}
                </select>
              </Field>

              <Field label="사업부지 지번" required>
                <input
                  type="text"
                  value={address}
                  onChange={(e) => setAddress(e.target.value)}
                  placeholder="지번 또는 도로명 주소"
                  className={input}
                />
              </Field>

              <Field label="처리대상폐기물" className="md:col-span-2">
                <div className="flex flex-wrap gap-2">
                  {WASTE_OPTIONS.map((waste) => {
                    const checked = wasteTypes.includes(waste.value);
                    return (
                      <label
                        key={waste.value}
                        className={`flex cursor-pointer items-center gap-2 rounded-lg border px-3 py-2 text-sm transition hover:border-[#1F4E79] focus-within:ring-2 focus-within:ring-[#1F4E79]/20 ${
                          checked
                            ? 'border-[#1F4E79] bg-[#1F4E79]/5 text-[#1F4E79]'
                            : 'border-[#E5E3DC] text-gray-700'
                        }`}
                      >
                        <input
                          type="checkbox"
                          checked={checked}
                          onChange={() => toggleWaste(waste.value)}
                          className="h-4 w-4 accent-[#1F4E79]"
                        />
                        {waste.label}
                      </label>
                    );
                  })}
                </div>
              </Field>

              <Field label="사업구분" required>
                <div className="flex gap-4 py-2 text-sm text-gray-700">
                  {(['NEW', 'EXPANSION'] as const).map((k) => (
                    <label key={k} className="flex cursor-pointer items-center gap-2">
                      <input
                        type="radio"
                        name="projectKind"
                        checked={projectKind === k}
                        onChange={() => setProjectKind(k)}
                        className="h-4 w-4 accent-[#1F4E79]"
                      />
                      {k === 'NEW' ? '신설' : '증설'}
                    </label>
                  ))}
                </div>
              </Field>

              <Field label="시설용량" required>
                <div className="flex items-center gap-2">
                  <input type="number" min={0} value={capacity} onChange={(e) => setCapacity(e.target.value)} placeholder="0" className={input} />
                  <span className="shrink-0 text-sm text-gray-500">톤/일</span>
                </div>
                <p className="mt-1 text-xs text-[#B45309]">100톤/일 이상 시 설치승인 대상</p>
              </Field>

              {projectKind === 'EXPANSION' && (
                <Field label="기존 용량" required>
                  <div className="flex items-center gap-2">
                    <input
                      type="number"
                      min={0}
                      value={existingCapacity}
                      onChange={(e) => setExistingCapacity(e.target.value)}
                      placeholder="0"
                      className={input}
                    />
                    <span className="shrink-0 text-sm text-gray-500">톤/일</span>
                  </div>
                  <p className="mt-1 text-xs text-gray-500">증설은 변경승인·변경신고(30% 이상) 판정에 필요합니다</p>
                </Field>
              )}

              <Field label="착공예정일">
                <input type="date" value={startDate} onChange={(e) => setStartDate(e.target.value)} className={input} />
              </Field>
            </div>

            <div className="mt-8 flex justify-end">
              <button
                type="button"
                disabled={!canProceed || busy}
                onClick={handleNext}
                className="rounded-lg bg-[#1F4E79] px-5 py-2.5 text-sm font-semibold text-white transition hover:bg-[#173a5c] focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/40 disabled:cursor-not-allowed disabled:opacity-40"
              >
                {busy ? '도출 중…' : '다음: 인허가 자동 도출 →'}
              </button>
            </div>
          </section>
        ) : (
          <section className="rounded-xl border border-[#E5E3DC] bg-white p-6 shadow-sm sm:p-8">
            <h1 className="text-2xl font-bold text-[#1F4E79]">인허가 자동 도출</h1>
            <p className="mt-1 text-sm text-gray-500">
              규칙표로 판정했습니다. 해당·판정 보류 항목은 검토 목록에 대기 중이며, 승인해야 인허가 항목이 됩니다.
            </p>

            {/* Summary bar */}
            <div className="mt-5 flex flex-wrap items-center justify-between gap-3 rounded-lg bg-[#1F4E79]/5 px-4 py-3">
              <p className="text-sm font-semibold text-[#1F4E79]">
                해당 {result?.applicable ?? 0}개 · 판정 보류 {result?.onHold ?? 0}개 · 해당없음 {result?.notApplicable ?? 0}개
              </p>
              <p className="text-sm text-gray-600">
                ruleset-v{result?.rulesetVersion} · {result && new Date(result.ranAt).toLocaleString('ko-KR')}
              </p>
            </div>
            {!!result?.errors && (
              <p className="mt-2 text-sm text-[#991B1B]">규칙 오류 {result.errors}건 — 해당 규칙은 판정하지 못했습니다.</p>
            )}

            {/* Inputs the on-hold rows are waiting for */}
            {Object.keys(waiting).length > 0 && (
              <div className="mt-4 space-y-2">
                <p className="text-sm font-semibold text-gray-700">추가 정보 필요</p>
                {Object.entries(waiting).map(([k, w]) => {
                  const cfg = EXTRA_INPUT[k] ?? { unit: '', placeholder: '' };
                  return (
                    <div
                      key={k}
                      className="flex flex-wrap items-center gap-x-3 gap-y-2 rounded-lg border border-dashed border-[#B45309]/50 bg-amber-50 px-3 py-2 text-sm"
                    >
                      <label htmlFor={`extra-${k}`} className="w-32 font-medium text-gray-800">
                        {w.label}
                      </label>
                      {cfg.options ? (
                        <select
                          id={`extra-${k}`}
                          disabled={busy}
                          defaultValue=""
                          onChange={(e) => e.target.value && fillExtra(k, e.target.value)}
                          className="w-40 rounded-md border border-[#E5E3DC] bg-white px-2 py-1 text-sm"
                        >
                          <option value="">선택</option>
                          {cfg.options.map((o) => (
                            <option key={o.value} value={o.value}>
                              {o.label}
                            </option>
                          ))}
                        </select>
                      ) : (
                        <input
                          id={`extra-${k}`}
                          type="number"
                          min={0}
                          disabled={busy}
                          placeholder={cfg.placeholder}
                          onKeyDown={(e) => {
                            if (e.key === 'Enter') e.currentTarget.blur();
                          }}
                          onBlur={(e) => {
                            const v = e.currentTarget.value.trim();
                            if (v !== '') fillExtra(k, Number(v) * (cfg.scale ?? 1));
                          }}
                          className="w-32 rounded-md border border-[#E5E3DC] bg-white px-2 py-1 text-sm"
                        />
                      )}
                      <span className="text-gray-500">{cfg.unit}</span>
                      <span className="text-gray-600">
                        → {w.permits.join(', ')} <b>{w.permits.length}건</b> 판정 대기
                      </span>
                    </div>
                  );
                })}
              </div>
            )}

            {/* Category tabs */}
            <div className="mt-5 flex flex-wrap gap-2">
              <CategoryTab label={`전체(${counts.all})`} active={activeCategory === 'ALL'} onClick={() => setActiveCategory('ALL')} />
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
                    <th className="px-4 py-3 font-medium">판정</th>
                    <th className="px-4 py-3 font-medium">인허가명</th>
                    <th className="px-4 py-3 font-medium">카테고리</th>
                    <th className="px-4 py-3 font-medium">해당 사유</th>
                    <th className="px-4 py-3 font-medium">관련 법규</th>
                    <th className="px-4 py-3 font-medium">승인 시점</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[#E5E3DC]">
                  {visiblePermits.map((p) => (
                    <tr key={p.rule_key} className="transition hover:bg-[#F8F7F2]">
                      <td className="px-4 py-3">
                        <span
                          className={`inline-block whitespace-nowrap rounded-full px-2.5 py-0.5 text-xs font-medium ${
                            p.determination === 'ON_HOLD'
                              ? 'bg-amber-100 text-[#B45309]'
                              : 'bg-emerald-100 text-[#0F6E56]'
                          }`}
                        >
                          {p.determination === 'ON_HOLD' ? '판정 보류' : '해당'}
                        </span>
                      </td>
                      <td className="px-4 py-3 font-medium text-gray-800">{p.permit_name}</td>
                      <td className="px-4 py-3">
                        <span className={`inline-block rounded-full px-2.5 py-0.5 text-xs font-medium ${CATEGORY_BADGE[p.category]}`}>
                          {CATEGORY_LABEL[p.category]}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-gray-600">
                        {p.hold_info ? (
                          <>
                            <b className="text-[#B45309]">{p.hold_info.missing_inputs.join(', ')} 필요</b> · {p.hold_info.criterion}
                          </>
                        ) : (
                          p.reason
                        )}
                      </td>
                      <td className="px-4 py-3">
                        <LawLink row={p} />
                      </td>
                      <td className="px-4 py-3 text-gray-600">
                        {p.approval_stage}
                        {p.required_by_text && <span className="block text-xs text-gray-400">{p.required_by_text}</span>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* Not-applicable rows, read-only */}
            {notApplicable.length > 0 && (
              <div className="mt-4">
                <button
                  type="button"
                  aria-expanded={showNotApplicable}
                  onClick={() => setShowNotApplicable((v) => !v)}
                  className="text-sm font-medium text-[#1F4E79] underline-offset-2 hover:underline focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/30"
                >
                  {showNotApplicable ? '해당없음 접기' : `해당없음 ${notApplicable.length}건 보기`}
                </button>
                {showNotApplicable && (
                  <ul className="mt-2 divide-y divide-[#E5E3DC] rounded-lg border border-[#E5E3DC] text-sm">
                    {notApplicable.map((p) => (
                      <li key={p.rule_key} className="flex flex-wrap items-baseline gap-x-3 gap-y-1 px-4 py-2.5">
                        <span className="font-medium text-gray-500">{p.permit_name}</span>
                        <span className="text-gray-600">{p.reason}</span>
                        <span className="ml-auto">
                          <LawLink row={p} />
                        </span>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            )}

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
                onClick={() => navigate(`/projects/${projectId}`)}
                className="rounded-lg bg-[#1F4E79] px-5 py-2.5 text-sm font-semibold text-white transition hover:bg-[#173a5c] focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/40"
              >
                완료
              </button>
            </div>
          </section>
        )}
      </div>
    </div>
  );
}

function LawLink({ row }: { row: JudgementRow }) {
  const name = row.law_names[0];
  const text = row.legal_basis[0] ?? name;
  return name ? (
    <a
      href={law(name)}
      target="_blank"
      rel="noreferrer"
      className="text-[#1F4E79] underline-offset-2 transition hover:underline focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/30"
    >
      {text}
    </a>
  ) : (
    <span className="text-gray-600">{text}</span>
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
