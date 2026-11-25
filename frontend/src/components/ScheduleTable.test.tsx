// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ScheduleItem } from '../api/schedule';
import ScheduleTable, { durationOf, endFromDuration, parseDateText, pushedSuccessors } from './ScheduleTable';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

describe('date text', () => {
  it('reads the ways the team types a date', () => {
    expect(parseDateText('26.11.19')).toBe('2026-11-19');
    expect(parseDateText('2026-11-19')).toBe('2026-11-19');
    expect(parseDateText(' 2026.1.5 ')).toBe('2026-01-05');
    expect(parseDateText('26/3/7')).toBe('2026-03-07');
  });

  it('refuses what is not a real day', () => {
    expect(parseDateText('26.02.30')).toBeNull();
    expect(parseDateText('2026-13-01')).toBeNull();
    expect(parseDateText('내일')).toBeNull();
    expect(parseDateText('')).toBeNull();
  });
});

describe('duration', () => {
  it('counts both ends, like the server', () => {
    expect(durationOf('2026-11-19', '2026-11-19')).toBe(1);
    expect(durationOf('2026-08-24', '2026-11-16')).toBe(85);
    expect(endFromDuration('2026-08-24', 85)).toBe('2026-11-16');
    expect(endFromDuration('2026-02-27', 3)).toBe('2026-03-01');
  });
});

describe('pushed successors', () => {
  const item = (id: number, endDate: string, actualEnd: string | null = null) => ({ id, title: `항목${id}`, endDate, actualEnd });
  // 1 → 2 → 3, 1 → 4 (done) → 5, 2 → 3 also through 6
  const items = [item(1, '2026-01-20'), item(2, '2026-02-10'), item(3, '2026-02-15'), item(4, '2026-01-30', '2026-01-29'),
    item(5, '2026-02-20'), item(6, '2026-02-12'), item(7, '2026-03-01')];
  const deps = [[1, 2], [2, 3], [1, 4], [4, 5], [2, 6], [6, 3]].map(([p, s]) => ({ predecessorId: p, successorId: s }));

  it('lists every open successor down the chain once, stopping at a completed one', () => {
    expect(pushedSuccessors(items, deps, 1, '2026-01-23').map((i) => i.id)).toEqual([2, 3, 6]);
  });

  it('moves nothing when the end does not get later', () => {
    expect(pushedSuccessors(items, deps, 1, '2026-01-20')).toEqual([]);
    expect(pushedSuccessors(items, deps, 1, '2026-01-10')).toEqual([]);
    expect(pushedSuccessors(items, deps, 7, '2026-03-09')).toEqual([]);
  });
});

// --- the table itself: the DOM a keyboard user gets is the seam ----------------------------------------------
const row = (id: number, title: string, startDate: string, endDate: string, extra: Partial<ScheduleItem> = {}) => ({
  id, title, startDate, endDate, kind: 'TASK', disciplineId: null, disciplineName: null, parentId: null, baselineStart: null,
  baselineEnd: null, actualEnd: null, anchor: null, offsetDays: null, offsetMonths: null, durationDays: null, ownerUserId: null,
  ownerName: '이가온', partnerName: null, weight: null, progress: 0, delayReason: null, version: 0, status: 'PLANNED',
  delayDays: 0, slipDays: null, submission: false, ...extra,
}) as ScheduleItem;
const plan = [row(1, '기본계획', '2026-11-02', '2026-11-16'), row(2, '실시설계', '2026-11-17', '2026-12-31'),
  row(3, '중간보고', '2027-01-05', '2027-01-05', { kind: 'MILESTONE' })];
const chain = [{ predecessorId: 1, successorId: 2 }, { predecessorId: 2, successorId: 3 }];

let root: Root | null = null;
let host: HTMLElement;
afterEach(() => { act(() => root?.unmount()); root = null; host?.remove(); });
function mount(props: Partial<Parameters<typeof ScheduleTable>[0]> = {}) {
  const onSave = vi.fn(() => Promise.resolve());
  host = document.body.appendChild(document.createElement('div'));
  root = createRoot(host);
  act(() => root!.render(<ScheduleTable items={plan} all={plan} dependencies={chain} disciplines={[]} canEdit shift
    onSave={onSave} onOpen={() => {}} {...props} />));
  return onSave;
}
const cell = (label: string) => host.querySelector<HTMLElement>(`[aria-label="${label}"]`)!;
const typeInto = (input: HTMLInputElement, value: string) => act(() => {
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
});
const key = (el: Element, k: string) => act(() => { el.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true })); });
const editor = () => host.querySelector<HTMLInputElement>('input[type="text"]')!;

describe('ScheduleTable', () => {
  it('shows the six columns, 마일스톤 in the end cell and — for its length', () => {
    mount();
    expect([...host.querySelectorAll('thead th')].map((th) => th.textContent)).toEqual(['항목', '담당', '시작일', '종료일', '기간(일)', '기준 대비']);
    const milestone = [...host.querySelectorAll('tbody tr')].find((tr) => tr.textContent?.includes('중간보고'))!;
    expect(milestone.textContent).toContain('마일스톤');
    expect(milestone.textContent).toContain('—');
  });

  it('Esc puts the old date back without saving', () => {
    const onSave = mount();
    act(() => cell('시작일 고치기: 기본계획').click());
    typeInto(editor(), '26.11.05');
    key(editor(), 'Escape');
    expect(onSave).not.toHaveBeenCalled();
    expect(editor()).toBeNull();
    expect(cell('시작일 고치기: 기본계획').textContent).toBe('26.11.02');
  });

  it('a new length moves the end; with nothing behind it, Enter saves at once', async () => {
    const onSave = mount({ dependencies: [] });
    act(() => cell('기간 고치기: 기본계획').click());
    typeInto(editor(), '20');
    key(editor(), 'Enter');
    expect(onSave).toHaveBeenCalledWith(1, { startDate: '2026-11-02', endDate: '2026-11-21' }, false);
  });

  it('a later end with items behind asks first in a yellow line, then saves them together', () => {
    const onSave = mount();
    act(() => cell('종료일 고치기: 기본계획').click());
    typeInto(editor(), '26.11.19');
    key(editor(), 'Enter');
    expect(onSave).not.toHaveBeenCalled();
    const note = host.querySelector('[role="status"]')!;
    expect(note.textContent).toContain('종료일이 3일 늦어집니다. 뒤 일정 2건(실시설계, 중간보고)도 3일씩 밀립니다.');
    key(note, 'Enter');
    expect(onSave).toHaveBeenCalledWith(1, { startDate: '2026-11-02', endDate: '2026-11-19' }, true);
  });

  it('without 뒤 일정 같이 밀기 only the item changes', () => {
    const onSave = mount({ shift: false });
    act(() => cell('종료일 고치기: 기본계획').click());
    typeInto(editor(), '2026-11-19');
    key(editor(), 'Enter');
    expect(onSave).toHaveBeenCalledWith(1, { startDate: '2026-11-02', endDate: '2026-11-19' }, false);
  });

  it('the demo login only reads', () => {
    mount({ canEdit: false });
    expect(cell('시작일 고치기: 기본계획')).toBeNull();
    expect(host.textContent).toContain('26.11.02');
  });
});
