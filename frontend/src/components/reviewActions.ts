import {
  acceptSuggestion, rejectSuggestion, reopenSuggestion, returnSuggestion, submitSuggestion, type Suggestion,
} from '../api/review';
import type { ButtonVariant } from './ui/Button';

export const FREQ: Record<string, string> = {
  ONCE: '1회', MONTHLY: '월', QUARTERLY: '분기', SEMIANNUAL: '반기', ANNUAL: '연', OTHER: '기타',
};

export const READ_ONLY = '데모 계정은 조회만 가능합니다.';

export interface Action { label: string; variant: ButtonVariant; onClick: () => void; disabled?: boolean; title?: string }

/** Runs a server call, shows the result, reloads; resolves true when the call succeeded. */
export type Run = (action: () => Promise<unknown>, done: string) => Promise<boolean>;
export type Ask = (label: string, initial?: string) => Promise<string | null>;

export const decidable = (s: { status: string }) => s.status === 'PENDING' || s.status === 'SUBMITTED';

/**
 * Bottom-row buttons of one permit condition, left to right (DESIGN.md 검토·비교 화면).
 * Who may decide follows the member's can_self_approve (`lead`); others only send requests.
 * The review memo pre-fills every reason prompt.
 */
export function conditionActions({ s, lead, unviewedScan, firstDue, memo, ask, run, onEdit, onNext }: {
  s: Suggestion; lead: boolean; unviewedScan: boolean; firstDue: string; memo: string;
  ask: Ask; run: Run; onEdit: () => void; onNext: () => void;
}): Action[] {
  const withReason = (label: string, act: (r: string) => Promise<unknown>, done: string) => async () => {
    const r = (await ask(label, memo))?.trim();
    if (r) await run(() => act(r), done);
  };
  const out: Action[] = [];
  if (lead && decidable(s)) {
    out.push({ label: '반려', variant: 'tertiary', onClick: withReason('반려 사유', (r) => rejectSuggestion(s, r), '반려했습니다.') });
    if (s.status === 'SUBMITTED') {
      out.push({ label: '요청 돌려보내기', variant: 'tertiary',
        onClick: withReason('돌려보내는 사유 (요청자에게 갑니다)', (r) => returnSuggestion(s, r), '요청자에게 돌려보냈습니다.') });
    }
    if (s.status === 'PENDING') out.push({ label: '고쳐서 승인', variant: 'secondary', onClick: onEdit });
    out.push({
      label: '승인하고 다음', variant: 'primary', disabled: unviewedScan,
      title: unviewedScan ? '원문 페이지를 연 뒤 승인할 수 있습니다' : undefined,
      onClick: () => void run(() => acceptSuggestion(s, firstDue || null),
        s.editedPayload ? '수정 내용으로 승인했습니다. 수정 기록이 남습니다.' : '승인했습니다. 인허가 대장과 기한 알림에 등록됩니다.')
        .then((ok) => { if (ok) onNext(); }),
    });
  } else if (!lead && s.status === 'PENDING') {
    out.push({ label: '반려 요청', variant: 'tertiary',
      onClick: withReason('반려 요청 사유', (r) => submitSuggestion(s, 'REJECT', r), '반려 요청을 보냈습니다.') });
    out.push({ label: '고쳐서 요청', variant: 'secondary', onClick: onEdit });
    out.push({ label: '승인 요청하고 다음', variant: 'primary',
      onClick: () => void run(() => submitSuggestion(s, 'ACCEPT'), '승인 요청을 보냈습니다.').then((ok) => { if (ok) onNext(); }) });
  } else if (lead && s.status === 'REJECTED') {
    out.push({ label: '재검토', variant: 'tertiary', onClick: () => void run(() => reopenSuggestion(s), '검토 대기로 되돌렸습니다.') });
  }
  return out;
}
