import { useEffect, useState } from 'react';
import { DUE_SOON_DAYS } from '../terms';
import { useSearchParams } from 'react-router';
import { api } from '../api/client';
import { TODO_LABEL, loadTodos, type Todo, type TodoKind } from '../api/todos';
import TodoTable from '../components/TodoTable';
import type { Project } from '../types';
import { PageHeader } from '../components/ui/PageHeader';
import { Tabs, type TabItem } from '../components/ui/Tabs';
import { Alert } from '../components/ui/Alert';
import { Button } from '../components/ui/Button';

// ---------------------------------------------------------------------------
// InboxPage — 내 할 일: everything waiting across the projects I am a member of. ?kind= preselects a filter.
// ---------------------------------------------------------------------------

const KINDS = Object.keys(TODO_LABEL) as TodoKind[];
export type InboxFilter = TodoKind | 'ALL';

export const filterTodos = (todos: Todo[], kind: InboxFilter) => (kind === 'ALL' ? todos : todos.filter((t) => t.kind === kind));

/** 전체 + one tab per kind, each with its count. */
export const inboxTabs = (todos: Todo[]): TabItem[] =>
  (['ALL', ...KINDS] as const).map((k) => ({ key: k, label: k === 'ALL' ? '전체' : TODO_LABEL[k], count: filterTodos(todos, k).length }));

export default function InboxPage() {
  const [params, setParams] = useSearchParams();
  const [todos, setTodos] = useState<Todo[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const kind: InboxFilter = KINDS.find((k) => k === params.get('kind')) ?? 'ALL';
  const setKind = (k: string) => setParams(k === 'ALL' ? {} : { kind: k }, { replace: true });

  const load = () => {
    setError(null);
    setTodos(null);
    api<Project[]>('/api/projects').then((ps) => loadTodos(ps)).then(setTodos, (e: Error) => setError(e.message));
  };
  useEffect(load, []);

  const shown = todos && filterTodos(todos, kind);

  return (
    <div className="min-h-full bg-paper">
      <div className="py-8">
        <PageHeader title="내 할 일" description={<>내가 멤버인 프로젝트에서 기다리는 일입니다. 기한 임박은 완료 전이고 {DUE_SOON_DAYS}일 이내인 내 몫의 기한입니다(대시보드는 프로젝트 전체를 셉니다). 내용을 누르면 해당 프로젝트 탭으로 갑니다.</>} />

        <Tabs label="할 일 분류" className="mt-6" tabs={inboxTabs(todos ?? [])} value={kind} onChange={setKind} />

        <div className="mt-4">
          {error ? (
            <Alert tone="danger" title="할 일을 불러오지 못했습니다.">
              <p>{error}</p>
              <Button variant="tertiary" size="sm" className="mt-3" onClick={load}>다시 시도</Button>
            </Alert>
          ) : !shown ? (
            <div aria-busy="true" aria-label="불러오는 중" className="rounded border border-line bg-white">
              {[0, 1, 2, 3, 4].map((i) => (
                <div key={i} className="flex h-10 items-center border-t border-line-soft px-4 first:border-t-0">
                  <div className="h-3 w-full animate-pulse rounded-sm bg-paper" />
                </div>
              ))}
            </div>
          ) : shown.length === 0 && kind !== 'ALL' ? (
            <div className="rounded border border-line bg-white px-4 py-10 text-center">
              <p className="font-bold text-ink">조건에 맞는 항목이 없습니다.</p>
              <Button variant="tertiary" className="mt-4" onClick={() => setKind('ALL')}>필터 모두 지우기</Button>
            </div>
          ) : (
            <TodoTable todos={shown} />
          )}
        </div>
      </div>
    </div>
  );
}
