import { Link } from 'react-router';
import { TODO_LABEL, dDayOf, type Todo } from '../api/todos';
import { dDayClasses } from './dashboard';

/** One line per todo; the whole row links into the project tab that handles it. */
export default function TodoTable({ todos, showProject = true }: { todos: Todo[]; showProject?: boolean }) {
  return (
    <div className="overflow-x-auto rounded border border-line bg-white shadow-sm">
      <table className="w-full min-w-[640px] whitespace-nowrap text-left text-sm">
        <thead className="bg-paper text-xs text-body">
          <tr>
            <th className="px-4 py-3 font-medium">구분</th>
            {showProject && <th className="px-4 py-3 font-medium">프로젝트</th>}
            <th className="px-4 py-3 font-medium">내용</th>
            <th className="px-4 py-3 font-medium">기한</th>
            <th className="px-4 py-3 font-medium">D-day</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-line">
          {todos.map((t) => {
            const d = t.due ? dDayOf(t.due) : null;
            return (
              <tr key={t.key} className="hover:bg-paper">
                <td className="px-4 py-2.5 text-body">{TODO_LABEL[t.kind]}</td>
                {showProject && (
                  <td className="max-w-[200px] px-4 py-2.5">
                    <span className="block truncate text-body" title={t.projectName}>{t.projectName}</span>
                  </td>
                )}
                <td className="w-full max-w-0 px-4 py-2.5">
                  <Link to={t.to} title={t.text} className="block truncate font-medium text-ink hover:text-brand hover:underline">
                    {t.text}
                  </Link>
                </td>
                <td className="px-4 py-2.5 text-body">{t.due ?? '—'}</td>
                <td className={`px-4 py-2.5 ${d === null ? 'text-mute' : d < 0 ? 'font-semibold text-danger' : dDayClasses(d)}`}>
                  {d === null ? '—' : d === 0 ? 'D-DAY' : d > 0 ? `D-${d}` : `D+${-d} 지남`}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      {todos.length === 0 && <p className="py-10 text-center text-sm text-mute">지금 처리할 일이 없습니다.</p>}
    </div>
  );
}
