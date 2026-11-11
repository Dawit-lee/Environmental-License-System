import { useEffect, useMemo, useState } from 'react';
import { getDeadlines, ymd, type Deadline } from '../api/notifications';
import { loadTodos, type Todo } from '../api/todos';
import type { PermitItem, Project } from '../types';
import { ActivityList, DeadlineTimeline, PendingRow, StatusDonut, recentActivity } from './dashboard';
import { ProjectCompliance } from './Compliance';
import TodoTable from './TodoTable';

// ---------------------------------------------------------------------------
// ProjectOverview — 프로젝트 › 개요: the dashboard scoped to one project.
// Top: 규제 대응 현황 (operations-compliance F4, components/Compliance).
// ---------------------------------------------------------------------------

const card = 'rounded-xl border border-[#E5E3DC] bg-white p-5 shadow-sm';

export default function ProjectOverview({ project, permits }: { project: Project; permits: PermitItem[] }) {
  const [todos, setTodos] = useState<Todo[]>([]);
  const [deadlines, setDeadlines] = useState<Deadline[]>([]);
  const base = `/projects/${project.id}`;

  useEffect(() => {
    if (!project.member) return; // review, law and deadline lists are member-only
    const to = new Date();
    to.setDate(to.getDate() + 90);
    loadTodos([project]).then(setTodos, () => {});
    getDeadlines(ymd(new Date()), ymd(to)).then((l) => setDeadlines(l.filter((d) => d.projectId === project.id)), () => {});
  }, [project]);

  const activity = useMemo(() => recentActivity([project], permits), [project, permits]);
  const count = (k: Todo['kind']) => todos.filter((t) => t.kind === k).length;

  return (
    <div className="space-y-6">
      <ProjectCompliance projectId={project.id} />
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <section className={card}>
          <h2 className="text-lg font-semibold text-gray-900">인허가 상태</h2>
          <StatusDonut permits={permits} />
        </section>
        <section className={card}>
          <h2 className="text-lg font-semibold text-gray-900">처리 대기</h2>
          <ul className="mt-3 divide-y divide-[#E5E3DC]">
            <PendingRow to={`${base}/review`} label="AI 검토 대기" value={count('REVIEW')} />
            <PendingRow to={`${base}/laws`} label="법령 개정 확인 필요" value={count('LAW')} />
            <PendingRow to={`${base}/review`} label="현장 반영 대기" value={count('SITE')} />
          </ul>
        </section>
        <section className={card}>
          <h2 className="text-lg font-semibold text-gray-900">향후 90일 기한 (주별)</h2>
          <DeadlineTimeline deadlines={deadlines} />
        </section>
      </div>

      <section>
        <h2 className="mb-3 text-lg font-semibold text-gray-900">할 일 (기한은 30일 이내)</h2>
        <TodoTable todos={todos} showProject={false} />
      </section>

      <section className={card}>
        <h2 className="text-lg font-semibold text-gray-900">최근 활동</h2>
        <ActivityList activity={activity} />
      </section>
    </div>
  );
}
