import { afterEach, describe, expect, it, vi } from 'vitest';
import { needsReason, offsetLabel, type ScheduleItem } from './api/schedule';
import { dDayOf } from './api/todos';
import { lifecycleStage } from './components/dashboard';
import { isDueSoon } from './terms';
import type { PermitItem, Project } from './types';

describe('offsetLabel (schedule offset math)', () => {
  it('is null for a fixed-date item', () => {
    expect(offsetLabel({ anchor: null, offsetDays: 5, offsetMonths: null })).toBeNull();
  });
  it('shows days before an anchor with a real minus sign', () => {
    expect(offsetLabel({ anchor: 'SUBMISSION', offsetDays: -90, offsetMonths: null })).toMatch(/ −90일$/);
  });
  it('prefers months over days', () => {
    expect(offsetLabel({ anchor: 'CONTRACT', offsetDays: 10, offsetMonths: 4 })).toMatch(/ \+4개월$/);
  });
});

describe('needsReason (delay past the baseline)', () => {
  const item = (baselineEnd: string | null, endDate: string | null) => ({ baselineEnd, endDate }) as ScheduleItem;
  it('asks when the new end slips past both baseline and current end', () => {
    expect(needsReason(item('2026-03-01', '2026-03-01'), '2026-03-10')).toBe(true);
  });
  it('does not ask before a baseline exists or when moving earlier', () => {
    expect(needsReason(item(null, '2026-03-01'), '2026-03-10')).toBe(false);
    expect(needsReason(item('2026-03-01', '2026-03-20'), '2026-03-10')).toBe(false);
  });
});

describe('due dates', () => {
  afterEach(() => vi.useRealTimers());
  it('counts calendar days to a date', () => {
    vi.useFakeTimers({ now: new Date(2026, 10, 17, 15, 0) });
    expect(dDayOf('2026-11-17')).toBe(0);
    expect(dDayOf('2026-12-17')).toBe(30);
    expect(dDayOf('2026-11-10')).toBe(-7);
  });
  it('treats 0–30 days ahead as due soon', () => {
    expect([-1, 0, 30, 31].map(isDueSoon)).toEqual([false, true, true, false]);
  });
});

describe('lifecycleStage', () => {
  const project = { constructionStartDate: '2026-06-01', expectedEndDate: '2027-06-01' } as Project;
  const permit = (category: PermitItem['category'], status: PermitItem['status']) => ({ category, status }) as PermitItem;
  it('stays at 입지 until location/environment permits are done', () => {
    expect(lifecycleStage(project, [permit('LOCATION', 'IN_PROGRESS')], '2026-01-01')).toBe(0);
  });
  it('moves through 설계 → 착공 → 준공 → 운영 by date and status', () => {
    const permits = [permit('LOCATION', 'COMPLETED'), permit('ENVIRONMENT', 'COMPLETED')];
    expect(lifecycleStage(project, permits, '2026-01-01')).toBe(1);
    expect(lifecycleStage(project, permits, '2026-07-01')).toBe(2);
    expect(lifecycleStage(project, [...permits, permit('CONSTRUCTION', 'IN_PROGRESS')], '2027-07-01')).toBe(3);
    expect(lifecycleStage(project, permits, '2027-07-01')).toBe(4);
  });
});
