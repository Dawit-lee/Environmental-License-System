import { useEffect, useState, type ReactNode } from 'react';
import { DUE_SOON_DAYS } from '../terms';
import { Link } from 'react-router';
import { api, sessionUserId } from '../api/client';
import { getSchedule } from '../api/schedule';
import { listProjectMembers } from '../api/tasks';
import { getShareLinks } from '../api/share';
import type { PermitItem, Project } from '../types';

// ---------------------------------------------------------------------------
// Onboarding — 시작하기 checklist (dashboard), hover help for confusing controls,
// and the empty-state card the main tabs share. The same HELP text feeds /guide.
// ---------------------------------------------------------------------------

/** One line per confusing control, in the words the screen uses. */
export const HELP = {
  base: { term: '기준일', text: '일정 계산의 출발 날짜입니다. 입찰일이나 착수일을 넣으면 항목 날짜가 자동으로 정해집니다.' },
  baseline: { term: '기준 확정', text: '지금 일정을 기준선으로 저장합니다. 이후 늦어진 일수는 이 기준과 비교합니다.' },
  unbaselined: { term: '기준 미확정', text: '아직 기준선을 저장하지 않았습니다. 일정을 다 넣은 뒤 [기준 확정]을 누르세요.' },
  template: { term: '템플릿', text: '자주 쓰는 공정을 한 번에 넣습니다. 기준일을 넣으면 날짜가 계산됩니다.' },
  paste: { term: '붙여넣기', text: '엑셀에서 복사한 표로 항목을 만듭니다. 열 순서는 분야, 작업, 시작, 종료, 담당, 보할입니다.' },
  share: { term: '공유', text: '협력사에 읽기 전용 링크나 달력 구독(ICS)을 보냅니다. 기한이 지나면 링크가 닫힙니다.' },
  delays: { term: '지연 현황', text: '기준 확정 뒤 기준선보다 늦어진 항목과 연기 사유를 모아 봅니다.' },
  dueSoon: { term: '기한 임박', text: `완료 전이고 기한까지 ${DUE_SOON_DAYS}일 이내인 항목입니다. 대시보드는 내 프로젝트 전체를, 내 할 일은 내 몫만 셉니다.` },
  overdue: { term: '기한 경과', text: '완료 전인데 기한(계획 종료일)이 지난 항목입니다. 기준 확정 여부와 상관없습니다.' },
  slip: { term: '일정 지연', text: '기준 확정 뒤 기준선 종료일보다 늦어진 항목입니다. 기준을 확정하기 전에는 세지 않습니다.' },
  permitStatus: { term: '인허가 상태', text: `날짜로 정합니다. 신청일이 지나면 진행중, 취득일이 지나면 취득완료, 그 뒤 만료일까지 ${DUE_SOON_DAYS}일 이내면 만료임박, 지나면 만료입니다. 날짜가 없을 때만 직접 고른 상태를 씁니다.` },
  deadlines: { term: '인허가 기한', text: '인허가 만료일과 의무 기한을 달력으로 봅니다.' },
  print: { term: '인쇄본', text: '회의 자료용 공정표입니다. 인쇄하거나 PDF로 저장합니다.' },
  extract: { term: '일정 추출', text: 'AI가 공고문·과업내용서에서 날짜를 찾아 일정 후보로 제안합니다. 확인한 것만 일정에 들어갑니다.' },
  review: { term: 'AI 검토', text: 'AI가 허가증에서 뽑은 조건을 원문과 대조해 승인합니다. 승인한 조건만 의무사항이 됩니다.' },
  derive: { term: '인허가 도출', text: '시설 종류·용량·입지로 필요한 인허가를 규칙에 따라 찾습니다. 승인한 항목만 목록에 들어갑니다.' },
  obligation: { term: '의무사항', text: '허가조건에서 나온 정기 할 일입니다. 측정·보고 기한을 담당자에게 배정합니다.' },
  report: { term: '진행 현황 보고', text: '인허가 진행 현황을 엑셀 1쪽으로 내려받습니다.' },
} as const;
export type HelpKey = keyof typeof HELP;

const tipBox =
  // hidden (not invisible): an invisible box still widens the page near the right edge.
  'pointer-events-none hidden absolute top-full z-40 mt-1.5 w-64 whitespace-normal rounded-lg bg-gray-900 px-3 py-2 text-left text-xs font-normal leading-relaxed text-white shadow-lg group-hover:block group-focus-within:block';

/** Wraps a control: its one-line explanation shows on hover or keyboard focus. */
export function Tip({ k, children, align = 'right' }: { k: HelpKey; children: ReactNode; align?: 'left' | 'right' }) {
  return (
    <span className="group relative inline-flex">
      {children}
      <span role="tooltip" className={`${tipBox} ${align === 'right' ? 'right-0' : 'left-0'}`}>{HELP[k].text}</span>
    </span>
  );
}

/** A small "?" for terms shown as plain text (no control to hover). */
export function Help({ k, align = 'left' }: { k: HelpKey; align?: 'left' | 'right' }) {
  return (
    <Tip k={k} align={align}>
      <button type="button" aria-label={`${HELP[k].term} 설명: ${HELP[k].text}`}
        className="ml-1 inline-flex h-4 w-4 items-center justify-center rounded-full border border-gray-300 text-[10px] font-semibold leading-none text-gray-500 hover:border-[#1F4E79] hover:text-[#1F4E79] focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/30">
        ?
      </button>
    </Tip>
  );
}

/** Empty tab: what the tab is for, then the next thing to do. */
export function EmptyState({ text, children, bare }: { text: string; children?: ReactNode; bare?: boolean }) {
  // bare: inside an existing table card, so no second border
  return (
    <div className={`${bare ? '' : 'rounded-xl border border-dashed border-[#E5E3DC] bg-white'} px-6 py-10 text-center`}>
      {text.split(/(?<=\.)\s+/).map((line) => <p key={line} className="text-sm text-gray-600">{line}</p>)}
      {children && <div className="mt-4 flex flex-wrap items-center justify-center gap-2">{children}</div>}
    </div>
  );
}
export const primaryBtn = 'rounded-lg bg-[#1F4E79] px-3.5 py-1.5 text-xs font-semibold text-white hover:bg-[#173a5c]';
export const secondaryBtn = 'rounded-lg border border-[#E5E3DC] bg-white px-3.5 py-1.5 text-xs font-medium text-gray-700 hover:border-[#1F4E79] hover:text-[#1F4E79]';

// --- 시작하기 checklist ------------------------------------------------------

const DISMISS_EVENT = 'onboarding-dismissed-change';
// shortcut: the flag lives in this browser only; move to a per-user server setting if people switch devices often.
const dismissKey = () => `onboarding_dismissed_${sessionUserId() ?? 'anon'}`;

export function isOnboardingDismissed(): boolean {
  try {
    return localStorage.getItem(dismissKey()) === '1';
  } catch {
    return false;
  }
}
export function setOnboardingDismissed(v: boolean) {
  try {
    if (v) localStorage.setItem(dismissKey(), '1');
    else localStorage.removeItem(dismissKey());
  } catch {
    // storage blocked: the checklist just comes back next visit
  }
  window.dispatchEvent(new Event(DISMISS_EVENT));
}

interface Facts { items: number; anchors: number; members: number; docs: number; shares: number }

/**
 * Steps of the real workflow, ticked from data of the newest project the user is a member of.
 * `projects`/`permits` come from the dashboard, which already loads them.
 */
export function GettingStarted({ projects, permits }: { projects: Project[]; permits: PermitItem[] }) {
  const [dismissed, setDismissed] = useState(isOnboardingDismissed);
  const [facts, setFacts] = useState<Facts | null>(null);
  const target = projects.filter((p) => p.member).sort((a, b) => b.id - a.id)[0];

  useEffect(() => {
    const sync = () => setDismissed(isOnboardingDismissed());
    window.addEventListener(DISMISS_EVENT, sync);
    return () => window.removeEventListener(DISMISS_EVENT, sync);
  }, []);

  useEffect(() => {
    if (!target || dismissed) return;
    const zero = <T,>(p: Promise<T[]>) => p.then((l) => l.length, () => 0); // a failed lookup just leaves the step open
    Promise.all([
      getSchedule(target.id).then((s) => [s.items.length, s.anchors.filter((a) => a.date).length], () => [0, 0]),
      zero(listProjectMembers(target.id)),
      zero(api<unknown[]>(`/api/projects/${target.id}/documents`)),
      zero(getShareLinks(target.id)),
    ]).then(([[items, anchors], members, docs, shares]) => setFacts({ items, anchors, members, docs, shares }));
  }, [target?.id, dismissed]);

  if (dismissed) return null;

  const p = target ? `/projects/${target.id}` : null;
  const f = facts ?? { items: 0, anchors: 0, members: 0, docs: 0, shares: 0 };
  const steps = [
    { label: '프로젝트 만들기', desc: '시설 종류·용량·위치를 넣습니다.', to: '/projects/new', done: !!target },
    { label: '자동 도출된 인허가 확인·승인', desc: '규칙이 찾은 인허가 중 맞는 것만 승인합니다.', to: p ? `${p}/settings` : null,
      done: !!target && permits.some((x) => x.projectId === target.id) },
    { label: '일정 템플릿 적용', desc: '자주 쓰는 공정을 한 번에 넣습니다.', to: p ? `${p}/schedule?tool=template` : null, done: f.items > 0 },
    { label: '기준일 넣기', desc: '입찰일이나 착수일을 넣으면 날짜가 계산됩니다.', to: p ? `${p}/schedule?tool=base` : null, done: f.anchors > 0 },
    { label: '멤버·담당자 추가', desc: '함께 일할 사람을 프로젝트에 넣습니다.', to: p ? `${p}/settings` : null, done: f.members > 1 },
    { label: '문서 올리고 추출 날짜 확인', desc: '공고문을 올리고 [일정 추출]로 날짜를 받습니다.', to: p ? `${p}/documents` : null, done: f.docs > 0 },
    { label: '협력사와 일정 공유', desc: '읽기 전용 링크를 보냅니다.', to: p ? `${p}/schedule?tool=share` : null, done: f.shares > 0 },
  ];
  const doneCount = steps.filter((s) => s.done).length;

  return (
    <section aria-label="시작하기" className="mt-6 rounded-xl border border-[#1F4E79]/20 bg-white p-5 shadow-sm">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold text-gray-900">시작하기 <span className="text-sm font-normal text-gray-500">{doneCount}/{steps.length}</span></h2>
          <p className="mt-1 text-sm text-gray-500">
            {target ? <>“{target.name}” 기준으로 확인합니다.</> : '첫 프로젝트를 만들면 다음 단계가 열립니다.'}
          </p>
        </div>
        <div className="flex items-center gap-3 text-xs">
          <Link to="/guide" className="text-[#1F4E79] hover:underline">사용 가이드</Link>
          <button type="button" onClick={() => setOnboardingDismissed(true)} className="text-gray-500 hover:text-gray-800">
            {doneCount === steps.length ? '닫기' : '숨기기'}
          </button>
        </div>
      </div>
      <div className="mt-3 h-1.5 overflow-hidden rounded-full bg-[#F3F2EC]">
        <div className="h-full bg-[#0F6E56] transition-all" style={{ width: `${(doneCount / steps.length) * 100}%` }} />
      </div>
      <ol className="mt-4 grid gap-2 sm:grid-flow-col sm:grid-cols-2 sm:grid-rows-4">
        {steps.map((s, i) => {
          const body = (
            <>
              <span aria-hidden className={`mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full text-[11px] font-semibold ${
                s.done ? 'bg-[#0F6E56] text-white' : 'border border-gray-300 text-gray-500'}`}>
                {s.done ? '✓' : i + 1}
              </span>
              <span className="min-w-0">
                <span className={`block text-sm font-medium ${s.done ? 'text-gray-400 line-through' : 'text-gray-800'}`}>
                  {s.label}<span className="sr-only">{s.done ? ' (완료)' : ''}</span>
                </span>
                <span className="block text-xs text-gray-500">{s.desc}</span>
              </span>
            </>
          );
          const cls = 'flex items-start gap-3 rounded-lg px-3 py-2.5';
          return (
            <li key={s.label}>
              {s.to ? (
                <Link to={s.to} className={`${cls} transition hover:bg-[#F8F7F2] focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/30`}>{body}</Link>
              ) : (
                <div className={`${cls} opacity-50`} title="먼저 프로젝트를 만드세요">{body}</div>
              )}
            </li>
          );
        })}
      </ol>
    </section>
  );
}
