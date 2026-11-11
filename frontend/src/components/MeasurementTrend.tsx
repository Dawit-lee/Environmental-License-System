import { useEffect, useState, type MouseEvent } from 'react';
import { api } from '../api/client';
import { LEVEL, levelClass, type Level } from './PointMeasurements';

// ---------------------------------------------------------------------------
// MeasurementTrend — 운영 탭 "측정 추이" of one emission point (operations-compliance/05): one item over a period as
// a line chart with the permit limit (solid) and the 근접 line (dashed), plus the same values as a table.
// Limit, 근접 line and the level of each point are the snapshot stored with the value, not today's limit, so a
// limit change shows as a step. Drawn as plain SVG: no chart library in this project.
// ---------------------------------------------------------------------------

interface Measurement {
  id: number; measuredOn: string; value: number; unit: string; belowDetection: boolean;
  limitValue: number | null; warnRatio: number | null; level: Level;
}

const INPUT = 'rounded-lg border border-[#E5E3DC] bg-white px-3 py-1.5 text-sm text-gray-700 outline-none focus:border-[#1F4E79] focus:ring-2 focus:ring-[#1F4E79]/20';
const NAVY = '#1F4E79';
const RED = '#991B1B';
const W = 720, H = 260, LEFT = 52, RIGHT = 16, TOP = 14, BOTTOM = 28;
const iso = (d: Date) => d.toLocaleDateString('sv-SE');
const day = (s: string) => Date.parse(s) / 86_400_000;
const num = (n: number) => String(+n.toPrecision(6));
const near = (m: Measurement) => (m.limitValue === null || m.warnRatio === null ? null : +(m.limitValue * m.warnRatio).toPrecision(12));

export default function MeasurementTrend({ point, pollutants, limits, rev }: {
  point: { id: number; name: string; medium: string };
  pollutants: { code: string; medium: string; name: string; unit: string }[];
  limits: { pollutant: string }[];
  rev: number;
}) {
  const items = pollutants.filter((p) => p.medium === point.medium);
  const [item, setItem] = useState('');
  const [from, setFrom] = useState(() => { const d = new Date(); d.setFullYear(d.getFullYear() - 1); return iso(d); });
  const [to, setTo] = useState(() => iso(new Date()));
  const [data, setData] = useState<Measurement[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [hover, setHover] = useState<Measurement | null>(null);

  // the item of the first limit is the one most likely looked at; chosen again when the point changes
  const code = items.some((p) => p.code === item) ? item : limits.find((l) => items.some((p) => p.code === l.pollutant))?.pollutant ?? items[0]?.code ?? '';
  useEffect(() => setItem(''), [point.id]);
  useEffect(() => {
    setHover(null);
    if (!code || !from || !to) return;
    let stale = false;
    api<Measurement[]>(`/api/emission-points/${point.id}/measurements?pollutant=${code}&from=${from}&to=${to}`)
      .then((list) => { if (!stale) { setData([...list].reverse()); setError(null); } }, (e: Error) => { if (!stale) setError(e.message); });
    return () => { stale = true; };
  }, [point.id, code, from, to, rev]);

  const unit = items.find((p) => p.code === code)?.unit ?? '';
  const x0 = day(from), x1 = Math.max(day(to), x0 + 1);
  const top = Math.max(1e-9, ...data.flatMap((m) => [m.value, m.limitValue ?? 0])) * 1.1;
  const x = (m: Measurement) => LEFT + ((day(m.measuredOn) - x0) / (x1 - x0)) * (W - LEFT - RIGHT);
  const y = (v: number) => H - BOTTOM - (v / top) * (H - TOP - BOTTOM);
  /** One horizontal piece per value, from its day to the next value's day: the limit as it was when each was entered. */
  const steps = (of: (m: Measurement) => number | null) => data.map((m, i) => {
    const v = of(m);
    return v === null ? '' : `M${i === 0 ? LEFT : x(m)},${y(v)}H${i === data.length - 1 ? W - RIGHT : x(data[i + 1])}`;
  }).join('');

  const move = (e: MouseEvent<SVGSVGElement>) => {
    const box = e.currentTarget.getBoundingClientRect();
    const at = ((e.clientX - box.left) / box.width) * W;
    setHover(data.reduce<Measurement | null>((best, m) => (best && Math.abs(x(best) - at) <= Math.abs(x(m) - at) ? best : m), null));
  };

  return (
    <>
      <h2 className="mt-8 text-lg font-bold text-[#1F4E79]">측정 추이 · {point.name}</h2>
      {error && <p role="alert" className="mt-3 rounded-lg bg-red-50 px-3 py-2 text-sm text-[#991B1B]">{error}</p>}
      <div className="mt-3 flex flex-wrap items-end gap-3">
        <label className="text-xs text-gray-500">항목
          <select value={code} onChange={(e) => setItem(e.target.value)} className={`${INPUT} mt-1 block`}>
            {items.map((p) => <option key={p.code} value={p.code}>{p.name}</option>)}
          </select>
        </label>
        <label className="text-xs text-gray-500">시작일
          <input type="date" required value={from} max={to} onChange={(e) => setFrom(e.target.value)} className={`${INPUT} mt-1 block`} />
        </label>
        <label className="text-xs text-gray-500">종료일
          <input type="date" required value={to} min={from} onChange={(e) => setTo(e.target.value)} className={`${INPUT} mt-1 block`} />
        </label>
        <p className="pb-1.5 text-sm text-gray-500">{data.length}건 · 한도와 판정은 입력 시점 기준입니다.</p>
      </div>

      <div className="relative mt-3 rounded-xl border border-[#E5E3DC] bg-white p-3 shadow-sm">
        <svg viewBox={`0 0 ${W} ${H}`} className="block w-full" role="img" onMouseMove={move} onMouseLeave={() => setHover(null)}
          aria-label={`${point.name} ${items.find((p) => p.code === code)?.name ?? ''} 측정 추이 ${from} ~ ${to}, ${data.length}건. 값은 아래 표에 있습니다.`}>
          {[0, 0.25, 0.5, 0.75, 1].map((t) => {
            const v = (top / 1.1) * t;
            return (
              <g key={t}>
                <line x1={LEFT} x2={W - RIGHT} y1={y(v)} y2={y(v)} stroke="#E5E3DC" strokeWidth={t === 0 ? 1.5 : 0.5} />
                <text x={LEFT - 6} y={y(v) + 4} textAnchor="end" fontSize="11" fill="#6B7280">{data.length ? num(v) : ''}</text>
              </g>
            );
          })}
          <text x={LEFT} y={H - 8} fontSize="11" fill="#6B7280">{from}</text>
          <text x={W - RIGHT} y={H - 8} textAnchor="end" fontSize="11" fill="#6B7280">{to}</text>
          <text x={LEFT} y={10} fontSize="11" fill="#6B7280">{unit}</text>
          <path d={steps((m) => m.limitValue)} stroke={RED} strokeWidth="1.5" fill="none" />
          <path d={steps(near)} stroke="#6B7280" strokeWidth="1.5" strokeDasharray="5 4" fill="none" />
          <polyline points={data.map((m) => `${x(m)},${y(m.value)}`).join(' ')} stroke={NAVY} strokeWidth="1.5" fill="none" />
          {hover && <line x1={x(hover)} x2={x(hover)} y1={TOP} y2={H - BOTTOM} stroke="#9CA3AF" strokeWidth="1" />}
          {data.map((m) => (m.level === 'EXCEEDED'
            ? <circle key={m.id} cx={x(m)} cy={y(m.value)} r="5.5" fill={RED} stroke="#fff" strokeWidth="1.5" />
            : m.level === 'NEAR'
              ? <circle key={m.id} cx={x(m)} cy={y(m.value)} r="4.5" fill="#fff" stroke={NAVY} strokeWidth="2" />
              : <circle key={m.id} cx={x(m)} cy={y(m.value)} r="3" fill={NAVY} />))}
        </svg>
        {hover && (
          <div role="tooltip" className="pointer-events-none absolute z-10 -translate-x-1/2 -translate-y-full whitespace-nowrap rounded-lg border border-[#E5E3DC] bg-white px-3 py-2 text-xs text-gray-700 shadow-md"
            style={{ left: `clamp(90px, ${(x(hover) / W) * 100}%, calc(100% - 90px))`, top: `${(y(hover.value) / H) * 100}%`, marginTop: -4 }}>
            <p className="font-medium text-gray-800">{hover.measuredOn}</p>
            <p>{num(hover.value)} {hover.unit}{hover.belowDetection ? ' (불검출)' : ''} · <span className={levelClass(hover.level)}>{LEVEL[hover.level]}</span></p>
            <p className="text-gray-500">{hover.limitValue === null ? '한도 없음' : `한도 ${num(hover.limitValue)} · 근접 ${num(near(hover)!)}`}</p>
          </div>
        )}
        {data.length === 0 && <p className="absolute inset-0 flex items-center justify-center text-sm text-gray-400">이 기간에 측정값이 없습니다.</p>}
        <p className="mt-1 flex flex-wrap items-center gap-x-5 gap-y-1 px-1 text-xs text-gray-600">
          <span><svg width="22" height="8" className="mr-1 inline"><line x1="0" x2="22" y1="4" y2="4" stroke={RED} strokeWidth="1.5" /></svg>한도</span>
          <span><svg width="22" height="8" className="mr-1 inline"><line x1="0" x2="22" y1="4" y2="4" stroke="#6B7280" strokeWidth="1.5" strokeDasharray="5 4" /></svg>근접선</span>
          <span><svg width="12" height="12" className="mr-1 inline"><circle cx="6" cy="6" r="3" fill={NAVY} /></svg>정상</span>
          <span><svg width="12" height="12" className="mr-1 inline"><circle cx="6" cy="6" r="4" fill="#fff" stroke={NAVY} strokeWidth="2" /></svg>근접</span>
          <span><svg width="12" height="12" className="mr-1 inline"><circle cx="6" cy="6" r="5" fill={RED} /></svg>초과</span>
        </p>
      </div>

      <div className="mt-3 max-h-[360px] overflow-auto rounded-xl border border-[#E5E3DC] bg-white shadow-sm">
        <table className="w-full min-w-[720px] whitespace-nowrap text-left text-sm">
          <thead className="sticky top-0 bg-[#F8F7F2] text-xs text-gray-500">
            <tr>
              <th className="px-4 py-3 font-medium">측정일</th>
              <th className="px-4 py-3 text-right font-medium">측정값</th>
              <th className="px-4 py-3 font-medium">단위</th>
              <th className="px-4 py-3 text-right font-medium" title="입력 시점에 적용된 한도">한도</th>
              <th className="px-4 py-3 text-right font-medium" title="한도 × 근접 비율">근접선</th>
              <th className="px-4 py-3 text-right font-medium">한도 대비</th>
              <th className="px-4 py-3 font-medium">판정</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[#E5E3DC]">
            {[...data].reverse().map((m) => (
              <tr key={m.id} className={m === hover ? 'bg-[#1F4E79]/5' : ''}>
                <td className="px-4 py-2 text-gray-800">{m.measuredOn}</td>
                <td className="px-4 py-2 text-right tabular-nums text-gray-800">{m.value}{m.belowDetection ? ' (불검출)' : ''}</td>
                <td className="px-4 py-2 text-gray-600">{m.unit}</td>
                <td className="px-4 py-2 text-right tabular-nums text-gray-600">{m.limitValue ?? '—'}</td>
                <td className="px-4 py-2 text-right tabular-nums text-gray-600">{near(m) ?? '—'}</td>
                <td className="px-4 py-2 text-right tabular-nums text-gray-600">{m.limitValue === null || m.belowDetection ? '—' : `${Math.round((m.value / m.limitValue) * 1000) / 10}%`}</td>
                <td className={`px-4 py-2 ${levelClass(m.level)}`}>{LEVEL[m.level]}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {data.length === 0 && <p className="py-8 text-center text-sm text-gray-400">이 기간에 측정값이 없습니다.</p>}
      </div>
    </>
  );
}
