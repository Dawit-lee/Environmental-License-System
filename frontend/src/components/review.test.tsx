import { renderToStaticMarkup } from 'react-dom/server';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { RevisionSuggestion, Suggestion } from '../api/review';

vi.mock('../api/review', async (orig) => ({
  ...(await orig<typeof import('../api/review')>()),
  acceptSuggestion: vi.fn(async () => ({})),
  rejectSuggestion: vi.fn(async () => ({})),
  submitSuggestion: vi.fn(async () => ({})),
  returnSuggestion: vi.fn(async () => ({})),
  reopenSuggestion: vi.fn(async () => ({})),
}));

const api = await import('../api/review');
const { conditionActions, READ_ONLY } = await import('./reviewActions');
const { ActionBar, ReviewItem } = await import('./ReviewParts');
const { ChangeCounts } = await import('./RevisionSuggestions');

const html = (el: React.ReactElement) => renderToStaticMarkup(el).replaceAll('&amp;', '&');

const suggestion = (over: Partial<Suggestion> = {}): Suggestion => ({
  id: 7, projectId: 1, runId: 1, kind: 'OBLIGATION', targetParentId: 1, origin: 'AI',
  payload: { summary: '배출구 자가측정', requirement_text: '월 2회 측정', type: 'MEASURE_SURVEY', category: 'AIR_QUALITY', frequency: 'MONTHLY' },
  editedPayload: null, status: 'PENDING', proposedDecision: null, rejectReason: null, appliedEntityId: null, version: 3,
  ...over,
});

/** Actions with a run that awaits the call like the page does, and spies for the rest. */
const setup = (over: Partial<Parameters<typeof conditionActions>[0]> = {}) => {
  const deps = {
    s: suggestion(), lead: true, unviewedScan: false, firstDue: '', memo: '',
    ask: vi.fn(async () => '원문과 다름'),
    run: vi.fn(async (action: () => Promise<unknown>) => { await action(); return true; }),
    onEdit: vi.fn(), onNext: vi.fn(),
    ...over,
  };
  const actions = conditionActions(deps);
  const click = async (label: string) => {
    actions.find((a) => a.label === label)!.onClick();
    await vi.waitFor(() => expect(deps.run).toHaveBeenCalled());
    await Promise.resolve();
  };
  return { deps, actions, labels: actions.map((a) => a.label), click };
};

beforeEach(() => vi.clearAllMocks());

describe('ReviewItem', () => {
  it('marks only the selected candidate with aria-current, a blue left bar and a light blue background', () => {
    const on = html(<ul><ReviewItem active onClick={() => {}} kind="변경" where="3.2.1 · 1쪽" title="자가측정 주기" sub="월 2회 → 주 1회" /></ul>);
    const off = html(<ul><ReviewItem active={false} onClick={() => {}} kind="추가" title="황화수소 측정기" /></ul>);
    expect(on).toContain('aria-current="true"');
    expect(on).toMatch(/class="[^"]*border-l-brand[^"]*bg-brand-subtle/);
    expect(on).toContain('3.2.1 · 1쪽');
    expect(on).toContain('월 2회 → 주 1회');
    expect(off).not.toContain('aria-current');
    expect(off).not.toContain('bg-brand-subtle');
  });
});

describe('ChangeCounts', () => {
  it('counts 추가·삭제·변경·같음 of the open rows', () => {
    const row = (change_type: string) => ({ payload: { change_type } }) as RevisionSuggestion;
    const out = html(<ChangeCounts pending={['ADDED', 'CHANGED', 'CHANGED', 'UNCHANGED'].map(row)} />);
    expect([...out.matchAll(/<span[^>]*>([^<]*)<\/span>/g)].map((m) => m[1].replace(/<!-- -->/g, '')))
      .toEqual(['추가 1', '삭제 0', '변경 2', '같음 1']);
  });
});

describe('conditionActions', () => {
  it('gives a lead 반려 · 고쳐서 승인 · 승인하고 다음, left to right', () => {
    const { labels, actions } = setup();
    expect(labels).toEqual(['반려', '고쳐서 승인', '승인하고 다음']);
    expect(actions.map((a) => a.variant)).toEqual(['tertiary', 'secondary', 'primary']);
  });

  it('승인하고 다음 approves with the first due date, then moves on', async () => {
    const { deps, click } = setup({ firstDue: '2026-12-01' });
    await click('승인하고 다음');
    expect(api.acceptSuggestion).toHaveBeenCalledWith(deps.s, '2026-12-01');
    await vi.waitFor(() => expect(deps.onNext).toHaveBeenCalledOnce());
  });

  it('does not move on when the approval fails', async () => {
    const { deps, click } = setup({ run: vi.fn(async () => false) });
    await click('승인하고 다음');
    expect(deps.onNext).not.toHaveBeenCalled();
  });

  it('반려 asks for a reason pre-filled with the memo and rejects with it', async () => {
    const { deps, click } = setup({ memo: '측정 횟수 확인 필요' });
    await click('반려');
    expect(deps.ask).toHaveBeenCalledWith('반려 사유', '측정 횟수 확인 필요');
    expect(api.rejectSuggestion).toHaveBeenCalledWith(deps.s, '원문과 다름');
  });

  it('반려 does nothing when the reason prompt is cancelled', async () => {
    const { deps, actions } = setup({ ask: vi.fn(async () => null) });
    actions[0].onClick();
    await vi.waitFor(() => expect(deps.ask).toHaveBeenCalled());
    expect(deps.run).not.toHaveBeenCalled();
  });

  it('고쳐서 승인 opens the edit form', () => {
    const { deps, actions } = setup();
    actions[1].onClick();
    expect(deps.onEdit).toHaveBeenCalledOnce();
  });

  it('blocks approval of an unopened scan', () => {
    expect(setup({ unviewedScan: true }).actions.at(-1)).toMatchObject({ label: '승인하고 다음', disabled: true });
  });

  it('lets a member without approval rights only send requests', async () => {
    const { labels, deps, click } = setup({ lead: false });
    expect(labels).toEqual(['반려 요청', '고쳐서 요청', '승인 요청하고 다음']);
    await click('승인 요청하고 다음');
    expect(api.submitSuggestion).toHaveBeenCalledWith(deps.s, 'ACCEPT');
    expect(api.acceptSuggestion).not.toHaveBeenCalled();
  });

  it('adds 요청 돌려보내기 for a submitted request and 재검토 for a rejected one', () => {
    expect(setup({ s: suggestion({ status: 'SUBMITTED' }) }).labels).toEqual(['반려', '요청 돌려보내기', '승인하고 다음']);
    expect(setup({ s: suggestion({ status: 'REJECTED' }) }).labels).toEqual(['재검토']);
    expect(setup({ s: suggestion({ status: 'ACCEPTED' }) }).labels).toEqual([]);
  });
});

describe('ActionBar', () => {
  const actions = setup().actions;

  it('shows the remaining count and enabled buttons', () => {
    const out = html(<ActionBar remaining={5} actions={actions} readOnly={false} />);
    expect(out).toContain('남은 검토 5건');
    expect(out).not.toContain('disabled=""');
  });

  it('disables every button for the read-only demo login and says why', () => {
    const out = html(<ActionBar remaining={5} actions={actions} readOnly />);
    const buttons = [...out.matchAll(/<button [^>]*>/g)].map((m) => m[0]);
    expect(buttons).toHaveLength(3);
    for (const b of buttons) {
      expect(b).toContain('disabled=""');
      expect(b).toContain(`title="${READ_ONLY}"`);
    }
  });
});
