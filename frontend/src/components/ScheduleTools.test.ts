import { describe, expect, it } from 'vitest';
import { offsetText, resolveTemplateItem } from './ScheduleTools';

const m = { kind: 'MILESTONE' as const, durationDays: null, offsetMonths: null };

describe('template preview', () => {
  it('counts back from the bid date like the server', () => {
    expect(resolveTemplateItem({ ...m, offsetDays: -150 }, '2027-01-15')).toEqual({ start: '2026-08-18', end: '2026-08-18' });
    // a task ends on the offset and runs back over its length
    expect(resolveTemplateItem({ kind: 'TASK', offsetDays: -60, offsetMonths: null, durationDays: 85 }, '2027-01-15'))
      .toEqual({ start: '2026-08-24', end: '2026-11-16' });
    // +4개월 from 1/31 clamps to 5/31 (Java plusMonths)
    expect(resolveTemplateItem({ ...m, offsetDays: null, offsetMonths: 4 }, '2026-01-31')?.end).toBe('2026-05-31');
    expect(resolveTemplateItem({ ...m, offsetDays: -1 }, '')).toBeNull();
  });

  it('labels offsets the way the team says them', () => {
    expect(offsetText({ ...m, anchor: 'SUBMISSION', offsetDays: -150 })).toBe('입찰 D-150');
    expect(offsetText({ ...m, anchor: 'SUBMISSION', offsetDays: 0 })).toBe('입찰 당일');
    expect(offsetText({ ...m, anchor: 'CONTRACT', offsetDays: null, offsetMonths: 4 })).toBe('계약 +4개월');
    expect(offsetText({ ...m, anchor: 'COMPLETION', offsetDays: -15 })).toBe('완료 −15일');
    expect(offsetText({ kind: 'TASK', anchor: 'SUBMISSION', offsetDays: -60, offsetMonths: null, durationDays: 85 })).toBe('입찰 D-60 종료 · 85일');
  });
});
