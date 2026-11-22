import { lazy, Suspense, useEffect, useState } from 'react';
import { getCalendar, type CalendarData, type ProjectEvent } from '../api/events';
import { ymd } from '../api/notifications';
import type { Discipline } from '../api/schedule';
import { EntryDetail, EventForm } from './EventPanels';
import type { CalendarEntry } from './ScheduleCalendar';

// FullCalendar is large; only the calendar views load it
const ScheduleCalendar = lazy(() => import('./ScheduleCalendar'));

// ---------------------------------------------------------------------------
// ProjectCalendar — 프로젝트 › 일정 › [달력]: this project's events, milestones and (toggle) 인허가 기한.
// Event click → detail (→ edit); milestone click → the schedule item form (onOpenItem).
// ---------------------------------------------------------------------------

const EMPTY: CalendarData = { events: [], milestones: [], deadlines: [] };

export default function ProjectCalendar({ projectId, disciplines, members, onOpenItem, onChanged }: {
  projectId: number;
  disciplines: Discipline[];
  members: { userId: number; name: string }[];
  onOpenItem: (id: number) => void;
  /** events or milestones changed (the Gantt reloads) */
  onChanged: () => void;
}) {
  const [range, setRange] = useState<[string, string] | null>(null);
  const [data, setData] = useState<CalendarData>(EMPTY);
  const [showDeadlines, setShowDeadlines] = useState(false);
  const [open, setOpen] = useState<CalendarEntry | null>(null);
  const [editing, setEditing] = useState<ProjectEvent | 'new' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loads, setLoads] = useState(0);

  useEffect(() => {
    if (!range) return;
    getCalendar(range[0], range[1], { project: String(projectId) }).then(setData, (e: Error) => setError(e.message));
  }, [projectId, range, loads]);

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-3">
        <label className="flex items-center gap-1.5 text-xs text-body">
          <input type="checkbox" checked={showDeadlines} onChange={(e) => setShowDeadlines(e.target.checked)} />
          인허가 기한 표시
        </label>
        <p className="text-xs text-mute">이벤트 {data.events.length}건 · 마일스톤 {data.milestones.length}건</p>
        <button type="button" onClick={() => setEditing('new')}
          className="ml-auto rounded bg-brand px-3.5 py-1.5 text-xs font-semibold text-white hover:bg-brand-hover">+ 이벤트</button>
      </div>
      {error && <p role="alert" className="rounded bg-red-50 px-3 py-2 text-sm text-danger">{error}</p>}
      <Suspense fallback={<div className="h-[640px] rounded border border-line bg-white" />}>
        <ScheduleCalendar data={data} showDeadlines={showDeadlines} showProject={false}
          onRange={(from, to) => setRange([from, to])}
          onOpen={(entry) => (entry.type === 'milestone' ? onOpenItem(entry.milestone.id) : setOpen(entry))} />
      </Suspense>

      {open && <EntryDetail entry={open} onClose={() => setOpen(null)}
        onEdit={(e) => { setOpen(null); setEditing(e); }} />}
      {editing && (
        <EventForm projectId={projectId} event={editing === 'new' ? null : editing} defaultDate={ymd(new Date())}
          disciplines={disciplines} members={members} onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); setLoads((n) => n + 1); onChanged(); }} />
      )}
    </div>
  );
}
