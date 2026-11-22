import { Link } from 'react-router';
import { TODO_LABEL, dDayOf, type Todo, type TodoKind } from '../api/todos';
import { Badge, dueBadge, type Tone } from './ui/Badge';
import { Table } from './ui/Table';
import { focusRing } from './ui/cn';

// DESIGN.md 상태 배지: 조치가 필요한 일은 warning, 검토·진행 중인 일은 info
export const TODO_TONE: Record<TodoKind, Tone> = {
  REVIEW: 'info', LAW: 'warning', SITE: 'warning', TASK: 'info', DEADLINE: 'warning', EVENT: 'info',
};

/** One line per todo; the 내용 link opens the project tab that handles it. Narrow screens scroll the table sideways. */
export default function TodoTable({ todos, showProject = true }: { todos: Todo[]; showProject?: boolean }) {
  if (todos.length === 0) {
    return (
      <div className="rounded border border-line bg-white px-4 py-10 text-center">
        <p className="font-bold text-ink">지금 처리할 일이 없습니다.</p>
        <p className="mt-1 text-[15px] text-body">검토·개정·기한이 생기면 여기에 모입니다.</p>
      </div>
    );
  }
  return (
    <Table density="compact" caption="할 일 목록" className="min-w-[880px]">
      <thead>
        <tr>
          <th scope="col">내용</th>
          <th scope="col">구분</th>
          {showProject && <th scope="col">프로젝트</th>}
          <th scope="col" className="text-right">기한</th>
          <th scope="col">남은 날</th>
        </tr>
      </thead>
      <tbody>
        {todos.map((t) => {
          const due = t.due ? dueBadge(dDayOf(t.due)) : null;
          return (
            <tr key={t.key}>
              <td className="w-full max-w-0">
                <Link to={t.to} title={t.text} className={`block truncate rounded text-brand-text hover:underline ${focusRing}`}>{t.text}</Link>
              </td>
              <td><Badge tone={TODO_TONE[t.kind]}>{TODO_LABEL[t.kind]}</Badge></td>
              {showProject && (
                <td className="max-w-[220px]"><span className="block truncate text-body" title={t.projectName}>{t.projectName}</span></td>
              )}
              <td className="whitespace-nowrap text-right tabular-nums text-body">{t.due ?? '—'}</td>
              <td>{due ? <Badge tone={due.tone}>{due.text}</Badge> : <span className="text-mute">—</span>}</td>
            </tr>
          );
        })}
      </tbody>
    </Table>
  );
}
