import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { PageHeader } from './ui/PageHeader';

// react-router's links warn that layout effects do nothing on the server; the markup is still right
beforeAll(() => {
  const error = console.error;
  vi.spyOn(console, 'error').mockImplementation((...a) => { if (!String(a[0]).includes('useLayoutEffect')) error(...a); });
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

  it('puts the trail between 홈 and the title (홈 › 프로젝트 › 프로젝트명 › 탭명)', () => {
    const out = head(<PageHeader title="AI 검토" trail={[{ to: '/projects', label: '프로젝트' }, { to: '/projects/7/overview', label: '새벽시 매립가스' }]} />);
    const crumbs = [...out.matchAll(/<(?:a|span)[^>]*>([^<›]+)<\/(?:a|span)>/g)].map((m) => m[1]);
    expect(crumbs).toEqual(['홈', '프로젝트', '새벽시 매립가스', 'AI 검토']);
    expect(out).toContain('href="/projects/7/overview"');
  });
});
