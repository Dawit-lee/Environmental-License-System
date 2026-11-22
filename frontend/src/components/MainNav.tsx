import { useRef } from 'react';
import { NavLink } from 'react-router';
import { cn, focusRing } from './ui/cn';
import { useEscape } from './useEscape';

export interface NavItem { to: string; label: string }

const NAV: NavItem[] = [
  { to: '/dashboard', label: '대시보드' },
  { to: '/projects', label: '프로젝트' },
  { to: '/inbox', label: '내 할 일' },
  { to: '/calendar', label: '회사 달력' },
];
const ADMIN_NAV: NavItem[] = [
  { to: '/join-requests', label: '가입 요청' },
  { to: '/org', label: '조직 관리' },
];

/** Main menu items; the company master also gets the admin screens. */
export const navFor = (admin: boolean): NavItem[] => (admin ? [...NAV, ...ADMIN_NAV] : NAV);

/**
 * DESIGN.md 주 메뉴: horizontal links (17px, 700) from lg up, selected = blue text + 3px blue underline.
 * Below lg a 메뉴 button opens the same links as a list under the header (the header must be `relative`).
 * `open` is held by the shell so it can close the list; Esc and choosing a link close it too.
 */
export function MainNav({ items, open, onToggle }: { items: NavItem[]; open: boolean; onToggle: () => void }) {
  const button = useRef<HTMLButtonElement>(null);
  useEscape(() => { onToggle(); button.current?.focus(); }, open);
  return (
    <>
      <nav aria-label="주 메뉴" className="hidden self-stretch lg:flex">
        {items.map((item) => (
          <NavLink key={item.to} to={item.to}
            className={({ isActive }) => cn(
              'flex items-center whitespace-nowrap border-b-[3px] px-4 pt-[3px] text-[17px] font-bold', focusRing, 'focus-visible:-outline-offset-2',
              isActive ? 'border-brand text-brand-text' : 'border-transparent text-body hover:text-ink',
            )}>
            {item.label}
          </NavLink>
        ))}
      </nav>
      <button ref={button} type="button" onClick={onToggle} aria-expanded={open} aria-controls="mobile-nav"
        className={cn('ml-auto flex h-11 items-center gap-2 rounded border border-line px-3 text-[17px] font-bold text-ink lg:hidden', focusRing)}>
        <span aria-hidden>{open ? '✕' : '☰'}</span>메뉴
      </button>
      {open && (
        <nav id="mobile-nav" aria-label="주 메뉴" className="absolute inset-x-0 top-full z-40 border-b border-line bg-white py-2 shadow-lg lg:hidden">
          {items.map((item) => (
            <NavLink key={item.to} to={item.to} onClick={onToggle}
              className={({ isActive }) => cn(
                'flex min-h-12 items-center border-l-[3px] px-6 text-[17px]', focusRing, 'focus-visible:-outline-offset-2',
                isActive ? 'border-brand bg-brand-subtle font-bold text-brand-text' : 'border-transparent text-body hover:bg-paper',
              )}>
              {item.label}
            </NavLink>
          ))}
        </nav>
      )}
    </>
  );
}
