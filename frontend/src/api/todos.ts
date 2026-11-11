// "내 할 일": what waits on someone across projects. Built client-side from the existing lists.
import { api } from './client';
import { getLawImpacts } from './lawImpacts';
import { getDeadlines, ymd } from './notifications';
import { listSiteImplementations } from './revisions';
import { listMyTasks } from './tasks';

export type TodoKind = 'REVIEW' | 'LAW' | 'SITE' | 'TASK' | 'DEADLINE';

export interface Todo {
  key: string;
  kind: TodoKind;
  projectId: number;
  projectName: string;
  text: string;
  due: string | null; // YYYY-MM-DD
  /** the project tab that handles it */
  to: string;
}

export const TODO_LABEL: Record<TodoKind, string> = {
  REVIEW: 'AI 검토 대기', LAW: '법령 개정 미확인', SITE: '현장 반영 대기', TASK: '이행 업무', DEADLINE: '기한 임박',
};

interface OpenSuggestion {
  id: number;
  kind: string;
  status: string;
  payload: { summary?: string };
  editedPayload: { summary?: string } | null;
}

/**
 * Open review items, unreviewed law impacts, site work, my open tasks and deadlines within `days` for the given projects
 * (must be projects I am a member of), soonest due first, undated last.
 * shortcut: two calls per project; add a server-side inbox endpoint if project counts grow large.
 */
export async function loadTodos(projects: { id: number; name: string }[], days = 30): Promise<Todo[]> {
  const names = new Map(projects.map((p) => [p.id, p.name]));
  const today = new Date();
  const until = new Date(today);
  until.setDate(until.getDate() + days);
  const [suggestions, site, impacts, deadlines, tasks] = await Promise.all([
    Promise.all(projects.map((p) => api<OpenSuggestion[]>(`/api/projects/${p.id}/suggestions`).then((l) => l.map((s) => ({ ...s, projectId: p.id }))))),
    Promise.all(projects.map((p) => listSiteImplementations(p.id, 'open'))),
    getLawImpacts(),
    getDeadlines(ymd(today), ymd(until)),
    listMyTasks(),
  ]);
  // a task of mine is listed as 이행 업무, so its own D-day entry would be a duplicate
  const myObligations = new Set(tasks.filter((t) => t.kind === 'OBLIGATION').map((t) => t.obligationId));
  const myTasks = new Set(tasks.map((t) => t.id));
  const mine = (d: { kind: string; targetId: number }) =>
    (d.kind === 'OBLIGATION' && myObligations.has(d.targetId)) || (d.kind === 'CORRECTIVE_TASK' && myTasks.has(d.targetId));
  const todos: Todo[] = [
    ...suggestions.flat()
      // same rows the review tab shows: obligations and version-diff revisions
      .filter((s) => (s.kind === 'OBLIGATION' || s.kind === 'OBLIGATION_REVISION') && (s.status === 'PENDING' || s.status === 'SUBMITTED'))
      .map((s): Todo => ({
        key: `r${s.id}`, kind: 'REVIEW', projectId: s.projectId, projectName: names.get(s.projectId)!,
        text: (s.editedPayload ?? s.payload)?.summary ?? (s.kind === 'OBLIGATION_REVISION' ? '허가조건 개정 비교 결과' : '의무사항'),
        due: null, to: `/projects/${s.projectId}/review`,
      })),
    ...impacts.filter((i) => i.status === 'NEW' && names.has(i.projectId)).map((i): Todo => ({
      key: `l${i.id}`, kind: 'LAW', projectId: i.projectId, projectName: i.projectName,
      text: `${i.lawName} ${i.revisionType} — ${i.permitName}`, due: i.enforcedOn, to: `/projects/${i.projectId}/laws?id=${i.id}`,
    })),
    ...site.flat().map((s): Todo => ({
      key: `s${s.suggestionId}`, kind: 'SITE', projectId: s.projectId, projectName: names.get(s.projectId)!,
      text: `${s.summary} — ${s.permitName}`, due: s.targetDate, to: `/projects/${s.projectId}/review`,
    })),
    ...tasks.filter((t) => names.has(t.projectId)).map((t): Todo => ({
      key: `t${t.id}`, kind: 'TASK', projectId: t.projectId, projectName: names.get(t.projectId)!,
      text: t.title, due: t.dueDate, to: `/projects/${t.projectId}/obligations`,
    })),
    ...deadlines.filter((d) => names.has(d.projectId) && !mine(d)).map((d): Todo => ({
      key: `d${d.id}`, kind: 'DEADLINE', projectId: d.projectId, projectName: d.projectName,
      text: d.title, due: d.dueDate, to: `/projects/${d.projectId}/${d.kind === 'OBLIGATION' || d.kind === 'CORRECTIVE_TASK' ? 'obligations' : 'schedule'}`,
    })),
  ];
  return todos.sort((a, b) => (a.due ?? '9999').localeCompare(b.due ?? '9999'));
}

export const dDayOf = (date: string) => Math.round((Date.parse(date) - Date.parse(ymd(new Date()))) / 86_400_000);
