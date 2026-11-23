import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import type { Deadline } from '../api/notifications';
import { UpcomingDeadlines, permitSummaryItems, summaryItems } from './dashboard';
import type { PermitItem } from '../types';
import { SummaryStrip } from './ui/SummaryStrip';

beforeAll(() => {
  const error = console.error;
  vi.spyOn(console, 'error').mockImplementation((...a) => { if (!String(a[0]).includes('useLayoutEffect')) error(...a); });
});

const html = (node: React.ReactNode) => renderToStaticMarkup(<MemoryRouter>{node}</MemoryRouter>);

const dl = (id: number, dDay: number, extra: Partial<Deadline> = {}): Deadline => ({
  id, kind: 'OBLIGATION', targetId: id, projectId: 1, projectName: '가람시 하수처리장', title: `항목${id}`,
  dueDate: '2026-12-01', dDay, assigneeId: null, assigneeName: null, ...extra,
});

/** Visible text of each <tbody> row's cells. */
const rows = (out: string) =>
  [...out.split('<tbody>')[1].matchAll(/<tr>(.*?)<\/tr>/g)].map(([, r]) =>
    [...r.matchAll(/<td[^>]*>(.*?)<\/td>/g)].map(([, c]) => c.replace(/<[^>]+>/g, '')));

describe('dashboard summary strip', () => {
  const counts = { projects: 5, permits: 15, overdue: 1, dueSoon: 4, notApplied: 3, reviewOpen: 32 };

  it('shows every count in one strip, with 기한 경과 on a red ground', () => {
    const out = html(<SummaryStrip items={summaryItems(counts)} />);
    expect(out.match(/<dl /g)).toHaveLength(1);
    for (const label of ['진행 중 프로젝트', '관리 중 인허가', '기한 경과', '기한 임박', '미신청 인허가', 'AI 검토 대기']) {
      expect(out).toContain(label);
    }
    const cells = out.split('<div class="').slice(1);
    expect(cells.filter((c) => c.includes('bg-danger-bg'))).toHaveLength(1);
    expect(cells.find((c) => c.includes('bg-danger-bg'))).toContain('기한 경과');
  });

  it('keeps 기한 경과 plain when nothing is overdue', () => {
    expect(html(<SummaryStrip items={summaryItems({ ...counts, overdue: 0 })} />)).not.toContain('bg-danger-bg');
  });
});

describe('UpcomingDeadlines', () => {
  it('lists overdue and 30-day items with worded badges, skipping later ones', () => {
    const out = html(<UpcomingDeadlines deadlines={[
      dl(1, -3, { assigneeName: '이담당' }), dl(2, 0), dl(3, 4), dl(4, 30), dl(5, 31),
    ]} />);
    expect(out).toContain('30일 안에 닥치는 기한');
    expect(rows(out).map((r) => r[0])).toEqual(['경과 3일', 'D-DAY', 'D-4', 'D-30']);
    expect(rows(out)[0]).toEqual(['경과 3일', '2026-12-01', '항목1', '가람시 하수처리장', '이담당']);
    expect(rows(out)[1][4]).toBe('전체 멤버');
    expect(out).toContain('bg-danger-bg'); // 경과 badge
    expect(out).toContain('bg-warn-bg'); // D-N within 30 days
  });

  it('offers each project once in the filter', () => {
    const out = html(<UpcomingDeadlines deadlines={[dl(1, 1), dl(2, 2), dl(3, 3, { projectId: 2, projectName: '누리시 자원회수시설' })]} />);
    expect(out.match(/<option/g)).toHaveLength(3); // 전체 + 2 projects
  });

  it('shows ten rows and a button for the rest', () => {
    const out = html(<UpcomingDeadlines deadlines={Array.from({ length: 12 }, (_, i) => dl(i + 1, i))} />);
    expect(rows(out)).toHaveLength(10);
    expect(out).toContain('나머지 2건 더 보기');
  });

  it('says so when nothing is due, and shows a load error instead', () => {
    expect(html(<UpcomingDeadlines deadlines={[]} />)).toContain('30일 안에 닥치는 기한이 없습니다.');
    expect(html(<UpcomingDeadlines deadlines={[]} error="불러오지 못했습니다" />)).toContain('불러오지 못했습니다');
  });
});

describe('project 개요 summary strip', () => {
  const permits = (...s: PermitItem['status'][]) => s.map((status) => ({ status }) as PermitItem);

  it('counts permits by status and colours 만료 임박 only when there is one', () => {
    const items = permitSummaryItems(permits('COMPLETED', 'COMPLETED', 'NEAR_EXPIRY', 'NOT_APPLIED'));
    expect(items.map((i) => i.value)).toEqual([4, 2, 1, 1]);
    expect(items[2].tone).toBe('warning');
    expect(permitSummaryItems(permits('COMPLETED'))[2].tone).toBeUndefined();
  });
});
