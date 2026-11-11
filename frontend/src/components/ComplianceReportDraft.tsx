import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { PermitItem } from '../types';

// ---------------------------------------------------------------------------
// ComplianceReportDraft — 이행 보고서 초안 (operations-compliance/08), shown in 프로젝트 › 문서.
// Pick a permit item, a period and emission points → 미리보기 (saves nothing) → [XLSX 생성] stores the file as a
// document of that permit item. The preview and the file come from the same server query. 인쇄 = browser print
// of the preview (styles/index.css prints only .print-area).
// ---------------------------------------------------------------------------

type Cell = string | number;
interface Sheet { name: string; note: string; headers: string[]; rows: Cell[][] }
interface Report { fileName: string; from: string; to: string; sheets: Sheet[] }
interface Point { id: number; permitItemId: number | null; name: string }

const INPUT = 'rounded-lg border border-[#E5E3DC] bg-white px-3 py-1.5 text-sm text-gray-700 outline-none focus:border-[#1F4E79] focus:ring-2 focus:ring-[#1F4E79]/20';
const BTN = 'rounded-lg border border-[#E5E3DC] px-3 py-1.5 text-xs font-medium text-gray-700 hover:border-[#1F4E79] hover:text-[#1F4E79] disabled:opacity-40';
/** Only these two are coloured; every other status is plain text. */
const ALERT = new Set<Cell>(['초과', '기한 경과']);

const today = () => new Date().toLocaleDateString('sv-SE');
export const monthsBefore = (iso: string, months: number) => {
  const d = new Date(`${iso}T00:00:00`);
  d.setMonth(d.getMonth() - months);
  return d.toLocaleDateString('sv-SE');
};

export default function ComplianceReportDraft({ projectId, permits, initial, onCreated, onClose }: {
  projectId: number; permits: PermitItem[];
  /** from a 보고·제출 task: its permit item and the cycle before its due date */
  initial: { permitId: number | null; from: string | null; to: string | null };
  onCreated: () => void; onClose: () => void;
}) {
  const [permitId, setPermitId] = useState<number | ''>(initial.permitId ?? permits[0]?.id ?? '');
  const [to, setTo] = useState(initial.to ?? today());
  const [from, setFrom] = useState(initial.from ?? monthsBefore(initial.to ?? today(), 6));
  const [points, setPoints] = useState<Point[]>([]);
  const [excluded, setExcluded] = useState<Set<number>>(new Set());
  const [report, setReport] = useState<Report | null>(null);
  const [saved, setSaved] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api<Point[]>(`/api/projects/${projectId}/emission-points`).then(setPoints, (e: Error) => setError(e.message));
  }, [projectId]);

  const ofPermit = points.filter((p) => p.permitItemId === permitId);
  const included = ofPermit.filter((p) => !excluded.has(p.id)).map((p) => p.id);
  const base = `/api/projects/${projectId}/permit-items/${permitId}/compliance-reports`;
  // any change of the inputs drops the preview, so the file is always what was last previewed
  const edit = (change: () => void) => { change(); setReport(null); setSaved(null); };

  const run = async (work: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await work();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const preview = () => run(async () => {
    const ids = excluded.size ? `&emissionPointIds=${included.join(',')}` : '';
    setReport(await api<Report>(`${base}/preview?from=${from}&to=${to}${ids}`));
  });

  const create = () => run(async () => {
    await api(base, { method: 'POST', body: JSON.stringify({ from, to, emissionPointIds: excluded.size ? included : null }) });
    setSaved(report?.fileName ?? '');
    onCreated();
  });

  return (
    <section className="mt-4 rounded-xl border border-[#E5E3DC] bg-white p-4 shadow-sm">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-gray-800">이행 보고서 초안</h3>
        <button type="button" onClick={onClose} aria-label="닫기" className="text-xl leading-none text-gray-400 hover:text-gray-700">×</button>
      </div>
      <p className="mt-1 text-xs text-gray-500">
        기록된 업무·측정값·조치를 모은 초안입니다. 제출 전 법정 서식과 내용을 확인하세요. 측정 항목·단위는 법령 원문 대조 전 초안입니다.
      </p>
      <div className="mt-3 flex flex-wrap items-end gap-3 text-sm">
        <label className="flex flex-col gap-1 text-xs text-gray-500">인허가 항목
          <select value={permitId} className={`${INPUT} max-w-[280px]`}
            onChange={(e) => edit(() => { setPermitId(Number(e.target.value)); setExcluded(new Set()); })}>
            {permits.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-xs text-gray-500">시작일
          <input type="date" value={from} max={to} onChange={(e) => edit(() => setFrom(e.target.value))} className={INPUT} />
        </label>
        <label className="flex flex-col gap-1 text-xs text-gray-500">종료일
          <input type="date" value={to} min={from} onChange={(e) => edit(() => setTo(e.target.value))} className={INPUT} />
        </label>
        <button type="button" disabled={busy || permitId === '' || !from || !to} onClick={preview} className={BTN}>미리보기</button>
        <button type="button" disabled={busy || !report} onClick={create} className={BTN}>XLSX 생성</button>
        <button type="button" disabled={!report} onClick={() => window.print()} className={BTN}>인쇄</button>
      </div>
      <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-gray-700">
        <span className="text-xs text-gray-500">포함할 배출구</span>
        {ofPermit.map((p) => (
          <label key={p.id} className="flex items-center gap-1.5">
            <input type="checkbox" checked={!excluded.has(p.id)} onChange={() => edit(() => {
              const next = new Set(excluded);
              if (!next.delete(p.id)) next.add(p.id);
              setExcluded(next);
            })} />
            <span className="max-w-[200px] truncate" title={p.name}>{p.name}</span>
          </label>
        ))}
        {ofPermit.length === 0 && <span className="text-xs text-gray-400">이 인허가 항목에 연결된 배출구가 없습니다.</span>}
      </div>
      {error && <p role="alert" className="mt-3 rounded-lg bg-red-50 px-3 py-2 text-sm text-[#991B1B]">{error}</p>}
      {saved !== null && <p role="status" className="mt-3 text-sm text-gray-700">문서 목록에 추가했습니다: {saved}</p>}

      {report && (
        <div className="print-area mt-4 space-y-5">
          {report.sheets.map((s) => (
            <div key={s.name}>
              <h4 className="text-sm font-semibold text-gray-800">{s.name} <span className="font-normal text-gray-400">({s.rows.length})</span></h4>
              <p className="text-xs text-gray-500">{s.note}</p>
              <div className="mt-1.5 overflow-x-auto rounded-lg border border-[#E5E3DC]">
                <table className="w-full whitespace-nowrap text-left text-sm">
                  <thead className="bg-[#F8F7F2] text-xs text-gray-500">
                    <tr>{s.headers.map((h) => <th key={h} className="px-3 py-2 font-medium">{h}</th>)}</tr>
                  </thead>
                  <tbody className="divide-y divide-[#E5E3DC]">
                    {s.rows.map((r, i) => (
                      <tr key={i}>
                        {r.map((c, j) => (
                          <td key={j} title={String(c)}
                            className={`max-w-[260px] truncate px-3 py-1.5 ${ALERT.has(c) ? 'font-semibold text-[#991B1B]' : 'text-gray-700'}`}>{c}</td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
                {s.rows.length === 0 && <p className="py-4 text-center text-xs text-gray-400">기간 안에 기록이 없습니다.</p>}
              </div>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
