import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { addDays, format } from 'date-fns';
import { describe, expect, it } from 'vitest';
import type { Todo, TodoKind } from '../api/todos';
import TodoTable from '../components/TodoTable';
import { calendarLegend } from '../components/ScheduleCalendar';
import { filterTodos, inboxTabs } from './InboxPage';

const day = (n: number) => format(addDays(new Date(), n), 'yyyy-MM-dd');
const todo = (key: string, kind: TodoKind, due: string | null): Todo =>
  ({ key, kind, projectId: 1, projectName: '가람시 하수처리장', text: `할 일 ${key}`, due, to: '/projects/1/review' });

const todos = [todo('a', 'REVIEW', null), todo('b', 'LAW', day(-3)), todo('c', 'DEADLINE', day(4)), todo('d', 'LAW', day(90))];
const html = (list: Todo[]) => renderToStaticMarkup(<MemoryRouter><TodoTable todos={list} /></MemoryRouter>);

describe('내 할 일 필터', () => {
  it('전체 keeps every todo; a kind keeps only that kind', () => {
    expect(filterTodos(todos, 'ALL')).toHaveLength(4);
    expect(filterTodos(todos, 'LAW').map((t) => t.key)).toEqual(['b', 'd']);
    expect(filterTodos(todos, 'EVENT')).toEqual([]);
  });

  it('tabs list 전체 first, then every kind with its count', () => {
    const tabs = inboxTabs(todos);
    expect(tabs[0]).toEqual({ key: 'ALL', label: '전체', count: 4 });
    expect(tabs.find((t) => t.key === 'LAW')).toEqual({ key: 'LAW', label: '법령 개정 미확인', count: 2 });
    expect(tabs.find((t) => t.key === 'SITE')?.count).toBe(0);
    expect(tabs).toHaveLength(7);
  });
});

describe('내 할 일 표 배지', () => {
  it('shows the kind and the deadline as words, not colour alone', () => {
    const out = html(todos);
    expect(out).toContain('>AI 검토 대기</span>');
    expect(out).toContain('>경과 3일</span>');
    expect(out).toContain('>D-4</span>');
    expect(out).toContain('>D-90</span>');
  });

  it('colours by meaning: overdue danger, due soon warning, far off neutral', () => {
    const cls = (text: string) => new RegExp(`class="([^"]*)">${text}</span>`).exec(html(todos))?.[1] ?? '';
    expect(cls('경과 3일')).toContain('text-danger');
    expect(cls('D-4')).toContain('text-warn');
    expect(cls('D-90')).toContain('text-body');
  });

  it('shows the empty state when nothing waits', () => {
    expect(html([])).toContain('지금 처리할 일이 없습니다.');
  });
});

describe('달력 범례', () => {
  it('names every colour in words and adds 기한 only when deadlines are shown', () => {
    expect(calendarLegend(false).map((l) => l.label)).toEqual(['이벤트(회의·심의 등)', '출장', '◆ 마일스톤']);
    expect(calendarLegend(true).map((l) => l.label)).toContain('기한 · 인허가 기한');
  });
});
