import { useCallback, useEffect, useState } from 'react';
import {
  acceptRevision, listRevisions, pairRevisions, rediffRevision, rejectSuggestion, unpairRevision,
  bulkAccept, type ChangeType, type ObligationPayload, type RevisionSuggestion,
} from '../api/review';
import { ApiError } from '../api/client';

// ④ version-diff: compare view of a project's revision suggestions (summary bar, v1 | v2 word diff, approve).

const TYPE: Record<ChangeType, { label: string; sign: string; cls: string }> = {
  ADDED: { label: '추가', sign: '+', cls: 'bg-emerald-100 text-[#0F6E56]' },
  REMOVED: { label: '삭제', sign: '−', cls: 'bg-red-100 text-[#991B1B]' },
  CHANGED: { label: '변경', sign: '~', cls: 'bg-amber-100 text-[#B45309]' },
  MINOR: { label: '경미', sign: '', cls: 'bg-gray-100 text-gray-600' },
  UNCHANGED: { label: '변경 없음', sign: '', cls: 'bg-gray-100 text-gray-500' },
};
const ORDER: ChangeType[] = ['CHANGED', 'REMOVED', 'ADDED', 'MINOR', 'UNCHANGED'];
const FLAG: Record<string, string> = {
  NEEDS_CONFIRM: '매칭 확인 필요', KEY_SAME_TEXT_DIFF: '같은 번호·다른 본문', DUPLICATE_KEY: '번호 중복',
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

export default function RevisionSuggestions({ projectId }: { projectId: number }) {
  const [items, setItems] = useState<RevisionSuggestion[]>([]);
  const [selected, setSelected] = useState<number | null>(null);
  const [showUnchanged, setShowUnchanged] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(() => listRevisions(projectId).then(setItems, () => setItems([])), [projectId]);
  useEffect(() => { load(); }, [load]);

  const pending = items.filter(open);
  // rejected (not restructured) rows still point at the old document: the live obligation kept its v1 basis
  const keptOld = items.filter((s) => s.status === 'REJECTED' && !RESTRUCTURED.includes(s.rejectReason ?? '')
    && s.payload.prev_obligation_id != null);
  if (!pending.length && !keptOld.length) return null;

  const rank = (s: RevisionSuggestion) => (s.payload.match.flags.length ? -1 : ORDER.indexOf(s.payload.change_type));
  const visible = pending.filter((s) => !hidden(s)).sort((a, b) => rank(a) - rank(b));
  const unchanged = pending.filter(hidden);
  const count = (t: ChangeType) => pending.filter((s) => s.payload.change_type === t).length;
  const current = items.find((s) => s.id === selected) ?? null;

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
  }, `변경 없음 ${unchanged.length}건 확인`).catch(() => {});

  return (
    <section className="mt-5 rounded-xl border border-[#E5E3DC] bg-white shadow-sm">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2 border-b border-[#E5E3DC] px-4 py-3 text-sm">
        <h2 className="text-sm font-semibold text-gray-800">개정 비교 · 남은 {pending.length}건</h2>
        <span className="text-[#0F6E56]">+{count('ADDED')} 추가</span>
        <span className="text-[#991B1B]">−{count('REMOVED')} 삭제</span>
        <span className="text-[#B45309]">~{count('CHANGED')} 변경</span>
        <span className="text-gray-600">경미 {count('MINOR')}</span>
        <span className="text-gray-500">· 변경 없음 {unchanged.length}</span>
        {unchanged.length > 0 && (
          <button type="button" onClick={confirmUnchanged}
            className="rounded-md bg-[#1F4E79] px-2.5 py-1 text-xs font-medium text-white hover:bg-[#173d5f]">
            변경 없음 {unchanged.length}건 일괄 확인
          </button>
        )}
      </div>
      {notice && <p role="status" className="border-b border-[#E5E3DC] bg-[#F8F7F2] px-4 py-2 text-sm text-gray-700">{notice}</p>}
      <div className="grid grid-cols-1 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
        <ul className="divide-y divide-[#E5E3DC] border-[#E5E3DC] lg:border-r">
          {(showUnchanged ? [...visible, ...unchanged] : visible).map((s) => (
            <Row key={s.id} s={s} active={s.id === selected} onClick={() => setSelected(s.id)} />
          ))}
          {unchanged.length > 0 && (
            <li>
              <button type="button" onClick={() => setShowUnchanged((v) => !v)}
                className="w-full px-4 py-2.5 text-left text-sm text-gray-600 hover:bg-[#F8F7F2]">
                변경 없음 {unchanged.length}건 {showUnchanged ? '접기' : '보기'}
              </button>
            </li>
          )}
          {keptOld.map((s) => <Row key={s.id} s={s} active={s.id === selected} onClick={() => setSelected(s.id)} />)}
        </ul>
        {current ? (
          <Detail key={current.id + ':' + current.version} s={current} all={pending} run={run}
            onReplaced={(id) => setSelected(id)} />
        ) : (
          <p className="px-4 py-6 text-sm text-gray-500">왼쪽에서 조건을 고르면 이전 | 새 버전을 나란히 봅니다.</p>
        )}
      </div>
    </section>
  );
}

function Row({ s, active, onClick }: { s: RevisionSuggestion; active: boolean; onClick: () => void }) {
  const p = s.payload;
  const row = newRow(s) ?? p.prev;
  const t = TYPE[p.change_type];
  const key = p.prev && p.new && p.prev.citation_key !== p.new.citation_key
    ? `${p.prev.citation_key} → ${p.new.citation_key}` : row?.citation_key;
  return (
    <li>
      <button type="button" onClick={onClick}
        className={`w-full px-4 py-2.5 text-left text-sm ${active ? 'bg-[#EEF3F8]' : 'hover:bg-[#F8F7F2]'} ${open(s) ? '' : 'opacity-70'}`}>
        <div className="flex flex-wrap items-center gap-2">
          <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${t.cls}`}>{t.sign}{t.label}</span>
          <span className="font-mono text-xs text-gray-500">{key}</span>
          <span className="min-w-0 flex-1 truncate text-gray-800">{row?.summary}</span>
          {!open(s) && <span className="rounded-full bg-violet-100 px-2 py-0.5 text-xs text-violet-800">v1 근거</span>}
          {p.prev_obligation_closed && <span className="text-xs text-gray-500">종료된 의무</span>}
          {p.match.flags.map((f) => (
            <span key={f} className="rounded-full bg-sky-100 px-2 py-0.5 text-xs text-sky-800">{FLAG[f] ?? f}</span>
          ))}
        </div>
      </button>
    </li>
  );
}

type Run = (f: () => Promise<unknown>, done?: string) => Promise<void>;

function Detail({ s, all, run, onReplaced }: {
  s: RevisionSuggestion; all: RevisionSuggestion[]; run: Run; onReplaced: (id: number | null) => void;
}) {
  const p = s.payload;
  const next = newRow(s);
  const [due, setDue] = useState('');
  const [absent, setAbsent] = useState(false);
  const [stale, setStale] = useState(false);
  const [partner, setPartner] = useState<number | ''>('');
  const type = p.change_type;
  const partners = all.filter((o) => o.runId === s.runId && o.payload.change_type === (type === 'ADDED' ? 'REMOVED' : 'ADDED'));
  const blocked = (needsDate(s) && !due) || (type === 'REMOVED' && !absent);

  const accept = () => run(() => acceptRevision(s, needsDate(s) ? due : null, absent), '승인했습니다.')
    .catch((e) => { if (e instanceof ApiError && e.status === 409 && e.message === STALE) setStale(true); });
  const reject = () => {
    const reason = window.prompt('거절 사유');
    if (reason) run(() => rejectSuggestion(s, reason), '거절했습니다. 의무사항은 v1 근거로 남습니다.').catch(() => {});
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

  return (
    <div className="min-w-0 space-y-4 px-4 py-4 text-sm">
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <Side title="v1 (이전)" row={p.prev} other={next} side="old" />
        <Side title="v2 (새 버전)" row={next} other={p.prev} side="new" />
      </div>
      {(p.changed_fields.length > 0 || p.numeric_changes.length > 0) && (
        <p className="text-xs text-gray-500">
          {p.numeric_changes.length > 0 && <>숫자: <b className="text-gray-800">{p.numeric_changes.map((n) => `${n.before ?? '—'} → ${n.after ?? '—'}`).join(', ')}</b> · </>}
          바뀐 항목: {p.changed_fields.join(', ') || '—'}
        </p>
      )}

      {!open(s) ? (
        <p className="rounded-lg bg-violet-50 px-3 py-2 text-violet-900">거절됨: 라이브 의무사항은 바뀌지 않았고 v1 근거로 남아 있습니다.</p>
      ) : (
        <div className="space-y-3 border-t border-[#E5E3DC] pt-3">
          {needsDate(s) && (
            <label className="flex flex-wrap items-center gap-2">
              다음 기한일 (주기·기간 변경, 필수)
              <input type="date" value={due} onChange={(e) => setDue(e.target.value)}
                className="rounded-md border border-[#E5E3DC] px-2 py-1" />
            </label>
          )}
          {type === 'REMOVED' && (
            <label className="flex items-start gap-2 rounded-lg bg-red-50 px-3 py-2 text-[#991B1B]">
              <input type="checkbox" checked={absent} onChange={(e) => setAbsent(e.target.checked)} className="mt-0.5" />
              v2 원문에 이 조건이 없음을 확인함 (승인하면 의무사항이 종료되고 알림이 멈춥니다. 일괄 승인 불가)
            </label>
          )}
          {stale && (
            <p className="flex flex-wrap items-center gap-2 rounded-lg bg-amber-50 px-3 py-2 text-[#B45309]">
              {STALE}
              <button type="button" onClick={() => run(() => rediffRevision(s), '다시 비교했습니다.').catch(() => {})}
                className="rounded-md border border-[#B45309] px-2 py-0.5 text-xs">다시 비교</button>
            </p>
          )}
          <div className="flex flex-wrap gap-2">
            <button type="button" disabled={blocked} onClick={accept}
              className="rounded-md bg-[#1F4E79] px-3 py-1.5 font-medium text-white disabled:opacity-40">승인</button>
            <button type="button" onClick={reject}
              className="rounded-md border border-[#E5E3DC] px-3 py-1.5 text-gray-700 hover:bg-[#F8F7F2]">거절</button>
            {['CHANGED', 'MINOR', 'UNCHANGED'].includes(type) && (
              <button type="button" onClick={unpair}
                className="rounded-md border border-[#E5E3DC] px-3 py-1.5 text-gray-700 hover:bg-[#F8F7F2]">분리</button>
            )}
            {(type === 'ADDED' || type === 'REMOVED') && partners.length > 0 && (
              <span className="flex items-center gap-1">
                <select value={partner} onChange={(e) => setPartner(e.target.value ? Number(e.target.value) : '')}
                  aria-label="결합할 제안" className="max-w-48 rounded-md border border-[#E5E3DC] px-2 py-1.5">
                  <option value="">결합할 {type === 'ADDED' ? '삭제' : '추가'} 선택</option>
                  {partners.map((o) => {
                    const r = newRow(o) ?? o.payload.prev;
                    return <option key={o.id} value={o.id}>{r?.citation_key} {r?.summary}</option>;
                  })}
                </select>
                <button type="button" disabled={partner === ''} onClick={pair}
                  className="rounded-md border border-[#E5E3DC] px-3 py-1.5 text-gray-700 hover:bg-[#F8F7F2] disabled:opacity-40">결합</button>
              </span>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function Side({ title, row, other, side }: {
  title: string; row: ObligationPayload | null; other: ObligationPayload | null; side: 'old' | 'new';
}) {
  return (
    <div className="min-w-0 rounded-lg border border-[#E5E3DC] p-3">
      <p className="text-xs font-semibold text-gray-500">{title} {row?.citation_key && <span className="font-mono">· {row.citation_key}</span>}</p>
      {row ? (
        <>
          <p className="mt-1 font-medium text-gray-800">{row.summary}</p>
          <p className="mt-1 break-words leading-relaxed text-gray-700">
            {other ? <WordDiff a={side === 'old' ? row.requirement_text : other.requirement_text}
              b={side === 'old' ? other.requirement_text : row.requirement_text} side={side} /> : row.requirement_text}
          </p>
          <p className="mt-1 text-xs text-gray-500">주기 {row.frequency}{row.frequency_text ? ` (${row.frequency_text})` : ''}</p>
        </>
      ) : (
        <p className="mt-1 text-gray-400">{side === 'new' ? 'v2에서 찾지 못함' : '이전 버전에 없음'}</p>
      )}
    </div>
  );
}

/** Word LCS diff; the old side shows deletions struck through in red, the new side additions in green. */
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
  const cls = side === 'old' ? 'bg-red-50 text-[#991B1B] line-through' : 'bg-emerald-50 text-[#0F6E56]';
  return <>{out.map((w, k) => (w.changed && w.t.trim()
    ? <span key={k} className={`${cls} ${/\d/.test(w.t) ? 'font-bold' : ''}`}>{w.t}</span>
    : <span key={k}>{w.t}</span>))}</>;
}
