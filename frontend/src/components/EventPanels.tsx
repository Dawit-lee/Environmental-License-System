import { useState, type ReactNode } from 'react';
import { Link } from 'react-router';
import {
  createEvent, deleteEvent, EVENT_KIND_LABEL, EVENT_KINDS, eventWhen, NEEDS_COUNTERPART, toEventInput, updateEvent,
  type EventInput, type EventKind, type ProjectEvent,
} from '../api/events';
import type { Discipline } from '../api/schedule';
import type { CalendarEntry } from './ScheduleCalendar';

// ---------------------------------------------------------------------------
// EventPanels — right-side panels for the calendars: EntryDetail (read-only, any calendar entry) and EventForm
// (create / edit a project event with its attendees). Same drawer layout as the schedule item form.
// ---------------------------------------------------------------------------

interface Member { userId: number; name: string }

const field = 'w-full rounded-lg border border-[#E5E3DC] px-2.5 py-1.5 text-sm outline-none focus:border-[#1F4E79]';
const btn = 'rounded-lg border border-[#E5E3DC] px-3 py-1.5 text-xs font-medium text-gray-700 hover:border-[#1F4E79] hover:text-[#1F4E79] disabled:opacity-40';
const row = 'grid grid-cols-[88px_1fr] items-center gap-3';
const SUBKINDS: Partial<Record<EventKind, string[]>> = {
  CLIENT_REPORT: ['착수', '중간', '최종', '월간', '격주'],
  MEETING: ['공정회의', '분야 미팅', '업체 미팅'],
};

function Drawer({ label, sub, title, onClose, children }: {
  label: string; sub: string; title: string; onClose: () => void; children: ReactNode;
}) {
  return (
    <div className="fixed inset-0 z-50">
      <div onClick={onClose} className="absolute inset-0 bg-black/40" />
      <aside role="dialog" aria-modal="true" aria-label={label}
        className="absolute right-0 top-0 flex h-full w-full max-w-[480px] flex-col bg-white shadow-xl">
        <div className="flex items-start justify-between gap-4 border-b border-[#E5E3DC] px-6 py-4">
          <div className="min-w-0">
            <p className="text-xs text-gray-500">{sub}</p>
            <h2 className="mt-1 truncate text-lg font-semibold text-gray-900" title={title}>{title}</h2>
          </div>
          <button type="button" onClick={onClose} aria-label="닫기" className="text-xl leading-none text-gray-400 hover:text-gray-700">×</button>
        </div>
        {children}
      </aside>
    </div>
  );
}

const Line = ({ label, value }: { label: string; value: ReactNode }) => value ? (
  <div className="grid grid-cols-[88px_1fr] gap-3 text-sm"><dt className="text-gray-500">{label}</dt><dd className="min-w-0 text-gray-800">{value}</dd></div>
) : null;

/** Read-only detail of a calendar entry. onEdit shows [수정] for events (project calendar, members). */
export function EntryDetail({ entry, onClose, onEdit }: { entry: CalendarEntry; onClose: () => void; onEdit?: (e: ProjectEvent) => void }) {
  if (entry.type !== 'event') {
    const m = entry.type === 'milestone' ? entry.milestone : null;
    const d = entry.type === 'deadline' ? entry.deadline : null;
    const projectId = (m ?? d)!.projectId;
    return (
      <Drawer label="달력 항목" sub={m ? '마일스톤' : '기한'} title={(m ?? d)!.title} onClose={onClose}>
        <dl className="space-y-2.5 px-6 py-5">
          <Line label="프로젝트" value={(m ?? d)!.projectName} />
          <Line label="날짜" value={m ? `${m.date}${m.done ? ' · 완료' : ''}` : d!.dueDate} />
          <Line label="분야" value={m?.disciplineName} />
        </dl>
        <div className="px-6"><Link className="text-sm text-[#1F4E79] hover:underline" to={`/projects/${projectId}/schedule`}>프로젝트 일정으로 이동</Link></div>
      </Drawer>
    );
  }
  const e = entry.event;
  return (
    <Drawer label="이벤트" sub={`${EVENT_KIND_LABEL[e.kind]}${e.subkind ? ` · ${e.subkind}` : ''}`} title={e.title} onClose={onClose}>
      <dl className="flex-1 space-y-2.5 overflow-y-auto px-6 py-5">
        <Line label="프로젝트" value={<Link className="text-[#1F4E79] hover:underline" to={`/projects/${e.projectId}/schedule`}>{e.projectName}</Link>} />
        <Line label="일시" value={eventWhen(e)} />
        <Line label="장소" value={e.location} />
        <Line label="상대 기관" value={e.counterpartOrg} />
        <Line label="목적" value={e.purpose} />
        <Line label="분야" value={e.disciplineName} />
        <Line label="참석자" value={e.attendees.length ? (
          <ul className="space-y-0.5">{e.attendees.map((a, i) => (
            <li key={i} className="truncate">{a.name ?? a.externalName}{a.userId ? '' : ' (외부)'}{a.role ? ` · ${a.role}` : ''}</li>
          ))}</ul>) : null} />
        <Line label="체크리스트" value={e.checklist && (
          <ul className="list-inside list-disc space-y-0.5">{e.checklist.split('\n').filter(Boolean).map((l, i) => <li key={i}>{l}</li>)}</ul>)} />
      </dl>
      {onEdit && <div className="border-t border-[#E5E3DC] px-6 py-4"><button type="button" className={btn} onClick={() => onEdit(e)}>수정</button></div>}
    </Drawer>
  );
}

/** Create (event = null) or edit a project event. */
export function EventForm({ projectId, event, defaultDate, disciplines, members, onClose, onSaved }: {
  projectId: number;
  event: ProjectEvent | null;
  defaultDate: string;
  disciplines: Discipline[];
  members: Member[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const [form, setForm] = useState<EventInput>(() => event ? toEventInput(event) : {
    kind: 'MEETING', subkind: null, title: '', startsAt: `${defaultDate}T10:00`, endsAt: `${defaultDate}T11:00`, allDay: false,
    location: null, counterpartOrg: null, purpose: null, disciplineId: null, checklist: null, attendees: [],
  });
  const [milestone, setMilestone] = useState<boolean | null>(null); // null = server default for the kind
  const [external, setExternal] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const set = (patch: Partial<EventInput>) => setForm((f) => ({ ...f, ...patch }));
  const text = (v: string) => v || null;
  const milestoneOn = milestone ?? (form.kind === 'SUBMISSION' || form.kind === 'CLIENT_REPORT');

  const run = async (action: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    try {
      await action();
      onSaved();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  const save = () => run(() => event ? updateEvent(projectId, event.id, form)
    : createEvent(projectId, { ...form, addMilestone: milestoneOn }));
  // all-day keeps dates only; the server drops times as well
  const dateOnly = (v: string) => v.slice(0, 10);
  const attending = new Set(form.attendees.map((a) => a.userId).filter(Boolean));

  return (
    <Drawer label="이벤트" sub={event ? EVENT_KIND_LABEL[event.kind] : '새 이벤트'} title={event?.title ?? '이벤트 추가'} onClose={onClose}>
      <form className="flex-1 space-y-3 overflow-y-auto px-6 py-5" onSubmit={(e) => { e.preventDefault(); save(); }}>
        {error && <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-[#991B1B]">{error}</p>}
        <label className={row}><span className="text-sm text-gray-500">종류</span>
          <select className={field} value={form.kind} onChange={(e) => {
            const kind = e.target.value as EventKind;
            set({ kind, subkind: null, ...(kind === 'TRIP' ? { allDay: true } : {}) });
          }}>
            {EVENT_KINDS.map((k) => <option key={k} value={k}>{EVENT_KIND_LABEL[k]}</option>)}
          </select></label>
        {SUBKINDS[form.kind] && (
          <label className={row}><span className="text-sm text-gray-500">세부</span>
            <select className={field} value={form.subkind ?? ''} onChange={(e) => set({ subkind: text(e.target.value) })}>
              <option value="">선택 안 함</option>
              {SUBKINDS[form.kind]!.map((s) => <option key={s} value={s}>{s}</option>)}
            </select></label>
        )}
        <label className={row}><span className="text-sm text-gray-500">제목</span>
          <input required maxLength={200} className={field} placeholder="예: 중간보고회" value={form.title}
            onChange={(e) => set({ title: e.target.value })} /></label>
        <label className={row}><span className="text-sm text-gray-500">종일</span>
          <input type="checkbox" className="justify-self-start" checked={form.allDay} onChange={(e) => set({ allDay: e.target.checked })} /></label>
        <label className={row}><span className="text-sm text-gray-500">시작</span>
          {form.allDay
            ? <input type="date" required className={field} value={dateOnly(form.startsAt)} onChange={(e) => set({ startsAt: `${e.target.value}T00:00` })} />
            : <input type="datetime-local" required className={field} value={form.startsAt} onChange={(e) => set({ startsAt: e.target.value })} />}</label>
        <label className={row}><span className="text-sm text-gray-500">{form.allDay ? '마지막 날' : '종료'}</span>
          {form.allDay
            ? <input type="date" required className={field} value={dateOnly(form.endsAt)} onChange={(e) => set({ endsAt: `${e.target.value}T00:00` })} />
            : <input type="datetime-local" required className={field} value={form.endsAt} onChange={(e) => set({ endsAt: e.target.value })} />}</label>
        <label className={row}><span className="text-sm text-gray-500">장소</span>
          <input maxLength={200} className={field} placeholder="예: 가온시청 별관 3층" value={form.location ?? ''}
            onChange={(e) => set({ location: text(e.target.value) })} /></label>
        <label className={row}><span className="text-sm text-gray-500">상대 기관</span>
          <input maxLength={200} required={NEEDS_COUNTERPART.includes(form.kind)} className={field}
            placeholder="예: 가온시 환경과, 한국환경공단" value={form.counterpartOrg ?? ''}
            onChange={(e) => set({ counterpartOrg: text(e.target.value) })} /></label>
        <label className={row}><span className="text-sm text-gray-500">목적</span>
          <input maxLength={500} className={field} placeholder="예: 소규모 환경영향평가 사전 협의" value={form.purpose ?? ''}
            onChange={(e) => set({ purpose: text(e.target.value) })} /></label>
        <label className={row}><span className="text-sm text-gray-500">분야</span>
          <select className={field} value={form.disciplineId ?? ''} onChange={(e) => set({ disciplineId: e.target.value ? Number(e.target.value) : null })}>
            <option value="">공통(미지정)</option>
            {disciplines.filter((d) => d.active || d.id === form.disciplineId).map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
          </select></label>
        <div className={`${row} items-start`}><span className="pt-1 text-sm text-gray-500">참석자</span>
          <div className="space-y-1.5">
            <div className="max-h-32 space-y-1 overflow-y-auto rounded-lg border border-[#E5E3DC] px-2.5 py-1.5">
              {members.map((m) => (
                <label key={m.userId} className="flex items-center gap-2 truncate text-sm">
                  <input type="checkbox" checked={attending.has(m.userId)} onChange={(e) => set({
                    attendees: e.target.checked ? [...form.attendees, { userId: m.userId, externalName: null, role: null }]
                      : form.attendees.filter((a) => a.userId !== m.userId),
                  })} />
                  <span className="truncate">{m.name}</span>
                </label>
              ))}
            </div>
            {form.attendees.filter((a) => !a.userId).map((a) => (
              <p key={a.externalName} className="flex items-center justify-between text-sm text-gray-700">
                <span className="truncate">{a.externalName} (외부)</span>
                <button type="button" className="text-xs text-gray-400 hover:text-[#991B1B]"
                  onClick={() => set({ attendees: form.attendees.filter((x) => x !== a) })}>빼기</button>
              </p>
            ))}
            <div className="flex gap-2">
              <input maxLength={100} className={field} placeholder="외부 참석자 이름" value={external} onChange={(e) => setExternal(e.target.value)} />
              <button type="button" className={btn} disabled={!external.trim()} onClick={() => {
                set({ attendees: [...form.attendees, { userId: null, externalName: external.trim(), role: null }] });
                setExternal('');
              }}>추가</button>
            </div>
          </div>
        </div>
        <label className={`${row} items-start`}><span className="pt-1 text-sm text-gray-500">체크리스트</span>
          <textarea rows={3} maxLength={4000} className={field} placeholder="한 줄에 하나 (지참 서류·준비물)" value={form.checklist ?? ''}
            onChange={(e) => set({ checklist: text(e.target.value) })} /></label>
        {!event && (
          <label className="flex items-center gap-2 text-sm text-gray-700">
            <input type="checkbox" checked={milestoneOn} onChange={(e) => setMilestone(e.target.checked)} />
            공정표에도 마일스톤 추가
          </label>
        )}
        <div className="flex justify-end pt-2">
          <button type="submit" disabled={busy}
            className="rounded-lg bg-[#1F4E79] px-4 py-2 text-sm font-semibold text-white hover:bg-[#173a5c] disabled:opacity-40">저장</button>
        </div>
      </form>
      {event && (
        <div className="border-t border-[#E5E3DC] px-6 py-4">
          <button type="button" disabled={busy} className={`${btn} hover:!border-[#991B1B] hover:!text-[#991B1B]`}
            onClick={() => window.confirm(`'${event.title}'을 삭제할까요?`) && run(() => deleteEvent(projectId, event.id))}>삭제</button>
        </div>
      )}
    </Drawer>
  );
}
