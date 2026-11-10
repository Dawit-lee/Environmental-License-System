import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router';
import { api, sessionUserId } from '../api/client';
import { getCompanyUsers, getDepartments, type CompanyUser, type Department } from '../api/org';
import {
  closedBySystem,
  derive,
  listPermitSuggestions,
  missingInputs,
  saveAttributes,
  type Attributes,
  type DerivationResult,
  type JudgementRow,
  type MissingInput,
  type PermitSuggestion,
  type RuleCategory,
} from '../api/derivation';
import { acceptSuggestion, editSuggestion, rejectSuggestion } from '../api/review';
import type { FacilityType, Project, ProjectStatus } from '../types';

// ---------------------------------------------------------------------------
// NewProjectPage — two-step wizard.
//   Step 1: capture project attributes (field dictionary keys) and create the project.
//   Step 2: the rule engine's judgement (POST /api/projects/{id}/derivations).
//           APPLICABLE / ON_HOLD rows are PENDING suggestions; approve/reject them right here.
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

/** Attributes the step-1 form owns (see baseAttributes). */
const BASE_FIELDS = ['facility_type', 'capacity_tpd', 'sido', 'address', 'proponent', 'project_kind',
  'existing_capacity_tpd', 'waste_types', 'construction_start'];

const law = (name: string) => `https://www.law.go.kr/법령/${name.replace(/\s/g, '')}`;
const input =
  'w-full rounded-lg border border-[#E5E3DC] bg-white px-3 py-2 text-sm outline-none transition focus:border-[#1F4E79] focus:ring-2 focus:ring-[#1F4E79]/20';

/**
 * New project wizard. With `projectId` it is the project's 설정 tab: the same form prefilled from the project and its
 * attributes, saving with PUT and re-running the derivation (step 2) when the facility type supports it.
 */
export default function NewProjectPage({ projectId: editId, onSaved }: { projectId?: number; onSaved?: () => void } = {}) {
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
  const [expectedEndDate, setExpectedEndDate] = useState('');
  const [status, setStatus] = useState<ProjectStatus>('IN_PROGRESS');
  const [saved, setSaved] = useState(false);
  const [depts, setDepts] = useState<Department[]>([]);
  const [people, setPeople] = useState<CompanyUser[]>([]);
  const [teamId, setTeamId] = useState<number | null>(null);
  const [pmUserId, setPmUserId] = useState<number | null>(null);
  const [emUserId, setEmUserId] = useState<number | null>(null);

  useEffect(() => {
    // defaults: my team, me as PM
    Promise.all([getDepartments(), getCompanyUsers()]).then(([ds, us]) => {
      setDepts(ds);
      setPeople(us);
      if (editId) return;
      const me = us.find((u) => u.id === sessionUserId());
      setTeamId((t) => t ?? me?.teamId ?? null);
      setPmUserId((u) => u ?? me?.id ?? null);
    }, () => {});
  }, [editId]);

  useEffect(() => {
    if (!editId) return;
    api<Project>(`/api/projects/${editId}`).then((p) => {
      const a = (p.attributes ?? {}) as Attributes;
      setProjectId(p.id);
      // keep earlier "추가 정보 필요" answers; the form's own fields come from baseAttributes() on save
      setExtra(Object.fromEntries(Object.entries(a).filter(([k]) => !BASE_FIELDS.includes(k))));
      setName(p.name);
      setFacilityType(p.facilityType);
      setSido(typeof a.sido === 'string' ? a.sido : '');
      setAddress(typeof a.address === 'string' ? a.address : p.location);
      if (typeof a.proponent === 'string') setProponent(a.proponent);
      if (a.project_kind === 'EXPANSION') setProjectKind('EXPANSION');
      if (a.existing_capacity_tpd != null) setExistingCapacity(String(a.existing_capacity_tpd));
      if (Array.isArray(a.waste_types)) setWasteTypes(a.waste_types);
      setCapacity(p.capacity == null ? '' : String(p.capacity));
      setStartDate(p.startDate ?? '');
      setExpectedEndDate(p.expectedEndDate ?? '');
      setStatus(p.status);
      setTeamId(p.teamId);
      setPmUserId(p.pmUserId);
      setEmUserId(p.emUserId);
    }, (e: Error) => setError(e.message));
  }, [editId]);

  // ---- Step 2 state ------------------------------------------------------
  const [projectId, setProjectId] = useState<number | null>(null);
  const [extra, setExtra] = useState<Attributes>({});
  const [result, setResult] = useState<DerivationResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [activeCategory, setActiveCategory] = useState<'ALL' | RuleCategory>('ALL');
  const [showNotApplicable, setShowNotApplicable] = useState(false);
  const [suggestions, setSuggestions] = useState<PermitSuggestion[]>([]);
  const [missing, setMissing] = useState<MissingInput[]>([]);
  const [permitNames, setPermitNames] = useState<Set<string>>(new Set());
  const [showClosed, setShowClosed] = useState(false);

  const rows = result?.results ?? [];
  const derived = rows.filter((r) => r.determination === 'APPLICABLE' || r.determination === 'ON_HOLD');
  const notApplicable = rows.filter((r) => r.determination === 'NOT_APPLICABLE');

  // "추가 정보 필요": pending ON_HOLD suggestions grouped by the field they wait for (server-side)
  const waiting = useMemo(
    () => Object.fromEntries(missing.map((m) => [m.field, { label: m.label, permits: m.rules }])),
    [missing],
  );
  // latest suggestion per rule: what the review cell acts on
  const byRule = useMemo(() => {
    const acc: Record<string, PermitSuggestion> = {};
    for (const s of suggestions) {
      if (!closedBySystem(s) && (!acc[s.payload.rule_key] || acc[s.payload.rule_key].id < s.id)) acc[s.payload.rule_key] = s;
    }
    return acc;
  }, [suggestions]);
  const closed = suggestions.filter(closedBySystem);

  const canSave = name.trim().length > 0 && address.trim() !== '';
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

  const reload = async (pid: number) => {
    const [s, m, permits] = await Promise.all([
      listPermitSuggestions(pid),
      missingInputs(pid),
      api<{ name: string }[]>(`/api/projects/${pid}/permits`),
    ]);
    setSuggestions(s);
    setMissing(m);
    setPermitNames(new Set(permits.map((p) => p.name)));
  };

  // the server applies the re-run policy: unchanged suggestions stay, changed ones are replaced
  const run = async (pid: number, attrs: Attributes) => {
    await saveAttributes(pid, attrs);
    setResult(await derive(pid));
    await reload(pid);
  };

  const act = async (fn: () => Promise<unknown>) => {
    if (!projectId) return;
    setBusy(true);
    setError(null);
    try {
      await fn();
      await reload(projectId);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const approve = (s: PermitSuggestion) =>
    act(async () => {
      const p = s.editedPayload ?? s.payload;
      if (p.determination !== 'ON_HOLD') return acceptSuggestion(s, null);
      // ON_HOLD can't be approved as is: the reviewer judges it applicable and says why (EDITED_ACCEPTED)
      const why = window.prompt('해당으로 판단한 근거를 입력하세요.')?.trim();
      if (!why) return;
      const edited = await editSuggestion(s, { ...p, determination: 'APPLICABLE', hold_info: null, reason: why });
      return acceptSuggestion(edited, null);
    });

  const reject = (s: PermitSuggestion) => {
    const why = window.prompt('거절 사유')?.trim();
    if (why) act(() => rejectSuggestion(s, why));
  };

  const handleNext = async (derivation = true) => {
    setBusy(true);
    setError(null);
    setSaved(false);
    try {
      const body = JSON.stringify({
        name: name.trim(),
        facilityType,
        location: sido ? `${sido} ${address.trim()}` : address.trim(),
        capacity: capacity === '' ? null : Number(capacity),
        capacityUnit: '톤/일',
        startDate: startDate || null,
        expectedEndDate: expectedEndDate || null,
        status,
        teamId,
        pmUserId,
        emUserId,
      });
      const pid =
        projectId ??
        (await api<{ id: number }>('/api/projects', { method: 'POST', body })).id;
      if (projectId) await api(`/api/projects/${pid}`, { method: 'PUT', body });
      setProjectId(pid);
      onSaved?.();
      if (!derivation) return setSaved(true);
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
    <div className={editId ? '' : 'min-h-full bg-[#F8F7F2]'}>
      <div className={editId ? 'max-w-5xl' : 'mx-auto max-w-5xl px-4 py-8 sm:px-6'}>
        {/* Step indicator */}
        <ol className="mb-8 flex items-center gap-4 text-sm">
          <StepDot index={1} label={editId ? '프로젝트 정보·도출 조건' : '프로젝트 정보 입력'} active={step >= 1} current={step === 1} />
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
            <h1 className="text-2xl font-bold text-[#1F4E79]">{editId ? '프로젝트 정보' : '프로젝트 정보 입력'}</h1>
            <p className="mt-1 text-sm text-gray-500">
              {editId
                ? '정보를 고친 뒤 저장합니다. 사업 속성을 바꿨다면 인허가를 다시 도출하세요. 바뀐 판정만 검토 목록에 새로 올라갑니다.'
                : '사업 속성을 입력하면 다음 단계에서 필요한 인허가가 자동으로 도출됩니다.'}
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

              {editId && (
                <>
                  <Field label="준공예정일">
                    <input type="date" value={expectedEndDate} onChange={(e) => setExpectedEndDate(e.target.value)} className={input} />
                  </Field>
                  <Field label="진행 상태">
                    <select value={status} onChange={(e) => setStatus(e.target.value as ProjectStatus)} className={input}>
                      <option value="IN_PROGRESS">진행중</option>
                      <option value="COMPLETED">완료</option>
                      <option value="ON_HOLD">보류</option>
                    </select>
                  </Field>
                </>
              )}

              <Field label="부서/팀">
                <select value={teamId ?? ''} onChange={(e) => setTeamId(e.target.value ? Number(e.target.value) : null)} className={input}>
                  <option value="">미지정</option>
                  {depts.map((d) => (
                    <optgroup key={d.id} label={d.name}>
                      {d.teams.map((t) => <option key={t.id} value={t.id}>{d.name} / {t.name}</option>)}
                    </optgroup>
                  ))}
                </select>
              </Field>

              <div className="grid grid-cols-2 gap-3">
                <Field label="PM">
                  <PersonSelect people={people} value={pmUserId} onChange={setPmUserId} />
                </Field>
                <Field label="EM">
                  <PersonSelect people={people} value={emUserId} onChange={setEmUserId} />
                </Field>
              </div>
            </div>

            <div className="mt-8 flex items-center justify-end gap-3">
              {saved && <span role="status" className="text-sm text-[#0F6E56]">저장했습니다</span>}
              {editId && (
                <button
                  type="button"
                  disabled={!canSave || busy}
                  onClick={() => handleNext(false)}
                  className="rounded-lg border border-[#E5E3DC] px-5 py-2.5 text-sm font-semibold text-gray-700 transition hover:bg-[#F8F7F2] disabled:cursor-not-allowed disabled:opacity-40"
                >
                  저장
                </button>
              )}
              <button
                type="button"
                disabled={!canProceed || busy}
                onClick={() => handleNext()}
                className="rounded-lg bg-[#1F4E79] px-5 py-2.5 text-sm font-semibold text-white transition hover:bg-[#173a5c] focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/40 disabled:cursor-not-allowed disabled:opacity-40"
              >
                {busy ? '처리 중…' : editId ? '저장 후 인허가 다시 도출 →' : '다음: 인허가 자동 도출 →'}
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

            {result?.warnings?.map((w) => (
              <p key={w.rule_key} className="mt-2 rounded-lg bg-amber-50 px-3 py-2 text-sm text-[#B45309]">
                {w.permit_name}: {w.message} (등록된 항목은 그대로 둡니다)
              </p>
            ))}

            {/* Inputs the on-hold rows are waiting for */}
            {Object.keys(waiting).length > 0 && (
              <div className="mt-4 space-y-2">
                <p className="text-sm font-semibold text-gray-700">추가 정보 필요</p>
                {Object.entries(waiting).map(([k, w]) => {
                  const m = missing.find((x) => x.field === k)!;
                  const cfg = EXTRA_INPUT[k] ?? {
                    unit: m.unit,
                    placeholder: '',
                    options: m.type === 'ENUM' ? Object.entries(m.values).map(([value, label]) => ({ value, label })) : undefined,
                  };
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
                    <th className="px-4 py-3 font-medium">검토</th>
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
                        {p.required_by_text && (
                          <span className="block text-xs text-gray-400">
                            {p.required_by_text}
                            {byRule[p.rule_key]?.payload.required_by_date && ` (${byRule[p.rule_key].payload.required_by_date})`}
                          </span>
                        )}
                      </td>
                      <td className="px-4 py-3">
                        <ReviewCell
                          s={byRule[p.rule_key]}
                          duplicate={permitNames.has(p.permit_name)}
                          busy={busy}
                          onApprove={approve}
                          onReject={reject}
                        />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* Suggestions the re-run replaced or closed (SYSTEM), with the reason */}
            {closed.length > 0 && (
              <div className="mt-4">
                <button
                  type="button"
                  aria-expanded={showClosed}
                  onClick={() => setShowClosed((v) => !v)}
                  className="text-sm font-medium text-[#1F4E79] underline-offset-2 hover:underline focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/30"
                >
                  {showClosed ? '재실행으로 정리된 제안 접기' : `재실행으로 정리된 제안 ${closed.length}건 보기`}
                </button>
                {showClosed && (
                  <ul className="mt-2 divide-y divide-[#E5E3DC] rounded-lg border border-[#E5E3DC] text-sm">
                    {closed.map((s) => (
                      <li key={s.id} className="flex flex-wrap items-baseline gap-x-3 gap-y-1 px-4 py-2.5">
                        <span className="font-medium text-gray-500">{s.payload.permit_name}</span>
                        <span className="text-gray-600">시스템 · {s.rejectReason}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            )}

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
                onClick={() => (editId ? setStep(1) : navigate(`/projects/${projectId}`))}
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

function ReviewCell({
  s,
  duplicate,
  busy,
  onApprove,
  onReject,
}: {
  s: PermitSuggestion | undefined;
  duplicate: boolean;
  busy: boolean;
  onApprove: (s: PermitSuggestion) => void;
  onReject: (s: PermitSuggestion) => void;
}) {
  if (!s) return <span className="text-gray-400">—</span>;
  if (s.status === 'ACCEPTED' || s.status === 'EDITED_ACCEPTED') return <span className="text-[#0F6E56]">승인됨</span>;
  if (s.status === 'REJECTED') return <span className="text-gray-500" title={s.rejectReason ?? ''}>거절됨</span>;
  const onHold = (s.editedPayload ?? s.payload).determination === 'ON_HOLD';
  const btn =
    'rounded-md border px-2 py-1 text-xs font-medium transition disabled:opacity-50 focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/30';
  return (
    <div className="flex flex-col gap-1">
      <div className="flex gap-1 whitespace-nowrap">
        <button type="button" disabled={busy} onClick={() => onApprove(s)} className={`${btn} border-[#0F6E56] text-[#0F6E56] hover:bg-emerald-50`}>
          {onHold ? '해당으로 승인' : '승인'}
        </button>
        <button type="button" disabled={busy} onClick={() => onReject(s)} className={`${btn} border-[#E5E3DC] text-gray-600 hover:bg-[#F8F7F2]`}>
          거절
        </button>
      </div>
      {s.status === 'SUBMITTED' && <span className="text-xs text-gray-500">결재 중</span>}
      {s.payload.warning && <span className="text-xs text-[#B45309]">{s.payload.warning}</span>}
      {duplicate && <span className="text-xs text-[#B45309]">중복 가능: 같은 이름의 항목이 있습니다</span>}
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

function PersonSelect({ people, value, onChange }: {
  people: CompanyUser[]; value: number | null; onChange: (id: number | null) => void;
}) {
  return (
    <select value={value ?? ''} onChange={(e) => onChange(e.target.value ? Number(e.target.value) : null)} className={input}>
      <option value="">미지정</option>
      {people.map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}
    </select>
  );
}
