import { useEffect, useState, type FormEvent } from 'react';
import { api } from '../api/client';
import { listProjectObligations, type Obligation } from '../api/revisions';
import type { PermitItem } from '../types';
import MeasurementPaste from './MeasurementPaste';
import MeasurementTrend from './MeasurementTrend';
import PointMeasurements from './PointMeasurements';

// ---------------------------------------------------------------------------
// ProjectOperations — 프로젝트 › 운영: emission points, their permit limits and measured values (operations-compliance F2).
// A limit is never edited: [한도 변경] closes the current row and adds a new one, so history stays.
// Measured values of the selected point: PointMeasurements (typed), MeasurementPaste (pasted), MeasurementTrend (chart).
// ---------------------------------------------------------------------------

type Medium = 'AIR' | 'WATER';
interface Pollutant { code: string; medium: Medium; name: string; unit: string }
interface Point {
  id: number; permitItemId: number | null; permitName: string | null; medium: Medium; name: string;
  active: boolean; needsReviewCount: number;
}
interface Limit {
  id: number; pollutant: string; pollutantName: string; unit: string; limitValue: number; warnRatio: number;
  obligationId: number | null; obligationSummary: string | null; basisText: string | null;
  effectiveFrom: string; effectiveTo: string | null; needsReview: boolean;
}

const MEDIUM: Record<Medium, string> = { AIR: '대기', WATER: '수질' };
const today = () => new Date().toISOString().slice(0, 10);
const INPUT = 'rounded-lg border border-[#E5E3DC] bg-white px-3 py-1.5 text-sm text-gray-700 outline-none focus:border-[#1F4E79] focus:ring-2 focus:ring-[#1F4E79]/20';
const BTN = 'rounded-lg border border-[#E5E3DC] px-3 py-1 text-xs font-medium text-gray-700 hover:border-[#1F4E79] hover:text-[#1F4E79] disabled:opacity-40';
const PRIMARY = 'rounded-lg bg-[#1F4E79] px-4 py-1.5 text-sm font-medium text-white hover:bg-[#173a5c] disabled:opacity-40';

const emptyPoint = { id: null as number | null, name: '', medium: 'AIR' as Medium, permitItemId: '', active: true };
const emptyLimit = () => ({
  replaces: null as Limit | null, pollutant: '', limitValue: '', warnRatio: '0.80', obligationId: '', basisText: '',
  effectiveFrom: today(), effectiveTo: '',
});

export default function ProjectOperations({ projectId, permits }: { projectId: number; permits: PermitItem[] }) {
  const [points, setPoints] = useState<Point[] | null>(null);
  const [pollutants, setPollutants] = useState<Pollutant[]>([]);
  const [obligations, setObligations] = useState<Obligation[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [limits, setLimits] = useState<Limit[]>([]);
  const [showPast, setShowPast] = useState(false);
  const [pointForm, setPointForm] = useState<typeof emptyPoint | null>(null);
  const [limitForm, setLimitForm] = useState<ReturnType<typeof emptyLimit> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [rev, setRev] = useState(0); // bumped when a measurement is saved or deleted: list and trend reload

  const loadPoints = () =>
    api<Point[]>(`/api/projects/${projectId}/emission-points`).then((list) => {
      setPoints(list);
      setSelectedId((cur) => (list.some((p) => p.id === cur) ? cur : list[0]?.id ?? null));
    }, (e: Error) => setError(e.message));
  const loadLimits = (pointId: number | null) =>
    pointId === null ? setLimits([]) : api<Limit[]>(`/api/emission-points/${pointId}/limits`).then(setLimits, (e: Error) => setError(e.message));

  useEffect(() => {
    loadPoints();
    api<Pollutant[]>('/api/pollutants').then(setPollutants, () => {});
    listProjectObligations(projectId).then(setObligations, () => {});
  }, [projectId]);
  useEffect(() => {
    setLimitForm(null);
    loadLimits(selectedId);
  }, [selectedId]);

  /** Runs a change, then reloads both tables (the 확인 필요 count sits on the point row). */
  const run = async (action: () => Promise<unknown>) => {
    setError(null);
    try {
      await action();
      await Promise.all([loadPoints(), loadLimits(selectedId)]);
      return true;
    } catch (e) {
      setError((e as Error).message);
      return false;
    }
  };
  const send = (path: string, method: string, body?: unknown) =>
    api(path, { method, body: body === undefined ? undefined : JSON.stringify(body) });

  if (!points) return <p className="text-sm text-gray-400">{error ?? '불러오는 중…'}</p>;

  const selected = points.find((p) => p.id === selectedId) ?? null;
  const visibleLimits = limits.filter((l) => showPast || !l.effectiveTo || l.effectiveTo >= today());
  const pastCount = limits.length - limits.filter((l) => !l.effectiveTo || l.effectiveTo >= today()).length;

  const savePoint = async (e: FormEvent) => {
    e.preventDefault();
    if (!pointForm) return;
    const permitItemId = pointForm.permitItemId ? Number(pointForm.permitItemId) : null;
    const ok = await run(() => pointForm.id === null
      ? send(`/api/projects/${projectId}/emission-points`, 'POST', { name: pointForm.name, medium: pointForm.medium, permitItemId })
      : send(`/api/emission-points/${pointForm.id}`, 'PATCH', { name: pointForm.name, permitItemId, active: pointForm.active }));
    if (ok) setPointForm(null);
  };

  const saveLimit = async (e: FormEvent) => {
    e.preventDefault();
    if (!limitForm || !selected) return;
    const ok = await run(() => send(`/api/emission-points/${selected.id}/limits`, 'POST', {
      pollutant: limitForm.pollutant,
      limitValue: Number(limitForm.limitValue),
      warnRatio: Number(limitForm.warnRatio),
      obligationId: limitForm.obligationId ? Number(limitForm.obligationId) : null,
      basisText: limitForm.basisText.trim() || null,
      effectiveFrom: limitForm.effectiveFrom,
      effectiveTo: limitForm.effectiveTo || null,
      replacesLimitId: limitForm.replaces?.id ?? null,
    }));
    if (ok) setLimitForm(null);
  };

  const closeLimit = (l: Limit) => {
    const date = window.prompt(`${l.pollutantName} 한도의 유효 종료일 (YYYY-MM-DD)`, today())?.trim();
    if (date) run(() => send(`/api/limits/${l.id}/close`, 'POST', { effectiveTo: date }));
  };

  const formUnit = pollutants.find((p) => p.code === limitForm?.pollutant)?.unit;

  return (
    <div>
      <p className="rounded-lg bg-[#F8F7F2] px-3 py-2 text-sm text-gray-600">
        측정 항목·단위는 초안이며 법령 원문 대조 전입니다. 확정 전까지 항목과 단위가 바뀔 수 있습니다.
      </p>
      {error && <p role="alert" className="mt-3 rounded-lg bg-red-50 px-3 py-2 text-sm text-[#991B1B]">{error}</p>}

      {/* 배출구 */}
      <div className="mt-6 flex items-center justify-between gap-3">
        <h2 className="text-lg font-bold text-[#1F4E79]">배출구·방류구</h2>
        <button type="button" className={BTN} onClick={() => setPointForm({ ...emptyPoint })}>+ 배출구 추가</button>
      </div>

      {pointForm && (
        <form onSubmit={savePoint} className="mt-3 flex flex-wrap items-end gap-3 rounded-xl border border-[#E5E3DC] bg-white p-4">
          <label className="text-xs text-gray-500">이름
            <input required maxLength={100} value={pointForm.name} placeholder="예: 소각로 1호기 굴뚝"
              onChange={(e) => setPointForm({ ...pointForm, name: e.target.value })} className={`${INPUT} mt-1 block w-64`} />
          </label>
          <label className="text-xs text-gray-500">매체
            <select value={pointForm.medium} disabled={pointForm.id !== null} title={pointForm.id !== null ? '매체는 바꿀 수 없습니다' : undefined}
              onChange={(e) => setPointForm({ ...pointForm, medium: e.target.value as Medium })} className={`${INPUT} mt-1 block`}>
              <option value="AIR">대기</option>
              <option value="WATER">수질</option>
            </select>
          </label>
          <label className="text-xs text-gray-500">근거 인허가 항목 (선택)
            <select value={pointForm.permitItemId} onChange={(e) => setPointForm({ ...pointForm, permitItemId: e.target.value })}
              className={`${INPUT} mt-1 block w-64`}>
              <option value="">선택 안 함</option>
              {permits.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select>
          </label>
          {pointForm.id !== null && (
            <label className="flex items-center gap-2 pb-1.5 text-sm text-gray-600">
              <input type="checkbox" checked={pointForm.active} className="accent-[#1F4E79]"
                onChange={(e) => setPointForm({ ...pointForm, active: e.target.checked })} />
              사용 중
            </label>
          )}
          <button type="submit" className={PRIMARY}>{pointForm.id === null ? '추가' : '저장'}</button>
          <button type="button" className={BTN} onClick={() => setPointForm(null)}>취소</button>
        </form>
      )}

      <div className="mt-3 overflow-x-auto rounded-xl border border-[#E5E3DC] bg-white shadow-sm">
        <table className="w-full min-w-[720px] whitespace-nowrap text-left text-sm">
          <thead className="bg-[#F8F7F2] text-xs text-gray-500">
            <tr>
              <th className="px-4 py-3 font-medium">이름</th>
              <th className="px-4 py-3 font-medium">매체</th>
              <th className="px-4 py-3 font-medium">근거 인허가</th>
              <th className="px-4 py-3 font-medium">사용</th>
              <th className="px-4 py-3 font-medium">한도 확인</th>
              <th className="px-4 py-3" />
            </tr>
          </thead>
          <tbody className="divide-y divide-[#E5E3DC]">
            {points.map((p) => (
              <tr key={p.id} onClick={() => setSelectedId(p.id)} aria-selected={p.id === selectedId}
                className={`cursor-pointer ${p.id === selectedId ? 'bg-[#1F4E79]/5' : 'hover:bg-[#F8F7F2]'}`}>
                <td className="max-w-[280px] px-4 py-2.5">
                  <button type="button" title={p.name} className={`block max-w-full truncate font-medium ${p.active ? 'text-gray-800' : 'text-gray-400'}`}>{p.name}</button>
                </td>
                <td className="px-4 py-2.5 text-gray-600">{MEDIUM[p.medium]}</td>
                <td className="max-w-[260px] px-4 py-2.5 text-gray-600"><span className="block truncate" title={p.permitName ?? ''}>{p.permitName ?? '—'}</span></td>
                <td className="px-4 py-2.5 text-gray-600">{p.active ? '사용 중' : '사용 중지'}</td>
                <td className="px-4 py-2.5 text-gray-800">{p.needsReviewCount > 0 ? `확인 필요 ${p.needsReviewCount}건` : '—'}</td>
                <td className="px-4 py-2.5 text-right">
                  <button type="button" className={BTN} onClick={(e) => {
                    e.stopPropagation();
                    setPointForm({ id: p.id, name: p.name, medium: p.medium, permitItemId: p.permitItemId ? String(p.permitItemId) : '', active: p.active });
                  }}>수정</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {points.length === 0 && (
          <p className="py-10 text-center text-sm text-gray-400">등록된 배출구가 없습니다. 굴뚝이나 방류구를 추가하고 항목별 허가 한도를 넣으세요.</p>
        )}
      </div>

      {/* 한도 */}
      {selected && (
        <>
          <div className="mt-8 flex flex-wrap items-center justify-between gap-3">
            <h2 className="text-lg font-bold text-[#1F4E79]">허가 한도 · {selected.name}</h2>
            <div className="flex items-center gap-4">
              {pastCount > 0 && (
                <label className="flex items-center gap-2 text-sm text-gray-600">
                  <input type="checkbox" checked={showPast} onChange={(e) => setShowPast(e.target.checked)} className="accent-[#1F4E79]" />
                  지난 한도 보기 ({pastCount})
                </label>
              )}
              <button type="button" className={BTN} onClick={() => setLimitForm(emptyLimit())}>+ 한도 추가</button>
            </div>
          </div>

          {limitForm && (
            <form onSubmit={saveLimit} className="mt-3 rounded-xl border border-[#E5E3DC] bg-white p-4">
              {limitForm.replaces && (
                <p className="mb-3 text-sm text-gray-600">
                  기존 한도({limitForm.replaces.limitValue} {limitForm.replaces.unit}, {limitForm.replaces.effectiveFrom}~)는 새 시작일 전날로 닫히고 이력에 남습니다.
                </p>
              )}
              {selected.medium === 'AIR' && (
                <p className="mb-3 text-sm text-gray-600">대기 한도는 표준산소농도 보정 후 값 기준으로 입력합니다.</p>
              )}
              <div className="flex flex-wrap items-end gap-3">
                <label className="text-xs text-gray-500">항목
                  <select required value={limitForm.pollutant} disabled={!!limitForm.replaces}
                    onChange={(e) => setLimitForm({ ...limitForm, pollutant: e.target.value })} className={`${INPUT} mt-1 block`}>
                    <option value="">선택</option>
                    {pollutants.filter((p) => p.medium === selected.medium).map((p) => <option key={p.code} value={p.code}>{p.name}</option>)}
                  </select>
                </label>
                <label className="text-xs text-gray-500">한도 ({formUnit ?? '단위'}) 이하
                  <input required type="number" min="0" step="any" value={limitForm.limitValue}
                    onChange={(e) => setLimitForm({ ...limitForm, limitValue: e.target.value })} className={`${INPUT} mt-1 block w-32`} />
                </label>
                <label className="text-xs text-gray-500" title="한도 × 비율 이상이면 근접으로 판정합니다 (0 초과 1 미만)">근접 비율
                  <input required type="number" min="0.01" max="0.99" step="0.01" value={limitForm.warnRatio}
                    onChange={(e) => setLimitForm({ ...limitForm, warnRatio: e.target.value })} className={`${INPUT} mt-1 block w-24`} />
                </label>
                <label className="text-xs text-gray-500">유효 시작일
                  <input required type="date" value={limitForm.effectiveFrom}
                    onChange={(e) => setLimitForm({ ...limitForm, effectiveFrom: e.target.value })} className={`${INPUT} mt-1 block`} />
                </label>
                <label className="text-xs text-gray-500">유효 종료일 (선택)
                  <input type="date" value={limitForm.effectiveTo}
                    onChange={(e) => setLimitForm({ ...limitForm, effectiveTo: e.target.value })} className={`${INPUT} mt-1 block`} />
                </label>
              </div>
              <div className="mt-3 flex flex-wrap items-end gap-3">
                <label className="text-xs text-gray-500">근거 의무사항 (선택)
                  <select value={limitForm.obligationId} onChange={(e) => setLimitForm({ ...limitForm, obligationId: e.target.value })}
                    className={`${INPUT} mt-1 block w-72`}>
                    <option value="">선택 안 함</option>
                    {obligations.filter((o) => o.status === 'ACTIVE' || String(o.id) === limitForm.obligationId)
                      .map((o) => <option key={o.id} value={o.id}>{o.citationKey ? `${o.citationKey} · ` : ''}{o.summary}</option>)}
                  </select>
                </label>
                <label className="grow text-xs text-gray-500">근거 문구 (선택)
                  <input maxLength={500} value={limitForm.basisText} placeholder="예: 허가조건 제4호, 별표 번호"
                    onChange={(e) => setLimitForm({ ...limitForm, basisText: e.target.value })} className={`${INPUT} mt-1 block w-full min-w-[240px]`} />
                </label>
                <button type="submit" className={PRIMARY}>{limitForm.replaces ? '한도 변경' : '추가'}</button>
                <button type="button" className={BTN} onClick={() => setLimitForm(null)}>취소</button>
              </div>
            </form>
          )}

          <div className="mt-3 overflow-x-auto rounded-xl border border-[#E5E3DC] bg-white shadow-sm">
            <table className="w-full min-w-[900px] whitespace-nowrap text-left text-sm">
              <thead className="bg-[#F8F7F2] text-xs text-gray-500">
                <tr>
                  <th className="px-4 py-3 font-medium">항목</th>
                  <th className="px-4 py-3 text-right font-medium">한도</th>
                  <th className="px-4 py-3 font-medium">단위</th>
                  <th className="px-4 py-3 font-medium" title="한도 × 근접 비율 이상이면 근접">근접</th>
                  <th className="px-4 py-3 font-medium">근거</th>
                  <th className="px-4 py-3 font-medium">유효기간</th>
                  <th className="px-4 py-3 font-medium">상태</th>
                  <th className="px-4 py-3" />
                </tr>
              </thead>
              <tbody className="divide-y divide-[#E5E3DC]">
                {visibleLimits.map((l) => {
                  const basis = [l.obligationSummary, l.basisText].filter(Boolean).join(' · ');
                  const open = !l.effectiveTo;
                  return (
                    <tr key={l.id} className={open || l.effectiveTo! >= today() ? '' : 'text-gray-400'}>
                      <td className="px-4 py-2.5 font-medium text-gray-800">{l.pollutantName}</td>
                      <td className="px-4 py-2.5 text-right tabular-nums text-gray-800">{l.limitValue}</td>
                      <td className="px-4 py-2.5 text-gray-600">{l.unit}</td>
                      <td className="px-4 py-2.5 tabular-nums text-gray-600">{Math.round(l.warnRatio * 100)}% ({+(l.limitValue * l.warnRatio).toFixed(4)})</td>
                      <td className="max-w-[300px] px-4 py-2.5 text-gray-600"><span className="block truncate" title={basis}>{basis || '—'}</span></td>
                      <td className="px-4 py-2.5 text-gray-600">{l.effectiveFrom} ~ {l.effectiveTo ?? ''}</td>
                      <td className="px-4 py-2.5 text-gray-800">{l.needsReview ? '한도 확인 필요' : open ? '적용 중' : '종료'}</td>
                      <td className="space-x-2 px-4 py-2.5 text-right">
                        {l.needsReview && (
                          <button type="button" className={BTN} title="개정된 조건을 확인했고 한도는 그대로입니다"
                            onClick={() => run(() => send(`/api/limits/${l.id}/reviewed`, 'POST'))}>확인</button>
                        )}
                        {open && (
                          <>
                            <button type="button" className={BTN} onClick={() => setLimitForm({
                              ...emptyLimit(), replaces: l, pollutant: l.pollutant, warnRatio: String(l.warnRatio),
                              obligationId: l.obligationId ? String(l.obligationId) : '', basisText: l.basisText ?? '',
                            })}>한도 변경</button>
                            <button type="button" className={BTN} onClick={() => closeLimit(l)}>닫기</button>
                          </>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            {visibleLimits.length === 0 && (
              <p className="py-10 text-center text-sm text-gray-400">등록된 한도가 없습니다. 허가증의 항목별 배출 한도를 추가하세요.</p>
            )}
          </div>

          <PointMeasurements projectId={projectId} point={selected} limits={limits} rev={rev} onChanged={() => setRev((n) => n + 1)} />
          <MeasurementPaste point={selected} onSaved={() => setRev((n) => n + 1)} />
          <MeasurementTrend point={selected} pollutants={pollutants} limits={limits} rev={rev} />
        </>
      )}
    </div>
  );
}
