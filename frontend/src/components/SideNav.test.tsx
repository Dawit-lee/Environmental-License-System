// @vitest-environment jsdom
import { act, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router';
import { afterEach, describe, expect, it } from 'vitest';
import { NavDrawer, SideNav, navFor, projectTabs } from './SideNav';

// The DOM the browser gets is the seam: links, their aria-current and classes.
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
let root: Root | null = null;
let host: HTMLElement;
afterEach(() => { act(() => root?.unmount()); root = null; host?.remove(); });

function mount(el: React.ReactElement, path = '/dashboard') {
  host = document.body.appendChild(document.createElement('div'));
  root = createRoot(host);
  act(() => root!.render(<MemoryRouter initialEntries={[path]}>{el}</MemoryRouter>));
  return host;
}
const labels = (el: Element) => [...el.querySelectorAll('a')].map((a) => a.textContent);
const current = (el: Element) => [...el.querySelectorAll('a[aria-current="page"]')];

describe('SideNav', () => {
  it('marks the current screen with aria-current, blue text, light blue ground and a 3px blue bar', () => {
    const el = mount(<SideNav items={navFor(false)} recent={[]} />, '/calendar');
    expect(current(el).map((a) => a.textContent)).toEqual(['회사 달력']);
    expect(current(el)[0].classList).toContain('text-brand-text');
    expect(current(el)[0].classList).toContain('bg-brand-subtle');
    expect(current(el)[0].classList).toContain('border-l-[3px]');
    expect(current(el)[0].classList).toContain('border-brand');
    expect(el.querySelector('a[href="/dashboard"]:not([aria-current])')?.classList).not.toContain('border-brand');
  });

  it('tells 법령 개정 (/inbox?kind=LAW) from 내 할 일 (/inbox) and shows the open count', () => {
    const law = mount(<SideNav items={navFor(false, 12)} recent={[]} />, '/inbox?kind=LAW');
    expect(current(law).map((a) => a.textContent)).toEqual(['법령 개정']);
    act(() => root!.unmount()); host.remove();
    const inbox = mount(<SideNav items={navFor(false, 12)} recent={[]} />, '/inbox?kind=REVIEW');
    expect(current(inbox).map((a) => a.textContent)).toEqual(['내 할 일12건']);
  });

  it('unfolds the open project under 프로젝트, selects its tab and drops the top-level selection', () => {
    const project = { id: 7, name: '새벽시 매립가스', tabs: projectTabs({ member: true, operationsEnabled: false }, 32) };
    const el = mount(<SideNav items={navFor(false)} project={project} recent={[]} />, '/projects/7/review');
    expect(current(el).map((a) => a.getAttribute('href'))).toEqual(['/projects/7/review']);
    expect(el.textContent).toContain('새벽시 매립가스');
    expect(labels(el.querySelector('nav[aria-label="주 메뉴"]')!)).toEqual([
      '대시보드', '프로젝트', '개요', '인허가', '의무사항', 'AI 검토32건', '법령 영향', '일정', '문서', '설정', '내 할 일', '회사 달력', '법령 개정',
    ]);
  });

  it('opens 개요 for /projects/:id and folds the sub menu away on other screens', () => {
    const project = { id: 7, name: 'P', tabs: projectTabs({ member: true, operationsEnabled: true }) };
    const el = mount(<SideNav items={navFor(false)} project={project} recent={[]} />, '/projects/7');
    expect(current(el).map((a) => a.textContent)).toEqual(['개요']);
    expect(labels(el)).toContain('운영');
    act(() => root!.unmount()); host.remove();
    const away = mount(<SideNav items={navFor(false)} project={project} recent={[]} />, '/projects');
    expect(labels(away)).not.toContain('개요');
    expect(current(away).map((a) => a.textContent)).toEqual(['프로젝트']);
  });

  it('shows admin screens only to the company master, and 개요·인허가 only to a non-member', () => {
    expect(navFor(false).map((n) => n.label)).toEqual(['대시보드', '프로젝트', '내 할 일', '회사 달력', '법령 개정']);
    expect(navFor(true).map((n) => n.label)).toEqual(['대시보드', '프로젝트', '내 할 일', '회사 달력', '법령 개정', '가입 요청', '조직 관리']);
    expect(projectTabs({ member: false, operationsEnabled: true }).map((t) => t.key)).toEqual(['overview', 'permits']);
  });

  it('lists 최근 프로젝트 at the bottom', () => {
    const el = mount(<SideNav items={navFor(false)} recent={[{ id: 3, name: '가람시 하수처리장' }]} />);
    const recent = el.querySelector('nav[aria-label="최근 프로젝트"]')!;
    expect(recent.querySelector('a')?.getAttribute('href')).toBe('/projects/3');
  });
});

describe('NavDrawer', () => {
  function Harness() {
    const [open, setOpen] = useState(false);
    return (
      <>
        <button type="button" aria-expanded={open} aria-controls="side-nav-drawer" onClick={() => setOpen(true)}>메뉴</button>
        <NavDrawer open={open} onClose={() => setOpen(false)}>
          <SideNav items={navFor(false)} recent={[]} onNavigate={() => setOpen(false)} />
        </NavDrawer>
      </>
    );
  }
  const press = (key: string, shiftKey = false) =>
    act(() => { (document.activeElement ?? document.body).dispatchEvent(new KeyboardEvent('keydown', { key, shiftKey, bubbles: true })); });

  it('opens from the 메뉴 button, keeps focus inside, and closes on Esc back to the button', () => {
    const el = mount(<Harness />);
    const button = el.querySelector('button')!;
    expect(button.getAttribute('aria-expanded')).toBe('false');
    expect(el.querySelector('[role="dialog"]')).toBeNull();

    button.focus();
    act(() => button.click());
    const dialog = el.querySelector('[role="dialog"]')!;
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(button.getAttribute('aria-expanded')).toBe('true');
    expect(dialog.contains(document.activeElement)).toBe(true);

    press('Tab', true); // shift+Tab from the first control wraps to the last
    expect(document.activeElement).toBe([...dialog.querySelectorAll('a, button')].at(-1));
    press('Tab');
    expect(document.activeElement).toBe(dialog.querySelector('a, button'));

    press('Escape');
    expect(el.querySelector('[role="dialog"]')).toBeNull();
    expect(button.getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement).toBe(button);
  });

  it('closes when a menu item is chosen or the close button is pressed', () => {
    const el = mount(<Harness />);
    act(() => el.querySelector('button')!.click());
    act(() => el.querySelector<HTMLAnchorElement>('[role="dialog"] a[href="/inbox"]')!.click());
    expect(el.querySelector('[role="dialog"]')).toBeNull();
    act(() => el.querySelector('button')!.click());
    act(() => el.querySelector<HTMLButtonElement>('button[aria-label="메뉴 닫기"]')!.click());
    expect(el.querySelector('[role="dialog"]')).toBeNull();
  });
});
