import { useEffect, useState, type ReactNode } from 'react';
import { addDays, addMonths, differenceInCalendarDays, eachMonthOfInterval, format, parseISO } from 'date-fns';
import {
  ANCHOR_LABEL, ANCHORS, applyTemplate, confirmBaseline, getDelays, getTemplates, pasteItems, setAnchor, setBase,
  type Anchor, type AnchorChange, type DelayRow, type Discipline, type PasteResult, type Schedule, type Template,
  type TemplateItem,
} from '../api/schedule';
import { useEscape } from './useEscape';

// ---------------------------------------------------------------------------
// ScheduleTools — the 일정 tab's design-schedule/03 pieces: base dates (with the move preview), 기준 확정,
// 지연 현황, templates and Excel paste. ProjectSchedule only places them. All dialogs share one shape:
// header (title + one line), sections, and a footer with a summary on the left and the action that says what it does.
// ---------------------------------------------------------------------------

export const btn = 'rounded-lg border border-line bg-white px-3 py-1.5 text-xs font-medium text-gray-700 hover:border-brand hover:text-brand disabled:opacity-40';
export const primary = 'rounded-lg bg-brand px-3.5 py-1.5 text-xs font-semibold text-white hover:bg-brand-hover disabled:cursor-not-allowed disabled:opacity-40';
export const segBtn = (on: boolean) => `px-3 py-1.5 text-xs ${on ? 'bg-brand font-semibold text-white' : 'text-gray-600 hover:bg-paper'}`;
const cell = 'max-w-[220px] truncate px-3 py-2';
const head = 'px-3 py-2 font-medium';
const dateInput = 'rounded-lg border border-line bg-white px-2 py-1 text-sm tabular-nums outline-none focus:border-brand';
const md = (d: string | null | undefined) => (d ? d.slice(2).replaceAll('-', '.') : '미정');
const ymd = (d: Date) => format(d, 'yyyy-MM-dd');

const WIDTH = { sm: 'max-w-xl', md: 'max-w-2xl', lg: 'max-w-4xl' };

export function Modal({ title, subtitle, onClose, children, footer, wide, size }: {
  title: string; subtitle?: ReactNode; onClose: () => void; children: ReactNode; footer?: ReactNode; wide?: boolean;
  size?: keyof typeof WIDTH;
}) {
  useEscape(onClose);
  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/40 px-4 py-10">
      <div role="dialog" aria-modal="true" aria-label={title}
        className={`w-full ${WIDTH[size ?? (wide ? 'lg' : 'md')]} rounded-xl bg-white shadow-xl`}>
        <div className="flex items-start justify-between gap-3 border-b border-line px-6 py-4">
          <div className="min-w-0">
            <h2 className="text-lg font-semibold text-gray-900">{title}</h2>
            {subtitle && <p className="mt-0.5 text-xs text-gray-400">{subtitle}</p>}
          </div>
          <button type="button" onClick={onClose} aria-label="닫기" className="text-xl leading-none text-gray-400 hover:text-gray-700">×</button>
        </div>
        <div className="space-y-5 px-6 py-5">{children}</div>
        {footer && (
          <div className="flex flex-wrap items-center gap-2 rounded-b-xl border-t border-line bg-paper px-6 py-3.5">{footer}</div>
        )}
      </div>
    </div>
  );
}

/** Left side of a dialog footer. */
export const Summary = ({ children }: { children?: ReactNode }) =>
  <span className="mr-auto text-sm tabular-nums text-gray-500">{children}</span>;

export function Section({ title, hint, children }: { title: string; hint?: ReactNode; children: ReactNode }) {
  return (
    <section className="space-y-2.5">
      <h3 className="flex items-baseline gap-2 text-sm font-semibold text-gray-900">
        {title}{hint && <span className="text-xs font-normal text-gray-400">{hint}</span>}
      </h3>
      {children}
    </section>
  );
}

/** Label / number boxes in one row (기준 확정). */
function Stats({ items }: { items: [ReactNode, string, boolean?][] }) {
  return (
    <div className="grid grid-cols-[minmax(0,1fr)_minmax(0,1.7fr)_minmax(0,1fr)] divide-x divide-line rounded-lg border border-line">
      {items.map(([value, label, bad], i) => (
        <div key={i} className="min-w-0 px-4 py-3">
          <div className={`truncate text-base font-semibold tabular-nums ${bad ? 'text-danger' : 'text-gray-900'}`}>{value}</div>
          <div className="text-xs text-gray-400">{label}</div>
        </div>
      ))}
    </div>
  );
}

export const Alert = ({ error }: { error: string | null }) =>
  error ? <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-danger">{error}</p> : null;

/** Runs one async action with busy/error state. */
function useRun() {
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
  return { error, setError, busy, run };
}

/** 기준일: the main base, every date with how many items use it, and one combined move preview before applying. */
export function BaseDatesDialog({ projectId, schedule, onClose, onChanged }: {
  projectId: number; schedule: Schedule; onClose: () => void; onChanged: () => void;
}) {
  const saved = (a: Anchor) => schedule.anchors.find((x) => x.anchor === a);
  const [dates, setDates] = useState<Record<string, string>>(() => Object.fromEntries(ANCHORS.map((a) => [a, saved(a)?.date ?? ''])));
  const [notes, setNotes] = useState<Record<string, string>>(() => Object.fromEntries(ANCHORS.map((a) => [a, saved(a)?.note ?? ''])));
  const [previews, setPreviews] = useState<AnchorChange[]>([]);
  const { error, setError, busy, run } = useRun();
  const projectDate = (a: Anchor) => a === 'KICKOFF' || a === 'COMPLETION'; // stored on the project row, no memo
  const dateChanged = (a: Anchor) => !!dates[a] && dates[a] !== (saved(a)?.date ?? '');
  const changed = ANCHORS.filter((a) => dateChanged(a) || (!!dates[a] && !projectDate(a) && notes[a] !== (saved(a)?.note ?? '')));
  const uses = (a: Anchor) => schedule.items.filter((i) => i.anchor === a).length;

  // preview every changed date together (server rule: unfinished relative items move, done/fixed/baseline stay)
  const key = ANCHORS.map((a) => (dateChanged(a) ? dates[a] : '')).join('|');
  useEffect(() => {
    const list = ANCHORS.filter(dateChanged);
    if (!list.length) return;
    const t = setTimeout(() => {
      Promise.all(list.map((a) => setAnchor(projectId, a, dates[a], true, notes[a])))
        .then(setPreviews, (e: Error) => setError(e.message));
    }, 300);
    return () => clearTimeout(t);
  }, [key]); // eslint-disable-line react-hooks/exhaustive-deps
  const shown = previews.filter((p) => dateChanged(p.anchor) && p.to === dates[p.anchor]);
  const moves = shown.flatMap((p) => p.moves);

  const apply = () => run(async () => {
    for (const a of changed) await setAnchor(projectId, a, dates[a], false, notes[a]);
    setPreviews([]);
    onChanged();
  });
  const reset = () => {
    setDates(Object.fromEntries(ANCHORS.map((a) => [a, saved(a)?.date ?? ''])));
    setNotes(Object.fromEntries(ANCHORS.map((a) => [a, saved(a)?.note ?? ''])));
  };

  return (
    <Modal title="기준일" subtitle="항목은 이 날짜에서 며칠 전·후로 계산됩니다." onClose={onClose}
      footer={<>
        <Summary>{changed.length ? `바뀐 기준일 ${changed.length}개` : '바뀐 것 없음'}</Summary>
        <button type="button" className={btn} onClick={changed.length ? reset : onClose}>{changed.length ? '되돌리기' : '닫기'}</button>
        {!!changed.length && (
          <button type="button" className={primary} disabled={busy} onClick={apply}>
            {moves.length ? `적용 · ${moves.length}건 이동` : '적용'}</button>
        )}
      </>}>
      <Alert error={error} />
      <div className="flex flex-wrap items-center gap-3 text-sm">
        <span className="w-16 text-gray-500">주 기준일</span>
        <div className="inline-flex overflow-hidden rounded-lg border border-line bg-white">
          {(['KICKOFF', 'SUBMISSION'] as const).map((b) => (
            <button key={b} type="button" aria-pressed={schedule.base === b} disabled={busy} className={segBtn(schedule.base === b)}
              onClick={() => schedule.base !== b && run(() => setBase(projectId, b).then(onChanged))}>{ANCHOR_LABEL[b]}</button>
          ))}
        </div>
        <span className="text-xs text-gray-400">
          {schedule.base === 'SUBMISSION' ? '새 항목의 기본값이고, 간트 축에 D-day로 표시됩니다.' : '새 항목의 기본값입니다.'}</span>
      </div>
      <table className="w-full whitespace-nowrap text-left text-sm">
        <thead className="text-xs text-gray-400">
          <tr><th className="py-1.5 pr-3 font-normal">기준일</th><th className="py-1.5 pr-3 font-normal">날짜</th>
            <th className="py-1.5 pr-3 font-normal">쓰는 항목</th><th className="py-1.5 font-normal">메모</th></tr>
        </thead>
        <tbody className="divide-y divide-line-soft">
          {ANCHORS.map((a) => (
            <tr key={a}>
              <td className={`py-1.5 pr-3 ${changed.includes(a) ? 'font-semibold text-brand' : 'text-gray-700'}`}>
                {ANCHOR_LABEL[a]}{projectDate(a) && <span className="ml-1 text-xs font-normal text-gray-400">프로젝트</span>}</td>
              <td className="py-1.5 pr-3">
                <input type="date" aria-label={`${ANCHOR_LABEL[a]} 날짜`} value={dates[a]} className={dateInput}
                  onChange={(e) => setDates({ ...dates, [a]: e.target.value })} />
              </td>
              <td className={`py-1.5 pr-3 tabular-nums ${uses(a) ? 'text-gray-600' : 'text-gray-300'}`}>{uses(a) ? `${uses(a)}건` : '—'}</td>
              <td className="py-1.5">
                {!projectDate(a) && (
                  <input aria-label={`${ANCHOR_LABEL[a]} 메모`} maxLength={200} value={notes[a]} placeholder="메모" disabled={!dates[a]}
                    onChange={(e) => setNotes({ ...notes, [a]: e.target.value })}
                    className="w-full min-w-32 rounded-lg border border-line px-2 py-1 text-sm outline-none focus:border-brand disabled:bg-paper" />
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {!!shown.length && (
        <div className="space-y-3 rounded-lg border border-brand p-4">
          {shown.map((p) => {
            const d = p.from ? differenceInCalendarDays(parseISO(p.to), parseISO(p.from)) : 0;
            return (
              <p key={p.anchor} className="text-sm text-gray-700">
                <b className="text-brand">{ANCHOR_LABEL[p.anchor]} {md(p.from)} → {md(p.to)}</b>
                {d !== 0 && <span className="ml-2 text-xs text-gray-400">{d > 0 ? `${d}일 늦춤` : `${-d}일 당김`}</span>}
              </p>
            );
          })}
          <p className="text-xs leading-relaxed text-gray-500">
            {moves.length ? <>이 날짜를 쓰는 미완료 항목 {moves.length}건이 함께 움직입니다.<br /></> : <>이 날짜를 쓰는 미완료 항목이 없습니다. 날짜만 바뀝니다.<br /></>}
            완료·고정 항목과 기준 계획은 그대로입니다.
          </p>
          {!!moves.length && (
            <div className="max-h-52 overflow-y-auto rounded-lg border border-line">
              <table className="w-full whitespace-nowrap text-left text-sm">
                <thead className="sticky top-0 bg-paper text-xs text-gray-500">
                  <tr><th className={head}>항목</th><th className={head}>지금</th><th className={head}>이동 후</th></tr>
                </thead>
                <tbody className="divide-y divide-line-soft">
                  {moves.map((m) => (
                    <tr key={m.id}>
                      <td className={cell} title={m.title}>{m.title}</td>
                      <td className="px-3 py-2 tabular-nums text-gray-400">{md(m.fromEnd)}</td>
                      <td className="px-3 py-2 tabular-nums">{md(m.toEnd)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </Modal>
  );
}

/** 기준 확정 / 재확정: what gets saved, in numbers; a re-confirm shows the slipped items and needs a reason. */
export function BaselineDialog({ projectId, schedule, onClose, onDone }: {
  projectId: number; schedule: Schedule; onClose: () => void; onDone: () => void;
}) {
  const [reason, setReason] = useState('');
  const { error, busy, run } = useRun();
  const again = schedule.baselineNo > 0;
  const next = schedule.baselineNo + 1;
  const items = schedule.items;
  const dated = items.filter((i) => i.startDate && i.endDate);
  const undated = items.length - dated.length;
  const first = dated.reduce<string | null>((m, i) => (!m || i.startDate! < m ? i.startDate : m), null);
  const last = dated.reduce<string | null>((m, i) => (!m || i.endDate! > m ? i.endDate : m), null);
  const missing = [...new Set(items.filter((i) => !i.endDate && i.anchor).map((i) => ANCHOR_LABEL[i.anchor!]))];
  const late = items.filter((i) => (i.slipDays ?? 0) > 0).sort((a, b) => b.slipDays! - a.slipDays!);
  const early = items.filter((i) => (i.slipDays ?? 0) < 0).length;

  return (
    <Modal title={again ? '기준 재확정' : '기준 확정'} subtitle={again ? 'PM·프로젝트 관리자만 할 수 있습니다.' : undefined}
      onClose={onClose} size="sm"
      footer={<>
        <Summary />
        <button type="button" className={btn} onClick={onClose}>취소</button>
        <button type="button" className={primary} disabled={busy || (again && !reason.trim())}
          onClick={() => run(async () => { await confirmBaseline(projectId, again ? reason.trim() : null); onDone(); })}>
          기준 {next}차 확정</button>
      </>}>
      <Alert error={error} />
      {again ? (
        <>
          <Stats items={[[`기준 ${schedule.baselineNo}차`, '지금 기준'], [`${late.length}건`, '기준보다 늦어짐', late.length > 0], [`${early}건`, '기준보다 당겨짐']]} />
          {!!late.length && (
            <Section title="늦어진 항목">
              <div className="max-h-56 overflow-auto rounded-lg border border-line">
                <table className="w-full whitespace-nowrap text-left text-sm">
                  <thead className="sticky top-0 bg-paper text-xs text-gray-500">
                    <tr><th className={head}>항목</th><th className={head}>기준</th><th className={head}>지금</th>
                      <th className={`${head} text-right`}>차이</th><th className={head}>사유</th></tr>
                  </thead>
                  <tbody className="divide-y divide-line-soft">
                    {late.map((i) => (
                      <tr key={i.id}>
                        <td className={cell} title={i.title}>{i.title}</td>
                        <td className="px-3 py-2 tabular-nums text-gray-400">{md(i.baselineEnd)}</td>
                        <td className="px-3 py-2 tabular-nums">{md(i.endDate)}</td>
                        <td className="px-3 py-2 text-right tabular-nums text-danger">+{i.slipDays}일</td>
                        <td className={cell} title={i.delayReason ?? ''}>{i.delayReason ?? '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Section>
          )}
          <p className="text-sm leading-relaxed text-gray-600">
            {next}차로 확정하면 지금 날짜가 새 기준이 됩니다.<br />{schedule.baselineNo}차 기준과 지연 이력은 남습니다.</p>
          <Section title="재확정 사유" hint="필수">
            <input aria-label="재확정 사유" value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)}
              placeholder="예: 발주처 과업 변경 협의 결과 반영"
              className="w-full rounded-lg border border-line px-3 py-2 text-sm outline-none focus:border-brand" />
          </Section>
        </>
      ) : (
        <>
          <Stats items={[[`${items.length}건`, '항목'], [first ? `${md(first)} ~ ${md(last)}` : '—', '기간'], [`${undated}건`, '날짜 미정', undated > 0]]} />
          {undated > 0 && (
            <p className="rounded-lg bg-red-50 px-3 py-2 text-sm leading-relaxed text-danger">
              날짜 미정 {undated}건은 기준 날짜 없이 저장됩니다.
              {!!missing.length && <><br />{missing.join('·')}이 비어 있어서입니다. [기준일]에서 넣으면 계산됩니다.</>}
            </p>
          )}
          <p className="text-sm leading-relaxed text-gray-600">
            지금 일정을 기준 1차로 저장합니다.<br />이후 기준보다 늦추면 연기 사유를 받습니다.<br />지연 현황은 이 기준과 비교합니다.</p>
        </>
      )}
    </Modal>
  );
}

/** 지연 현황: one line per item, most days first; copies as tab-separated text for the weekly report. */
export function DelayTable({ projectId, baselined }: { projectId: number; baselined: boolean }) {
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
        <p className="text-sm text-gray-500">기준 계획보다 늦은 항목 {rows.length}건 · 지연 일수 순</p>
        <button type="button" className={btn} disabled={!rows.length} onClick={copy}>{copied ? '복사됨' : '주간보고용 복사'}</button>
      </div>
      {rows.length === 0 ? (
        <p className="rounded-xl border border-dashed border-line bg-white px-6 py-12 text-center text-sm text-gray-500">
          {baselined ? '지연된 항목이 없습니다.' : '기준을 확정하면 기준보다 늦어진 항목이 여기에 나옵니다. 계획 종료일이 지난 항목은 간트의 "기한 경과"로 봅니다.'}</p>
      ) : (
        <div className="overflow-x-auto rounded-xl border border-line bg-white">
          <table className="w-full whitespace-nowrap text-left text-sm">
            <thead className="bg-paper text-xs text-gray-500">
              <tr>
                <th className={head}>분야</th><th className={head}>항목</th><th className={head}>담당</th>
                <th className={head}>기준 종료</th><th className={head}>현재 종료</th><th className={head}>실적</th>
                <th className={`${head} text-right`}>지연</th><th className={head}>사유</th><th className={`${head} text-right`}>후행</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {rows.map((r) => (
                <tr key={r.id}>
                  <td className={cell}>{r.disciplineName ?? '공통'}</td>
                  <td className={cell} title={r.title}>{r.title}</td>
                  <td className={cell}>{r.ownerName ?? '—'}</td>
                  <td className="px-3 py-2 text-gray-500">{r.baselineEnd ?? '—'}</td>
                  <td className="px-3 py-2">{r.endDate}</td>
                  <td className="px-3 py-2 text-gray-500">{r.actualEnd ?? '—'}</td>
                  <td className="px-3 py-2 text-right text-danger">+{r.delayDays}일</td>
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


// --- 템플릿으로 시작 ----------------------------------------------------------------------------------

/** When to use each default template (by code); a company's own templates show only the numbers. */
const TEMPLATE_USE: Record<string, string[]> = {
  TURNKEY_BID: ['입찰일에서 거꾸로 짭니다.', '턴키·기술제안 입찰 준비에 씁니다.'],
  SERVICE: ['계약일과 완료일 사이에 보고 일정을 넣습니다.', '실시설계·연구 용역에 씁니다.'],
};
const SHORT: Record<Anchor, string> = {
  CONTRACT: '계약', KICKOFF: '착수', SUBMISSION: '입찰', COMPLETION: '완료', REVIEW: '심의',
  SITE_BRIEFING: '현설', NOTICE: '통보', CONSTRUCTION_START: '착공',
};

/** "입찰 D-150", "계약 +4개월", "완료 −15일"; tasks add "종료 · 85일". */
export function offsetText(i: Pick<TemplateItem, 'kind' | 'anchor' | 'offsetDays' | 'offsetMonths' | 'durationDays'>) {
  const n = i.offsetMonths ?? i.offsetDays ?? 0;
  const s = i.offsetMonths != null ? `${SHORT[i.anchor]} ${n < 0 ? '−' : '+'}${Math.abs(n)}개월`
    : i.anchor === 'SUBMISSION' ? (n === 0 ? '입찰 당일' : `입찰 D${n < 0 ? '-' : '+'}${Math.abs(n)}`)
      : `${SHORT[i.anchor]} ${n < 0 ? '−' : '+'}${Math.abs(n)}일`;
  return i.kind === 'TASK' ? `${s} 종료 · ${i.durationDays}일` : s;
}

/** Same rule as the server's ScheduleDates.resolve: the offset gives the end, a task runs back from it. */
export function resolveTemplateItem(i: Pick<TemplateItem, 'kind' | 'offsetDays' | 'offsetMonths' | 'durationDays'>, base: string | null | undefined) {
  if (!base) return null;
  const end = i.offsetMonths != null ? addMonths(parseISO(base), i.offsetMonths) : addDays(parseISO(base), i.offsetDays ?? 0);
  return { start: ymd(i.kind === 'MILESTONE' ? end : addDays(end, -((i.durationDays ?? 1) - 1))), end: ymd(end) };
}

function KindMark({ kind, faded }: { kind: TemplateItem['kind']; faded?: boolean }) {
  return kind === 'MILESTONE'
    ? <span className={`inline-block size-2 rotate-45 rounded-[1px] bg-brand ${faded ? 'opacity-25' : ''}`} />
    : <span className={`inline-block h-1.5 w-3 rounded-sm bg-[#8FA9C2] ${faded ? 'opacity-25' : ''}`} />;
}

interface Lane { name: string; items: { id: number; kind: TemplateItem['kind']; title: string; start: string; end: string; off: boolean }[] }

/** Small Gantt of the template: one lane per discipline, the base dates as vertical lines. relative = D-day axis. */
function MiniTimeline({ lanes, marks, relative }: { lanes: Lane[]; marks: { label: string; date: string }[]; relative: boolean }) {
  const all = lanes.flatMap((l) => l.items);
  if (!all.length) return null;
  const t = (s: string) => parseISO(s).getTime();
  let lo = Math.min(...all.map((i) => t(i.start)), ...marks.map((m) => t(m.date)));
  let hi = Math.max(...all.map((i) => t(i.end)), ...marks.map((m) => t(m.date)));
  const pad = (hi - lo) * 0.03 + 864e5;
  lo -= pad;
  hi += pad;
  const x = (s: string) => (t(s) - lo) / (hi - lo);
  const pct = (s: string) => `${(x(s) * 100).toFixed(2)}%`;
  const ticks: [string, string][] = [];
  if (relative) {
    const zero = t(marks[0].date);
    for (let d = -720; d <= 720; d += 30) {
      const at = zero + d * 864e5;
      if (at > lo && at < hi) ticks.push([ymd(new Date(at)), d === 0 ? 'D-day' : `D${d < 0 ? '' : '+'}${d}`]);
    }
  } else {
    const months = eachMonthOfInterval({ start: new Date(lo + 864e5 * 28), end: new Date(hi) });
    const step = months.length > 9 ? 2 : 1;
    months.forEach((m, k) => k % step === 0 && ticks.push([ymd(m), format(m, 'yy.MM')]));
  }
  // a tick label right under a base-date label would be hidden by it
  const near = (s: string) => marks.some((m) => Math.abs(x(s) - x(m.date)) < 0.15);
  return (
    <div className="overflow-x-auto rounded-lg border border-line">
      <div className="relative min-w-[600px] pb-1.5">
        <div className="pointer-events-none absolute inset-y-0 left-28 right-4">
          {ticks.map(([s]) => <div key={s} className="absolute bottom-1.5 top-6 border-l border-dashed border-[#EEECE6]" style={{ left: pct(s) }} />)}
          {marks.map((m) => (
            <div key={m.label} className="absolute bottom-1.5 top-0 border-l-[1.5px] border-brand" style={{ left: pct(m.date) }}>
              <span className={`absolute top-1.5 whitespace-nowrap bg-white px-1 text-[11px] font-semibold text-brand ${x(m.date) > 0.7 ? 'right-1' : 'left-1'}`}>
                {m.label}{relative ? '' : ` ${md(m.date)}`}</span>
            </div>
          ))}
        </div>
        <div className="relative ml-28 mr-4 h-6 border-b border-line">
          {ticks.filter(([s]) => !near(s)).map(([s, l]) => <span key={s} className="absolute top-1.5 -translate-x-1/2 whitespace-nowrap text-[11px] tabular-nums text-gray-400" style={{ left: pct(s) }}>{l}</span>)}
        </div>
        {lanes.map((l) => (
          <div key={l.name} className="flex h-[22px] items-center">
            <span className="w-28 shrink-0 truncate pl-3 text-[11.5px] text-gray-500">{l.name}</span>
            <div className="relative mr-4 h-full flex-1">
              {l.items.map((i) => i.kind === 'MILESTONE'
                ? <span key={i.id} title={`${i.title}${relative ? '' : ` ${md(i.end)}`}`}
                  className={`absolute top-1/2 size-[9px] -translate-x-1/2 -translate-y-1/2 rotate-45 rounded-[1px] bg-brand ${i.off ? 'opacity-20' : ''}`}
                  style={{ left: pct(i.end) }} />
                : <span key={i.id} title={i.title}
                  className={`absolute top-1/2 h-2 -translate-y-1/2 rounded-sm bg-[#8FA9C2] ${i.off ? 'opacity-20' : ''}`}
                  style={{ left: pct(i.start), width: `${((x(i.end) - x(i.start)) * 100).toFixed(2)}%` }} />)}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

/** 템플릿으로 시작: cards → base dates inline → timeline + checklist by discipline → "N개 항목 추가". */
export function TemplateDialog({ projectId, schedule, disciplines, onClose, onChanged }: {
  projectId: number; schedule: Schedule; disciplines: Discipline[]; onClose: () => void; onChanged: () => void;
}) {
  const saved = (a: Anchor) => schedule.anchors.find((x) => x.anchor === a);
  const [templates, setTemplates] = useState<Template[]>([]);
  const [chosen, setChosen] = useState<number | null>(null);
  const [dates, setDates] = useState<Record<string, string>>(() => Object.fromEntries(ANCHORS.map((a) => [a, saved(a)?.date ?? ''])));
  const [off, setOff] = useState<Set<number>>(new Set());
  const { error, setError, busy, run } = useRun();
  const existing = new Set(schedule.items.map((i) => i.title.trim()));
  // items already in the project (same title) start unchecked
  const choose = (t: Template | undefined) => {
    setChosen(t?.id ?? null);
    setOff(new Set(t?.items.filter((i) => existing.has(i.title.trim())).map((i) => i.id)));
  };
  useEffect(() => {
    getTemplates().then((t) => { setTemplates(t); choose(t[0]); }, (e: Error) => setError(e.message));
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const template = templates.find((t) => t.id === chosen);
  const items = template?.items ?? [];
  const anchors = [...new Set(items.map((i) => i.anchor))];
  const discName = (code: string | null) => (code && disciplines.find((d) => d.code === code)?.name) || '공통';
  const discOrder = (code: string | null) => (code ? disciplines.find((d) => d.code === code)?.sortOrder ?? 999 : -1);
  const groups = [...new Set(items.map((i) => i.disciplineCode))].sort((a, b) => discOrder(a) - discOrder(b));
  const on = items.filter((i) => !off.has(i.id));
  const milestones = on.filter((i) => i.kind === 'MILESTONE').length;
  const toggle = (ids: number[], add: boolean) => {
    const next = new Set(off);
    ids.forEach((id) => (add ? next.delete(id) : next.add(id)));
    setOff(next);
  };

  const missing = anchors.filter((a) => !dates[a]);
  const relative = anchors.length === 1 && missing.length === 1; // draw on a D-day axis until the date is set
  const tlDates = relative ? { [anchors[0]]: '2000-01-01' } : dates;
  const resolved = on.map((i) => resolveTemplateItem(i, dates[i.anchor])).filter((r) => r !== null);
  const first = resolved.reduce<string | null>((m, r) => (!m || r.start < m ? r.start : m), null);
  const last = resolved.reduce<string | null>((m, r) => (!m || r.end > m ? r.end : m), null);
  const changedAnchors = anchors.filter((a) => dates[a] && dates[a] !== (saved(a)?.date ?? ''));
  const moving = schedule.items.filter((i) => i.anchor && changedAnchors.includes(i.anchor) && i.status !== 'DONE').length;

  const span = (t: Template) => {
    const as = [...new Set(t.items.map((i) => i.anchor))];
    const min = Math.min(...t.items.map((i) => i.offsetDays ?? 0));
    return as.length === 1 && as[0] === 'SUBMISSION' && min < 0 ? `입찰 ${-min}일 전부터` : as.map((a) => ANCHOR_LABEL[a]).join(' ~ ');
  };

  const add = () => run(async () => {
    // a date typed here is the project's base date from now on
    for (const a of changedAnchors) await setAnchor(projectId, a, dates[a], false, saved(a)?.note ?? '');
    await applyTemplate(projectId, chosen!, [...off].filter((id) => items.some((i) => i.id === id)));
    onChanged();
  });

  return (
    <Modal title="템플릿으로 시작" subtitle="자주 쓰는 공정을 한 번에 넣습니다." onClose={onClose} size="lg"
      footer={<>
        <Summary>{template && `${new Set(on.map((i) => i.disciplineCode)).size}개 분야 · 마일스톤 ${milestones} · 작업 ${on.length - milestones}`}</Summary>
        <button type="button" className={btn} onClick={onClose}>취소</button>
        <button type="button" className={primary} disabled={busy || !on.length} onClick={add}>
          {on.length ? `${on.length}개 항목 추가` : '항목을 고르세요'}</button>
      </>}>
      <Alert error={error} />
      <Section title="템플릿">
        <div role="radiogroup" aria-label="템플릿" className="grid gap-3 sm:grid-cols-2">
          {templates.map((t) => {
            const sel = t.id === chosen;
            return (
              <button key={t.id} type="button" role="radio" aria-checked={sel} onClick={() => choose(t)}
                className={`grid gap-1.5 rounded-xl border bg-white px-4 py-3.5 text-left ${sel ? 'border-brand ring-1 ring-brand' : 'border-line hover:border-[#B9C6D3]'}`}>
                <span className="flex items-center gap-2 text-sm font-semibold text-gray-900">
                  <span className={`size-3.5 shrink-0 rounded-full ${sel ? 'border-4 border-brand' : 'border-[1.5px] border-gray-300'}`} />{t.name}
                </span>
                {TEMPLATE_USE[t.code ?? ''] && (
                  <span className="text-xs leading-relaxed text-gray-500">{TEMPLATE_USE[t.code ?? ''].map((l) => <span key={l} className="block">{l}</span>)}</span>
                )}
                <span className="text-xs tabular-nums text-gray-400">
                  {t.items.length}개 항목 · {new Set(t.items.map((i) => i.disciplineCode)).size}개 분야 · {span(t)}</span>
              </button>
            );
          })}
        </div>
      </Section>

      {template && (
        <>
          <Section title="기준일" hint="여기서 넣으면 프로젝트 기준일로 저장됩니다">
            <div className="flex flex-wrap items-center gap-x-6 gap-y-2 text-sm">
              {anchors.map((a) => (
                <label key={a} className="flex items-center gap-2.5 text-gray-700">{ANCHOR_LABEL[a]}
                  <input type="date" aria-label={`${ANCHOR_LABEL[a]} 날짜`} value={dates[a]}
                    className={`${dateInput} ${dates[a] ? '' : 'border-dashed text-gray-400'}`}
                    onChange={(e) => setDates({ ...dates, [a]: e.target.value || (saved(a)?.date ?? '') })} />
                </label>
              ))}
              <span className="text-xs tabular-nums text-gray-500">
                {!missing.length ? (first ? `${md(first)} ~ ${md(last)} · ${differenceInCalendarDays(parseISO(last!), parseISO(first)) + 1}일` : '')
                  : '비워 두면 날짜 미정으로 들어가고, 나중에 [기준일]에서 넣으면 계산됩니다.'}</span>
            </div>
            {moving > 0 && <p className="text-xs text-gray-500">이 기준일을 쓰는 기존 항목 {moving}건도 함께 움직입니다.</p>}
          </Section>

          {schedule.items.length > 0 && (
            <p className="rounded-lg bg-paper px-3.5 py-2.5 text-xs leading-relaxed text-gray-600">
              이 프로젝트에 이미 항목 {schedule.items.length}건이 있습니다.<br />기존 항목은 그대로 두고 아래 항목만 더합니다.
              {items.some((i) => existing.has(i.title.trim())) && <><br />이름이 같은 항목은 빼 두었습니다. 넣으려면 다시 체크하세요.</>}
            </p>
          )}

          <Section title="일정 미리보기">
            {missing.length && !relative
              ? <p className="rounded-lg border border-line px-4 py-6 text-center text-xs text-gray-400">{missing.map((a) => ANCHOR_LABEL[a]).join('·')}을 넣으면 일정 막대가 보입니다.</p>
              : <MiniTimeline relative={relative}
                marks={anchors.map((a) => ({ label: SHORT[a], date: tlDates[a] }))}
                lanes={groups.map((g) => ({
                  name: discName(g),
                  items: items.filter((i) => i.disciplineCode === g).map((i) => ({ id: i.id, kind: i.kind, title: i.title, off: off.has(i.id), ...resolveTemplateItem(i, tlDates[i.anchor])! })),
                }))} />}
          </Section>

          <Section title="추가할 항목" hint="체크를 풀면 빠집니다">
            <div className="max-h-80 overflow-auto rounded-lg border border-line">
              {groups.map((g) => {
                const gi = items.filter((i) => i.disciplineCode === g);
                const n = gi.filter((i) => !off.has(i.id)).length;
                return (
                  <div key={g ?? ''}>
                    <label className="sticky top-0 z-10 flex items-center gap-2.5 border-b border-line bg-paper px-3.5 py-2 text-xs font-semibold text-gray-700">
                      <input type="checkbox" checked={n === gi.length} aria-label={`${discName(g)} 전체`}
                        ref={(el) => { if (el) el.indeterminate = n > 0 && n < gi.length; }}
                        onChange={(e) => toggle(gi.map((i) => i.id), e.target.checked)} />
                      <span className="flex-1">{discName(g)}</span>
                      <span className="font-normal tabular-nums text-gray-400">{n}/{gi.length}</span>
                    </label>
                    {gi.map((i) => {
                      const r = resolveTemplateItem(i, dates[i.anchor]);
                      const out = off.has(i.id);
                      return (
                        <label key={i.id} className={`grid grid-cols-[16px_14px_minmax(0,1fr)_auto] items-center gap-2.5 whitespace-nowrap border-b border-line-soft px-3.5 py-1.5 text-sm last:border-b-0 sm:grid-cols-[16px_14px_minmax(0,1fr)_150px_64px] ${out ? 'text-gray-400' : 'text-gray-800'}`}>
                          <input type="checkbox" checked={!out} onChange={(e) => toggle([i.id], e.target.checked)} />
                          <span className="flex justify-center"><KindMark kind={i.kind} faded={out} /></span>
                          <span className="truncate" title={i.title}>
                            <span className={out ? 'line-through decoration-gray-300' : ''}>{i.title}</span>
                            {existing.has(i.title.trim()) && <span className="ml-1.5 text-xs text-gray-400">이미 있음</span>}
                          </span>
                          <span className="hidden text-xs tabular-nums text-gray-500 sm:block">{offsetText(i)}</span>
                          <span className={`text-right tabular-nums ${r && !out ? '' : 'text-gray-400'}`}>{md(r?.end)}</span>
                        </label>
                      );
                    })}
                  </div>
                );
              })}
            </div>
          </Section>
        </>
      )}
    </Modal>
  );
}

// --- 엑셀 붙여넣기 ------------------------------------------------------------------------------------

const PASTE_COLS = ['분야', '작업', '시작', '종료', '담당', '보할'];

/** 엑셀 붙여넣기: column header over the box → preview as you paste → adds the rows without errors. */
export function PasteDialog({ projectId, onClose, onChanged }: { projectId: number; onClose: () => void; onChanged: () => void }) {
  const [text, setText] = useState('');
  const [preview, setPreview] = useState<{ text: string; result: PasteResult } | null>(null);
  const { error, setError, busy, run } = useRun();
  useEffect(() => {
    if (!text.trim()) return;
    const t = setTimeout(() => {
      pasteItems(projectId, text, false).then((result) => setPreview({ text, result }), (e: Error) => setError(e.message));
    }, 350);
    return () => clearTimeout(t);
  }, [projectId, text, setError]);
  const result = text.trim() && preview?.text === text ? preview.result : null;
  const ok = result ? result.rows.length - result.errors : 0;

  // confirm sends only the lines without errors (line = 1-based line of the pasted text, as the server counts)
  const add = () => run(async () => {
    const good = new Set(result!.rows.filter((r) => !r.error).map((r) => r.line));
    await pasteItems(projectId, text.split(/\r?\n/).filter((_, i) => good.has(i + 1)).join('\n'), true);
    onChanged();
  });

  return (
    <Modal title="엑셀 붙여넣기" subtitle="엑셀에서 아래 순서대로 열을 골라 복사하세요. 시작을 비우면 마일스톤입니다." onClose={onClose} size="lg"
      footer={<>
        <Summary>{result && (result.errors ? `${result.rows.length}행 중 오류 ${result.errors}행은 빼고 추가합니다.` : '고정 날짜 항목으로 들어갑니다.')}</Summary>
        <button type="button" className={btn} onClick={onClose}>취소</button>
        <button type="button" className={primary} disabled={busy || !ok} onClick={add}>{ok ? `${ok}건 추가` : '추가'}</button>
      </>}>
      <div>
        <div className="grid grid-cols-6 divide-x divide-line rounded-t-lg border border-b-0 border-line bg-paper text-xs text-gray-500">
          {PASTE_COLS.map((c) => <span key={c} className="truncate px-2.5 py-1.5">{c}</span>)}
        </div>
        <textarea aria-label="붙여 넣을 표" rows={6} value={text} spellCheck={false} onChange={(e) => setText(e.target.value)}
          className="block w-full resize-y whitespace-pre rounded-b-lg border border-line px-3 py-2 font-mono text-xs leading-relaxed outline-none focus:border-brand"
          placeholder={'공정·기계\tP&ID 2차\t2027-03-02\t2027-04-13\t이가온\t15'} />
      </div>
      <Alert error={error} />
      {result && (
        <Section title="미리보기" hint={`${result.rows.length}행 · ${result.errors ? `오류 ${result.errors}행` : '모두 정상'}`}>
          <div className="max-h-72 overflow-auto rounded-lg border border-line">
            <table className="w-full whitespace-nowrap text-left text-sm">
              <thead className="sticky top-0 bg-paper text-xs text-gray-500">
                <tr>
                  <th className={`${head} text-right`}>행</th><th className={head}>분야</th><th className={head}>작업</th><th className={head}>시작</th>
                  <th className={head}>종료</th><th className={head}>담당</th><th className={`${head} text-right`}>보할</th><th className={head}>확인</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-soft">
                {result.rows.map((r) => (
                  <tr key={r.line} className={r.error ? 'bg-red-50' : ''}>
                    <td className="px-3 py-2 text-right tabular-nums text-gray-400">{r.line}</td>
                    <td className={cell}>{r.discipline || '공통'}</td>
                    <td className={cell} title={r.title}>{r.title}{r.kind === 'MILESTONE' && !r.error && <span className="ml-1.5 text-xs text-gray-400">마일스톤</span>}</td>
                    <td className="px-3 py-2 tabular-nums">{r.kind === 'TASK' ? md(r.start) : ''}</td>
                    <td className="px-3 py-2 tabular-nums">{r.end ? md(r.end) : ''}</td>
                    <td className={cell}>{r.owner}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{r.weight ?? ''}</td>
                    <td className={`px-3 py-2 ${r.error ? 'text-danger' : 'text-gray-400'}`}>{r.error ?? '정상'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Section>
      )}
    </Modal>
  );
}
