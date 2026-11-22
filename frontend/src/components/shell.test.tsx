import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { MainNav, navFor } from './MainNav';
import { PageHeader } from './ui/PageHeader';

// react-router's links warn that layout effects do nothing on the server; the markup is still right
beforeAll(() => {
  const error = console.error;
  vi.spyOn(console, 'error').mockImplementation((...a) => { if (!String(a[0]).includes('useLayoutEffect')) error(...a); });
});

// Rendered markup is the seam: what a screen reader and the browser get from the main menu.
const render = (path: string, { admin = false, open = false } = {}) =>
  renderToStaticMarkup(
    <MemoryRouter initialEntries={[path]}>
      <MainNav items={navFor(admin)} open={open} onToggle={() => {}} />
    </MemoryRouter>,
  ).replaceAll('&amp;', '&');

/** Every <a> as { label, current, classes }. */
const links = (html: string) =>
  [...html.matchAll(/<a ([^>]*)>([^<]*)<\/a>/g)].map(([, attrs, label]) => ({
    label,
    current: /aria-current="page"/.test(attrs),
    classes: /class="([^"]*)"/.exec(attrs)?.[1].split(' ') ?? [],
  }));

describe('MainNav', () => {
  it('marks the current screen with aria-current, blue text and a blue underline', () => {
    const desktop = links(render('/projects/12/permits'));
    const current = desktop.filter((l) => l.current);
    expect(current.map((l) => l.label)).toEqual(['프로젝트']);
    expect(current[0].classes).toEqual(expect.arrayContaining(['text-brand-text', 'border-brand']));
    expect(desktop.find((l) => l.label === '대시보드')?.classes).not.toContain('border-brand');
  });

  it('shows admin screens only to the company master', () => {
    const labels = (admin: boolean) => navFor(admin).map((n) => n.label);
    expect(labels(false)).toEqual(['대시보드', '프로젝트', '내 할 일', '회사 달력']);
    expect(labels(true)).toEqual(['대시보드', '프로젝트', '내 할 일', '회사 달력', '가입 요청', '조직 관리']);
    expect(links(render('/dashboard', { admin: true })).map((l) => l.label)).toContain('조직 관리');
    expect(links(render('/dashboard')).map((l) => l.label)).not.toContain('조직 관리');
  });

  it('folds into a 메뉴 button on narrow screens that opens and closes the list', () => {
    const closed = render('/inbox');
    expect(closed).toMatch(/<button[^>]*aria-expanded="false"[^>]*aria-controls="mobile-nav"[^>]*>.*메뉴/);
    expect(closed).not.toContain('id="mobile-nav"');

    const open = render('/inbox', { open: true });
    expect(open).toMatch(/<button[^>]*aria-expanded="true"/);
    const list = /id="mobile-nav".*$/.exec(open)?.[0] ?? '';
    expect(links(list).map((l) => l.label)).toEqual(['대시보드', '프로젝트', '내 할 일', '회사 달력']);
    expect(links(list).find((l) => l.current)?.label).toBe('내 할 일');
  });
});

describe('PageHeader', () => {
  const head = (el: React.ReactElement) => renderToStaticMarkup(<MemoryRouter>{el}</MemoryRouter>);

  it('shows where you are (홈 › title) above a 24px bold title, a one-line description and actions', () => {
    const out = head(<PageHeader title="프로젝트 목록" description="참여한 프로젝트입니다." actions={<button type="button">새 프로젝트</button>} />);
    expect(out).toMatch(/<nav aria-label="현재 위치"><ol[^>]*><li><a[^>]*href="\/dashboard"[^>]*>홈<\/a><\/li><li[^>]*><span aria-hidden="true">›<\/span><span aria-current="page">프로젝트 목록<\/span><\/li><\/ol><\/nav>/);
    expect(out).toMatch(/<h1 class="[^"]*text-2xl font-bold text-ink[^"]*">프로젝트 목록<\/h1>/);
    expect(out).toContain('참여한 프로젝트입니다.');
    expect(out).toContain('>새 프로젝트</button>');
  });
});
