import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { api, isDemo, sessionUserId } from '../api/client';
import {
  addSuggestion, bulkAccept, editSuggestion, effective, listSuggestions, pageImage, recordPageView, suggestionEvents,
  type ObligationPayload, type QuoteCheck, type Suggestion, type SuggestionEvent, type SuggestionStatus,
} from '../api/review';
import type { PermitItem, Project } from '../types';
import RevisionSuggestions from '../components/RevisionSuggestions';
import ScheduleSuggestionReview from '../components/ScheduleSuggestionReview';
import { usePrompt } from '../components/PromptDialog';
import { Help } from '../components/Onboarding';
import { Alert } from '../components/ui/Alert';
import { Badge, type Tone } from '../components/ui/Badge';
import { Button } from '../components/ui/Button';
import { Input, Textarea } from '../components/ui/Field';
import { ActionBar, DetailHead, QuoteBox, ReviewItem } from '../components/ReviewParts';
import { conditionActions, decidable, FREQ, READ_ONLY, type Run } from '../components/reviewActions';

// ---------------------------------------------------------------------------
// ReviewQueuePage — ③ human review of permit conditions (obligations).
// Suggestions stay here until approved; nothing reaches the ledger or alerts
// before that. Buttons follow the member's can_self_approve / can_bulk_approve.
// ---------------------------------------------------------------------------

interface Member { userId: number; canSelfApprove: boolean; canBulkApprove: boolean }

const QC: Record<QuoteCheck, { label: string; tone: Tone; cls: string }> = {
  MISMATCH: { label: '원문과 불일치', tone: 'danger', cls: 'text-danger' },
  UNVERIFIABLE: { label: '원문 대조 필요', tone: 'warning', cls: 'text-warn' },
  VERIFIED: { label: '원문과 일치', tone: 'success', cls: 'text-ok' },
};
const MANUAL_BADGE = { label: '직접 추가', tone: 'neutral' as Tone, cls: 'text-body' };
const ST: Record<SuggestionStatus, { label: string; cls: string }> = {
  PENDING: { label: '검토 대기', cls: 'text-body' },
  SUBMITTED: { label: '승인 요청됨', cls: 'text-information' },
  ACCEPTED: { label: '승인', cls: 'text-ok' },
  EDITED_ACCEPTED: { label: '수정 후 승인', cls: 'text-ok' },
  REJECTED: { label: '반려', cls: 'text-danger' },
};
const QC_ORDER: Record<QuoteCheck | 'NONE', number> = { MISMATCH: 0, UNVERIFIABLE: 1, VERIFIED: 2, NONE: 3 };
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
  CREATED: '등록', EDITED: '수정', PAGE_VIEWED: '원문 열람', SUBMITTED: '승인 요청', RETURNED: '돌려보냄',
  ACCEPTED: '승인', REJECTED: '반려', REOPENED: '재검토',
  UNPAIRED: '나눔', PAIRED: '합침', REDIFFED: '변경분 다시 계산',
};

const EMPTY: ObligationPayload = {
  citation_raw: '', summary: '', requirement_text: '', type: 'MEASURE_SURVEY', category: 'AIR_QUALITY', frequency: 'MONTHLY',
};

const input = 'w-full rounded border border-input bg-white px-3 py-2 text-[15px]';

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
  const readOnly = isDemo();

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
  const run: Run = async (action, done) => {
    let ok = true;
    try {
      await action();
      notify(done);
    } catch (e) {
      ok = false;
      notify((e as Error).message);
    }
    await reload();
    return ok;
  };

  const qc = (s: Suggestion) => effective(s).quote_check ?? 'NONE';
  const rows = items
    .filter((s) => filter === 'ALL' || s.status === filter)
    .sort((a, b) => QC_ORDER[qc(a)] - QC_ORDER[qc(b)] || a.id - b.id);
  // same citation_key twice in one run → warn on every such row (design §3.4 rule 6); saving is still allowed
  const keyCount = new Map<string, number>();
  items.forEach((s) => { const k = `${s.runId}:${effective(s).citation_key}`; if (effective(s).citation_key) keyCount.set(k, (keyCount.get(k) ?? 0) + 1); });
  const dupKeys = new Set([...keyCount].filter(([, n]) => n > 1).map(([k]) => k));
  const sel = items.find((s) => s.id === selectedId) ?? rows.find(decidable) ?? rows[0] ?? null;
  const lead = !!me?.canSelfApprove;
  const project = projects.find((p) => p.id === projectId);
  const remaining = items.filter(decidable).length;
  // 승인하고 다음: the next open row below this one, else the first open row left
  const nextAfter = (id: number) => {
    const i = rows.findIndex((r) => r.id === id);
    const next = rows.slice(i + 1).find(decidable) ?? rows.find((r) => r.id !== id && decidable(r));
    if (next) setSelectedId(next.id);
  };

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
    <div className="min-h-full">
      <div className={fixedId ? 'space-y-5' : 'space-y-5 py-8'}>
        {!fixedId && (
          <div className="flex flex-wrap items-end justify-between gap-4">
            <div>
              <p className="text-xs font-semibold tracking-wide text-mute">허가조건 검토 · {project?.name ?? '프로젝트 없음'}</p>
              <h1 className="mt-1 text-2xl font-bold text-ink">AI 검토</h1>
            </div>
            <label className="flex items-center gap-2 text-sm text-body">
              프로젝트
              <select
                value={projectId ?? ''}
                onChange={(e) => setProjectId(Number(e.target.value))}
                className="rounded border border-input bg-white px-2.5 py-1.5 text-sm"
              >
                {projects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
              </select>
            </label>
          </div>
        )}

        <Alert tone="info">
          <p>
            AI가 찾은 조건은 <b>승인 전까지 인허가 대장과 기한 알림에 들어가지 않습니다.</b> 원문과 다른 항목은 맨 위에 표시합니다.
          </p>
          {me && (
            <p className="mt-1 flex items-center text-sm text-body">
              내 권한: {lead ? '혼자 승인' : '승인 요청만'}{me.canBulkApprove && ' · 일괄 승인'}{readOnly && ' · 조회 전용'}
              <Help k="review" />
            </p>
          )}
        </Alert>

        {error && <Alert tone="danger">{error}</Alert>}

        {projectId && <RevisionSuggestions projectId={projectId} readOnly={readOnly} />}
        {projectId && <ScheduleSuggestionReview key={projectId} projectId={projectId} readOnly={readOnly} />}

        <div className="flex flex-wrap gap-2" role="group" aria-label="상태로 거르기">
          {(['ALL', 'PENDING', 'SUBMITTED', 'ACCEPTED', 'REJECTED'] as const).map((f) => (
            <button
              key={f}
              type="button"
              aria-pressed={filter === f}
              onClick={() => setFilter(f)}
              className={`h-10 rounded border px-3.5 text-[15px] transition ${filter === f ? 'border-brand bg-brand-subtle font-bold text-brand-text' : 'border-line bg-white text-body hover:bg-paper'}`}
            >
              {f === 'ALL' ? '전체' : ST[f].label} {f === 'ALL' ? items.length : items.filter((s) => s.status === f).length}
            </button>
          ))}
        </div>

        <div className="grid grid-cols-1 items-start gap-5 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
          {/* List */}
          <section aria-label="검토 대상 조건" className="min-w-0 rounded border border-line bg-white">
            <div className="flex flex-wrap items-center justify-between gap-2 border-b border-line px-4 py-3">
              <h2 className="text-[17px] font-bold text-ink">검토 대상 조건 {rows.length}건</h2>
              <div className="flex flex-wrap gap-2">
                <Button size="sm" variant="tertiary" onClick={() => setMode('add')} disabled={readOnly || !permits.length}
                  title={readOnly ? READ_ONLY : permits.length ? undefined : '인허가 항목을 먼저 등록하세요'}>+ 직접 추가</Button>
                {me?.canBulkApprove && (
                  <Button size="sm" variant="tertiary" onClick={bulk} disabled={readOnly} title={readOnly ? READ_ONLY : undefined}>선택 항목 일괄 승인</Button>
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
            <ul className="lg:max-h-[760px] lg:overflow-y-auto">
              {rows.map((s) => {
                const p = effective(s);
                const q = p.quote_check ? QC[p.quote_check] : MANUAL_BADGE;
                return (
                  <ReviewItem
                    key={s.id}
                    active={s.id === sel?.id && mode !== 'add'}
                    onClick={() => { setSelectedId(s.id); setMode('view'); }}
                    leading={me?.canBulkApprove && (
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
                        className="ml-3 mt-4 h-4 w-4 shrink-0 accent-brand"
                      />
                    )}
                    kind={<b className={ST[s.status].cls}>{ST[s.status].label}</b>}
                    where={<>
                      <span title={p.citation_raw ?? undefined}>{p.citation_key || p.citation_raw || '번호 없음'}</span>
                      {p.page_no != null && ` · ${p.page_no}쪽`}
                      {p.citation_synthetic && <span className="ml-1 text-warn" title="문서에 번호가 없어 시스템이 만든 키">*</span>}
                      {dupKeys.has(`${s.runId}:${p.citation_key}`) && <span className="ml-1 text-danger" title="같은 문서 안에서 조건번호가 겹칩니다">중복</span>}
                    </>}
                    title={p.summary}
                    sub={<>주기 {FREQ[p.frequency]} · <span className={q.cls}>{q.label}</span></>}
                  />
                );
              })}
              {!rows.length && (
                <li className="px-4 py-10 text-center text-[15px] text-body">
                  <p className="font-bold text-ink">검토할 조건이 없습니다.</p>
                  <p className="mt-1">인허가 상세에서 허가증을 올리면 AI가 조건을 뽑아 여기에 올립니다. 직접 넣으려면 [+ 직접 추가]를 누르세요.</p>
                </li>
              )}
            </ul>
          </section>

          {/* Detail */}
          <section aria-label="선택한 조건" className="flex min-w-0 flex-col rounded border border-line bg-white">
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
                readOnly={readOnly}
                remaining={remaining}
                permitName={permits.find((p) => p.id === sel.targetParentId)?.name ?? '—'}
                onEdit={() => setMode('edit')}
                onNext={() => nextAfter(sel.id)}
                run={run}
              />
            ) : (
              <p className="px-5 py-6 text-[15px] text-body">왼쪽에서 조건을 선택하세요.</p>
            )}
          </section>
        </div>
      </div>
      {toast && (
        <div role="status" className="fixed bottom-6 left-1/2 z-20 -translate-x-1/2 rounded bg-inverse px-4 py-2 text-sm text-white shadow-lg">{toast}</div>
      )}
    </div>
  );
}

function Detail({ s, lead, readOnly, remaining, permitName, onEdit, onNext, run }: {
  s: Suggestion;
  lead: boolean;
  readOnly: boolean;
  remaining: number;
  permitName: string;
  onEdit: () => void;
  onNext: () => void;
  run: Run;
}) {
  const p = effective(s);
  const [firstDue, setFirstDue] = useState('');
  const [memo, setMemo] = useState('');
  const [ask, promptUi] = usePrompt();
  const [events, setEvents] = useState<SuggestionEvent[]>([]);
  useEffect(() => {
    suggestionEvents(s.id).then(setEvents, () => setEvents([]));
  }, [s.id, s.version]);

  const open = decidable(s);
  // D7: a scan can only be approved after someone opened its page (the server refuses otherwise)
  const unviewedScan = p.quote_check === 'UNVERIFIABLE'
    && !events.some((e) => e.type === 'PAGE_VIEWED' && e.data?.page_no === p.page_no);
  const q = p.quote_check ? QC[p.quote_check] : MANUAL_BADGE;
  const actions = conditionActions({ s, lead, unviewedScan, firstDue, memo, ask, run, onEdit, onNext });

  return (
    <>
      {promptUi}
      <DetailHead
        state={<span className={ST[s.status].cls}>{s.origin === 'MANUAL' ? '직접 추가' : 'AI 추출'} · {ST[s.status].label}</span>}
        title={p.summary}
        badge={<Badge tone={q.tone}>{q.label}</Badge>}
      />

      <div className="flex flex-col gap-5 px-5 py-5">
        {p.quote_check === 'MISMATCH' && (
          <Alert tone="danger" title="환각 의심">AI 인용문이 원문과 다릅니다. 원문과 대조해 고치거나 반려하세요.</Alert>
        )}
        {unviewedScan && open && (
          <Alert tone="warning" title="원문 미열람 스캔본">아래 원문 페이지를 열어 확인해야 승인할 수 있습니다.</Alert>
        )}
        {s.status === 'SUBMITTED' && (
          <Alert tone="info" title={s.proposedDecision === 'REJECT' ? '반려 요청됨' : '승인 요청됨'}>
            혼자 승인 권한이 있는 사람이 최종 확정합니다.
          </Alert>
        )}
        {s.editedPayload && open && <Alert tone="info">고친 내용으로 승인됩니다. 수정 기록이 남습니다.</Alert>}
        {s.status === 'REJECTED' && s.rejectReason && <Alert tone="danger" title="반려 사유">{s.rejectReason}</Alert>}

        <div className="overflow-x-auto">
          <table className="w-full border-collapse text-[15px]">
            <tbody>
              <InfoRow label="조건번호" value={p.citation_key || p.citation_raw || '—'} />
              <InfoRow label="인허가 항목" value={permitName} />
              <InfoRow label="분야" value={categoryLabel(p.category)} />
              <InfoRow label="주기" value={`${FREQ[p.frequency]}${p.frequency_text ? ` (${p.frequency_text})` : ''}`} />
              <InfoRow label="유형" value={TYPE[p.type] ?? p.type} />
              {p.responsible_party && <InfoRow label="이행 담당" value={p.responsible_party} />}
            </tbody>
          </table>
        </div>

        <QuoteBox caption={p.source_quote ? `원문 인용 · ${p.page_no ?? '?'}쪽` : '조건 전문'}>
          {p.source_quote || p.requirement_text}
        </QuoteBox>

        {s.origin === 'AI' && p.page_no && (
          <PageViewer id={s.id} pageNo={p.page_no}
            onViewed={() => suggestionEvents(s.id).then(setEvents, () => undefined)} />
        )}

        {lead && open && (
          <Input type="date" label="첫 기한일 (선택)" hint="비우면 기한 미정으로 등록합니다." value={firstDue}
            onChange={(e) => setFirstDue(e.target.value)} className="max-w-xs" />
        )}
        {actions.length > 0 && (
          <Textarea label="검토 메모 (선택)" hint="반려할 때 사유 칸에 미리 채워집니다." rows={3} value={memo}
            onChange={(e) => setMemo(e.target.value)} maxLength={500} />
        )}

        <p className="text-sm text-mute">
          기록: {s.origin === 'MANUAL' ? '직접 추가' : 'AI 추출'}
          {events.slice(1).map((e) => ` → ${e.actorId == null ? '시스템 ' : ''}${EVENT[e.type]}`).join('')}
        </p>
      </div>

      <ActionBar remaining={remaining} actions={actions} readOnly={readOnly} />
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
      <p className="mb-2 text-sm text-body">원문 이미지 · {pageNo}쪽</p>
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
    <form onSubmit={submit} className="space-y-3 p-5">
      <h2 className="text-[19px] font-bold text-ink">{title}</h2>
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
      <div className="flex justify-end gap-2">
        <Button variant="tertiary" onClick={onCancel}>취소</Button>
        <Button type="submit">저장</Button>
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

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <tr className="border-b border-line-soft last:border-0">
      <th scope="row" className="w-28 py-2.5 pr-3 text-left align-top font-normal text-body">{label}</th>
      <td className="py-2.5 text-ink">{value}</td>
    </tr>
  );
}
