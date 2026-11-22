import { createContext, useEffect, useRef, type KeyboardEvent, type ReactNode } from 'react';
import { Link, useLocation } from 'react-router';
import type { Project } from '../types';
import { cn, focusRing } from './ui/cn';
import { useEscape } from './useEscape';

export interface NavItem { to: string; label: string; icon: ReactNode; count?: number }
export interface ProjectTab { key: string; label: string; count?: number }
/** The project open in the main area: its sub menu unfolds under 프로젝트. */
export interface NavProject { id: number; name: string; tabs: ProjectTab[] }

/** The project page tells the shell which project is open (null when it leaves), so the left menu can unfold it. */
export const OpenProjectContext = createContext<(p: Project | null) => void>(() => {});

const icon = (d: ReactNode) => (
  <svg width="18" height="18" viewBox="0 0 18 18" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.6"
    strokeLinecap="round" strokeLinejoin="round" className="shrink-0">{d}</svg>
);

const NAV: NavItem[] = [
  { to: '/dashboard', label: '대시보드', icon: icon(<><rect x="2" y="2" width="6" height="6" /><rect x="10" y="2" width="6" height="6" /><rect x="2" y="10" width="6" height="6" /><rect x="10" y="10" width="6" height="6" /></>) },
  { to: '/projects', label: '프로젝트', icon: icon(<path d="M2 5h5l1.5 2H16v8H2z" />) },
  { to: '/inbox', label: '내 할 일', icon: icon(<path d="M3 9l4 4 8-9" />) },
  { to: '/calendar', label: '회사 달력', icon: icon(<><rect x="2" y="3" width="14" height="13" /><path d="M2 7h14M6 1v4M12 1v4" /></>) },
  { to: '/inbox?kind=LAW', label: '법령 개정', icon: icon(<><path d="M4 2h8l3 3v11H4z" /><path d="M7 9h5M7 12h5" /></>) },
];
const ADMIN_NAV: NavItem[] = [
  { to: '/join-requests', label: '가입 요청', icon: icon(<><circle cx="7" cy="6" r="3" /><path d="M1.5 16c0-3 2.5-5 5.5-5s5.5 2 5.5 5M14 5v5M11.5 7.5h5" /></>) },
  { to: '/org', label: '조직 관리', icon: icon(<><rect x="6.5" y="1.5" width="5" height="4" /><rect x="1.5" y="12.5" width="5" height="4" /><rect x="11.5" y="12.5" width="5" height="4" /><path d="M9 5.5v3.5M4 12.5V9h10v3.5" /></>) },
];

/** Main menu items; the company master also gets the admin screens. 내 할 일 carries its open count. */
export const navFor = (admin: boolean, inbox?: number): NavItem[] =>
  [...NAV, ...(admin ? ADMIN_NAV : [])].map((n) => (n.to === '/inbox' ? { ...n, count: inbox } : n));

const PROJECT_TABS: ProjectTab[] = [
  { key: 'overview', label: '개요' },
  { key: 'permits', label: '인허가' },
  { key: 'obligations', label: '의무사항' },
  { key: 'review', label: 'AI 검토' },
  { key: 'laws', label: '법령 영향' },
  { key: 'schedule', label: '일정' },
  { key: 'operations', label: '운영' },
  { key: 'documents', label: '문서' },
  { key: 'settings', label: '설정' },
];

/** A project's sub menu: non-members get 개요·인허가 only (the rest is 403), 운영 only when the module is on. */
export const projectTabs = (p: Pick<Project, 'member' | 'operationsEnabled'>, review?: number): ProjectTab[] =>
  PROJECT_TABS
    .filter((t) => p.member || t.key === 'overview' || t.key === 'permits')
    .filter((t) => t.key !== 'operations' || p.operationsEnabled)
    .map((t) => (t.key === 'review' ? { ...t, count: review } : t));

/** The item for this URL: the longest path match, and among same paths the one whose query matches (법령 개정 = /inbox?kind=LAW). */
function currentItem(items: NavItem[], pathname: string, search: string): NavItem | undefined {
  const here = new URLSearchParams(search);
  const fits = (n: NavItem) => {
    const [path, query] = n.to.split('?');
    if (pathname !== path && !pathname.startsWith(`${path}/`)) return false;
    return [...new URLSearchParams(query)].every(([k, v]) => here.get(k) === v);
  };
  return items.filter(fits).sort((a, b) => b.to.length - a.to.length)[0];
}

const row = (on: boolean) => cn(
  'flex min-h-11 items-center gap-2.5 border-l-[3px] px-4 text-[15px]', focusRing, 'focus-visible:-outline-offset-2',
  on ? 'border-brand bg-brand-subtle font-bold text-brand-text' : 'border-transparent font-medium text-body hover:bg-paper hover:text-ink',
);

function Count({ n, tone }: { n?: number; tone: string }) {
  if (!n) return null;
  return <span className={cn('ml-auto rounded-full px-[7px] text-xs leading-5 font-bold text-white tabular-nums', tone)}>{n}<span className="sr-only">건</span></span>;
}

/**
 * DESIGN.md 3. 공통 틀 — 왼쪽 메뉴: logo, icon + label items, and 최근 프로젝트 at the bottom.
 * Selected = blue text, light blue ground, 3px blue bar on the left, aria-current="page".
 * Inside a project, its sub menu unfolds under 프로젝트 and carries the selection instead.
 */
export function SideNav({ items, project, recent, onNavigate }: {
  items: NavItem[];
  project?: NavProject | null;
  recent: { id: number; name: string }[];
  onNavigate?: () => void;
}) {
  const { pathname, search } = useLocation();
  const inProject = project && (pathname === `/projects/${project.id}` || pathname.startsWith(`/projects/${project.id}/`));
  const tab = inProject ? pathname.split('/')[3] || 'overview' : null;
  const active = currentItem(items, pathname, search);
  return (
    <div className="flex h-full flex-col">
      <Link to="/dashboard" onClick={onNavigate}
        className={cn('flex items-center gap-2.5 border-b border-line-soft px-4 py-[18px]', focusRing, 'focus-visible:-outline-offset-2')}>
        <svg width="26" height="26" viewBox="0 0 28 28" aria-hidden="true" className="text-brand">
          <rect x="1" y="1" width="26" height="26" rx="4" fill="currentColor" />
          <path d="M8 15l4 4 8-9" stroke="#fff" strokeWidth="2.5" fill="none" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
        <span className="text-lg font-bold text-ink">인허가 관리</span>
      </Link>
      <nav aria-label="주 메뉴" className="flex flex-col py-3">
        {items.map((n) => {
          const opened = inProject && n.to === '/projects';
          const on = !inProject && n === active;
          return (
            <div key={n.to}>
              <Link to={n.to} onClick={onNavigate} aria-current={on ? 'page' : undefined}
                className={cn(row(on), opened && 'font-bold text-ink')}>
                {n.icon}<span className="min-w-0 flex-1 break-keep">{n.label}</span><Count n={n.count} tone="bg-danger" />
              </Link>
              {opened && (
                <div className="pb-1">
                  <p className="break-keep pt-1.5 pr-4 pb-1 pl-11 text-[13px] leading-snug text-mute">{project.name}</p>
                  {project.tabs.map((t) => (
                    <Link key={t.key} to={`/projects/${project.id}/${t.key}`} onClick={onNavigate}
                      aria-current={t.key === tab ? 'page' : undefined}
                      className={cn(row(t.key === tab), 'min-h-[38px] pl-11 text-sm', t.key !== tab && 'font-normal')}>
                      <span className="flex-1">{t.label}</span><Count n={t.count} tone="bg-brand-text" />
                    </Link>
                  ))}
                </div>
              )}
            </div>
          );
        })}
      </nav>
      {recent.length > 0 && (
        <nav aria-label="최근 프로젝트" className="mt-auto flex flex-col gap-0.5 border-t border-line-soft px-4 py-3">
          <h2 className="mb-1 text-[13px] font-normal text-mute">최근 프로젝트</h2>
          {recent.map((p) => (
            <Link key={p.id} to={`/projects/${p.id}`} onClick={onNavigate}
              className={cn('flex min-h-9 items-center break-keep rounded text-sm text-body hover:text-ink hover:underline', focusRing)}>
              {p.name}
            </Link>
          ))}
        </nav>
      )}
    </div>
  );
}

/**
 * Below lg the left menu is a drawer opened by a 메뉴 button: focus moves in and stays in (Tab wraps),
 * Esc or the backdrop closes it and focus goes back to the button. The shell holds `open`.
 */
export function NavDrawer({ open, onClose, children }: { open: boolean; onClose: () => void; children: ReactNode }) {
  const panel = useRef<HTMLDivElement>(null);
  const opener = useRef<Element | null>(null);
  useEscape(onClose, open);
  useEffect(() => {
    if (!open) return;
    opener.current = document.activeElement;
    panel.current?.querySelector<HTMLElement>('a, button')?.focus();
    return () => (opener.current as HTMLElement | null)?.focus?.();
  }, [open]);
  if (!open) return null;
  const trap = (e: KeyboardEvent) => {
    if (e.key !== 'Tab') return;
    const all = [...panel.current!.querySelectorAll<HTMLElement>('a, button')];
    const [first, last] = [all[0], all[all.length - 1]];
    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
  };
  return (
    <div className="fixed inset-0 z-50 lg:hidden">
      <div className="absolute inset-0 bg-black/40" aria-hidden="true" onClick={onClose} />
      <div ref={panel} id="side-nav-drawer" role="dialog" aria-modal="true" aria-label="메뉴" onKeyDown={trap}
        className="absolute inset-y-0 left-0 flex w-[280px] max-w-[85vw] flex-col overflow-y-auto bg-white shadow-lg">
        <button type="button" onClick={onClose} aria-label="메뉴 닫기"
          className={cn('absolute top-3 right-3 flex h-11 w-11 items-center justify-center rounded text-xl text-body hover:bg-paper', focusRing)}>
          <span aria-hidden>✕</span>
        </button>
        {children}
      </div>
    </div>
  );
}
