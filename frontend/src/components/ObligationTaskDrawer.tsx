import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import { apiBlob } from '../api/client';
import { completeObligation, type Obligation } from '../api/revisions';
import {
  addEvidence, completeTask, documentPath, getTask, isOverdue, listObligationTasks, listPermitDocuments, listProjectMembers,
  removeEvidence, setDefaultAssignee, taskStatusLabel, updateTask, uploadPermitDocument,
  type ProjectMember, type Task, type TaskDetail, type TaskEvent,
} from '../api/tasks';
import type { PermitDocument, PermitItem } from '../types';
import { LEVEL, levelClass } from './PointMeasurements';

// ---------------------------------------------------------------------------
// ObligationTaskDrawer — 업무 상세 of one obligation (operations-compliance/01): the current cycle's evidence
// and [완료], plus past cycles. Evidence is always a document of the obligation's own permit item; a 측정·조사
// task can also be fulfilled by measured values entered in the 운영 tab (operations-compliance/04).
// ---------------------------------------------------------------------------

const ACCEPT = '.pdf,.jpg,.jpeg,.png,.xlsx';
const NEEDS_EVIDENCE = '증빙 문서나 측정값이 1건 이상 있어야 완료할 수 있습니다.';
const CANCELLED: Record<string, string> = {
  OBLIGATION_CLOSED: '의무사항 종료', REVISION_REMOVED: '허가증 개정으로 삭제', MANUAL: '수동 취소',
};
const day = (iso: string) => new Date(iso).toLocaleDateString('sv-SE');

function eventText(e: TaskEvent, name: (id: unknown) => string): string {
  const d = e.data ?? {};
  switch (e.type) {
    case 'CREATED': return d.source === 'COMPLETE_NEXT' ? '다음 회차 업무 생성' : d.source === 'MIGRATION' ? '업무 생성 (기존 의무사항 이관)' : '업무 생성';
    case 'DUE_CHANGED': return `기한 변경 ${d.from} → ${d.to}`;
    case 'ASSIGNED': return `담당 변경 ${name(d.from)} → ${name(d.to)}`;
    case 'EVIDENCE_ADDED': return '증빙 연결';
    case 'EVIDENCE_REMOVED': return '증빙 해제';
    case 'COMPLETED': return d.late ? '지연 완료' : '완료';
    case 'CANCELLED': return `취소 (${CANCELLED[String(d.reason)] ?? d.reason})`;
  }
}

export default function ObligationTaskDrawer({ projectId, obligation, permit, onClose, onChanged }: {
  projectId: number; obligation: Obligation; permit: PermitItem | undefined; onClose: () => void; onChanged: () => void;
}) {
  const [cycles, setCycles] = useState<Task[]>([]);
  const [detail, setDetail] = useState<TaskDetail | null>(null);
  const [docs, setDocs] = useState<PermitDocument[]>([]);
  const [pick, setPick] = useState('');
  const [note, setNote] = useState('');
  const [nextDue, setNextDue] = useState('');
  const [members, setMembers] = useState<ProjectMember[]>([]);
  const [due, setDue] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const file = useRef<HTMLInputElement>(null);

  /** Reloads the cycles and shows `taskId`, or the open cycle, or the latest one. */
  const load = async (taskId?: number) => {
    const list = await listObligationTasks(projectId, obligation.id);
    setCycles(list);
    const show = list.find((t) => t.id === taskId) ?? list.find((t) => t.status === 'OPEN') ?? list[list.length - 1];
    setDetail(show ? await getTask(show.id) : null);
    setDue(show?.dueDate ?? '');
  };
  useEffect(() => {
    load().catch((e: Error) => setError(e.message));
    listPermitDocuments(projectId, obligation.permitItemId).then(setDocs, () => {});
    listProjectMembers(projectId).then(setMembers, () => {});
  }, [projectId, obligation.id]);

  const run = async (fn: () => Promise<unknown>, changed = true) => {
    setBusy(true);
    setError(null);
    try {
      await fn();
      if (changed) onChanged();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const task = detail?.task;
  const open = task?.status === 'OPEN';
  const attached = new Set(detail?.evidence.map((e) => e.documentId));
  const choices = docs.filter((d) => !attached.has(d.id));
  const proof = (detail?.evidence.length ?? 0) + (detail?.measurements.length ?? 0);

  const upload = (f: File) => run(async () => {
    const doc = await uploadPermitDocument(projectId, obligation.permitItemId, f);
    setDocs((d) => [doc, ...d]);
    await addEvidence(task!.id, doc.id);
    await load(task!.id);
  });
  const download = (documentId: number, fileName: string) => run(async () => {
    const url = URL.createObjectURL(await apiBlob(documentPath(projectId, obligation.permitItemId, documentId)));
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
    a.click();
    URL.revokeObjectURL(url);
  }, false);
  const complete = () => run(async () => {
    await completeTask(task!.id, { note: note || undefined, nextDueDate: nextDue || undefined, version: task!.version });
    setNote('');
    setNextDue('');
    await load(task!.id); // stay on the finished cycle; the next one shows up in the list below
  });

  const memberName = (id: unknown) => (id == null ? '미지정' : members.find((m) => m.userId === id)?.name ?? '멤버 아님');
  const change = (body: { assigneeId?: number | null; dueDate?: string }) => run(async () => {
    await updateTask(task!.id, { ...body, version: task!.version });
    await load(task!.id);
  });
  const field = 'rounded-lg border border-[#E5E3DC] px-2 py-1 text-sm';
  const memberOptions = members.map((m) => <option key={m.userId} value={m.userId}>{m.name}</option>);

  const row = 'flex justify-between gap-4 py-1 text-sm';
  const button = 'rounded-lg border border-[#E5E3DC] px-3 py-1.5 text-xs font-medium text-gray-700 hover:border-[#1F4E79] hover:text-[#1F4E79] disabled:opacity-40';

  return (
    <div className="fixed inset-0 z-50">
      <div onClick={onClose} className="absolute inset-0 bg-black/40" />
      <aside role="dialog" aria-modal="true" aria-label="업무 상세"
        className="absolute right-0 top-0 flex h-full w-full max-w-[520px] flex-col bg-white shadow-xl">
        <div className="flex items-start justify-between gap-4 border-b border-[#E5E3DC] px-6 py-4">
          <div className="min-w-0">
            <p className="truncate text-xs text-gray-500" title={permit?.name}>{permit?.name ?? '인허가'}</p>
            <h2 className="mt-1 text-lg font-semibold text-gray-900">{obligation.summary}</h2>
          </div>
          <button type="button" onClick={onClose} aria-label="닫기" className="text-xl leading-none text-gray-400 hover:text-gray-700">×</button>
        </div>

        <div className="flex-1 space-y-6 overflow-y-auto px-6 py-5">
          {error && <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-[#991B1B]">{error}</p>}

          {!task && obligation.status === 'ACTIVE' && (
            <section>
              <h3 className="text-sm font-semibold text-gray-800">기한 미정</h3>
              <p className="mt-1 text-sm text-gray-500">기한을 넣으면 첫 회차 업무가 생깁니다.</p>
              <div className="mt-2 flex items-center gap-2">
                <input type="date" value={nextDue} onChange={(e) => setNextDue(e.target.value)} aria-label="첫 기한"
                  className="rounded-lg border border-[#E5E3DC] px-2 py-1.5 text-sm" />
                <button type="button" disabled={busy || !nextDue} className={button}
                  onClick={() => run(async () => { await completeObligation(obligation.id, nextDue); setNextDue(''); await load(); })}>
                  기한 입력
                </button>
              </div>
            </section>
          )}
          {!task && obligation.status === 'CLOSED' && <p className="text-sm text-gray-500">종료된 의무사항이며 업무 이력이 없습니다.</p>}

          {detail && task && (
            <>
              <section>
                <h3 className="text-sm font-semibold text-gray-800">{open ? '현재 회차' : `${task.dueDate} 회차`}</h3>
                <dl className="mt-2 divide-y divide-[#F0EEE7]">
                  <div className={`${row} items-center`}>
                    <dt className="text-gray-500">기한</dt>
                    <dd className="flex items-center gap-2">
                      {open ? (
                        <>
                          <input type="date" value={due} onChange={(e) => setDue(e.target.value)} aria-label="기한" className={field} />
                          {due && due !== task.dueDate
                            && <button type="button" disabled={busy} className={button} onClick={() => change({ dueDate: due })}>기한 변경</button>}
                        </>
                      ) : task.dueDate}
                    </dd>
                  </div>
                  <div className={`${row} items-center`}>
                    <dt className="text-gray-500">담당</dt>
                    <dd>
                      {open ? (
                        <select value={task.assigneeId ?? ''} disabled={busy} aria-label="담당" className={field}
                          onChange={(e) => change({ assigneeId: e.target.value ? Number(e.target.value) : null })}>
                          <option value="">미지정 (프로젝트 멤버 전체)</option>
                          {memberOptions}
                        </select>
                      ) : task.assigneeName ?? '미지정'}
                    </dd>
                  </div>
                  {open && (
                    <div className={`${row} items-center`}>
                      <dt className="text-gray-500" title="다음 회차부터 새 업무의 담당자. 비우면 프로젝트 EM, 없으면 PM">기본 담당 (다음 회차부터)</dt>
                      <dd>
                        <select value={obligation.defaultAssigneeId ?? ''} disabled={busy} aria-label="기본 담당" className={field}
                          onChange={(e) => run(() => setDefaultAssignee(obligation.id, e.target.value ? Number(e.target.value) : null))}>
                          <option value="">프로젝트 EM → PM</option>
                          {memberOptions}
                        </select>
                      </dd>
                    </div>
                  )}
                  <div className={row}>
                    <dt className="text-gray-500">상태</dt>
                    <dd className={isOverdue(task) ? 'font-semibold text-[#991B1B]' : ''}>
                      {taskStatusLabel(task)}{task.cancelledReason ? ` · ${CANCELLED[task.cancelledReason]}` : ''}
                    </dd>
                  </div>
                  {task.doneAt && <div className={row}><dt className="text-gray-500">완료</dt><dd>{day(task.doneAt)} · {task.doneByName ?? '—'}</dd></div>}
                  {task.note && <div className={row}><dt className="shrink-0 text-gray-500">메모</dt><dd className="whitespace-pre-wrap text-right">{task.note}</dd></div>}
                </dl>
              </section>

              <section>
                <h3 className="text-sm font-semibold text-gray-800">증빙 문서 ({detail.evidence.length})</h3>
                {detail.evidence.length === 0 && <p className="mt-2 text-sm text-gray-400">연결된 증빙이 없습니다.</p>}
                <ul className="mt-2 divide-y divide-[#F0EEE7]">
                  {detail.evidence.map((e) => (
                    <li key={e.documentId} className="flex items-center gap-3 py-1.5 text-sm">
                      <button type="button" onClick={() => download(e.documentId, e.fileName)} title={`${e.fileName} 내려받기`}
                        className="min-w-0 flex-1 truncate text-left text-[#1F4E79] hover:underline">{e.fileName}</button>
                      <span className="shrink-0 text-xs text-gray-500">{e.addedByName ?? '—'} · {day(e.addedAt)}</span>
                      {open && (
                        <button type="button" disabled={busy} className="shrink-0 text-xs text-gray-500 hover:text-[#991B1B]"
                          onClick={() => run(async () => { await removeEvidence(task.id, e.documentId); await load(task.id); })}>해제</button>
                      )}
                    </li>
                  ))}
                </ul>
                {open && (
                  <div className="mt-3 flex flex-wrap items-center gap-2">
                    <button type="button" disabled={busy} onClick={() => file.current?.click()} className={button}>파일 올리기</button>
                    <input ref={file} type="file" accept={ACCEPT} className="hidden"
                      onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ''; if (f) upload(f); }} />
                    {choices.length > 0 && (
                      <>
                        <select value={pick} onChange={(e) => setPick(e.target.value)} aria-label="이 인허가의 문서에서 선택"
                          className="min-w-0 max-w-[220px] flex-1 truncate rounded-lg border border-[#E5E3DC] px-2 py-1.5 text-xs">
                          <option value="">이 인허가의 문서에서 선택</option>
                          {choices.map((d) => <option key={d.id} value={d.id}>{d.fileName}</option>)}
                        </select>
                        <button type="button" disabled={busy || !pick} className={button}
                          onClick={() => run(async () => { await addEvidence(task.id, Number(pick)); setPick(''); await load(task.id); })}>연결</button>
                      </>
                    )}
                    <p className="w-full text-xs text-gray-400">올린 파일은 이 인허가 항목의 문서로 저장됩니다.</p>
                  </div>
                )}
              </section>

              {(detail.measurements.length > 0 || (open && obligation.type === 'MEASURE_SURVEY')) && (
                <section>
                  <h3 className="text-sm font-semibold text-gray-800">측정값 ({detail.measurements.length})</h3>
                  <ul className="mt-2 divide-y divide-[#F0EEE7]">
                    {detail.measurements.map((m) => (
                      <li key={m.id} className="flex items-center gap-3 py-1.5 text-sm">
                        <span className="shrink-0 text-gray-600">{m.measuredOn}</span>
                        <span className="min-w-0 flex-1 truncate" title={`${m.pointName} · ${m.pollutantName}`}>{m.pointName} · {m.pollutantName}</span>
                        <span className="shrink-0 tabular-nums">{m.value} {m.unit}{m.belowDetection ? ' (불검출)' : ''}</span>
                        <span className={`shrink-0 ${levelClass(m.level)}`}>{LEVEL[m.level]}</span>
                      </li>
                    ))}
                  </ul>
                  {open && (
                    <div className="mt-3 flex flex-wrap items-center gap-2">
                      <Link to={`/projects/${projectId}/operations?task=${task.id}`} className={button}>측정값 입력</Link>
                      <p className="w-full text-xs text-gray-400">운영 탭에서 넣은 측정값이 이 업무에 연결되고, 증빙 문서 없이도 완료할 수 있습니다.</p>
                    </div>
                  )}
                </section>
              )}

              {open && (
                <section>
                  <h3 className="text-sm font-semibold text-gray-800">완료</h3>
                  <textarea value={note} onChange={(e) => setNote(e.target.value)} rows={2} placeholder="완료 메모 (선택)"
                    className="mt-2 w-full rounded-lg border border-[#E5E3DC] px-3 py-2 text-sm" />
                  {obligation.frequency !== 'ONCE' && (
                    <label className="mt-2 flex items-center gap-2 text-sm text-gray-600">
                      다음 기한
                      <input type="date" value={nextDue} onChange={(e) => setNextDue(e.target.value)}
                        className="rounded-lg border border-[#E5E3DC] px-2 py-1 text-sm" />
                      <span className="text-xs text-gray-400">{obligation.frequency === 'OTHER' ? '필수' : '비우면 주기대로 계산'}</span>
                    </label>
                  )}
                  <div className="mt-3 flex items-center gap-3">
                    <button type="button" onClick={complete}
                      disabled={busy || proof === 0 || (obligation.frequency === 'OTHER' && !nextDue)}
                      className="rounded-lg bg-[#1F4E79] px-4 py-2 text-sm font-medium text-white hover:bg-[#173B5C] disabled:opacity-40">완료</button>
                    {proof === 0 && <span className="text-xs text-gray-500">{NEEDS_EVIDENCE}</span>}
                    {proof > 0 && task.dueDate < new Date().toLocaleDateString('sv-SE')
                      && <span className="text-xs text-[#991B1B]">기한이 지나 "지연 완료"로 기록됩니다.</span>}
                  </div>
                </section>
              )}

              <section>
                <h3 className="text-sm font-semibold text-gray-800">회차 이력 ({cycles.length})</h3>
                <table className="mt-2 w-full table-fixed whitespace-nowrap text-left text-sm">
                  <thead className="text-xs text-gray-500">
                    <tr><th className="w-24 py-1 font-medium">기한</th><th className="w-20 py-1 font-medium">상태</th>
                      <th className="w-24 py-1 font-medium">완료일</th><th className="w-12 py-1 font-medium">증빙</th><th className="py-1 font-medium">메모</th></tr>
                  </thead>
                  <tbody className="divide-y divide-[#F0EEE7]">
                    {[...cycles].reverse().map((c) => (
                      <tr key={c.id} onClick={() => run(async () => setDetail(await getTask(c.id)), false)}
                        className={`cursor-pointer hover:bg-[#F8F7F2] ${c.id === task.id ? 'bg-[#F8F7F2] font-medium' : ''}`}>
                        <td className="py-1.5">{c.dueDate}</td>
                        <td className={`py-1.5 ${isOverdue(c) ? 'text-[#991B1B]' : ''}`}>{taskStatusLabel(c)}</td>
                        <td className="py-1.5 text-gray-600">{c.doneAt ? day(c.doneAt) : '—'}</td>
                        <td className="py-1.5 text-gray-600" title="증빙 문서 + 측정값">{c.evidenceCount + c.measurementCount}</td>
                        <td className="truncate py-1.5 text-gray-600" title={c.note ?? ''}>{c.note ?? '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </section>

              <section>
                <h3 className="text-sm font-semibold text-gray-800">변경 기록</h3>
                <ul className="mt-2 space-y-1 text-xs text-gray-600">
                  {detail.events.map((e, i) => (
                    <li key={i} className="truncate">{day(e.at)} · {e.byName ?? '시스템'} · {eventText(e, memberName)}</li>
                  ))}
                </ul>
              </section>
            </>
          )}
        </div>
      </aside>
    </div>
  );
}
