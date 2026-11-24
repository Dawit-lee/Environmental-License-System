import { useEffect, useRef, useState, type ReactNode } from 'react';
import type { PermitCategory, PermitHistory, PermitItem, PermitStatus } from '../types';
import { api } from '../api/client';
import PermitDocuments from './PermitDocuments';
import PermitLawLinks from './PermitLawLinks';
import PermitVersions from './PermitVersions';
import { Help } from './Onboarding';
import { useDialogFocus, useEscape } from './useEscape';

// ---------------------------------------------------------------------------
// PermitDetailDrawer — slide-in panel (480px) showing a single permit's
// derivation reason, regulation, authority, schedule and next action.
// Controlled via `permit` (null = closed) + `onClose`.
// ---------------------------------------------------------------------------

const CATEGORY_LABEL: Record<PermitCategory, string> = {
  LOCATION: '입지',
  FACILITY: '시설',
  CONSTRUCTION: '건축',
  ELECTRICAL: '전기',
  ENVIRONMENT: '환경평가',
};

const STATUS_LABEL: Record<PermitStatus, string> = {
  NOT_APPLIED: '미신청',
  IN_PROGRESS: '진행중',
  COMPLETED: '취득완료',
  NEAR_EXPIRY: '만료임박',
  EXPIRED: '만료',
};

function dDayClasses(dDay: number | null): string {
  if (dDay === null) return 'bg-line-soft text-body';
  if (dDay <= 7) return 'bg-danger text-white';
  if (dDay <= 30) return 'bg-warn text-white';
  if (dDay <= 60) return 'bg-amber-400 text-amber-900';
  return 'bg-line-soft text-body';
}

function dDayLabel(dDay: number | null): string {
  if (dDay === null) return '해당 없음';
  if (dDay === 0) return 'D-DAY';
  return dDay > 0 ? `D-${dDay}` : `D+${Math.abs(dDay)}`;
}

function historyText(h: PermitHistory): string {
  if (!h.changedBy) return `날짜에 따라 상태가 ${h.fromStatus ? `${STATUS_LABEL[h.fromStatus]} → ` : ''}${STATUS_LABEL[h.toStatus]}(으)로 바뀌었습니다.`;
  return h.fromStatus
    ? `${h.changedBy}님이 상태를 ${STATUS_LABEL[h.fromStatus]} → ${STATUS_LABEL[h.toStatus]}(으)로 변경했습니다.`
    : `${h.changedBy}님이 ${STATUS_LABEL[h.toStatus]} 상태로 등록했습니다.`;
}

export default function PermitDetailDrawer({
  permit,
  onClose,
  onSaved,
}: {
  permit: PermitItem | null;
  onClose: () => void;
  onSaved?: (p: PermitItem) => void; // members only: edits 일정·상태 and the 보고 문구
}) {
  const [historyOpen, setHistoryOpen] = useState(false);
  const [history, setHistory] = useState<PermitHistory[]>([]);

  useEffect(() => {
    setHistory([]);
    if (permit) {
      api<PermitHistory[]>(`/api/projects/${permit.projectId}/permits/${permit.id}/history`).then(setHistory, () => {});
    }
  }, [permit]);

  const open = permit !== null;
  useEscape(onClose, open);
  const box = useRef<HTMLElement>(null);
  useDialogFocus(box, open);

  return (
    <div
      className={`fixed inset-0 z-50 ${open ? '' : 'pointer-events-none'}`}
      aria-hidden={!open}
    >
      {/* Overlay */}
      <div
        onClick={onClose}
        className={`absolute inset-0 bg-black/40 transition-opacity duration-300 ${
          open ? 'opacity-100' : 'opacity-0'
        }`}
      />

      {/* Drawer */}
      <aside
        role="dialog" ref={box} tabIndex={-1}
        aria-modal="true"
        aria-label="인허가 상세"
        className={`absolute right-0 top-0 flex h-full w-full max-w-[480px] flex-col bg-white shadow-xl transition-transform duration-300 ${
          open ? 'translate-x-0' : 'translate-x-full'
        }`}
      >
        {permit && (
          <>
            {/* Header */}
            <div className="flex items-start justify-between border-b border-line px-6 py-4">
              <div>
                <span className="text-sm text-mute">
                  {CATEGORY_LABEL[permit.category]}
                </span>
                <h2 className="mt-2 text-lg font-semibold text-ink">{permit.name}</h2>
              </div>
              <button
                type="button"
                onClick={onClose}
                aria-label="닫기"
                className="rounded p-1.5 text-mute transition hover:bg-paper hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand"
              >
                <span className="block h-5 w-5 text-center text-lg leading-5">✕</span>
              </button>
            </div>

            {/* Body */}
            <div className="flex-1 space-y-6 overflow-y-auto px-6 py-5">
              {/* 해당 사유 */}
              <Section title="해당 사유">
                <div className="rounded bg-paper p-3 text-sm leading-relaxed text-body">
                  {permit.reason}
                </div>
              </Section>

              {/* 관련 법령 */}
              <Section title="관련 법령">
                {permit.regulation && (
                  <p className="mb-2 text-xs text-mute">
                    등록 시 입력한 근거:{' '}
                    <a
                      href={permit.regulationUrl ?? undefined}
                      target="_blank"
                      rel="noreferrer"
                      className="text-brand underline-offset-2 hover:underline"
                    >
                      {permit.regulation}
                    </a>
                  </p>
                )}
                <PermitLawLinks permitId={permit.id} />
              </Section>

              {/* 승인기관 */}
              <Section title="승인기관">
                <div className="flex items-center justify-between rounded border border-line px-3 py-2.5">
                  <span className="text-sm text-body">{permit.authority}</span>
                  <a
                    href={permit.authorityUrl ?? undefined}
                    target="_blank"
                    rel="noreferrer"
                    className="text-sm font-medium text-brand transition hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand"
                  >
                    {permit.authority} 바로가기 →
                  </a>
                </div>
              </Section>

              {/* 일정 */}
              <Section title="일정">
                {onSaved ? (
                  <PermitDates key={`${permit.id}-${permit.status}-${permit.applicationDate}-${permit.acquisitionDate}-${permit.expiryDate}`}
                    permit={permit} onSaved={onSaved} />
                ) : (
                  <div className="flex items-center justify-between">
                    <Milestone label="신청일" value={permit.applicationDate} />
                    <Arrow />
                    <Milestone label="취득일" value={permit.acquisitionDate} />
                    <Arrow />
                    <Milestone label="만료일" value={permit.expiryDate} />
                  </div>
                )}
                <div className="mt-4 flex items-center gap-2">
                  <span
                    className={`inline-flex items-center rounded px-3 py-1.5 text-base font-bold ${dDayClasses(permit.dDay)}`}
                  >
                    {dDayLabel(permit.dDay)}
                  </span>
                  <span className="text-sm text-mute">
                    상태: {STATUS_LABEL[permit.status]}
                  </span>
                  <Help k="permitStatus" />
                </div>
              </Section>

              {/* 다음 액션 */}
              <Section title="다음 액션">
                <div className="rounded border border-warn/30 bg-warn/10 p-3">
                  <p className="text-sm text-warn">{permit.nextAction}</p>
                  <a
                    href={permit.nextActionUrl ?? undefined}
                    target="_blank"
                    rel="noreferrer"
                    className="mt-3 inline-flex rounded bg-warn px-3.5 py-2 text-sm font-semibold text-white transition hover:bg-warn-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand"
                  >
                    {permit.authority} 바로가기
                  </a>
                </div>
              </Section>

              {/* 진행 현황 보고 1쪽 문구 */}
              <Section title="보고 문구">
                <ReportNotes key={permit.id} permit={permit} onSaved={onSaved} />
              </Section>

              {/* 첨부 문서 */}
              <Section title="첨부 문서">
                <PermitDocuments permit={permit} />
              </Section>

              {/* ④ 문서 버전 · 의무사항 변경 이력 */}
              <Section title="문서 버전 · 의무사항">
                <PermitVersions permit={permit} />
              </Section>

              {/* 변경 이력 */}
              <Section title="변경 이력">
                <button
                  type="button"
                  onClick={() => setHistoryOpen((v) => !v)}
                  className="flex w-full items-center justify-between rounded border border-line px-3 py-2.5 text-sm text-body transition hover:bg-paper focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand"
                >
                  <span>이력 {history.length}건</span>
                  <span aria-hidden className={`transition-transform ${historyOpen ? 'rotate-180' : ''}`}>
                    ▾
                  </span>
                </button>
                {historyOpen && (
                  <ul className="mt-2 space-y-2">
                    {history.map((h) => (
                      <li key={h.id} className="flex gap-3 text-sm">
                        <span className="shrink-0 text-mute">{new Date(h.changedAt).toLocaleDateString('sv-SE')}</span>
                        <span className="text-body">{historyText(h)}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </Section>
            </div>
          </>
        )}
      </aside>
    </div>
  );
}

// --- 일정·상태 (2026-11-17 review H1·H3) -------------------------------------

const DATE_FIELDS = [
  { key: 'applicationDate', label: '신청일' },
  { key: 'acquisitionDate', label: '취득일' },
  { key: 'expiryDate', label: '만료일' },
] as const;

/** Dates and the status. The server derives the status from the dates; the pick only counts where they can't decide. */
function PermitDates({ permit, onSaved }: { permit: PermitItem; onSaved: (p: PermitItem) => void }) {
  const [draft, setDraft] = useState({
    applicationDate: permit.applicationDate ?? '',
    acquisitionDate: permit.acquisitionDate ?? '',
    expiryDate: permit.expiryDate ?? '',
    // 만료임박·만료 are computed; as a pick they mean "취득함"
    status: (permit.status === 'NEAR_EXPIRY' || permit.status === 'EXPIRED' ? 'COMPLETED' : permit.status) as PermitStatus,
  });
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const dirty = DATE_FIELDS.some((f) => draft[f.key] !== (permit[f.key] ?? ''))
    || draft.status !== (permit.status === 'NEAR_EXPIRY' || permit.status === 'EXPIRED' ? 'COMPLETED' : permit.status);

  async function save() {
    setBusy(true);
    setMessage(null);
    try {
      const body = { ...permit, status: draft.status, ...Object.fromEntries(DATE_FIELDS.map((f) => [f.key, draft[f.key] || null])) };
      const saved = await api<PermitItem>(`/api/projects/${permit.projectId}/permits/${permit.id}`, {
        method: 'PUT',
        body: JSON.stringify(body),
      });
      onSaved(saved);
      if (saved.status !== draft.status) setMessage(`날짜에 따라 ${STATUS_LABEL[saved.status]}(으)로 저장했습니다.`);
    } catch (e) {
      setMessage((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-2">
      {DATE_FIELDS.map((f) => (
        <label key={f.key} className="flex items-center gap-3 text-sm">
          <span className="w-16 shrink-0 text-mute">{f.label}</span>
          <input type="date" value={draft[f.key]} onChange={(e) => setDraft({ ...draft, [f.key]: e.target.value })}
            className="min-w-0 flex-1 rounded border border-input px-3 py-1.5 text-ink focus:border-brand focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand" />
        </label>
      ))}
      <label className="flex items-center gap-3 text-sm">
        <span className="w-16 shrink-0 text-mute">상태</span>
        <select value={draft.status} onChange={(e) => setDraft({ ...draft, status: e.target.value as PermitStatus })}
          className="min-w-0 flex-1 rounded border border-input px-3 py-1.5 text-ink focus:border-brand focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand">
          <option value="NOT_APPLIED">미신청</option>
          <option value="IN_PROGRESS">진행중</option>
          <option value="COMPLETED">취득완료</option>
        </select>
      </label>
      <p className="text-xs text-mute">날짜를 넣으면 상태는 날짜로 정해집니다.</p>
      <div className="flex items-center justify-end gap-3">
        {message && <span className="text-xs text-mute">{message}</span>}
        <button type="button" onClick={save} disabled={!dirty || busy}
          className="rounded bg-brand px-3 py-1.5 text-sm font-medium text-white transition hover:bg-brand-hover disabled:opacity-40">
          저장
        </button>
      </div>
    </div>
  );
}

// --- Report notes (design-schedule/08) -------------------------------------

const NOTE_FIELDS = [
  { key: 'progressNote', label: '진행 메모', max: 200, hint: '예: 가온시 도시과 검토 중(10/21)' },
  { key: 'expectedCompletion', label: '예상완료', max: 50, hint: '예: 11월 중순, 착공 전' },
  { key: 'issueNote', label: '현안 메모', max: 300, hint: '필요 조치, 선후 관계' },
] as const;

function ReportNotes({ permit, onSaved }: { permit: PermitItem; onSaved?: (p: PermitItem) => void }) {
  const [draft, setDraft] = useState({
    progressNote: permit.progressNote ?? '',
    expectedCompletion: permit.expectedCompletion ?? '',
    issueNote: permit.issueNote ?? '',
  });
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const dirty = NOTE_FIELDS.some((f) => draft[f.key] !== (permit[f.key] ?? ''));

  async function save() {
    setBusy(true);
    setMessage(null);
    try {
      const body = { ...permit, ...Object.fromEntries(NOTE_FIELDS.map((f) => [f.key, draft[f.key].trim() || null])) };
      const saved = await api<PermitItem>(`/api/projects/${permit.projectId}/permits/${permit.id}`, {
        method: 'PUT',
        body: JSON.stringify(body),
      });
      onSaved?.(saved);
      setMessage('저장했습니다.');
    } catch (e) {
      setMessage((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-2">
      {NOTE_FIELDS.map((f) => (
        <label key={f.key} className="flex items-center gap-3 text-sm">
          <span className="w-16 shrink-0 text-mute">{f.label}</span>
          <input
            value={draft[f.key]}
            maxLength={f.max}
            placeholder={f.hint}
            readOnly={!onSaved}
            onChange={(e) => setDraft({ ...draft, [f.key]: e.target.value })}
            className="min-w-0 flex-1 rounded border border-input px-3 py-1.5 text-ink focus:border-brand focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand"
          />
        </label>
      ))}
      {onSaved && (
        <div className="flex items-center justify-end gap-3">
          {message && <span className="text-xs text-mute">{message}</span>}
          <button
            type="button"
            onClick={save}
            disabled={!dirty || busy}
            className="rounded bg-brand px-3 py-1.5 text-sm font-medium text-white transition hover:bg-brand-hover disabled:opacity-40"
          >
            저장
          </button>
        </div>
      )}
    </div>
  );
}

// --- Local presentational helpers ------------------------------------------

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section>
      <h3 className="mb-2 text-sm font-semibold text-ink">{title}</h3>
      {children}
    </section>
  );
}

function Milestone({ label, value }: { label: string; value: string | null }) {
  return (
    <div className="flex flex-1 flex-col items-center text-center">
      <span className="text-xs text-mute">{label}</span>
      <span className="mt-1 text-sm font-medium text-body">{value ?? '—'}</span>
    </div>
  );
}

function Arrow() {
  return <span className="px-1 text-line" aria-hidden>→</span>;
}
