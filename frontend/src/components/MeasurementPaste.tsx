import { useEffect, useState } from 'react';
import { api } from '../api/client';
import { LEVEL, levelClass, type Level } from './PointMeasurements';

// ---------------------------------------------------------------------------
// MeasurementPaste — 운영 탭 "엑셀 붙여넣기" of one emission point (operations-compliance/05): 측정일·항목·값 copied
// from a sheet → server preview (judgement or error per line, nothing saved) → save without the error lines.
// The server parses the same text again on save, so what is stored never depends on this screen.
// ---------------------------------------------------------------------------

interface PasteRow {
  line: number; raw: string; measuredOn: string | null; pollutantName: string | null; value: number | null;
  unit: string | null; limitValue: number | null; level: Level | null; error: string | null;
}

const INPUT = 'rounded-lg border border-[#E5E3DC] bg-white px-3 py-1.5 text-sm text-gray-700 outline-none focus:border-[#1F4E79] focus:ring-2 focus:ring-[#1F4E79]/20';
const BTN = 'rounded-lg border border-[#E5E3DC] px-3 py-1 text-xs font-medium text-gray-700 hover:border-[#1F4E79] hover:text-[#1F4E79] disabled:opacity-40';
const PRIMARY = 'rounded-lg bg-[#1F4E79] px-4 py-1.5 text-sm font-medium text-white hover:bg-[#173a5c] disabled:opacity-40';

export default function MeasurementPaste({ point, onSaved }: { point: { id: number; name: string }; onSaved: () => void }) {
  const [text, setText] = useState('');
  const [rows, setRows] = useState<PasteRow[] | null>(null);
  const [saved, setSaved] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setText('');
    setRows(null);
    setSaved(null);
    setError(null);
  }, [point.id]);

  const send = async <T,>(path: string, then: (out: T) => void) => {
    setBusy(true);
    setError(null);
    setSaved(null);
    try {
      then(await api<T>(`/api/emission-points/${point.id}/measurements/${path}`, { method: 'POST', body: JSON.stringify({ text }) }));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  const preview = () => send<PasteRow[]>('preview', setRows);
  const save = () => send<{ level: Level }[]>('paste', (out) => {
    const over = out.filter((m) => m.level === 'EXCEEDED').length;
    setSaved(`${out.length}건 저장했습니다.${over ? ` 초과 ${over}건.` : ''}`);
    setText('');
    setRows(null);
    onSaved();
  });

  const good = rows?.filter((r) => !r.error).length ?? 0;
  const bad = (rows?.length ?? 0) - good;

  return (
    <>
      <h2 className="mt-8 text-lg font-bold text-[#1F4E79]">엑셀 붙여넣기 · {point.name}</h2>
      <p className="mt-2 text-sm text-gray-500">
        엑셀에서 측정일·항목·값 3열을 복사해 붙여넣습니다. 첫 행이 제목이면 건너뜁니다. 항목은 코드(CO)나 이름(일산화탄소) 모두 되고, 최대 500행입니다.
      </p>
      {error && <p role="alert" className="mt-3 rounded-lg bg-red-50 px-3 py-2 text-sm text-[#991B1B]">{error}</p>}
      {saved && <p role="status" className="mt-3 rounded-lg bg-[#F8F7F2] px-3 py-2 text-sm text-gray-700">{saved}</p>}
      <textarea value={text} rows={5} aria-label="붙여넣을 측정값" spellCheck={false}
        placeholder={'측정일\t항목\t값\n2026-01-05\tCO\t12.5'}
        onChange={(e) => { setText(e.target.value); setRows(null); }}
        className={`${INPUT} mt-3 block w-full whitespace-pre font-mono`} />
      <div className="mt-3 flex items-center gap-3">
        <button type="button" className={BTN} disabled={busy || !text.trim()} onClick={preview}>미리보기</button>
        {rows && (
          <button type="button" className={PRIMARY} disabled={busy || good === 0} onClick={save}>
            {bad > 0 ? `오류 제외 ${good}건 저장` : `${good}건 저장`}
          </button>
        )}
        {rows && <span className="text-xs text-gray-500">전체 {rows.length}행 · 저장 가능 {good}행 · 오류 {bad}행</span>}
      </div>

      {rows && (
        <div className="mt-3 max-h-[420px] overflow-auto rounded-xl border border-[#E5E3DC] bg-white shadow-sm">
          <table className="w-full min-w-[900px] whitespace-nowrap text-left text-sm">
            <thead className="sticky top-0 bg-[#F8F7F2] text-xs text-gray-500">
              <tr>
                <th className="px-4 py-3 text-right font-medium">행</th>
                <th className="px-4 py-3 font-medium">측정일</th>
                <th className="px-4 py-3 font-medium">항목</th>
                <th className="px-4 py-3 text-right font-medium">측정값</th>
                <th className="px-4 py-3 font-medium">단위</th>
                <th className="px-4 py-3 text-right font-medium">한도</th>
                <th className="px-4 py-3 font-medium">판정</th>
                <th className="px-4 py-3 font-medium">오류</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-[#E5E3DC]">
              {rows.map((r) => (
                <tr key={r.line} className={r.error ? 'text-gray-400' : 'text-gray-800'}>
                  <td className="px-4 py-2 text-right tabular-nums">{r.line}</td>
                  {r.measuredOn && r.pollutantName && r.value !== null ? (
                    <>
                      <td className="px-4 py-2">{r.measuredOn}</td>
                      <td className="px-4 py-2 font-medium">{r.pollutantName}</td>
                      <td className="px-4 py-2 text-right tabular-nums">{r.value}</td>
                    </>
                  ) : ( // not readable as three cells: show the line as pasted
                    <td colSpan={3} className="max-w-[360px] px-4 py-2"><span className="block truncate font-mono" title={r.raw}>{r.raw}</span></td>
                  )}
                  <td className="px-4 py-2">{r.unit ?? '—'}</td>
                  <td className="px-4 py-2 text-right tabular-nums">{r.limitValue ?? '—'}</td>
                  <td className={`px-4 py-2 ${r.level ? levelClass(r.level) : ''}`}>{r.level ? LEVEL[r.level] : '—'}</td>
                  <td className="max-w-[320px] px-4 py-2 text-gray-800"><span className="block truncate" title={r.error ?? ''}>{r.error ?? ''}</span></td>
                </tr>
              ))}
            </tbody>
          </table>
          {rows.length === 0 && <p className="py-8 text-center text-sm text-gray-400">읽을 수 있는 행이 없습니다.</p>}
        </div>
      )}
    </>
  );
}
