import { useEffect, useRef, useState } from 'react';
import type { Dependency, Discipline, ScheduleItem } from '../api/schedule';
import { Button } from './ui/Button';
import { Table } from './ui/Table';
import { cn, focusRing } from './ui/cn';

// ---------------------------------------------------------------------------
// ScheduleTable — 일정 › 표: items grouped by 분야, dates fixed in place. A date or length cell turns into an input
// (click or Enter); Enter / leaving the cell saves, Esc puts it back. With 뒤 일정 같이 밀기 on, an end moved later
// first shows a yellow line naming the FS successors that move the same days; the server moves them in one request.
// ---------------------------------------------------------------------------

type Field = 'start' | 'end' | 'dur';
interface Dates { startDate: string; endDate: string }

const DAY = 86_400_000;
const utc = (iso: string) => Date.parse(`${iso}T00:00:00Z`);
const iso = (ms: number) => new Date(ms).toISOString().slice(0, 10);
/** 2026-11-19 → 26.11.19, the way it is typed */
const short = (d: string) => d.slice(2).replaceAll('-', '.');

/** 26.11.19 · 2026-11-19 · 2026.1.5 · 26/3/7 → 2026-11-19; null when it is not a real day. */
export function parseDateText(text: string): string | null {
  const m = text.trim().match(/^(\d{2}|\d{4})[.\-/](\d{1,2})[.\-/](\d{1,2})\.?$/);
  if (!m) return null;
  const out = `${m[1].length === 2 ? `20${m[1]}` : m[1]}-${m[2].padStart(2, '0')}-${m[3].padStart(2, '0')}`;
  const ms = utc(out);
  return !Number.isNaN(ms) && iso(ms) === out ? out : null; // 02.30 rolls over or fails: either way refused
}

/** Both ends count, as on the server: a one-day task is 1. */
export const durationOf = (start: string, end: string) => Math.round((utc(end) - utc(start)) / DAY) + 1;
export const endFromDuration = (start: string, days: number) => iso(utc(start) + (days - 1) * DAY);

type Link = Pick<ScheduleItem, 'id' | 'title' | 'endDate' | 'actualEnd'>;
/** Mirrors the server's [뒤 일정 같이 밀기]: open FS successors down the chain, only when the end gets later. */
export function pushedSuccessors<T extends Link>(items: T[], deps: Dependency[], id: number, newEnd: string): T[] {
  const moved = items.find((i) => i.id === id);
  if (!moved?.endDate || newEnd <= moved.endDate) return [];
  const byId = new Map(items.map((i) => [i.id, i]));
  const seen = new Set([id]);
  const out: T[] = [];
  for (const next = [id]; next.length;) {
    const p = next.shift()!;
    for (const d of deps) {
      if (d.predecessorId !== p || seen.has(d.successorId)) continue;
      seen.add(d.successorId);
      const s = byId.get(d.successorId);
      if (!s || s.actualEnd || !s.endDate) continue; // a completed item stops the chain
      out.push(s);
      next.push(s.id);
    }
  }
  return out;
}

const COL: Record<Field, string> = { start: '시작일', end: '종료일', dur: '기간' };

export default function ScheduleTable({ items, all, dependencies, disciplines, canEdit, shift, onSave, onOpen }: {
  items: ScheduleItem[]; // filtered rows
  all: ScheduleItem[]; // every item, for the successor chain
  dependencies: Dependency[];
  disciplines: Discipline[];
  canEdit: boolean;
  shift: boolean;
  onSave: (id: number, dates: Dates, shift: boolean) => Promise<unknown>;
  onOpen: (id: number) => void;
}) {
  const [edit, setEdit] = useState<{ id: number; field: Field } | null>(null);
  const [pending, setPending] = useState<{ item: ScheduleItem; dates: Dates; days: number; pushed: ScheduleItem[] } | null>(null);
  const [error, setError] = useState<{ id: number; text: string } | null>(null);
  const back = useRef<string | null>(null); // the cell that gets focus back when the edit closes
  const host = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (edit || pending || !back.current) return;
    host.current?.querySelector<HTMLElement>(`[data-cell="${back.current}"]`)?.focus();
    back.current = null;
  }, [edit, pending]);

  const open = (id: number, field: Field) => { setError(null); back.current = `${id}:${field}`; setEdit({ id, field }); };
  /** false = the text is wrong; the message shows under the row. */
  const commit = (item: ScheduleItem, field: Field, text: string): boolean => {
    const start = item.startDate!, end = item.endDate!;
    let dates: Dates;
    if (field === 'dur') {
      const n = Number(text.trim());
      if (!Number.isInteger(n) || n < 1) { setError({ id: item.id, text: '기간은 1 이상의 정수로 입력하세요.' }); return false; }
      dates = { startDate: start, endDate: endFromDuration(start, n) };
    } else {
      const d = parseDateText(text);
      if (!d) { setError({ id: item.id, text: '날짜를 26.11.19 또는 2026-11-19처럼 입력하세요.' }); return false; }
      dates = item.kind === 'MILESTONE' ? { startDate: d, endDate: d } : field === 'start' ? { startDate: d, endDate: end } : { startDate: start, endDate: d };
      if (dates.startDate > dates.endDate) { setError({ id: item.id, text: '시작일이 종료일보다 늦을 수 없습니다.' }); return false; }
    }
    setError(null);
    setEdit(null);
    if (dates.startDate === start && dates.endDate === end) return true;
    const pushed = shift ? pushedSuccessors(all, dependencies, item.id, dates.endDate) : [];
    if (pushed.length) setPending({ item, dates, days: durationOf(end, dates.endDate) - 1, pushed });
    else void onSave(item.id, dates, false);
    return true;
  };
  const confirm = () => { if (pending) void onSave(pending.item.id, pending.dates, true); setPending(null); };

  // 분야 order from settings; 공통 first
  const rank = new Map(disciplines.map((d) => [d.id, d.sortOrder]));
  const byKey = new Map<number, ScheduleItem[]>();
  for (const i of items) byKey.set(i.disciplineId ?? 0, [...(byKey.get(i.disciplineId ?? 0) ?? []), i]);
  const groups = [...byKey]
    .sort(([a], [b]) => (a === 0 ? -Infinity : rank.get(a) ?? 1e9) - (b === 0 ? -Infinity : rank.get(b) ?? 1e9));

  const dateCell = (item: ScheduleItem, field: Field, shown: string | null) => {
    if (shown == null) return <span className="text-body">미정</span>;
    if (edit?.id === item.id && edit.field === field) {
      return <Editor field={field} title={item.title} initial={field === 'dur' ? shown : short(shown)}
        onDone={(text) => commit(item, field, text)} onCancel={() => { setError(null); setEdit(null); }} onClose={() => setEdit(null)} />;
    }
    const text = field === 'dur' ? shown : short(shown);
    if (!canEdit) return <span>{text}</span>;
    return (
      <button type="button" data-cell={`${item.id}:${field}`} aria-label={`${COL[field]} 고치기: ${item.title}`}
        onClick={() => open(item.id, field)}
        className={cn('-mx-2 min-h-10 w-[calc(100%+1rem)] cursor-text rounded-sm px-2 text-right tabular-nums hover:bg-brand-subtle', focusRing)}>
        {text}
      </button>
    );
  };

  return (
    <div ref={host}>
      <Table caption="일정 표: 날짜를 바로 고칠 수 있습니다" className="min-w-[760px]">
        <thead>
          <tr>
            <th scope="col" className="min-w-60">항목</th>
            <th scope="col" className="w-32">담당</th>
            <th scope="col" className="w-36 text-right">시작일</th>
            <th scope="col" className="w-36 text-right">종료일</th>
            <th scope="col" className="w-28 text-right">기간(일)</th>
            <th scope="col" className="w-28 text-right">기준 대비</th>
          </tr>
        </thead>
        <tbody>
          {groups.map(([key, rows]) => (
            <Group key={key} name={rows[0].disciplineName ?? '공통(미지정)'} count={rows.length}>
              {rows.map((i) => {
                const milestone = i.kind === 'MILESTONE';
                const hot = edit?.id === i.id || pending?.item.id === i.id;
                return [
                  <tr key={i.id} className={cn(hot && 'bg-brand-subtle!')}>
                    <td className={cn(i.parentId != null && 'pl-10')}>
                      <button type="button" onClick={() => onOpen(i.id)} className={cn('rounded-sm text-left hover:underline', focusRing)}>
                        {milestone && <span aria-hidden className="mr-1 text-brand">◆</span>}{i.title}
                      </button>
                    </td>
                    <td className="truncate text-body">{i.ownerName ?? '—'}</td>
                    <td className="text-right tabular-nums">{dateCell(i, 'start', milestone ? i.endDate : i.startDate)}</td>
                    <td className="text-right tabular-nums">{milestone ? <span className="text-body">마일스톤</span> : dateCell(i, 'end', i.endDate)}</td>
                    <td className="text-right tabular-nums">
                      {milestone ? <span className="text-body">—</span>
                        : dateCell(i, 'dur', i.startDate && i.endDate ? String(durationOf(i.startDate, i.endDate)) : null)}
                    </td>
                    <td className={cn('text-right tabular-nums', (i.slipDays ?? 0) > 0 && 'font-bold text-danger')}>
                      {i.slipDays == null ? <span className="text-body">—</span> : i.slipDays > 0 ? `+${i.slipDays}일` : `${i.slipDays}일`}
                    </td>
                  </tr>,
                  error?.id === i.id && (
                    <tr key={`${i.id}:error`}><td colSpan={6} role="alert" className="py-2 text-danger">{error.text}</td></tr>
                  ),
                  pending?.item.id === i.id && (
                    <tr key={`${i.id}:pending`} className="bg-warn-bg!">
                      <td colSpan={6} className="py-2">
                        <PushNote days={pending.days} milestone={milestone} pushed={pending.pushed} onSave={confirm} onCancel={() => setPending(null)} />
                      </td>
                    </tr>
                  ),
                ];
              })}
            </Group>
          ))}
        </tbody>
      </Table>
    </div>
  );
}

function Group({ name, count, children }: { name: string; count: number; children: React.ReactNode }) {
  return (
    <>
      <tr><th scope="colgroup" colSpan={6} className="text-[15px]">{name} <span className="font-normal text-body">· {count}건</span></th></tr>
      {children}
    </>
  );
}

/** Text input (26.11.19) with a native picker beside it; focus moving between the two is not "leaving the cell". */
function Editor({ field, title, initial, onDone, onCancel, onClose }: {
  field: Field; title: string; initial: string; onDone: (text: string) => boolean; onCancel: () => void; onClose: () => void;
}) {
  const [text, setText] = useState(initial);
  const closed = useRef(false);
  const finish = (fromBlur: boolean) => {
    if (closed.current) return;
    if (onDone(text)) closed.current = true;
    else if (fromBlur) { closed.current = true; onClose(); } // wrong text and gone: the message stays, the date does not change
  };
  const picked = field === 'dur' ? null : parseDateText(text);
  return (
    <div className="inline-flex items-center justify-end gap-1"
      onBlur={(e) => { if (!e.currentTarget.contains(e.relatedTarget as Node | null)) finish(true); }}
      onKeyDown={(e) => {
        if (e.key === 'Enter') { e.preventDefault(); finish(false); }
        if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); closed.current = true; onCancel(); }
      }}>
      <input type="text" autoFocus aria-label={`${COL[field]} 입력: ${title}`} value={text} onFocus={(e) => e.target.select()}
        onChange={(e) => setText(e.target.value)} inputMode={field === 'dur' ? 'numeric' : undefined}
        className={cn('h-10 rounded border border-brand bg-white px-2 text-right text-[15px] tabular-nums', field === 'dur' ? 'w-16' : 'w-24', focusRing)} />
      {field !== 'dur' && (
        <input type="date" aria-label={`달력에서 고르기: ${title}`} value={picked ?? ''}
          onChange={(e) => e.target.value && setText(short(e.target.value))}
          className={cn('h-10 w-10 rounded border border-input bg-white px-1 text-[15px] text-transparent', focusRing)} />
      )}
    </div>
  );
}

function PushNote({ days, milestone, pushed, onSave, onCancel }: {
  days: number; milestone: boolean; pushed: ScheduleItem[]; onSave: () => void; onCancel: () => void;
}) {
  const names = pushed.slice(0, 3).map((p) => p.title).join(', ') + (pushed.length > 3 ? ' …' : '');
  return (
    <div role="status" className="flex flex-wrap items-center gap-x-3 gap-y-2"
      onKeyDown={(e) => {
        if (e.key === 'Enter') { e.preventDefault(); onSave(); }
        if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); onCancel(); }
      }}>
      <span className="break-keep text-ink">
        {milestone ? '날짜가' : '종료일이'} {days}일 늦어집니다. 뒤 일정 {pushed.length}건({names})도 {days}일씩 밀립니다.
      </span>
      <span className="text-body">Enter 저장 · Esc 취소</span>
      <span className="flex gap-2">
        <Button size="sm" autoFocus onClick={onSave}>저장</Button>
        <Button size="sm" variant="tertiary" onClick={onCancel}>취소</Button>
      </span>
    </div>
  );
}
