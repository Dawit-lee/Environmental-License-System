import { describe, expect, it } from 'vitest';
import { dragDates } from './ScheduleGantt';

const start = new Date(2026, 10, 2); // 2026-11-02, the chart's first day

describe('dragDates', () => {
  it('turns bar pixels into whole days, end inclusive', () => {
    // 10px per day (주 보기): x=225 → day 22.5 → rounds to 11/25 (half rounds up), width 98 → 10 days
    expect(dragDates(225, 98, start, 10, 'TASK')).toEqual({ startDate: '2026-11-25', endDate: '2026-12-04' });
  });

  it('a one-day task keeps one day', () => {
    expect(dragDates(400, 40, start, 40, 'TASK')).toEqual({ startDate: '2026-11-12', endDate: '2026-11-12' });
  });

  it('a milestone ends on its start', () => {
    expect(dragDates(30, 10, start, 10, 'MILESTONE')).toEqual({ startDate: '2026-11-05', endDate: '2026-11-05' });
  });
});
