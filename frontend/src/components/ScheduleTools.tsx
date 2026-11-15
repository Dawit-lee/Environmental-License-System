import { useEffect, useState, type ReactNode } from 'react';
import {
  ANCHOR_LABEL, ANCHORS, applyTemplate, getDelays, getTemplates, pasteItems, setAnchor, setBase,
  type Anchor, type AnchorChange, type DelayRow, type PasteResult, type Schedule, type Template,
} from '../api/schedule';

// ---------------------------------------------------------------------------
// ScheduleTools — the 일정 tab's design-schedule/03 pieces: base dates (with the move preview), 지연 현황,
// templates and Excel paste. ProjectSchedule only places them.
// ---------------------------------------------------------------------------

const btn = 'rounded-lg border border-[#E5E3DC] px-3 py-1.5 text-xs font-medium text-gray-700 hover:border-[#1F4E79] hover:text-[#1F4E79] disabled:opacity-40';
const primary = 'rounded-lg bg-[#1F4E79] px-3.5 py-1.5 text-xs font-semibold text-white hover:bg-[#173a5c] disabled:opacity-40';
const cell = 'max-w-[220px] truncate px-3 py-2';
const head = 'px-3 py-2 font-medium';
const md = (d: string | null) => (d ? d.slice(2).replaceAll('-', '.') : '미정');

export function Modal({ title, onClose, children, wide }: { title: string; onClose: () => void; children: ReactNode; wide?: boolean }) {
  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/40 px-4 py-10">
      <div role="dialog" aria-modal="true" aria-label={title}
        className={`w-full ${wide ? 'max-w-4xl' : 'max-w-2xl'} rounded-xl bg-white shadow-xl`}>
        <div className="flex items-center justify-between border-b border-[#E5E3DC] px-6 py-4">
          <h2 className="text-lg font-semibold text-gray-900">{title}</h2>
          <button type="button" onClick={onClose} aria-label="닫기" className="text-xl leading-none text-gray-400 hover:text-gray-700">×</button>
        </div>
        <div className="space-y-4 px-6 py-5">{children}</div>
      </div>
    </div>
  );
}

export const Alert = ({ error }: { error: string | null }) =>
  error ? <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-[#991B1B]">{error}</p> : null;

/** 기준일: the main base, the five dates, and "N건이 이동합니다" before a date is applied. */
export function BaseDatesDialog({ projectId, schedule, onClose, onChanged }: {
  projectId: number; schedule: Schedule; onClose: () => void; onChanged: () => void;
}) {
  const [dates, setDates] = useState<Record<string, string>>(
    () => Object.fromEntries(schedule.anchors.map((a) => [a.anchor, a.date ?? ''])));
  const [notes, setNotes] = useState<Record<string, string>>(
    () => Object.fromEntries(schedule.anchors.map((a) => [a.anchor, a.note ?? ''])));
  const [preview, setPreview] = useState<AnchorChange | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const run = async (action: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  const current = (a: Anchor) => schedule.anchors.find((x) => x.anchor === a);
  const projectDate = (a: Anchor) => a === 'KICKOFF' || a === 'COMPLETION';

  return (
    <Modal title="기준일" onClose={onClose}>
      <Alert error={error} />
      <div className="flex items-center gap-3 text-sm">
        <span className="text-gray-500">주 기준일</span>
        {(['KICKOFF', 'SUBMISSION'] as const).map((b) => (
          <label key={b} className="flex items-center gap-1.5">
            <input type="radio" name="base" checked={schedule.base === b} disabled={busy}
              onChange={() => run(() => setBase(projectId, b).then(onChanged))} />
            {ANCHOR_LABEL[b]}
          </label>
        ))}
        <span className="text-xs text-gray-400">새 항목의 기본 기준일, 입찰일이면 간트 축에 D-day</span>
      </div>
      <table className="w-full whitespace-nowrap text-left text-sm">
        <thead className="bg-[#F8F7F2] text-xs text-gray-500">
          <tr><th className={head}>기준일</th><th className={head}>날짜</th><th className={head}>메모</th><th className={head} /></tr>
        </thead>
        <tbody className="divide-y divide-[#E5E3DC]">
          {ANCHORS.map((a) => (
            <tr key={a}>
              <td className="px-3 py-2 text-gray-700">{ANCHOR_LABEL[a]}{projectDate(a) && <span className="text-xs text-gray-400"> (프로젝트)</span>}</td>
              <td className="px-3 py-2">
                <input type="date" aria-label={`${ANCHOR_LABEL[a]} 날짜`} value={dates[a]}
                  onChange={(e) => setDates({ ...dates, [a]: e.target.value })}
                  className="rounded-lg border border-[#E5E3DC] px-2 py-1 text-sm" />
              </td>
              <td className="px-3 py-2">
                {!projectDate(a) && (
                  <input aria-label={`${ANCHOR_LABEL[a]} 메모`} maxLength={200} value={notes[a]}
                    onChange={(e) => setNotes({ ...notes, [a]: e.target.value })}
                    className="w-40 rounded-lg border border-[#E5E3DC] px-2 py-1 text-sm" />
                )}
              </td>
              <td className="px-3 py-2 text-right">
                <button type="button" className={btn}
                  disabled={busy || !dates[a] || (dates[a] === (current(a)?.date ?? '') && notes[a] === (current(a)?.note ?? ''))}
                  onClick={() => run(async () => setPreview(await setAnchor(projectId, a, dates[a], true, notes[a])))}>변경</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {preview && (
        <div className="space-y-3 rounded-lg border border-[#E5E3DC] p-4">
          <p className="text-sm text-gray-700">
            {ANCHOR_LABEL[preview.anchor]} {md(preview.from)} → {md(preview.to)} · 이 기준일을 쓰는 미완료 항목 <b>{preview.count}건</b>이 이동합니다.
            완료·고정 항목과 기준 계획은 그대로입니다.
          </p>
          {preview.count > 0 && (
            <div className="max-h-60 overflow-y-auto">
              <table className="w-full whitespace-nowrap text-left text-sm">
                <thead className="bg-[#F8F7F2] text-xs text-gray-500">
                  <tr><th className={head}>항목</th><th className={head}>지금</th><th className={head}>이동 후</th></tr>
                </thead>
                <tbody className="divide-y divide-[#E5E3DC]">
                  {preview.moves.map((m) => (
                    <tr key={m.id}>
                      <td className={cell} title={m.title}>{m.title}</td>
                      <td className="px-3 py-2 text-gray-500">{md(m.fromEnd)}</td>
                      <td className="px-3 py-2">{md(m.toEnd)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <div className="flex justify-end gap-2">
            <button type="button" className={btn} onClick={() => setPreview(null)}>취소</button>
            <button type="button" className={primary} disabled={busy} onClick={() => run(async () => {
              await setAnchor(projectId, preview.anchor, preview.to, false, notes[preview.anchor]);
              setPreview(null);
              onChanged();
            })}>적용</button>
          </div>
        </div>
      )}
    </Modal>
  );
}

/** 지연 현황: one line per item, most days first; copies as tab-separated text for the weekly report. */
export function DelayTable({ projectId }: { projectId: number }) {
  const [rows, setRows] = useState<DelayRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  useEffect(() => {
    getDelays(projectId).then(setRows, (e: Error) => setError(e.message));
  }, [projectId]);

  const copy = async () => {
    const header = ['분야', '항목', '담당', '기준 종료', '현재 종료', '실적', '지연', '사유', '후행'];
    const lines = (rows ?? []).map((r) => [r.disciplineName ?? '공통', r.title, r.ownerName ?? '', r.baselineEnd ?? '',
      r.endDate, r.actualEnd ?? '', `+${r.delayDays}일`, r.reason ?? '', String(r.successors)].join('\t'));
    try {
      await navigator.clipboard.writeText([header.join('\t'), ...lines].join('\n'));
      setCopied(true);
    } catch {
      setError('클립보드에 복사하지 못했습니다.');
    }
  };

  if (error) return <Alert error={error} />;
  if (!rows) return null;
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <p className="text-sm text-gray-500">기준 계획(없으면 현재 계획)보다 늦은 항목 {rows.length}건 · 지연 일수 순</p>
        <button type="button" className={btn} disabled={!rows.length} onClick={copy}>{copied ? '복사됨' : '주간보고용 복사'}</button>
      </div>
      {rows.length === 0 ? (
        <p className="rounded-xl border border-dashed border-[#E5E3DC] bg-white px-6 py-12 text-center text-sm text-gray-500">지연된 항목이 없습니다.</p>
      ) : (
        <div className="overflow-x-auto rounded-xl border border-[#E5E3DC] bg-white">
          <table className="w-full whitespace-nowrap text-left text-sm">
            <thead className="bg-[#F8F7F2] text-xs text-gray-500">
              <tr>
                <th className={head}>분야</th><th className={head}>항목</th><th className={head}>담당</th>
                <th className={head}>기준 종료</th><th className={head}>현재 종료</th><th className={head}>실적</th>
                <th className={`${head} text-right`}>지연</th><th className={head}>사유</th><th className={`${head} text-right`}>후행</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-[#E5E3DC]">
              {rows.map((r) => (
                <tr key={r.id}>
                  <td className={cell}>{r.disciplineName ?? '공통'}</td>
                  <td className={cell} title={r.title}>{r.title}</td>
                  <td className={cell}>{r.ownerName ?? '—'}</td>
                  <td className="px-3 py-2 text-gray-500">{r.baselineEnd ?? '—'}</td>
                  <td className="px-3 py-2">{r.endDate}</td>
                  <td className="px-3 py-2 text-gray-500">{r.actualEnd ?? '—'}</td>
                  <td className="px-3 py-2 text-right text-[#991B1B]">+{r.delayDays}일</td>
                  <td className={cell} title={r.reason ?? ''}>{r.reason ?? '—'}</td>
                  <td className="px-3 py-2 text-right">{r.successors || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

/** 템플릿으로 시작: adds the template's relative items; they get dates once their base date is set. */
export function TemplateDialog({ projectId, onClose, onChanged }: { projectId: number; onClose: () => void; onChanged: () => void }) {
  const [templates, setTemplates] = useState<Template[]>([]);
  const [chosen, setChosen] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    getTemplates().then((t) => { setTemplates(t); setChosen(t[0]?.id ?? null); }, (e: Error) => setError(e.message));
  }, []);
  const template = templates.find((t) => t.id === chosen);
  const bases = template ? [...new Set(template.items.map((i) => ANCHOR_LABEL[i.anchor]))].join(', ') : '';

  return (
    <Modal title="템플릿으로 시작" onClose={onClose}>
      <Alert error={error} />
      <div className="space-y-2">
        {templates.map((t) => (
          <label key={t.id} className="flex items-center gap-2 text-sm">
            <input type="radio" name="template" checked={chosen === t.id} onChange={() => setChosen(t.id)} />
            {t.name} <span className="text-gray-400">· {t.items.length}개 항목</span>
          </label>
        ))}
      </div>
      {template && (
        <p className="text-sm text-gray-500">기준일: {bases}. 기준일이 아직 없으면 날짜 미정으로 추가되고, [기준일]에서 날짜를 넣으면 계산됩니다.</p>
      )}
      <div className="flex justify-end">
        <button type="button" className={primary} disabled={busy || !chosen} onClick={async () => {
          setBusy(true);
          setError(null);
          try {
            await applyTemplate(projectId, chosen!);
            onChanged();
          } catch (e) {
            setError((e as Error).message);
            setBusy(false);
          }
        }}>추가</button>
      </div>
    </Modal>
  );
}

/** 엑셀 붙여넣기: 분야 | 작업 | 시작 | 종료 | 담당 | 보할 → preview with per-row errors → confirm. */
export function PasteDialog({ projectId, onClose, onChanged }: { projectId: number; onClose: () => void; onChanged: () => void }) {
  const [text, setText] = useState('');
  const [result, setResult] = useState<PasteResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const run = async (confirm: boolean) => {
    setBusy(true);
    setError(null);
    try {
      const r = await pasteItems(projectId, text, confirm);
      if (confirm) return onChanged();
      setResult(r);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal title="엑셀 붙여넣기" onClose={onClose} wide>
      <p className="text-sm text-gray-500">엑셀에서 <b>분야 | 작업 | 시작 | 종료 | 담당 | 보할</b> 열을 복사해 붙여 넣으세요. 시작이 비었거나 종료와 같으면 마일스톤입니다.</p>
      <textarea aria-label="붙여 넣을 표" rows={6} value={text} onChange={(e) => { setText(e.target.value); setResult(null); }}
        className="w-full rounded-lg border border-[#E5E3DC] px-3 py-2 font-mono text-xs outline-none focus:border-[#1F4E79]"
        placeholder={'공정·기계\tP&ID 1차\t2027-03-02\t2027-04-13\t이가온\t15'} />
      <Alert error={error} />
      {result && (
        <div className="max-h-72 overflow-auto rounded-lg border border-[#E5E3DC]">
          <table className="w-full whitespace-nowrap text-left text-sm">
            <thead className="bg-[#F8F7F2] text-xs text-gray-500">
              <tr>
                <th className={head}>행</th><th className={head}>분야</th><th className={head}>작업</th><th className={head}>시작</th>
                <th className={head}>종료</th><th className={head}>담당</th><th className={head}>보할</th><th className={head}>확인</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-[#E5E3DC]">
              {result.rows.map((r) => (
                <tr key={r.line}>
                  <td className="px-3 py-2 text-gray-400">{r.line}</td>
                  <td className={cell}>{r.discipline || '공통'}</td>
                  <td className={cell} title={r.title}>{r.title}</td>
                  <td className="px-3 py-2">{r.kind === 'TASK' ? r.start : ''}</td>
                  <td className="px-3 py-2">{r.end ?? ''}</td>
                  <td className={cell}>{r.owner}</td>
                  <td className="px-3 py-2">{r.weight ?? ''}</td>
                  <td className={`${cell} ${r.error ? 'text-[#991B1B]' : 'text-gray-500'}`} title={r.error ?? ''}>{r.error ?? '정상'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div className="flex items-center justify-end gap-2">
        {result && <span className="mr-auto text-sm text-gray-500">{result.rows.length}행 · 오류 {result.errors}행</span>}
        <button type="button" className={btn} disabled={busy || !text.trim()} onClick={() => run(false)}>미리보기</button>
        <button type="button" className={primary} disabled={busy || !result || result.errors > 0 || !result.rows.length}
          onClick={() => run(true)}>{result ? `${result.rows.length}건 추가` : '추가'}</button>
      </div>
    </Modal>
  );
}
