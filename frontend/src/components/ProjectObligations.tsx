import { useEffect, useState } from 'react';
import { sessionUserId } from '../api/client';
import { listProjectObligations, type Obligation } from '../api/revisions';
import { isOverdue, listProjectTasks, taskStatusLabel, type Task } from '../api/tasks';
import type { PermitItem } from '../types';
import ObligationTaskDrawer, { TASK_TONE } from './ObligationTaskDrawer';
import { Link } from 'react-router';
import { EmptyBlock, FilterChips, SkeletonRows, type ActiveFilter } from './ListParts';
import { Alert } from './ui/Alert';
import { Badge, dueBadge } from './ui/Badge';
import { Button } from './ui/Button';
import { Select } from './ui/Field';
import { SummaryStrip } from './ui/SummaryStrip';
import { Table } from './ui/Table';
import { cn, focusRing } from './ui/cn';

// ---------------------------------------------------------------------------
// ProjectObligations — 프로젝트 › 의무사항: approved obligations of every permit, nearest due date first.
// Each row shows the current cycle's task (operations-compliance F1); the row opens the task detail.
// 조치 업무 (opened by a measured value over the limit, operations-compliance/06) are rows of the same table.
// ---------------------------------------------------------------------------

const FREQ: Record<Obligation['frequency'], string> = {
  ONCE: '1회', MONTHLY: '매월', QUARTERLY: '분기', SEMIANNUAL: '반기', ANNUAL: '매년', OTHER: '기타',
};

const today = () => new Date().toLocaleDateString('sv-SE');
/** One table line: an obligation with its current cycle, or a 조치 업무. */
interface Row {
  key: string;
  obligation?: Obligation;
  corrective?: Task;
  title: string;
  permitItemId: number | null;
  /** the open task, if any */
  task: Task | null;
  last: Task | null;
  closed: boolean;
  status: string;
}

const linkBtn = cn('inline-flex h-12 items-center rounded px-4 text-[17px]', focusRing);

const dDay = (date: string) => Math.round((Date.parse(date) - Date.parse(today())) / 86_400_000);

export default function ProjectObligations({ projectId, permits, onOpenPermit }: {
  projectId: number; permits: PermitItem[]; onOpenPermit: (p: PermitItem) => void;
}) {
  const [items, setItems] = useState<Obligation[] | null>(null);
  const [showClosed, setShowClosed] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [correctives, setCorrectives] = useState<Task[]>([]);
  const [openKey, setOpenKey] = useState<string | null>(null);
  const [kind, setKind] = useState<'all' | 'obligation' | 'corrective'>('all');
  const [who, setWho] = useState<'all' | 'me' | 'none'>('all');
  const [state, setState] = useState<'all' | 'open' | 'overdue' | 'done'>('all');

  const load = () => {
    listProjectObligations(projectId).then(setItems, (e: Error) => setError(e.message));
    listProjectTasks(projectId).then((l) => setCorrectives(l.filter((t) => t.kind === 'CORRECTIVE')), () => {});
  };
  useEffect(() => {
    load();
  }, [projectId]);

  /** 열림 / 기한 경과 / 완료 / 지연 완료 of the current cycle; plain text, only overdue is coloured. */
  const status = (o: Obligation) =>
    o.openTask ? taskStatusLabel(o.openTask)
      : o.status === 'ACTIVE' ? '기한 미정'
        : o.lastTask ? taskStatusLabel(o.lastTask) : '종료';

  if (!items && error) return <Alert tone="danger" title="의무사항을 불러오지 못했습니다.">{error}</Alert>;
  if (!items) return <Table caption="의무사항 불러오는 중"><SkeletonRows cols={6} /></Table>;

  const permitById = new Map(permits.map((p) => [p.id, p]));
  const me = sessionUserId();
  const all: Row[] = [
    ...correctives.map((t): Row => ({
      key: `c${t.id}`, corrective: t, title: t.title, permitItemId: t.permitItemId, task: t.status === 'OPEN' ? t : null,
      last: t.status === 'OPEN' ? null : t, closed: t.status !== 'OPEN', status: taskStatusLabel(t),
    })),
    ...items.map((o): Row => ({
      key: `o${o.id}`, obligation: o, title: o.summary, permitItemId: o.permitItemId, task: o.openTask ?? null,
      last: o.lastTask ?? null, closed: o.status !== 'ACTIVE', status: status(o),
    })),
  ].sort((a, b) => (a.task?.dueDate ?? '9999').localeCompare(b.task?.dueDate ?? '9999')); // stable: undated keep their order
  const rows = all.filter((r) => {
    const t = r.task;
    if (kind !== 'all' && (kind === 'corrective') !== !!r.corrective) return false;
    if (who === 'me' && (t ?? r.last)?.assigneeId !== me) return false;
    if (who === 'none' && !(t && t.assigneeId === null)) return false;
    if (state === 'open') return !!t && !isOverdue(t);
    if (state === 'overdue') return !!t && isOverdue(t);
    if (state === 'done') return !t && r.last?.status === 'DONE'; // finished for good; earlier cycles are in the row's detail
    return showClosed || !r.closed;
  });
  const opened = all.find((r) => r.key === openKey);
  const closedCount = all.filter((r) => r.closed).length;
  const openTasks = all.flatMap((r) => (r.task ? [r.task] : []));
  const overdueCount = openTasks.filter(isOverdue).length;
  const filters: ActiveFilter[] = [
    ...(kind !== 'all' ? [{ key: 'kind', label: `종류: ${kind === 'obligation' ? '의무' : '조치'}`, clear: () => setKind('all') }] : []),
    ...(who !== 'all' ? [{ key: 'who', label: `담당: ${who === 'me' ? '나' : '미지정'}`, clear: () => setWho('all') }] : []),
    ...(state !== 'all' ? [{ key: 'state', label: `상태: ${{ open: '열림', overdue: '기한 경과', done: '완료' }[state]}`, clear: () => setState('all') }] : []),
  ];
  const clearAll = () => { setKind('all'); setWho('all'); setState('all'); };

  return (
    <div>
      <p className="break-keep text-base text-body">AI 검토에서 승인된 의무사항입니다. 행을 누르면 이번 회차 업무에 증빙을 붙이고 완료할 수 있습니다.</p>
      {all.length > 0 && (
        <SummaryStrip className="mt-6" items={[
          { label: '의무사항', value: all.filter((r) => r.obligation).length, unit: '건' },
          { label: '조치 업무', value: all.filter((r) => r.corrective).length, unit: '건' },
          { label: '열린 업무', value: openTasks.length, unit: '건', tone: 'info' },
          { label: '기한 경과', value: overdueCount, unit: '건', tone: overdueCount ? 'danger' : undefined, alert: overdueCount > 0 },
        ]} />
      )}

      {/* 필터 줄: 종류·담당·상태 + 종료 항목 보기 */}
      <div className="mt-6 flex flex-wrap items-end gap-3">
        <Select label="종류" className="w-full sm:w-40" value={kind} onChange={(e) => setKind(e.target.value as typeof kind)}>
          <option value="all">전체</option><option value="obligation">의무</option><option value="corrective">조치</option>
        </Select>
        <Select label="담당" className="w-full sm:w-40" value={who} onChange={(e) => setWho(e.target.value as typeof who)}>
          <option value="all">전체</option><option value="me">나</option><option value="none">미지정</option>
        </Select>
        <Select label="상태" className="w-full sm:w-40" value={state} onChange={(e) => setState(e.target.value as typeof state)}>
          <option value="all">전체</option><option value="open">열림</option><option value="overdue">기한 경과</option><option value="done">완료</option>
        </Select>
        {closedCount > 0 && state === 'all' && (
          <label className="flex h-12 items-center gap-2 text-[15px] text-ink">
            <input type="checkbox" checked={showClosed} onChange={(e) => setShowClosed(e.target.checked)} className={cn('size-5 accent-brand', focusRing)} />
            종료·완료된 항목 보기 (<span className="tabular-nums">{closedCount}</span>)
          </label>
        )}
      </div>
      <FilterChips filters={filters} onClearAll={clearAll} />
      {error && <Alert tone="danger" className="mt-4">{error}</Alert>}

      <div className="mt-6">
        {all.length === 0 ? (
          <EmptyBlock
            title="등록된 의무사항이 없습니다."
            description="인허가 상세에서 허가증을 올리고 AI 검토에서 조건을 승인하면, 지켜야 할 정기 할 일이 담당자·기한과 함께 여기에 모입니다."
            action={(
              <div className="flex flex-wrap justify-center gap-2">
                <Link to={`/projects/${projectId}/review`} className={cn(linkBtn, 'border border-input bg-white text-ink hover:bg-paper')}>AI 검토 열기</Link>
                <Link to={`/projects/${projectId}/permits`} className={cn(linkBtn, 'bg-brand font-bold text-white hover:bg-brand-hover')}>허가증 올리러 가기</Link>
              </div>
            )}
          />
        ) : rows.length === 0 ? (
          <EmptyBlock title="조건에 맞는 항목이 없습니다." action={<Button variant="tertiary" onClick={clearAll}>필터 모두 지우기</Button>} />
        ) : (
          <>
            <p className="mb-2 text-[15px] text-body" aria-live="polite"><span className="tabular-nums font-bold text-ink">{rows.length}</span>건</p>
            <Table density="compact" caption="의무사항·조치 업무 목록" className="min-w-[960px] whitespace-nowrap">
              <thead>
                <tr>
                  <th scope="col">의무사항 · 조치</th>
                  <th scope="col">상태</th>
                  <th scope="col">종류</th>
                  <th scope="col">인허가</th>
                  <th scope="col">주기</th>
                  <th scope="col" className="text-right">기한</th>
                  <th scope="col">남은 날</th>
                  <th scope="col">담당</th>
                  <th scope="col" className="text-right">증빙</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => {
                  const permit = r.permitItemId ? permitById.get(r.permitItemId) : undefined;
                  const t = r.task;
                  const due = t ? dueBadge(dDay(t.dueDate)) : null;
                  return (
                    <tr key={r.key} onClick={() => setOpenKey(r.key)} className={cn('cursor-pointer', openKey === r.key && 'bg-brand-subtle')}>
                      <td className="max-w-[320px]">
                        <button type="button" title={r.title}
                          className={cn('block max-w-full truncate rounded-sm text-left font-bold text-brand-text hover:underline', focusRing)}>{r.title}</button>
                      </td>
                      <td><Badge tone={TASK_TONE[r.status] ?? 'neutral'}>{r.status}</Badge></td>
                      <td>{r.corrective ? <Badge tone="warning">조치</Badge> : <span className="text-body">의무</span>}</td>
                      <td className="max-w-[220px]">
                        {permit ? (
                          <button type="button" onClick={(e) => { e.stopPropagation(); onOpenPermit(permit); }} title={permit.name}
                            className={cn('block max-w-full truncate rounded-sm text-left text-body hover:text-brand-text hover:underline', focusRing)}>{permit.name}</button>
                        ) : <span className="text-mute">—</span>}
                      </td>
                      <td className="text-body">{r.obligation ? FREQ[r.obligation.frequency] : <span className="text-mute">—</span>}</td>
                      <td className="text-right tabular-nums text-body">{t?.dueDate ?? <span className="text-mute">—</span>}</td>
                      <td>{due ? <Badge tone={due.tone} className="tabular-nums">{due.text}</Badge> : <span className="text-mute">—</span>}</td>
                      <td className="max-w-[120px] truncate text-body" title={t?.assigneeName ?? ''}>{t ? t.assigneeName ?? '미지정' : <span className="text-mute">—</span>}</td>
                      <td className="text-right tabular-nums text-body">{t ? `${t.evidenceCount + t.measurementCount}건` : <span className="text-mute">—</span>}</td>
                    </tr>
                  );
                })}
              </tbody>
            </Table>
          </>
        )}
      </div>
      {opened && (
        <ObligationTaskDrawer key={opened.key} projectId={projectId} obligation={opened.obligation} corrective={opened.corrective}
          permit={opened.permitItemId ? permitById.get(opened.permitItemId) : undefined}
          onClose={() => setOpenKey(null)} onChanged={load} />
      )}
    </div>
  );
}
