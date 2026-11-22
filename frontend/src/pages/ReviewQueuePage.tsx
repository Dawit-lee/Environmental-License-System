import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { api, sessionUserId } from '../api/client';
import {
  acceptSuggestion, addSuggestion, bulkAccept, editSuggestion, effective, listSuggestions, pageImage, recordPageView,
  rejectSuggestion, reopenSuggestion, returnSuggestion, submitSuggestion, suggestionEvents,
  type Frequency, type ObligationPayload, type QuoteCheck, type Suggestion, type SuggestionEvent, type SuggestionStatus,
} from '../api/review';
import type { PermitItem, Project } from '../types';
import RevisionSuggestions from '../components/RevisionSuggestions';
import ScheduleSuggestionReview from '../components/ScheduleSuggestionReview';
import { usePrompt } from '../components/PromptDialog';
import { Help } from '../components/Onboarding';
import { Alert } from '../components/ui/Alert';

// ---------------------------------------------------------------------------
// ReviewQueuePage — ③ human review of permit conditions (obligations).
// Suggestions stay here until approved; nothing reaches the ledger or alerts
// before that. Buttons follow the member's can_self_approve / can_bulk_approve.
// ---------------------------------------------------------------------------

interface Member { userId: number; canSelfApprove: boolean; canBulkApprove: boolean }

const QC: Record<QuoteCheck, { label: string; cls: string }> = {
  MISMATCH: { label: '원문 불일치', cls: 'text-danger' },
  UNVERIFIABLE: { label: '원문 대조 필요', cls: 'text-body' },
  VERIFIED: { label: '원문 확인됨', cls: 'text-ok' },
};
const MANUAL_BADGE = { label: '직접 추가', cls: 'text-body' };
const ST: Record<SuggestionStatus, { label: string; cls: string }> = {
  PENDING: { label: '검토 대기', cls: 'text-body' },
  SUBMITTED: { label: '승인 요청됨', cls: 'text-sky-800' },
  ACCEPTED: { label: '승인', cls: 'text-ok' },
  EDITED_ACCEPTED: { label: '수정 후 승인', cls: 'text-ok' },
  REJECTED: { label: '거절', cls: 'text-danger' },
};
const QC_ORDER: Record<QuoteCheck | 'NONE', number> = { MISMATCH: 0, UNVERIFIABLE: 1, VERIFIED: 2, NONE: 3 };
const FREQ: Record<Frequency, string> = {
  ONCE: '1회', MONTHLY: '월', QUARTERLY: '분기', SEMIANNUAL: '반기', ANNUAL: '연', OTHER: '기타',
};
const TYPE: Record<string, string> = {
  MEASURE_SURVEY: '측정·조사', REPORT_SUBMIT: '보고·제출', FACILITY_OPERATION: '시설·운영기준', RECORD_KEEP: '기록·보존', OTHER: '기타',
};
// 환경영향평가 평가항목 (design §3.5): code → [분야, 항목]
const CATEGORY: Record<string, [string, string]> = {
  FLORA_FAUNA: ['자연생태', '동·식물상'], NATURAL_ASSET: ['자연생태', '자연환경자산'],
  WEATHER: ['대기', '기상'], AIR_QUALITY: ['대기', '대기질'], ODOR: ['대기', '악취'], GHG: ['대기', '온실가스'],
  WATER_QUALITY: ['수', '수질'], HYDROLOGY: ['수', '수리·수문'], MARINE: ['수', '해양환경'],
  LAND_USE: ['토지', '토지이용'], SOIL: ['토지', '토양'], TOPOGRAPHY: ['토지', '지형·지질'],
  RESOURCE_CIRCULATION: ['생활', '자원순환'], NOISE_VIBRATION: ['생활', '소음·진동'], RECREATION_LANDSCAPE: ['생활', '위락·경관'],
  PUBLIC_HEALTH: ['생활', '위생·공중보건'], RADIO_INTERFERENCE: ['생활', '전파장해'], SUNLIGHT: ['생활', '일조장해'],
  POPULATION: ['사회·경제', '인구'], HOUSING: ['사회·경제', '주거'], INDUSTRY: ['사회·경제', '산업'], OTHER: ['기타', '기타'],
};
const categoryLabel = (c: string) => (CATEGORY[c] ? `${CATEGORY[c][0]} › ${CATEGORY[c][1]}` : c);
const EVENT: Record<SuggestionEvent['type'], string> = {
  CREATED: '등록', EDITED: '수정', PAGE_VIEWED: '원문 열람', SUBMITTED: '승인 요청', RETURNED: '반려',
  ACCEPTED: '승인', REJECTED: '거절', REOPENED: '재검토',
  UNPAIRED: '나눔', PAIRED: '합침', REDIFFED: '변경분 다시 계산',
};

const EMPTY: ObligationPayload = {
  citation_raw: '', summary: '', requirement_text: '', type: 'MEASURE_SURVEY', category: 'AIR_QUALITY', frequency: 'MONTHLY',
};

const btn = 'rounded px-3.5 py-2 text-sm font-semibold transition focus:outline-none focus:ring-2 focus:ring-brand/30 disabled:cursor-not-allowed disabled:opacity-40';
const btnPrimary = `${btn} bg-brand text-white hover:bg-brand-hover`;
const btnGhost = `${btn} border border-line text-body hover:bg-paper`;
const input = 'w-full rounded border border-line px-3 py-1.5 text-sm';

/** With `projectId` (project tab) the project dropdown is hidden. */
export default function ReviewQueuePage({ projectId: fixedId }: { projectId?: number }) {
  const [projects, setProjects] = useState<Project[]>([]);
  const [projectId, setProjectId] = useState<number | null>(fixedId ?? null);
  const [permits, setPermits] = useState<PermitItem[]>([]);
  const [me, setMe] = useState<Member | null>(null);
  const [items, setItems] = useState<Suggestion[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [checked, setChecked] = useState<Set<number>>(new Set());
  const [filter, setFilter] = useState<'ALL' | SuggestionStatus>('ALL');
  const [mode, setMode] = useState<'view' | 'edit' | 'add'>('view');
  const [toast, setToast] = useState('');
  const [bulkResults, setBulkResults] = useState<{ id: number; ok: boolean; reason: string | null }[] | null>(null);
  const [error, setError] = useState('');

  const notify = (m: string) => {
    setToast(m);
    window.setTimeout(() => setToast(''), 2400);
  };

  useEffect(() => {
    if (fixedId) return;
    api<Project[]>('/api/projects').then((ps) => {
      setProjects(ps);
      if (ps.length) setProjectId(ps[0].id);
    }, (e: Error) => setError(e.message));
  }, [fixedId]);

  const reload = useCallback(async () => {
    if (projectId == null) return;
    setItems(await listSuggestions(projectId));
  }, [projectId]);

  useEffect(() => {
    if (projectId == null) return;
    setSelectedId(null);
    setChecked(new Set());
    setMode('view');
    Promise.all([
      listSuggestions(projectId),
      api<PermitItem[]>(`/api/projects/${projectId}/permits`),
      api<Member[]>(`/api/projects/${projectId}/members`),
    ]).then(([s, p, m]) => {
      setItems(s);
      setPermits(p);
      setMe(m.find((x) => x.userId === sessionUserId()) ?? null);
      setError('');
    }, (e: Error) => setError(e.message));
  }, [projectId]);

  // Runs an action; on failure (403/409/422) shows the server's reason and refreshes to the latest version.
  const run = async (action: () => Promise<unknown>, done: string) => {
    try {
      await action();
      notify(done);
    } catch (e) {
      notify((e as Error).message);
    }
    await reload();
  };

  const qc = (s: Suggestion) => effective(s).quote_check ?? 'NONE';
  const rows = items
    .filter((s) => filter === 'ALL' || s.status === filter)
    .sort((a, b) => QC_ORDER[qc(a)] - QC_ORDER[qc(b)] || a.id - b.id);
  // same citation_key twice in one run → warn on every such row (design §3.4 rule 6); saving is still allowed
  const keyCount = new Map<string, number>();
  items.forEach((s) => { const k = `${s.runId}:${effective(s).citation_key}`; if (effective(s).citation_key) keyCount.set(k, (keyCount.get(k) ?? 0) + 1); });
  const dupKeys = new Set([...keyCount].filter(([, n]) => n > 1).map(([k]) => k));
  const sel = items.find((s) => s.id === selectedId) ?? rows[0] ?? null;
  const lead = !!me?.canSelfApprove;
  const project = projects.find((p) => p.id === projectId);

  const bulk = async () => {
    const ids = items.filter((s) => checked.has(s.id) && s.status === 'PENDING').map((s) => s.id);
    if (!ids.length) return notify('일괄 승인할 항목을 체크하세요.');
    try {
      const res = await bulkAccept(ids);
      const failed = res.filter((r) => !r.ok);
      notify(`${res.length - failed.length}건 승인${failed.length ? ` · ${failed.length}건 제외` : ''}`);
      setBulkResults(res);
      setChecked(new Set(failed.map((r) => r.id))); // keep the excluded ones checked for a retry
    } catch (e) {
      notify((e as Error).message);
    }
    await reload();
  };

  return (
    <div className="min-h-full bg-paper">
      <div className={fixedId ? '' : 'py-8'}>
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div>
            {!fixedId && <p className="text-xs font-semibold tracking-wide text-mute">허가조건 검토 · {project?.name ?? '프로젝트 없음'}</p>}
            <h1 className="mt-1 flex items-center text-2xl font-bold text-brand">AI 검토<Help k="review" /></h1>
            <p className="mt-1 max-w-2xl text-sm text-mute">
              검토 중인 조건은 승인 전까지 인허가 대장과 기한 알림에 들어가지 않습니다. 원문과 다른 항목이 맨 위에 옵니다.
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-3">
            {!fixedId && <label className="flex items-center gap-2 text-sm text-body">
              프로젝트
              <select
                value={projectId ?? ''}
                onChange={(e) => setProjectId(Number(e.target.value))}
                className="rounded border border-line bg-white px-2.5 py-1.5 text-sm"
              >
                {projects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
              </select>
            </label>}
            {me && (
              <span className="text-xs text-mute">
                내 권한: {lead ? '혼자 승인' : '승인 요청만'}{me.canBulkApprove && ' · 일괄 승인'}
              </span>
            )}
          </div>
        </div>

        {error && <Alert tone="danger" className="mt-4">{error}</Alert>}

        {projectId && <RevisionSuggestions projectId={projectId} />}
        {projectId && <ScheduleSuggestionReview key={projectId} projectId={projectId} />}

        <div className="mt-5 flex flex-wrap gap-2">
          {(['ALL', 'PENDING', 'SUBMITTED', 'ACCEPTED', 'REJECTED'] as const).map((f) => (
            <button
              key={f}
              type="button"
              onClick={() => setFilter(f)}
              className={`rounded px-3.5 py-1.5 text-sm font-medium transition ${filter === f ? 'bg-brand text-white' : 'bg-white text-body hover:bg-line'}`}
            >
              {f === 'ALL' ? '전체' : ST[f].label} ({f === 'ALL' ? items.length : items.filter((s) => s.status === f).length})
            </button>
          ))}
        </div>

        <div className="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-2">
          {/* List */}
          <section className="min-w-0 rounded border border-line bg-white shadow-sm">
            <div className="flex flex-wrap items-center justify-between gap-2 border-b border-line px-4 py-3">
              <h2 className="text-sm font-semibold text-ink">검토 대상 조건 {rows.length}건</h2>
              <div className="flex gap-2">
                <button type="button" onClick={() => setMode('add')} disabled={!permits.length} className={btnGhost}
                  title={permits.length ? undefined : '인허가 항목을 먼저 등록하세요'}>+ 직접 추가</button>
                {me?.canBulkApprove && (
                  <button type="button" onClick={bulk} className={btnGhost}>선택 항목 일괄 승인</button>
                )}
              </div>
            </div>
            {bulkResults && (
              <div role="status" className="border-b border-line bg-paper px-4 py-3 text-sm">
                <div className="flex items-center justify-between">
                  <b className="text-ink">일괄 승인 결과: {bulkResults.filter((r) => r.ok).length}건 승인 · {bulkResults.filter((r) => !r.ok).length}건 제외</b>
                  <button type="button" onClick={() => setBulkResults(null)} className="text-xs text-mute hover:underline">닫기</button>
                </div>
                <ul className="mt-2 space-y-1">
                  {bulkResults.map((r) => {
                    const it = items.find((s) => s.id === r.id);
                    const label = it ? (effective(it).citation_key || effective(it).summary) : `#${r.id}`;
                    return (
                      <li key={r.id} className="flex gap-2">
                        <span className={`shrink-0 ${r.ok ? 'text-ok' : 'text-danger'}`}>{r.ok ? '✓ 승인' : '✕ 제외'}</span>
                        <span className="font-mono text-xs leading-5">{label}</span>
                        {!r.ok && <span className="text-body">— {r.reason}</span>}
                      </li>
                    );
                  })}
                </ul>
              </div>
            )}
            <div className="overflow-x-auto">
              <table className="w-full table-fixed text-left text-sm">
                <thead className="bg-paper text-xs text-body">
                  <tr>
                    {me?.canBulkApprove && <th className="w-10 px-3 py-2.5" />}
                    <th className="w-24 px-3 py-2.5 font-medium">조건번호</th>
                    <th className="px-3 py-2.5 font-medium">요약</th>
                    <th className="w-16 px-3 py-2.5 font-medium">주기</th>
                    <th className="w-28 px-3 py-2.5 font-medium">원문 대조</th>
                    <th className="w-24 px-3 py-2.5 font-medium">상태</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-line">
                  {rows.map((s) => {
                    const p = effective(s);
                    return (
                      <tr
                        key={s.id}
                        onClick={() => { setSelectedId(s.id); setMode('view'); }}
                        className={`cursor-pointer transition ${s.id === sel?.id && mode !== 'add' ? 'bg-brand/5' : 'hover:bg-paper'}`}
                      >
                        {me?.canBulkApprove && (
                          <td className="px-3 py-2.5" onClick={(e) => e.stopPropagation()}>
                            <input
                              type="checkbox"
                              aria-label={`${p.citation_raw || p.summary} 선택`}
                              disabled={s.status !== 'PENDING'}
                              checked={checked.has(s.id)}
                              onChange={(e) => setChecked((prev) => {
                                const next = new Set(prev);
                                if (e.target.checked) next.add(s.id); else next.delete(s.id);
                                return next;
                              })}
                              className="h-4 w-4 accent-brand"
                            />
                          </td>
                        )}
                        <td className="truncate px-3 py-2.5 font-mono text-xs" title={p.citation_raw ?? undefined}>
                          {p.citation_key || p.citation_raw || '—'}
                          {p.citation_synthetic && <span className="ml-1 font-sans text-warn" title="문서에 번호가 없어 시스템이 만든 키">*</span>}
                          {dupKeys.has(`${s.runId}:${p.citation_key}`) && <span className="ml-1 font-sans text-danger" title="같은 문서 안에서 조건번호가 겹칩니다">중복</span>}
                        </td>
                        <td className="truncate px-3 py-2.5 text-ink" title={p.summary}>{p.summary}</td>
                        <td className="truncate px-3 py-2.5 text-body">{FREQ[p.frequency]}</td>
                        <td className="truncate px-3 py-2.5 text-xs"><Badge {...(p.quote_check ? QC[p.quote_check] : MANUAL_BADGE)} /></td>
                        <td className="truncate px-3 py-2.5 text-xs"><Badge {...ST[s.status]} /></td>
                      </tr>
                    );
                  })}
                  {!rows.length && (
                    <tr><td colSpan={6} className="whitespace-normal px-3 py-8 text-center text-sm text-mute">
                      검토할 조건이 없습니다.<br />인허가 상세에서 허가증을 올리면 AI가 조건을 뽑아 여기에 올립니다.<br />직접 넣으려면 [+ 직접 추가]를 누르세요.
                    </td></tr>
                  )}
                </tbody>
              </table>
            </div>
          </section>

          {/* Detail */}
          <section className="min-w-0 rounded border border-line bg-white p-5 shadow-sm">
            {mode === 'add' && projectId != null ? (
              <PayloadForm
                title="조건 직접 추가"
                permits={permits}
                initial={EMPTY}
                onCancel={() => setMode('view')}
                onSave={(permitItemId, payload) => run(async () => {
                  const s = await addSuggestion(projectId, permitItemId!, payload);
                  setSelectedId(s.id);
                  setMode('view');
                }, '검토 대기에 추가했습니다.')}
              />
            ) : mode === 'edit' && sel ? (
              <PayloadForm
                title="조건 수정"
                initial={effective(sel)}
                onCancel={() => setMode('view')}
                onSave={(_, payload) => run(async () => { await editSuggestion(sel, payload); setMode('view'); },
                  '수정했습니다. 승인하면 수정 후 승인으로 기록됩니다.')}
              />
            ) : sel ? (
              <Detail
                key={sel.id}
                s={sel}
                lead={lead}
                permitName={permits.find((p) => p.id === sel.targetParentId)?.name ?? '—'}
                onEdit={() => setMode('edit')}
                run={run}
              />
            ) : (
              <p className="text-sm text-mute">왼쪽에서 조건을 선택하세요.</p>
            )}
          </section>
        </div>
      </div>
      {toast && (
        <div role="status" className="fixed bottom-6 left-1/2 -translate-x-1/2 rounded bg-inverse px-4 py-2 text-sm text-white shadow-lg">{toast}</div>
      )}
    </div>
  );
}

function Detail({ s, lead, permitName, onEdit, run }: {
  s: Suggestion;
  lead: boolean;
  permitName: string;
  onEdit: () => void;
  run: (action: () => Promise<unknown>, done: string) => Promise<void>;
}) {
  const p = effective(s);
  const [firstDue, setFirstDue] = useState('');
  const [ask, promptUi] = usePrompt();
  const [events, setEvents] = useState<SuggestionEvent[]>([]);
  useEffect(() => {
    suggestionEvents(s.id).then(setEvents, () => setEvents([]));
  }, [s.id, s.version]);

  const decidable = s.status === 'PENDING' || s.status === 'SUBMITTED';
  // D7: a scan can only be approved after someone opened its page (the server refuses otherwise)
  const unviewedScan = p.quote_check === 'UNVERIFIABLE'
    && !events.some((e) => e.type === 'PAGE_VIEWED' && e.data?.page_no === p.page_no);

  return (
    <>
      {promptUi}
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-base font-semibold text-ink">
          {p.citation_raw && <><span className="font-mono">{p.citation_raw}</span> · </>}{p.summary}
        </h2>
        <Badge {...ST[s.status]} />
      </div>

      <div className="mt-4 space-y-3">
        {p.quote_check === 'MISMATCH' && (
          <Callout tone="red" title="환각 의심">AI 인용문이 원문과 다릅니다. 원문과 대조해 수정하거나 거절하세요.</Callout>
        )}
        {unviewedScan && decidable && (
          <Callout tone="amber" title="원문 미열람 스캔본">아래 원문 페이지를 열어 확인해야 승인할 수 있습니다.</Callout>
        )}
        {s.status === 'SUBMITTED' && (
          <Callout tone="sky" title={s.proposedDecision === 'REJECT' ? '거절 요청됨' : '승인 요청됨'}>
            혼자 승인 권한이 있는 사람이 최종 확정합니다.
          </Callout>
        )}
        {s.status === 'REJECTED' && s.rejectReason && <Callout tone="red" title="거절 사유">{s.rejectReason}</Callout>}

        <div>
          <p className="mb-1 text-xs font-semibold text-mute">{p.source_quote ? `원문 인용 (${p.page_no ?? '?'}쪽)` : '조건 전문'}</p>
          <blockquote className="rounded-r border-l-4 border-brand bg-paper px-3 py-2 text-sm">{p.source_quote || p.requirement_text}</blockquote>
        </div>

        {s.origin === 'AI' && p.page_no && (
          <PageViewer id={s.id} pageNo={p.page_no}
            onViewed={() => suggestionEvents(s.id).then(setEvents, () => undefined)} />
        )}

        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Info label="인허가 항목" value={permitName} />
          <Info label="분야 (평가항목)" value={categoryLabel(p.category)} />
          <Info label="주기" value={FREQ[p.frequency]} />
          <Info label="유형" value={TYPE[p.type] ?? p.type} />
          {lead && decidable && (
            <label className="text-sm">
              <span className="mb-1 block text-xs font-medium text-mute">첫 기한일 (선택, 없으면 기한 미정)</span>
              <input type="date" value={firstDue} onChange={(e) => setFirstDue(e.target.value)} className={input} />
            </label>
          )}
          {p.responsible_party && <Info label="이행 담당" value={p.responsible_party} />}
        </div>

        <div className="flex flex-wrap gap-2">
          {lead && decidable && (
            <>
              <button type="button" className={btnPrimary} disabled={unviewedScan}
                title={unviewedScan ? '원문 페이지를 연 뒤 승인할 수 있습니다' : undefined}
                onClick={() => run(() => acceptSuggestion(s, firstDue || null),
                  s.editedPayload ? '수정 내용으로 승인했습니다. 수정 기록이 남습니다.' : '승인했습니다. 인허가 대장과 기한 알림에 등록됩니다.')}>
                {s.editedPayload ? '수정 후 승인' : '승인'}
              </button>
              <button type="button" className={`${btnGhost} text-danger`}
                onClick={async () => { const r = (await ask('거절 사유'))?.trim(); if (r) run(() => rejectSuggestion(s, r), '거절했습니다.'); }}>거절</button>
            </>
          )}
          {lead && s.status === 'SUBMITTED' && (
            <button type="button" className={btnGhost}
              onClick={async () => { const r = (await ask('반려 사유 (요청자에게 돌려보냅니다)'))?.trim(); if (r) run(() => returnSuggestion(s, r), '반려했습니다.'); }}>반려</button>
          )}
          {!lead && s.status === 'PENDING' && (
            <>
              <button type="button" className={btnPrimary}
                onClick={() => run(() => submitSuggestion(s, 'ACCEPT'), '승인 요청을 보냈습니다.')}>승인 요청</button>
              <button type="button" className={btnGhost}
                onClick={async () => { const r = (await ask('거절 요청 사유'))?.trim(); if (r) run(() => submitSuggestion(s, 'REJECT', r), '거절 요청을 보냈습니다.'); }}>거절 요청</button>
            </>
          )}
          {s.status === 'PENDING' && <button type="button" className={btnGhost} onClick={onEdit}>수정</button>}
          {lead && s.status === 'REJECTED' && (
            <button type="button" className={btnGhost} onClick={() => run(() => reopenSuggestion(s), '검토 대기로 되돌렸습니다.')}>재검토</button>
          )}
        </div>

        <p className="text-xs text-mute">
          기록: {s.origin === 'MANUAL' ? '직접 추가' : 'AI 추출'}
          {events.slice(1).map((e) => ` → ${e.actorId == null ? '시스템 ' : ''}${EVENT[e.type]}`).join('')}
        </p>
      </div>
    </>
  );
}

/** The suggestion's source page, rendered by the server. Showing it records PAGE_VIEWED (design D7). */
function PageViewer({ id, pageNo, onViewed }: { id: number; pageNo: number; onViewed: () => void }) {
  const [url, setUrl] = useState<string | null>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    let objectUrl: string | null = null;
    let stale = false; // a late answer for the previous page must not replace this one
    setUrl(null);
    setError('');
    pageImage(id, pageNo).then((b) => { if (stale) return; objectUrl = URL.createObjectURL(b); setUrl(objectUrl); },
      (e: Error) => { if (!stale) setError(e.message); });
    return () => { stale = true; if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [id, pageNo]);
  return (
    <div>
      <p className="mb-1 text-xs font-semibold text-mute">원문 {pageNo}쪽</p>
      <div className="max-h-[480px] overflow-auto rounded border border-line bg-paper">
        {url ? (
          <img src={url} alt={`원문 ${pageNo}쪽`} className="w-full"
            onLoad={() => recordPageView(id, pageNo).then(onViewed, () => undefined)} />
        ) : (
          <p className="px-3 py-8 text-center text-sm text-mute">{error || '원문 페이지를 불러오는 중…'}</p>
        )}
      </div>
    </div>
  );
}

function PayloadForm({ title, permits, initial, onSave, onCancel }: {
  title: string;
  permits?: PermitItem[];
  initial: ObligationPayload;
  onSave: (permitItemId: number | null, payload: ObligationPayload) => void;
  onCancel: () => void;
}) {
  const [p, setP] = useState(initial);
  const [permitId, setPermitId] = useState(permits?.[0]?.id ?? null);
  const set = (k: keyof ObligationPayload) => (e: { target: { value: string } }) => setP({ ...p, [k]: e.target.value });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    onSave(permitId, { ...p, citation_raw: p.citation_raw || null });
  };
  return (
    <form onSubmit={submit} className="space-y-3">
      <h2 className="text-base font-semibold text-ink">{title}</h2>
      {permits && (
        <Field label="인허가 항목">
          <select value={permitId ?? ''} onChange={(e) => setPermitId(Number(e.target.value))} className={input}>
            {permits.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
          </select>
        </Field>
      )}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <Field label="조건번호 (원문 표기)"><input value={p.citation_raw ?? ''} onChange={set('citation_raw')} className={input} placeholder="협의의견 3-2항" /></Field>
        <Field label="한 줄 요약"><input required maxLength={200} value={p.summary} onChange={set('summary')} className={input} /></Field>
      </div>
      <Field label="조건 전문">
        <textarea required rows={3} value={p.requirement_text} onChange={set('requirement_text')} className={input} />
      </Field>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <Field label="유형">
          <select value={p.type} onChange={set('type')} className={input}>
            {Object.entries(TYPE).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
        </Field>
        <Field label="분야 (평가항목)">
          <select value={p.category} onChange={set('category')} className={input}>
            {Object.keys(CATEGORY).map((k) => <option key={k} value={k}>{categoryLabel(k)}</option>)}
          </select>
        </Field>
        <Field label="주기">
          <select value={p.frequency} onChange={set('frequency')} className={input}>
            {Object.entries(FREQ).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
        </Field>
      </div>
      <div className="flex gap-2">
        <button type="submit" className={btnPrimary}>저장</button>
        <button type="button" onClick={onCancel} className={btnGhost}>취소</button>
      </div>
    </form>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="block text-sm">
      <span className="mb-1 block text-xs font-medium text-mute">{label}</span>
      {children}
    </label>
  );
}

function Badge({ label, cls }: { label: string; cls: string }) {
  return <span className={`whitespace-nowrap ${cls}`}>{label}</span>;
}

function Info({ label, value }: { label: string; value: string }) {
  return (
    <div className="text-sm">
      <span className="mb-1 block text-xs font-medium text-mute">{label}</span>
      <span className="text-ink">{value}</span>
    </div>
  );
}

const TONE = { red: 'bg-red-50 text-danger', amber: 'bg-amber-50 text-warn', sky: 'bg-sky-50 text-sky-800' };
function Callout({ tone, title, children }: { tone: keyof typeof TONE; title: string; children: ReactNode }) {
  return (
    <div className={`flex gap-2 rounded px-3 py-2 text-sm ${TONE[tone]}`}>
      <b className="shrink-0">{title}</b>
      <span className="text-body">{children}</span>
    </div>
  );
}
