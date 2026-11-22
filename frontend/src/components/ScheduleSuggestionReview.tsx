import { useCallback, useEffect, useState } from 'react';
import { api, sessionUserId } from '../api/client';
import {
  acceptSuggestion, editSuggestion, effective, listScheduleSuggestions, rejectSuggestion, submitSuggestion,
  type ScheduleRowPayload, type ScheduleSuggestion,
} from '../api/review';
import { ANCHOR_LABEL } from '../api/schedule';
import { usePrompt } from './PromptDialog';

// ---------------------------------------------------------------------------
// ScheduleSuggestionReview — design-schedule/07: dates found in a project document (프로젝트 › 문서 [일정 추출]),
// one row each. Nothing reaches the schedule or the calendar until a row is approved. A row similar to a live
// item updates it by default (기존 항목과 비슷함); [새로 추가] makes a new one instead.
// ---------------------------------------------------------------------------

interface Member { userId: number; canSelfApprove: boolean }

const TYPE: Record<ScheduleRowPayload['type'], string> = { EVENT: '이벤트', MILESTONE: '마일스톤', TASK: '작업', RECURRING: '정기 보고' };
const EVENT_KIND: Record<string, string> = {
  SITE_BRIEFING: '현장설명회', QNA_DEADLINE: '질의 마감', SUBMISSION: '제출', CLIENT_REPORT: '발주처 보고', REVIEW: '심의',
  MEETING: '회의', TRIP: '출장', OTHER: '기타',
};
const ANCHOR: Record<string, string> = ANCHOR_LABEL;
const INTERVAL: Record<string, string> = { WEEKLY: '매주', BIWEEKLY: '격주', MONTHLY: '매월', QUARTERLY: '분기마다' };
const CHECK: Record<string, string> = { YEAR_INFERRED: '연도 추정', RELATIVE_RULE: '상대 규칙', BASE_DATE_MISSING: '기준일 미입력' };
const STATUS: Record<string, string> = {
  PENDING: '검토 대기', SUBMITTED: '승인 요청됨', ACCEPTED: '승인', EDITED_ACCEPTED: '수정 후 승인', REJECTED: '거절',
};
const btn = 'rounded border border-line px-2 py-1 text-xs font-medium text-body hover:bg-paper disabled:opacity-40';

function when(p: ScheduleRowPayload): string {
  const rule = p.anchor
    ? `${ANCHOR[p.anchor] ?? p.anchor} ${p.offsetMonths != null ? `${p.offsetMonths >= 0 ? '+' : ''}${p.offsetMonths}개월` : `${(p.offsetDays ?? 0) >= 0 ? '+' : ''}${p.offsetDays ?? 0}일`}`
    : null;
  switch (p.type) {
    case 'EVENT': return p.startsAt ? p.startsAt.replace('T', ' ').slice(0, 16) : `${p.date ?? '—'} (종일)`;
    case 'MILESTONE': return rule ?? p.date ?? '—';
    case 'TASK': return rule ? `${rule}, ${p.durationDays ?? '?'}일간` : `${p.startDate ?? '?'} ~ ${p.date ?? '?'}`;
    case 'RECURRING': return `${INTERVAL[p.interval ?? ''] ?? '?'} · ${p.startDate ?? '착수일'} ~ ${p.date ?? '완료일'}`;
  }
}

export default function ScheduleSuggestionReview({ projectId }: { projectId: number }) {
  const [ask, promptUi] = usePrompt();
  const [items, setItems] = useState<ScheduleSuggestion[]>([]);
  const [lead, setLead] = useState(false);
  const [kind, setKind] = useState<'ALL' | 'SCHEDULE_ITEM' | 'PROJECT_EVENT'>('ALL');
  const [pendingOnly, setPendingOnly] = useState(true);
  const [editing, setEditing] = useState<{ id: number; title: string; date: string } | null>(null);
  const [message, setMessage] = useState('');

  const reload = useCallback(() => listScheduleSuggestions(projectId).then(setItems, () => setItems([])), [projectId]);
  useEffect(() => {
    reload();
    api<Member[]>(`/api/projects/${projectId}/members`)
      .then((m) => setLead(!!m.find((x) => x.userId === sessionUserId())?.canSelfApprove), () => setLead(false));
  }, [projectId, reload]);

  const run = async (action: () => Promise<unknown>, done: string) => {
    try {
      await action();
      setMessage(done);
    } catch (e) {
      setMessage((e as Error).message);
    }
    await reload();
  };

  if (!items.length) return null;
  const rows = items.filter((s) => (kind === 'ALL' || s.kind === kind)
    && (!pendingOnly || s.status === 'PENDING' || s.status === 'SUBMITTED'));
  const dateField = (p: ScheduleRowPayload) => (p.type === 'EVENT' && p.startsAt ? 'startsAt' : 'date');

  return (
    <section className="mt-5 rounded border border-line bg-white shadow-sm">
      {promptUi}
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-line px-4 py-3">
        <div>
          <h2 className="text-sm font-semibold text-ink">문서에서 찾은 일정 {rows.length}건</h2>
          <p className="text-xs text-mute">승인한 행만 일정·달력에 들어갑니다. 근거 문장을 원문과 대조하세요.</p>
        </div>
        <div className="flex flex-wrap items-center gap-2 text-xs">
          {(['ALL', 'SCHEDULE_ITEM', 'PROJECT_EVENT'] as const).map((k) => (
            <button key={k} type="button" onClick={() => setKind(k)}
              className={`rounded px-3 py-1 font-medium ${kind === k ? 'bg-brand text-white' : 'bg-paper text-body'}`}>
              {k === 'ALL' ? '전체' : k === 'SCHEDULE_ITEM' ? '일정 항목' : '이벤트'}
            </button>
          ))}
          <label className="ml-2 flex items-center gap-1 text-body">
            <input type="checkbox" checked={pendingOnly} onChange={(e) => setPendingOnly(e.target.checked)} className="accent-brand" />
            검토할 것만
          </label>
        </div>
      </div>
      {message && <p role="status" className="border-b border-line bg-paper px-4 py-2 text-xs text-body">{message}</p>}
      <div className="overflow-x-auto">
        <table className="w-full min-w-[900px] whitespace-nowrap text-left text-sm">
          <thead className="bg-paper text-xs text-body">
            <tr>
              <th className="px-3 py-2 font-medium">종류</th>
              <th className="px-3 py-2 font-medium">제목</th>
              <th className="px-3 py-2 font-medium">날짜·규칙</th>
              <th className="px-3 py-2 font-medium">근거</th>
              <th className="px-3 py-2 font-medium">확인</th>
              <th className="px-3 py-2 font-medium">상태</th>
              <th className="px-3 py-2" />
            </tr>
          </thead>
          <tbody className="divide-y divide-line">
            {rows.map((s) => {
              const p = effective(s);
              const open = s.status === 'PENDING' || s.status === 'SUBMITTED';
              const similar = p.matchId != null && !p.createNew;
              const isEditing = editing?.id === s.id;
              return (
                <tr key={s.id} className="align-top">
                  <td className="px-3 py-2 text-body">{TYPE[p.type]}{p.kind ? ` · ${EVENT_KIND[p.kind] ?? p.kind}` : ''}</td>
                  <td className="max-w-[200px] px-3 py-2">
                    {isEditing ? (
                      <input aria-label="제목" value={editing.title} onChange={(e) => setEditing({ ...editing, title: e.target.value })}
                        className="w-full rounded border border-line px-2 py-1 text-sm" />
                    ) : <span className="block truncate font-medium text-ink" title={p.title}>{p.title}</span>}
                    {p.discipline && <span className="block text-xs text-mute">{p.discipline}</span>}
                  </td>
                  <td className="px-3 py-2 text-body">
                    {isEditing && !p.anchor && p.type !== 'RECURRING' ? (
                      <input aria-label="날짜" type={dateField(p) === 'startsAt' ? 'datetime-local' : 'date'} value={editing.date}
                        onChange={(e) => setEditing({ ...editing, date: e.target.value })}
                        className="rounded border border-line px-2 py-1 text-sm" />
                    ) : <span title={p.rule}>{when(p)}</span>}
                  </td>
                  <td className="max-w-[280px] px-3 py-2 text-xs text-body">
                    <span className="block truncate" title={p.evidence.quote}>{p.evidence.page}쪽 · “{p.evidence.quote}”</span>
                    {p.quote_check === 'MISMATCH' && <span className="text-danger">원문 불일치</span>}
                  </td>
                  <td className="px-3 py-2 text-xs">
                    {(p.checks ?? []).map((c) => <span key={c} className="mr-1 block text-warn">확인 필요: {CHECK[c]}</span>)}
                    {similar && <span className="block text-brand" title={`기존 항목 #${p.matchId}을 갱신합니다`}>기존 항목과 비슷함</span>}
                  </td>
                  <td className="px-3 py-2 text-xs text-body">{STATUS[s.status]}</td>
                  <td className="px-3 py-2 text-right">
                    {open && isEditing && (
                      <span className="flex justify-end gap-1">
                        <button type="button" className={btn} onClick={() => run(async () => {
                          await editSuggestion(s, { ...p, title: editing.title.trim() || p.title,
                            ...(editing.date ? { [dateField(p)]: editing.date } : {}) });
                          setEditing(null);
                        }, '수정했습니다. 승인하면 수정 후 승인으로 기록됩니다.')}>저장</button>
                        <button type="button" className={btn} onClick={() => setEditing(null)}>취소</button>
                      </span>
                    )}
                    {open && !isEditing && (
                      <span className="flex justify-end gap-1">
                        {lead ? (
                          <>
                            <button type="button" className={`${btn} border-brand text-brand`}
                              onClick={() => run(() => acceptSuggestion(s, null), similar ? '기존 항목을 갱신했습니다.' : '일정에 추가했습니다.')}>
                              {similar ? '갱신 승인' : s.editedPayload ? '수정 후 승인' : '승인'}
                            </button>
                            {similar && (
                              <button type="button" className={btn} onClick={() => run(async () => {
                                const { matchId: _drop, ...rest } = p;
                                await acceptSuggestion(await editSuggestion(s, { ...rest, createNew: true }), null);
                              }, '새 항목으로 추가했습니다.')}>새로 추가</button>
                            )}
                            <button type="button" className={`${btn} text-danger`} onClick={async () => {
                              const r = (await ask('거절 사유'))?.trim();
                              if (r) run(() => rejectSuggestion(s, r), '거절했습니다.');
                            }}>거절</button>
                          </>
                        ) : s.status === 'PENDING' && (
                          <button type="button" className={btn} onClick={() => run(() => submitSuggestion(s, 'ACCEPT'), '승인 요청을 보냈습니다.')}>승인 요청</button>
                        )}
                        {s.status === 'PENDING' && (
                          <button type="button" className={btn}
                            onClick={() => setEditing({ id: s.id, title: p.title, date: (dateField(p) === 'startsAt' ? p.startsAt : p.date) ?? '' })}>
                            수정
                          </button>
                        )}
                      </span>
                    )}
                  </td>
                </tr>
              );
            })}
            {!rows.length && <tr><td colSpan={7} className="px-3 py-6 text-center text-sm text-mute">검토할 일정 제안이 없습니다.</td></tr>}
          </tbody>
        </table>
      </div>
    </section>
  );
}
