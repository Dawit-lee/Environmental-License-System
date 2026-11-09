import { useState, type ReactNode } from 'react';
import type { PermitCategory, PermitItem, PermitStatus } from '../types';

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

const CATEGORY_BADGE: Record<PermitCategory, string> = {
  LOCATION: 'bg-amber-100 text-amber-800',
  FACILITY: 'bg-sky-100 text-sky-800',
  CONSTRUCTION: 'bg-violet-100 text-violet-800',
  ELECTRICAL: 'bg-emerald-100 text-emerald-800',
  ENVIRONMENT: 'bg-rose-100 text-rose-800',
};

const STATUS_LABEL: Record<PermitStatus, string> = {
  NOT_APPLIED: '미신청',
  IN_PROGRESS: '진행중',
  COMPLETED: '취득완료',
  NEAR_EXPIRY: '만료임박',
  EXPIRED: '만료',
};

function dDayClasses(dDay: number | null): string {
  if (dDay === null) return 'bg-gray-200 text-gray-600';
  if (dDay <= 7) return 'bg-[#991B1B] text-white';
  if (dDay <= 30) return 'bg-[#B45309] text-white';
  if (dDay <= 60) return 'bg-amber-400 text-amber-900';
  return 'bg-gray-200 text-gray-600';
}

function dDayLabel(dDay: number | null): string {
  if (dDay === null) return '해당 없음';
  if (dDay === 0) return 'D-DAY';
  return dDay > 0 ? `D-${dDay}` : `D+${Math.abs(dDay)}`;
}

const MOCK_HISTORY = [
  { date: '2026-05-10', text: '상태가 진행중으로 변경되었습니다.' },
  { date: '2026-04-28', text: '신청서가 접수되었습니다.' },
  { date: '2026-04-15', text: '인허가 항목이 자동 도출되었습니다.' },
];

export default function PermitDetailDrawer({
  permit,
  onClose,
}: {
  permit: PermitItem | null;
  onClose: () => void;
}) {
  const [historyOpen, setHistoryOpen] = useState(false);

  const open = permit !== null;

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
        role="dialog"
        aria-modal="true"
        aria-label="인허가 상세"
        className={`absolute right-0 top-0 flex h-full w-full max-w-[480px] flex-col bg-white shadow-xl transition-transform duration-300 ${
          open ? 'translate-x-0' : 'translate-x-full'
        }`}
      >
        {permit && (
          <>
            {/* Header */}
            <div className="flex items-start justify-between border-b border-[#E5E3DC] px-6 py-4">
              <div>
                <span
                  className={`inline-block rounded-full px-2.5 py-0.5 text-xs font-medium ${CATEGORY_BADGE[permit.category]}`}
                >
                  {CATEGORY_LABEL[permit.category]}
                </span>
                <h2 className="mt-2 text-lg font-semibold text-gray-900">{permit.name}</h2>
              </div>
              <button
                type="button"
                onClick={onClose}
                aria-label="닫기"
                className="rounded-lg p-1.5 text-gray-500 transition hover:bg-[#F8F7F2] hover:text-gray-800 focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/30"
              >
                <span className="block h-5 w-5 text-center text-lg leading-5">✕</span>
              </button>
            </div>

            {/* Body */}
            <div className="flex-1 space-y-6 overflow-y-auto px-6 py-5">
              {/* 해당 사유 */}
              <Section title="해당 사유">
                <div className="rounded-lg bg-[#F8F7F2] p-3 text-sm leading-relaxed text-gray-700">
                  {permit.reason}
                </div>
              </Section>

              {/* 관련 법규 */}
              <Section title="관련 법규">
                <a
                  href={permit.regulationUrl}
                  target="_blank"
                  rel="noreferrer"
                  className="inline-flex items-center gap-1 text-sm font-medium text-[#1F4E79] underline-offset-2 transition hover:underline focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/30"
                >
                  {permit.regulation}
                  <span aria-hidden>↗</span>
                </a>
                <p className="mt-1 text-xs text-gray-400">국가법령정보센터에서 원문 확인</p>
              </Section>

              {/* 승인기관 */}
              <Section title="승인기관">
                <div className="flex items-center justify-between rounded-lg border border-[#E5E3DC] px-3 py-2.5">
                  <span className="text-sm text-gray-700">{permit.authority}</span>
                  <a
                    href={permit.authorityUrl}
                    target="_blank"
                    rel="noreferrer"
                    className="text-sm font-medium text-[#1F4E79] transition hover:underline focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/30"
                  >
                    {permit.authority} 바로가기 →
                  </a>
                </div>
              </Section>

              {/* 일정 */}
              <Section title="일정">
                <div className="flex items-center justify-between">
                  <Milestone label="신청일" value={permit.applicationDate} />
                  <Arrow />
                  <Milestone label="취득일" value={permit.acquisitionDate} />
                  <Arrow />
                  <Milestone label="만료일" value={permit.expiryDate} />
                </div>
                <div className="mt-4 flex items-center gap-2">
                  <span
                    className={`inline-flex items-center rounded-lg px-3 py-1.5 text-base font-bold ${dDayClasses(permit.dDay)}`}
                  >
                    {dDayLabel(permit.dDay)}
                  </span>
                  <span className="text-sm text-gray-500">
                    상태: {STATUS_LABEL[permit.status]}
                  </span>
                </div>
              </Section>

              {/* 다음 액션 */}
              <Section title="다음 액션">
                <div className="rounded-lg border border-[#B45309]/30 bg-[#B45309]/10 p-3">
                  <p className="text-sm text-[#B45309]">{permit.nextAction}</p>
                  <a
                    href={permit.nextActionUrl}
                    target="_blank"
                    rel="noreferrer"
                    className="mt-3 inline-flex rounded-lg bg-[#B45309] px-3.5 py-2 text-sm font-semibold text-white transition hover:bg-[#92400e] focus:outline-none focus:ring-2 focus:ring-[#B45309]/40"
                  >
                    {permit.authority} 바로가기
                  </a>
                </div>
              </Section>

              {/* 첨부 문서 */}
              <Section title="첨부 문서">
                <div className="rounded-lg border border-dashed border-[#E5E3DC] p-4 text-center text-sm text-gray-400">
                  첨부된 문서가 없습니다
                </div>
              </Section>

              {/* 변경 이력 */}
              <Section title="변경 이력">
                <button
                  type="button"
                  onClick={() => setHistoryOpen((v) => !v)}
                  className="flex w-full items-center justify-between rounded-lg border border-[#E5E3DC] px-3 py-2.5 text-sm text-gray-700 transition hover:bg-[#F8F7F2] focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/20"
                >
                  <span>이력 {MOCK_HISTORY.length}건</span>
                  <span aria-hidden className={`transition-transform ${historyOpen ? 'rotate-180' : ''}`}>
                    ▾
                  </span>
                </button>
                {historyOpen && (
                  <ul className="mt-2 space-y-2">
                    {MOCK_HISTORY.map((h) => (
                      <li key={h.date} className="flex gap-3 text-sm">
                        <span className="shrink-0 text-gray-400">{h.date}</span>
                        <span className="text-gray-700">{h.text}</span>
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

// --- Local presentational helpers ------------------------------------------

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section>
      <h3 className="mb-2 text-sm font-semibold text-gray-900">{title}</h3>
      {children}
    </section>
  );
}

function Milestone({ label, value }: { label: string; value: string | null }) {
  return (
    <div className="flex flex-1 flex-col items-center text-center">
      <span className="text-xs text-gray-400">{label}</span>
      <span className="mt-1 text-sm font-medium text-gray-700">{value ?? '—'}</span>
    </div>
  );
}

function Arrow() {
  return <span className="px-1 text-gray-300" aria-hidden>→</span>;
}
