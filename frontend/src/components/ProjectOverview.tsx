import { useEffect, useMemo, useState } from 'react';
import { getDeadlines, ymd, type Deadline } from '../api/notifications';
import { loadTodos, type Todo } from '../api/todos';
import type { PermitItem, Project } from '../types';
import { ActivityList, DeadlineTimeline, PendingRow, StatusDonut, recentActivity } from './dashboard';
import { ProjectCompliance } from './Compliance';
import TodoTable from './TodoTable';
import { ScheduleSummary } from './ScheduleWidgets';
import { Help } from './Onboarding';
import { DUE_SOON_DAYS } from '../terms';

// ---------------------------------------------------------------------------
// ProjectOverview — 프로젝트 › 개요: the dashboard scoped to one project.
// Top: 규제 대응 현황 (operations-compliance F4, components/Compliance).
// ---------------------------------------------------------------------------

// same boxes as the 대시보드: 1px border, title row split off by a line, no shadow
const box = 'min-w-0 rounded border border-line bg-white';
const boxTitle = 'border-b border-line px-5 py-4 text-[19px] font-bold text-ink';

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
      {project.member && <ProjectCompliance projectId={project.id} />}
      {project.member && <ScheduleSummary projectId={project.id} />}
      <div className="grid grid-cols-1 items-start gap-6 lg:grid-cols-3">
        <section className={box}>
          <h2 className={boxTitle}>인허가 상태</h2>
          <div className="px-5 pb-5"><StatusDonut permits={permits} /></div>
        </section>
        <section className={box}>
          <h2 className={boxTitle}>처리 대기</h2>
          <ul className="divide-y divide-line-soft px-5">
            <PendingRow to={`${base}/review`} label="AI 검토 대기" value={count('REVIEW')} />
            <PendingRow to={`${base}/laws`} label="법령 개정 확인 필요" value={count('LAW')} />
            <PendingRow to={`${base}/review`} label="현장 반영 대기" value={count('SITE')} />
          </ul>
        </section>
        <section className={box}>
          <h2 className={boxTitle}>향후 90일 기한 (주별)</h2>
          <div className="px-5 pb-5"><DeadlineTimeline deadlines={deadlines} /></div>
        </section>
      </div>

      <section>
        <h2 className="mb-3 text-[19px] font-bold text-ink">할 일 (기한 임박은 {DUE_SOON_DAYS}일 이내)<Help k="dueSoon" /></h2>
        <TodoTable todos={todos} showProject={false} />
      </section>

      <section className={box}>
        <h2 className={boxTitle}>최근 활동</h2>
        <div className="px-5 pb-2"><ActivityList activity={activity} /></div>
      </section>
    </div>
  );
}
