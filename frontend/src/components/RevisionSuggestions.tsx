import { useCallback, useEffect, useState, type ReactNode } from 'react';
import {
  acceptRevision, listRevisions, pairRevisions, rediffRevision, rejectSuggestion, unpairRevision,
  bulkAccept, type ChangeType, type ObligationPayload, type QuoteCheck, type RevisionSuggestion,
} from '../api/review';
import { api, ApiError } from '../api/client';
import {
  completeSiteImplementation, listSiteImplementations, updateSiteImplementation, type SiteImplementation,
} from '../api/revisions';
import { usePrompt } from './PromptDialog';
import { ActionBar, DetailHead, MARK, QuoteBox, ReviewItem } from './ReviewParts';
import { FREQ, READ_ONLY, type Action } from './reviewActions';
import { Alert } from './ui/Alert';
import { Badge, type Tone } from './ui/Badge';
import { Button } from './ui/Button';
import { Input, Textarea } from './ui/Field';
import { cn } from './ui/cn';

// ④ version-diff: compare view of a project's revision suggestions (summary badges, list, v1 | v2 table, approve).

const TYPE: Record<ChangeType, { label: string; cls: string; tone: Tone }> = {
  ADDED: { label: '추가', cls: 'text-information', tone: 'info' },
  REMOVED: { label: '삭제', cls: 'text-danger', tone: 'danger' },
  CHANGED: { label: '변경', cls: 'text-warn', tone: 'warning' },
  MINOR: { label: '경미', cls: 'text-body', tone: 'neutral' },
  UNCHANGED: { label: '같음', cls: 'text-mute', tone: 'neutral' },
};
const ORDER: ChangeType[] = ['CHANGED', 'REMOVED', 'ADDED', 'MINOR', 'UNCHANGED'];
const FLAG: Record<string, string> = {
  NEEDS_CONFIRM: '매칭 확인 필요', KEY_SAME_TEXT_DIFF: '같은 번호·다른 본문', DUPLICATE_KEY: '번호 중복',
};
const QC: Record<QuoteCheck, { label: string; tone: Tone }> = {
  MISMATCH: { label: '원문과 불일치', tone: 'danger' },
  UNVERIFIABLE: { label: '원문 대조 필요', tone: 'warning' },
  VERIFIED: { label: '원문과 일치', tone: 'success' },
};
const RESTRUCTURED = ['매칭 분리', '매칭 결합'];
const STALE = '비교 후 의무사항이 수정됨';
const open = (s: RevisionSuggestion) => s.status === 'PENDING' || s.status === 'SUBMITTED';
const hidden = (s: RevisionSuggestion) => s.payload.change_type === 'UNCHANGED' && !s.payload.match.flags.length;
const newRow = (s: RevisionSuggestion) => (s.editedPayload ?? s.payload).new;
/** CHANGED of frequency / frequency_text / period_text needs the next due date (design §4.1). */
const needsDate = (s: RevisionSuggestion) =>
  s.payload.change_type === 'CHANGED' && !s.payload.prev_obligation_closed &&
  s.payload.changed_fields.some((f) => ['frequency', 'frequency_text', 'period_text'].includes(f));
const msg = (e: unknown) => (e instanceof Error ? e.message : '처리하지 못했습니다.');
const freq = (r: ObligationPayload) => `${FREQ[r.frequency] ?? r.frequency}${r.frequency_text ? ` (${r.frequency_text})` : ''}`;
/** One line of what changed, for the list: numbers first, else the frequency. */
const changeSummary = (s: RevisionSuggestion) => {
  const p = s.payload;
  if (p.numeric_changes.length) return p.numeric_changes.map((n) => `${n.before ?? '—'} → ${n.after ?? '—'}`).join(', ');
  if (p.prev && p.new && freq(p.prev) !== freq(p.new)) return `주기 ${freq(p.prev)} → ${freq(p.new)}`;
  return null;
};

export default function RevisionSuggestions({ projectId, readOnly = false }: { projectId: number; readOnly?: boolean }) {
  const [items, setItems] = useState<RevisionSuggestion[]>([]);
  const [selected, setSelected] = useState<number | null>(null);
  const [showUnchanged, setShowUnchanged] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const [site, setSite] = useState<SiteImplementation[]>([]);
  const load = useCallback(() => Promise.all([
    listRevisions(projectId).then(setItems, () => setItems([])),
    listSiteImplementations(projectId).then(setSite, () => setSite([])),
  ]), [projectId]);
  useEffect(() => { load(); }, [load]);

  const pending = items.filter(open);
  // rejected (not restructured) rows still point at the old document: the live obligation kept its v1 basis
  const keptOld = items.filter((s) => s.status === 'REJECTED' && !RESTRUCTURED.includes(s.rejectReason ?? '')
    && s.payload.prev_obligation_id != null);
  if (!pending.length && !keptOld.length && !site.length) return null;

  const rank = (s: RevisionSuggestion) => (s.payload.match.flags.length ? -1 : ORDER.indexOf(s.payload.change_type));
  const visible = pending.filter((s) => !hidden(s)).sort((a, b) => rank(a) - rank(b));
  const unchanged = pending.filter(hidden);
  const shown = showUnchanged ? [...visible, ...unchanged] : visible;
  const current = items.find((s) => s.id === selected) ?? visible[0] ?? null;
  // 승인하고 다음: the next row in list order, else the first one left
  const next = (id: number) => {
    const i = shown.findIndex((s) => s.id === id);
    setSelected((shown.slice(i + 1)[0] ?? shown.find((s) => s.id !== id))?.id ?? null);
  };

  const run = async (f: () => Promise<unknown>, done?: string) => {
    setNotice(null);
    try {
      await f();
      if (done) setNotice(done);
      await load();
    } catch (e) {
      setNotice(msg(e));
      throw e;
    }
  };
  const confirmUnchanged = () => run(async () => {
    const res = await bulkAccept(unchanged.map((s) => s.id));
    const failed = res.filter((r) => !r.ok);
    if (failed.length) throw new ApiError(422, `${failed.length}건 실패: ${failed[0].reason}`);
  }, `같음 ${unchanged.length}건 확인`).catch(() => {});

  const row = (s: RevisionSuggestion) => {
    const p = s.payload;
    const r = newRow(s) ?? p.prev;
    const t = TYPE[p.change_type];
    const key = p.prev && p.new && p.prev.citation_key !== p.new.citation_key
      ? `${p.prev.citation_key} → ${p.new.citation_key}` : r?.citation_key;
    const page = p.change_type === 'REMOVED' ? (p.prev?.page_no != null ? ` · v1 ${p.prev.page_no}쪽` : '') : (r?.page_no != null ? ` · ${r.page_no}쪽` : '');
    const summary = changeSummary(s);
    return (
      <ReviewItem key={s.id} active={s.id === current?.id} onClick={() => setSelected(s.id)} muted={!open(s)}
        kind={<>
          <b className={t.cls}>{t.label}</b>
          {!open(s) && <span className="text-violet-800">v1 근거</span>}
          {p.prev_obligation_closed && <span className="text-mute">종료된 의무</span>}
          {p.match.flags.map((f) => <span key={f} className="text-information">{FLAG[f] ?? f}</span>)}
        </>}
        where={<>{key}{page}</>}
        title={r?.summary}
        sub={summary}
      />
    );
  };

  return (
    <div className="grid grid-cols-1 items-start gap-5 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
      <section aria-label="개정 비교 목록" className="min-w-0 rounded border border-line bg-white">
        <div className="flex flex-col gap-2.5 border-b border-line px-4 py-3">
          <h2 className="text-[17px] font-bold text-ink">개정 비교 · 남은 {pending.length}건</h2>
          <ChangeCounts pending={pending}>
            <span className="ml-1 text-sm text-body">현장 반영 {site.filter((r) => r.doneAt).length}/{site.length}</span>
          </ChangeCounts>
          {unchanged.length > 0 && (
            <Button size="sm" variant="secondary" onClick={confirmUnchanged} disabled={readOnly}
              title={readOnly ? READ_ONLY : undefined} className="self-start">
              같음 {unchanged.length}건 일괄 확인
            </Button>
          )}
        </div>
        {notice && <p role="status" className="border-b border-line bg-paper px-4 py-2 text-sm text-body">{notice}</p>}
        <ul className="lg:max-h-[640px] lg:overflow-y-auto">
          {shown.map(row)}
          {unchanged.length > 0 && (
            <li>
              <button type="button" onClick={() => setShowUnchanged((v) => !v)}
                className="w-full border-b border-line-soft px-4 py-3 text-left text-[15px] text-brand-text hover:bg-paper">
                같음 {unchanged.length}건 {showUnchanged ? '접기' : '보기'}
              </button>
            </li>
          )}
          {keptOld.map(row)}
        </ul>
        <SiteList projectId={projectId} rows={site.filter((r) => !r.doneAt)} run={run} readOnly={readOnly} />
      </section>
      <section aria-label="선택한 개정 조건" className="flex min-w-0 flex-col rounded border border-line bg-white">
        {current ? (
          <Detail key={current.id + ':' + current.version} s={current} all={pending} run={run} readOnly={readOnly}
            remaining={pending.length} onNext={() => next(current.id)} onReplaced={(id) => setSelected(id)} />
        ) : (
          <p className="px-5 py-6 text-[15px] text-body">왼쪽에서 조건을 고르면 v1과 v2를 나란히 봅니다.</p>
        )}
      </section>
    </div>
  );
}

/** 추가·삭제·변경·같음 counts of the open revision rows, as badges. */
export function ChangeCounts({ pending, children }: { pending: RevisionSuggestion[]; children?: ReactNode }) {
  const count = (t: ChangeType) => pending.filter((s) => s.payload.change_type === t).length;
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      <Badge tone="info">추가 {count('ADDED')}</Badge>
      <Badge tone="danger">삭제 {count('REMOVED')}</Badge>
      <Badge tone="warning">변경 {count('CHANGED')}</Badge>
      {count('MINOR') > 0 && <Badge>경미 {count('MINOR')}</Badge>}
      <Badge>같음 {count('UNCHANGED')}</Badge>
      {children}
    </div>
  );
}

/** 현장 반영 목록: open rows of approved CHANGED/ADDED, with assignee, target date (→ D-day alerts) and note. */
function SiteList({ projectId, rows, run, readOnly }: { projectId: number; rows: SiteImplementation[]; run: Run; readOnly: boolean }) {
  const [members, setMembers] = useState<{ userId: number; name: string }[]>([]);
  useEffect(() => {
    api<{ userId: number; name: string }[]>(`/api/projects/${projectId}/members`).then(setMembers, () => setMembers([]));
  }, [projectId]);
  if (!rows.length) return null;
  const save = (r: SiteImplementation) => run(() => updateSiteImplementation(r), '현장 반영 정보를 저장했습니다.').catch(() => {});
  const control = 'h-10 rounded border border-input bg-white px-2 text-[15px] disabled:bg-line disabled:text-disabled';
  return (
    <div className="border-t border-line px-4 py-3 text-sm">
      <h3 className="text-[15px] font-bold text-ink">현장 반영 대기 {rows.length}건</h3>
      <ul className="mt-2 divide-y divide-line-soft">
        {rows.map((r) => (
          <li key={r.suggestionId} className="flex flex-wrap items-center gap-2 py-2">
            <b className={`whitespace-nowrap text-[13px] ${TYPE[r.changeType].cls}`}>{TYPE[r.changeType].label}</b>
            <span className="text-[13px] text-mute">{r.citationKey}</span>
            <span className="min-w-0 basis-full truncate text-ink">{r.summary}</span>
            <select aria-label="담당자" value={r.assigneeId ?? ''} disabled={readOnly}
              onChange={(e) => save({ ...r, assigneeId: Number(e.target.value) })} className={control}>
              {r.assigneeId == null && <option value="">담당자 없음</option>}
              {members.map((m) => <option key={m.userId} value={m.userId}>{m.name}</option>)}
            </select>
            <input type="date" aria-label="목표일" defaultValue={r.targetDate ?? ''} disabled={readOnly}
              onBlur={(e) => { if ((e.target.value || null) !== r.targetDate) save({ ...r, targetDate: e.target.value || null }); }}
              className={control} />
            <input aria-label="메모" placeholder="메모" defaultValue={r.note ?? ''} maxLength={1000} disabled={readOnly}
              onBlur={(e) => { if ((e.target.value || null) !== r.note) save({ ...r, note: e.target.value || null }); }}
              className={cn(control, 'w-40')} />
            <Button size="sm" variant="secondary" disabled={readOnly} title={readOnly ? READ_ONLY : undefined}
              onClick={() => run(() => completeSiteImplementation(r.suggestionId), '반영 완료했습니다.').catch(() => {})}>반영 완료</Button>
          </li>
        ))}
      </ul>
    </div>
  );
}

type Run = (f: () => Promise<unknown>, done?: string) => Promise<void>;

function Detail({ s, all, run, readOnly, remaining, onNext, onReplaced }: {
  s: RevisionSuggestion; all: RevisionSuggestion[]; run: Run; readOnly: boolean; remaining: number;
  onNext: () => void; onReplaced: (id: number | null) => void;
}) {
  const p = s.payload;
  const next = newRow(s);
  const [due, setDue] = useState('');
  const [absent, setAbsent] = useState(false);
  const [stale, setStale] = useState(false);
  const [partner, setPartner] = useState<number | ''>('');
  const [memo, setMemo] = useState('');
  const [ask, promptUi] = usePrompt();
  const type = p.change_type;
  const t = TYPE[type];
  const partners = all.filter((o) => o.runId === s.runId && o.payload.change_type === (type === 'ADDED' ? 'REMOVED' : 'ADDED'));
  const blocked = (needsDate(s) && !due) || (type === 'REMOVED' && !absent);
  const shown = next ?? p.prev;
  const qc = shown?.quote_check ? QC[shown.quote_check] : null;

  const accept = () => run(() => acceptRevision(s, needsDate(s) ? due : null, absent), '승인했습니다.')
    .then(onNext, (e) => { if (e instanceof ApiError && e.status === 409 && e.message === STALE) setStale(true); });
  const reject = async () => {
    const reason = (await ask('반려 사유', memo))?.trim();
    if (reason) run(() => rejectSuggestion(s, reason), '반려했습니다. 의무사항은 v1 근거로 남습니다.').catch(() => {});
  };
  const unpair = () => run(() => unpairRevision(s), '분리했습니다: 추가 1건 + 삭제 1건').then(() => onReplaced(null), () => {});
  const pair = () => {
    const o = all.find((x) => x.id === partner);
    if (!o) return;
    const [added, removed] = type === 'ADDED' ? [s, o] : [o, s];
    let merged: number | null = null;
    run(async () => { merged = (await pairRevisions(added, removed)).id; }, '결합했습니다. 판정을 다시 계산했습니다.')
      .then(() => onReplaced(merged), () => {});
  };
  const actions: Action[] = open(s) ? [
    { label: '반려', variant: 'tertiary', onClick: reject },
    { label: '승인하고 다음', variant: 'primary', onClick: accept, disabled: blocked,
      title: blocked ? (type === 'REMOVED' ? 'v2 원문에 없음을 확인해야 승인할 수 있습니다' : '다음 기한일을 넣어야 승인할 수 있습니다') : undefined },
  ] : [];
  const state = open(s) ? (s.status === 'SUBMITTED' ? '승인 요청됨' : '대기 중') : '반려됨';

  return (
    <>
      {promptUi}
      <DetailHead state={<span className={t.cls}>{t.label} · {state}</span>} title={shown?.summary ?? '—'}
        badge={qc && <Badge tone={qc.tone}>{qc.label}</Badge>} />
      <div className="flex min-w-0 flex-col gap-5 px-5 py-5">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[440px] border-collapse text-[15px]">
            <thead>
              <tr className="bg-paper text-left">
                <th scope="col" className="w-24 border-b border-line px-3 py-2 font-normal text-body">항목</th>
                <th scope="col" className="border-b border-line px-3 py-2 font-normal text-body">v1 (이전)</th>
                <th scope="col" className="border-b border-line px-3 py-2 font-normal text-body">v2 (새 버전)</th>
              </tr>
            </thead>
            <tbody>
              <Compare label="조건번호" a={p.prev?.citation_key} b={next?.citation_key} />
              <Compare label="요약" a={p.prev?.summary} b={next?.summary} />
              <Compare label="주기" a={p.prev && freq(p.prev)} b={next && freq(next)} />
              <tr>
                <th scope="row" className="px-3 py-2.5 text-left align-top font-normal text-body">본문</th>
                <td className="break-words px-3 py-2.5 align-top leading-relaxed text-body">
                  {p.prev ? (next ? <WordDiff a={p.prev.requirement_text} b={next.requirement_text} side="old" /> : p.prev.requirement_text) : <Missing side="old" />}
                </td>
                <td className="break-words px-3 py-2.5 align-top leading-relaxed text-ink">
                  {next ? (p.prev ? <WordDiff a={p.prev.requirement_text} b={next.requirement_text} side="new" /> : next.requirement_text) : <Missing side="new" />}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        {(p.changed_fields.length > 0 || p.numeric_changes.length > 0) && (
          <p className="text-sm text-body">
            {p.numeric_changes.length > 0 && <>숫자: <b className="text-ink">{p.numeric_changes.map((n) => `${n.before ?? '—'} → ${n.after ?? '—'}`).join(', ')}</b> · </>}
            바뀐 항목: {p.changed_fields.join(', ') || '—'}
          </p>
        )}

        {shown?.source_quote && (
          <QuoteBox caption={`원문 인용 · ${next ? 'v2' : 'v1'}${shown.page_no != null ? `, ${shown.page_no}쪽` : ''}`}>
            {next && p.prev?.source_quote ? <WordDiff a={p.prev.source_quote} b={shown.source_quote} side="new" /> : shown.source_quote}
          </QuoteBox>
        )}

        {!open(s) ? (
          <p className="rounded bg-violet-50 px-4 py-3 text-[15px] text-violet-900">반려됨: 라이브 의무사항은 바뀌지 않았고 v1 근거로 남아 있습니다.</p>
        ) : (
          <>
            {needsDate(s) && (
              <Input type="date" label="다음 기한일" required hint="주기·기간이 바뀌어 다음 기한일이 있어야 승인됩니다."
                value={due} onChange={(e) => setDue(e.target.value)} className="max-w-xs" />
            )}
            {type === 'REMOVED' && (
              <label className="flex items-start gap-2 rounded border border-danger-border bg-danger-bg px-4 py-3 text-[15px] text-danger">
                <input type="checkbox" checked={absent} onChange={(e) => setAbsent(e.target.checked)} className="mt-1 h-4 w-4 accent-brand" />
                v2 원문에 이 조건이 없음을 확인함 (승인하면 의무사항이 종료되고 알림이 멈춥니다. 일괄 승인 불가)
              </label>
            )}
            {stale && (
              <Alert tone="warning" title={STALE}>
                <Button size="sm" variant="tertiary" className="mt-2" disabled={readOnly}
                  onClick={() => run(() => rediffRevision(s), '다시 비교했습니다.').catch(() => {})}>다시 비교</Button>
              </Alert>
            )}
            {(['CHANGED', 'MINOR', 'UNCHANGED'].includes(type) || ((type === 'ADDED' || type === 'REMOVED') && partners.length > 0)) && (
              <div className="flex flex-col gap-2">
                <p className="text-[15px] font-bold text-ink">짝 맞추기</p>
                {['CHANGED', 'MINOR', 'UNCHANGED'].includes(type) ? (
                  <div className="flex flex-wrap items-center gap-2 text-sm text-body">
                    다른 조건끼리 잘못 짝지어졌다면 추가 1건과 삭제 1건으로 나눕니다.
                    <Button size="sm" variant="tertiary" onClick={unpair} disabled={readOnly} title={readOnly ? READ_ONLY : undefined}>분리</Button>
                  </div>
                ) : (
                  <div className="flex flex-wrap items-center gap-2">
                    <select value={partner} onChange={(e) => setPartner(e.target.value ? Number(e.target.value) : '')}
                      aria-label="결합할 제안" className="h-10 min-w-0 max-w-full rounded border border-input bg-white px-2 text-[15px]">
                      <option value="">결합할 {type === 'ADDED' ? '삭제' : '추가'} 선택</option>
                      {partners.map((o) => {
                        const r = newRow(o) ?? o.payload.prev;
                        return <option key={o.id} value={o.id}>{r?.citation_key} {r?.summary}</option>;
                      })}
                    </select>
                    <Button size="sm" variant="tertiary" disabled={readOnly || partner === ''} onClick={pair}
                      title={readOnly ? READ_ONLY : undefined}>결합</Button>
                  </div>
                )}
              </div>
            )}
            <Textarea label="검토 메모 (선택)" hint="반려할 때 사유 칸에 미리 채워집니다." rows={3} value={memo}
              onChange={(e) => setMemo(e.target.value)} maxLength={500} />
          </>
        )}
      </div>
      {actions.length > 0 && <ActionBar remaining={remaining} actions={actions} readOnly={readOnly} />}
    </>
  );
}

/** One row of the v1 | v2 table: when the values differ the old one is struck through and the new one highlighted. */
function Compare({ label, a, b }: { label: string; a?: string | null; b?: string | null }) {
  const changed = a != null && b != null && a !== b;
  const cell = 'border-b border-line-soft px-3 py-2.5 align-top';
  return (
    <tr>
      <th scope="row" className={cn(cell, 'text-left font-normal text-body')}>{label}</th>
      <td className={cn(cell, changed ? 'text-mute line-through' : 'text-ink')}>{a ?? (b == null ? '—' : <Missing side="old" />)}</td>
      <td className={cn(cell, changed ? 'bg-warn-bg font-bold text-ink' : 'text-ink')}>{b ?? (a == null ? '—' : <Missing side="new" />)}</td>
    </tr>
  );
}

function Missing({ side }: { side: 'old' | 'new' }): ReactNode {
  return <span className="text-mute">{side === 'new' ? 'v2에서 찾지 못함' : '이전 버전에 없음'}</span>;
}

/** Word LCS diff; the old side strikes out removed words, the new side highlights added ones. */
function WordDiff({ a, b, side }: { a: string; b: string; side: 'old' | 'new' }) {
  const x = a.split(/(\s+)/);
  const y = b.split(/(\s+)/);
  const lcs = Array.from({ length: x.length + 1 }, () => new Array<number>(y.length + 1).fill(0));
  for (let i = x.length - 1; i >= 0; i--)
    for (let j = y.length - 1; j >= 0; j--)
      lcs[i][j] = x[i] === y[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
  const out: { t: string; changed: boolean }[] = [];
  let i = 0;
  let j = 0;
  while (i < x.length || j < y.length) {
    if (i < x.length && j < y.length && x[i] === y[j]) { out.push({ t: x[i], changed: false }); i++; j++; }
    else if (j >= y.length || (i < x.length && lcs[i + 1][j] >= lcs[i][j + 1])) { if (side === 'old') out.push({ t: x[i], changed: true }); i++; }
    else { if (side === 'new') out.push({ t: y[j], changed: true }); j++; }
  }
  return <>{out.map((w, k) => (w.changed && w.t.trim()
    ? side === 'old'
      ? <del key={k} className={cn('text-mute', /\d/.test(w.t) && 'font-bold')}>{w.t}</del>
      : <mark key={k} className={cn(MARK, /\d/.test(w.t) && 'font-bold')}>{w.t}</mark>
    : <span key={k}>{w.t}</span>))}</>;
}
