import { useEffect, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router';
import { api } from '../api/client';
import { getTask, type Task } from '../api/tasks';

// ---------------------------------------------------------------------------
// PointMeasurements — 운영 탭 "측정값 입력" of one emission point (operations-compliance/04): one row per limit in
// force on the measured day, judged while typing, plus the point's recent values. The stored judgement is the
// server's; `judge` below repeats the same rule (backend Judgement.java) only for the instant display.
// A value is never edited: [삭제] takes a reason and the value is entered again.
// Opened with ?task=ID from a 측정·조사 task, saved values are linked to that task.
// ---------------------------------------------------------------------------

export type Level = 'NORMAL' | 'NEAR' | 'EXCEEDED' | 'NO_LIMIT';
export const LEVEL: Record<Level, string> = { NORMAL: '정상', NEAR: '근접', EXCEEDED: '초과', NO_LIMIT: '한도 없음' };
/** Only 초과 is coloured; the other levels are plain text. */
export const levelClass = (l: Level) => (l === 'EXCEEDED' ? 'font-semibold text-[#991B1B]' : 'text-gray-800');

/** Same rule as the server. toPrecision drops binary noise (0.1 × 0.8 = 0.08000000000000002) so the line matches the server's decimals. */
export function judge(value: number, limit: number | null, warnRatio: number, belowDetection: boolean): Level {
  if (limit === null) return 'NO_LIMIT';
  if (belowDetection) return 'NORMAL';
  if (value > limit) return 'EXCEEDED';
  return value >= Number((limit * warnRatio).toPrecision(12)) ? 'NEAR' : 'NORMAL';
}
const percent = (value: number, limit: number) => `${Math.round((value / limit) * 1000) / 10}%`;

export interface PointLimit {
  id: number; pollutant: string; pollutantName: string; unit: string; limitValue: number; warnRatio: number;
  effectiveFrom: string; effectiveTo: string | null;
}
interface Measurement {
  id: number; pollutant: string; pollutantName: string; measuredOn: string; value: number; unit: string;
  belowDetection: boolean; taskId: number | null; limitValue: number | null; warnRatio: number | null; level: Level;
  enteredByName: string | null; enteredAt: string;
}

const RECENT = 20;
const today = () => new Date().toLocaleDateString('sv-SE');
const INPUT = 'rounded-lg border border-[#E5E3DC] bg-white px-3 py-1.5 text-sm text-gray-700 outline-none focus:border-[#1F4E79] focus:ring-2 focus:ring-[#1F4E79]/20';
const BTN = 'rounded-lg border border-[#E5E3DC] px-3 py-1 text-xs font-medium text-gray-700 hover:border-[#1F4E79] hover:text-[#1F4E79] disabled:opacity-40';
const PRIMARY = 'rounded-lg bg-[#1F4E79] px-4 py-1.5 text-sm font-medium text-white hover:bg-[#173a5c] disabled:opacity-40';

export default function PointMeasurements({ projectId, point, limits }: {
  projectId: number; point: { id: number; name: string; medium: 'AIR' | 'WATER' }; limits: PointLimit[];
}) {
  const [params, setParams] = useSearchParams();
  const taskId = Number(params.get('task')) || null;
  const [task, setTask] = useState<Task | null>(null);
  const [date, setDate] = useState(today());
  const [entries, setEntries] = useState<Record<string, { value: string; nd: boolean }>>({});
  const [recent, setRecent] = useState<Measurement[]>([]);
  const [saved, setSaved] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = () => api<Measurement[]>(`/api/emission-points/${point.id}/measurements`).then(setRecent, (e: Error) => setError(e.message));
  useEffect(() => {
    setEntries({});
    setSaved(null);
    setError(null);
    load();
  }, [point.id]);
  useEffect(() => {
    setTask(null);
    if (taskId) getTask(taskId).then((d) => setTask(d.task), () => setError('연결할 업무를 찾을 수 없습니다.'));
  }, [taskId]);

  const rows = limits.filter((l) => l.effectiveFrom <= date && (!l.effectiveTo || l.effectiveTo >= date));
  const filled = rows.filter((l) => entries[l.pollutant]?.value.trim());
  const linkTask = task?.status === 'OPEN' ? task : null;

  const save = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setSaved(null);
    try {
      const out = await api<Measurement[]>(`/api/emission-points/${point.id}/measurements`, {
        method: 'POST',
        body: JSON.stringify({
          source: 'MANUAL',
          taskId: linkTask?.id ?? null,
          rows: filled.map((l) => ({
            measuredOn: date, pollutant: l.pollutant, value: Number(entries[l.pollutant].value), unit: l.unit,
            belowDetection: entries[l.pollutant].nd,
          })),
        }),
      });
      const over = out.filter((m) => m.level === 'EXCEEDED').length;
      setSaved(`${out.length}건 저장했습니다.${over ? ` 초과 ${over}건.` : ''}${linkTask ? ' 업무에 연결되어 완료 조건을 채웁니다.' : ''}`);
      setEntries({});
      await load();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const remove = async (m: Measurement) => {
    const reason = window.prompt(`${m.measuredOn} ${m.pollutantName} ${m.value} ${m.unit} 삭제 사유 (필수). 수정하려면 삭제 후 다시 입력하세요.`)?.trim();
    if (!reason) return;
    setError(null);
    try {
      await api(`/api/measurements/${m.id}`, { method: 'DELETE', body: JSON.stringify({ reason }) });
      await load();
    } catch (err) {
      setError((err as Error).message);
    }
  };

  return (
    <>
      <h2 className="mt-8 text-lg font-bold text-[#1F4E79]">측정값 입력 · {point.name}</h2>
      {taskId && task && (
        <p className="mt-3 flex flex-wrap items-center gap-3 rounded-lg bg-[#F8F7F2] px-3 py-2 text-sm text-gray-700">
          <span className="min-w-0 truncate" title={task.title}>
            {linkTask ? `업무 "${task.title}" (기한 ${task.dueDate})에 연결해 저장합니다.` : `업무 "${task.title}"은 이미 닫혀 연결하지 않습니다.`}
          </span>
          <Link to={`/projects/${projectId}/obligations`} className="shrink-0 text-[#1F4E79] hover:underline">의무사항 탭으로</Link>
          <button type="button" className={BTN} onClick={() => setParams({}, { replace: true })}>연결 해제</button>
        </p>
      )}
      {error && <p role="alert" className="mt-3 rounded-lg bg-red-50 px-3 py-2 text-sm text-[#991B1B]">{error}</p>}
      {saved && <p role="status" className="mt-3 rounded-lg bg-[#F8F7F2] px-3 py-2 text-sm text-gray-700">{saved}</p>}

      <form onSubmit={save} className="mt-3">
        <div className="flex flex-wrap items-end gap-3">
          <label className="text-xs text-gray-500">측정일 (시료 채취일)
            <input required type="date" value={date} onChange={(e) => setDate(e.target.value)} className={`${INPUT} mt-1 block`} />
          </label>
          <p className="pb-1.5 text-sm text-gray-500">
            측정일에 유효한 한도 항목이 행으로 나옵니다.{point.medium === 'AIR' ? ' 대기 농도는 표준산소농도 보정 후 값을 넣습니다.' : ''}
          </p>
        </div>
        <div className="mt-3 overflow-x-auto rounded-xl border border-[#E5E3DC] bg-white shadow-sm">
          <table className="w-full min-w-[820px] whitespace-nowrap text-left text-sm">
            <thead className="bg-[#F8F7F2] text-xs text-gray-500">
              <tr>
                <th className="px-4 py-3 font-medium">항목</th>
                <th className="px-4 py-3 text-right font-medium">한도</th>
                <th className="px-4 py-3 font-medium">단위</th>
                <th className="px-4 py-3 font-medium">측정값</th>
                <th className="px-4 py-3 font-medium" title="불검출이면 값에 정량한계를 넣습니다. 판정은 정상입니다.">불검출</th>
                <th className="px-4 py-3 text-right font-medium">한도 대비</th>
                <th className="px-4 py-3 font-medium">판정</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-[#E5E3DC]">
              {rows.map((l) => {
                const en = entries[l.pollutant] ?? { value: '', nd: false };
                const value = en.value.trim() === '' ? null : Number(en.value);
                const level = value === null || Number.isNaN(value) ? null : judge(value, l.limitValue, l.warnRatio, en.nd);
                const set = (patch: Partial<typeof en>) => setEntries({ ...entries, [l.pollutant]: { ...en, ...patch } });
                return (
                  <tr key={l.id}>
                    <td className="px-4 py-2 font-medium text-gray-800">{l.pollutantName}</td>
                    <td className="px-4 py-2 text-right tabular-nums text-gray-600" title={`근접 ${Math.round(l.warnRatio * 100)}% 이상`}>{l.limitValue}</td>
                    <td className="px-4 py-2 text-gray-600">{l.unit}</td>
                    <td className="px-4 py-2">
                      <input type="number" min="0" step="any" value={en.value} aria-label={`${l.pollutantName} 측정값`}
                        onChange={(e) => set({ value: e.target.value })} className={`${INPUT} w-32 py-1 text-right tabular-nums`} />
                    </td>
                    <td className="px-4 py-2">
                      <input type="checkbox" checked={en.nd} aria-label={`${l.pollutantName} 불검출`} className="accent-[#1F4E79]"
                        onChange={(e) => set({ nd: e.target.checked })} />
                    </td>
                    <td className="px-4 py-2 text-right tabular-nums text-gray-600">{value === null || en.nd ? '—' : percent(value, l.limitValue)}</td>
                    <td className={`px-4 py-2 ${level ? levelClass(level) : 'text-gray-400'}`}>{level ? LEVEL[level] : '—'}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {rows.length === 0 && (
            <p className="py-8 text-center text-sm text-gray-400">{date}에 유효한 한도가 없습니다. 위에서 한도를 먼저 등록하세요.</p>
          )}
        </div>
        <div className="mt-3 flex items-center gap-3">
          <button type="submit" disabled={busy || filled.length === 0} className={PRIMARY}>
            {filled.length > 0 ? `${filled.length}건 저장` : '저장'}
          </button>
          <span className="text-xs text-gray-500">저장하면 판정이 입력 시점 한도와 함께 고정됩니다. 이후 한도가 바뀌어도 그대로입니다.</span>
        </div>
      </form>

      <h3 className="mt-8 text-sm font-semibold text-gray-800">
        최근 측정 {recent.length > RECENT ? `(최근 ${RECENT}건 / 전체 ${recent.length}건)` : `(${recent.length})`}
      </h3>
      <div className="mt-2 overflow-x-auto rounded-xl border border-[#E5E3DC] bg-white shadow-sm">
        <table className="w-full min-w-[900px] whitespace-nowrap text-left text-sm">
          <thead className="bg-[#F8F7F2] text-xs text-gray-500">
            <tr>
              <th className="px-4 py-3 font-medium">측정일</th>
              <th className="px-4 py-3 font-medium">항목</th>
              <th className="px-4 py-3 text-right font-medium">측정값</th>
              <th className="px-4 py-3 font-medium">단위</th>
              <th className="px-4 py-3 text-right font-medium" title="입력 시점에 적용된 한도">한도</th>
              <th className="px-4 py-3 text-right font-medium">한도 대비</th>
              <th className="px-4 py-3 font-medium">판정</th>
              <th className="px-4 py-3 font-medium">입력</th>
              <th className="px-4 py-3" />
            </tr>
          </thead>
          <tbody className="divide-y divide-[#E5E3DC]">
            {/* shortcut: the whole history is fetched and cut here; add from/to when ticket 05 brings the trend view */}
            {recent.slice(0, RECENT).map((m) => (
              <tr key={m.id}>
                <td className="px-4 py-2.5 text-gray-800">{m.measuredOn}</td>
                <td className="px-4 py-2.5 font-medium text-gray-800">{m.pollutantName}</td>
                <td className="px-4 py-2.5 text-right tabular-nums text-gray-800">{m.value}{m.belowDetection ? ' (불검출)' : ''}</td>
                <td className="px-4 py-2.5 text-gray-600">{m.unit}</td>
                <td className="px-4 py-2.5 text-right tabular-nums text-gray-600">{m.limitValue ?? '—'}</td>
                <td className="px-4 py-2.5 text-right tabular-nums text-gray-600">{m.limitValue === null || m.belowDetection ? '—' : percent(m.value, m.limitValue)}</td>
                <td className={`px-4 py-2.5 ${levelClass(m.level)}`}>{LEVEL[m.level]}</td>
                <td className="max-w-[220px] px-4 py-2.5 text-gray-600">
                  <span className="block truncate" title={`${m.enteredByName ?? '—'} · ${new Date(m.enteredAt).toLocaleString('sv-SE')}`}>
                    {m.enteredByName ?? '—'} · {new Date(m.enteredAt).toLocaleDateString('sv-SE')}{m.taskId ? ' · 업무 연결' : ''}
                  </span>
                </td>
                <td className="px-4 py-2.5 text-right"><button type="button" className={BTN} onClick={() => remove(m)}>삭제</button></td>
              </tr>
            ))}
          </tbody>
        </table>
        {recent.length === 0 && <p className="py-8 text-center text-sm text-gray-400">입력된 측정값이 없습니다.</p>}
      </div>
    </>
  );
}
