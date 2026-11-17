import { Component, Suspense, useEffect, type ReactNode } from 'react';
import { useLocation } from 'react-router';

/** Catches render errors and failed lazy chunks (stale tab after a deploy) so one screen fails, not the whole app. */
class ErrorBoundary extends Component<{ resetKey: string; children: ReactNode }, { error: Error | null }> {
  state = { error: null as Error | null };

  static getDerivedStateFromError(error: Error) {
    return { error };
  }

  componentDidCatch(error: Error) {
    console.error(error);
  }

  componentDidUpdate(prev: { resetKey: string }) {
    if (this.state.error && prev.resetKey !== this.props.resetKey) this.setState({ error: null });
  }

  render() {
    if (!this.state.error) return this.props.children;
    return (
      <div role="alert" className="mx-auto max-w-md p-8 text-center">
        <h1 className="text-lg font-bold text-gray-800">화면을 불러오지 못했습니다</h1>
        <p className="mt-2 text-sm text-gray-600">새 버전이 배포됐거나 잠시 오류가 났을 수 있습니다.</p>
        <button
          type="button"
          onClick={() => window.location.reload()}
          className="mt-4 rounded-lg bg-[#1F4E79] px-4 py-2 text-sm font-medium text-white hover:bg-[#173a5c] focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/30"
        >
          새로고침
        </button>
      </div>
    );
  }
}

const TITLES: [string, string][] = [
  ['/dashboard', '대시보드'], ['/projects/new', '새 프로젝트'], ['/projects', '프로젝트'], ['/inbox', '내 할 일'],
  ['/calendar', '회사 달력'], ['/join-requests', '가입 요청'], ['/org', '조직 관리'], ['/guide', '사용 가이드'],
];

/** Error + loading boundary for the routed screens; also sets the Korean tab title per screen. */
export function RouteBoundary({ children }: { children: ReactNode }) {
  const { pathname } = useLocation();
  useEffect(() => {
    const t = TITLES.find(([p]) => pathname.startsWith(p))?.[1];
    document.title = t ? `${t} · 환경시설 인허가 관리` : '환경시설 인허가 관리';
  }, [pathname]);
  return (
    <ErrorBoundary resetKey={pathname}>
      <Suspense fallback={<p className="p-8 text-sm text-gray-500">불러오는 중…</p>}>{children}</Suspense>
    </ErrorBoundary>
  );
}
