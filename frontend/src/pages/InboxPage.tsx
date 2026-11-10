import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router';
import { api } from '../api/client';
import { TODO_LABEL, loadTodos, type Todo, type TodoKind } from '../api/todos';
import TodoTable from '../components/TodoTable';
import type { Project } from '../types';

// ---------------------------------------------------------------------------
// InboxPage — 내 할 일: everything waiting across the projects I am a member of. ?kind= preselects a filter.
// ---------------------------------------------------------------------------

const KINDS = Object.keys(TODO_LABEL) as TodoKind[];

export default function InboxPage() {
  const [params, setParams] = useSearchParams();
  const [todos, setTodos] = useState<Todo[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const kind = KINDS.find((k) => k === params.get('kind')) ?? 'ALL';

  useEffect(() => {
    api<Project[]>('/api/projects').then((ps) => loadTodos(ps)).then(setTodos, (e: Error) => setError(e.message));
  }, []);

  return (
    <div className="min-h-full bg-[#F8F7F2]">
      <div className="mx-auto max-w-7xl px-4 py-8 sm:px-6">
        <h1 className="text-2xl font-bold text-[#1F4E79]">내 할 일</h1>
        <p className="mt-1 text-sm text-gray-500">내가 멤버인 프로젝트에서 기다리는 일입니다. 기한은 30일 이내만 보여 줍니다. 내용을 누르면 해당 프로젝트 탭으로 갑니다.</p>

        <div className="mt-5 flex flex-wrap gap-2">
          {(['ALL', ...KINDS] as const).map((k) => (
            <button key={k} type="button" onClick={() => setParams(k === 'ALL' ? {} : { kind: k }, { replace: true })}
              className={`rounded-full px-3.5 py-1.5 text-sm font-medium transition ${kind === k ? 'bg-[#1F4E79] text-white' : 'bg-white text-gray-600 hover:bg-[#E5E3DC]'}`}>
              {k === 'ALL' ? '전체' : TODO_LABEL[k]} ({(todos ?? []).filter((t) => k === 'ALL' || t.kind === k).length})
            </button>
          ))}
        </div>

        <div className="mt-4">
          {error ? <p role="alert" className="text-sm text-[#991B1B]">{error}</p>
            : !todos ? <p className="text-sm text-gray-400">불러오는 중…</p>
            : <TodoTable todos={todos.filter((t) => kind === 'ALL' || t.kind === kind)} />}
        </div>
      </div>
    </div>
  );
}
