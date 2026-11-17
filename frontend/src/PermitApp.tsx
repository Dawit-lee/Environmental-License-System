import { lazy, useEffect, useState, type MouseEvent } from 'react';
import { BrowserRouter, NavLink, Navigate, Route, Routes, useLocation } from 'react-router';
import LoginPage from './pages/LoginPage';
import SignupPage from './pages/SignupPage';
import SharePage from './pages/SharePage';
import { setOnboardingDismissed } from './components/Onboarding';
import NotificationBell from './components/NotificationBell';
import { RouteBoundary } from './components/ErrorBoundary';
import { logout, type Session } from './api/auth';
import { isMaster } from './api/client';

// routed screens load on demand to keep the first chunk small
const DashboardPage = lazy(() => import('./pages/DashboardPage'));
const ProjectListPage = lazy(() => import('./pages/ProjectListPage'));
const NewProjectPage = lazy(() => import('./pages/NewProjectPage'));
const ProjectDetailPage = lazy(() => import('./pages/ProjectDetailPage'));
const JoinRequestsPage = lazy(() => import('./pages/JoinRequestsPage'));
const OrgPage = lazy(() => import('./pages/OrgPage'));
const InboxPage = lazy(() => import('./pages/InboxPage'));
const CompanyCalendarPage = lazy(() => import('./pages/CompanyCalendarPage'));
const LegacyRedirect = lazy(() => import('./pages/LegacyRedirect'));
const GuidePage = lazy(() => import('./pages/GuidePage'));

// ---------------------------------------------------------------------------
// PermitApp — shell for the permit management UI (JWT session in localStorage).
// ---------------------------------------------------------------------------

const STORAGE_KEY = 'permit_session';

function loadSession(): Session | null {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) ?? 'null');
  } catch {
    return null;
  }
}

const NAV = [
  { to: '/dashboard', label: '대시보드', icon: '📊' },
  { to: '/projects', label: '프로젝트', icon: '📁' },
  { to: '/inbox', label: '내 할 일', icon: '✅' },
  { to: '/calendar', label: '회사 달력', icon: '📅' },
];
const ADMIN_NAV = [
  { to: '/join-requests', label: '가입 요청', icon: '👥' },
  { to: '/org', label: '조직 관리', icon: '🏢' },
];

const SIDEBAR_KEY = 'permit_sidebar_collapsed';

/** Browser tab title: "화면명 · 환경시설 인허가 관리". */
function PageTitle({ nav }: { nav: { to: string; label: string }[] }) {
  const { pathname } = useLocation();
  useEffect(() => {
    const label = pathname === '/guide' ? '사용 가이드' : nav.find((n) => pathname.startsWith(n.to))?.label;
    document.title = label ? `${label} · 환경시설 인허가 관리` : '환경시설 인허가 관리';
  }, [pathname, nav]);
  return null;
}

export default function PermitApp() {
  const [session, setSession] = useState<Session | null>(loadSession);
  const [collapsed, setCollapsed] = useState(() => { try { return localStorage.getItem(SIDEBAR_KEY) === '1'; } catch { return false; } });
  const toggleSidebar = () => setCollapsed((c) => {
    try { localStorage.setItem(SIDEBAR_KEY, c ? '0' : '1'); } catch { /* private mode: not remembered */ }
    return !c;
  });
  const user = session?.user.email ?? null;
  const nav = isMaster() ? [...NAV, ...ADMIN_NAV] : NAV;

  const handleLogin = (s: Session) => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(s));
    setSession(s);
  };

  const handleLogout = () => {
    if (session) logout(session.accessToken);
    localStorage.removeItem(STORAGE_KEY);
    setSession(null);
  };

  // partner share page: no login, no app shell, same for signed-in users
  if (window.location.pathname.startsWith('/share/')) {
    return <BrowserRouter><Routes><Route path="/share/:token" element={<SharePage />} /></Routes></BrowserRouter>;
  }

  return (
    <BrowserRouter>
      {!user ? (
        <Routes>
          <Route path="/login" element={<LoginPage onLogin={handleLogin} />} />
          <Route path="/signup" element={<SignupPage onLogin={handleLogin} />} />
          <Route path="/join/:code" element={<SignupPage onLogin={handleLogin} />} />
          <Route path="*" element={<Navigate to="/login" replace />} />
        </Routes>
      ) : (
        <div
          className="flex min-h-screen bg-paper text-gray-900"
          style={{ fontFamily: 'system-ui, sans-serif' }}
        >
          <PageTitle nav={nav} />
          {/* Sidebar — folds to icons so a 1024 laptop keeps the width for tables */}
          <aside className={`hidden shrink-0 flex-col border-r border-line bg-white sm:flex ${collapsed ? 'w-16' : 'w-60'}`}>
            <div className={`flex h-16 items-center gap-2 border-b border-line ${collapsed ? 'justify-center' : 'px-5'}`}>
              <span className="text-xl" aria-hidden>🌿</span>
              {!collapsed && <span className="text-sm font-bold text-brand">환경시설 인허가 관리</span>}
            </div>
            <nav className="flex flex-1 flex-col gap-1 p-3">
              {nav.map((item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  title={collapsed ? item.label : undefined}
                  className={({ isActive }) =>
                    `flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition focus:outline-none focus:ring-2 focus:ring-brand/30 ${
                      isActive ? 'bg-brand text-white' : 'text-gray-600 hover:bg-paper'
                    }`
                  }
                >
                  <span aria-hidden>{item.icon}</span>
                  {collapsed ? <span className="sr-only">{item.label}</span> : item.label}
                </NavLink>
              ))}
              <button type="button" onClick={toggleSidebar} aria-expanded={!collapsed}
                title={collapsed ? '메뉴 펼치기' : '메뉴 접기'} aria-label={collapsed ? '메뉴 펼치기' : '메뉴 접기'}
                className="mt-auto flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm text-gray-500 hover:bg-paper focus:outline-none focus:ring-2 focus:ring-brand/30">
                <span aria-hidden>{collapsed ? '»' : '«'}</span>
                {!collapsed && '접기'}
              </button>
            </nav>
          </aside>

          {/* Main */}
          <div className="flex min-w-0 flex-1 flex-col">
            {/* Top header — logout lives at the top-right on every screen size */}
            <header className="flex h-16 items-center justify-between gap-3 border-b border-line bg-white px-4 sm:px-6">
              {/* Left: mobile-only menu (desktop nav is in the sidebar) */}
              <details className="relative sm:hidden">
                <summary aria-label="메뉴"
                  className="flex h-9 cursor-pointer list-none items-center gap-2 rounded-lg border border-line px-3 text-sm font-medium text-gray-700 [&::-webkit-details-marker]:hidden">
                  <span aria-hidden>☰</span> 메뉴
                </summary>
                <nav className="absolute left-0 z-50 mt-2 w-48 rounded-lg border border-line bg-white py-1 shadow-lg">
                  {nav.map((item) => (
                    <NavLink
                      key={item.to}
                      to={item.to}
                      onClick={(e) => e.currentTarget.closest('details')?.removeAttribute('open')}
                      className={({ isActive }) =>
                        `flex items-center gap-3 px-4 py-2.5 text-sm ${isActive ? 'font-semibold text-brand' : 'text-gray-700'}`
                      }
                    >
                      <span aria-hidden>{item.icon}</span>
                      {item.label}
                    </NavLink>
                  ))}
                </nav>
              </details>

              {/* Right: bell + user + logout */}
              <div className="ml-auto flex shrink-0 items-center gap-3 whitespace-nowrap">
                <NotificationBell />
                <HelpMenu />
                <span className="hidden max-w-[180px] truncate text-sm text-gray-500 sm:inline" title={user}>
                  {user}
                </span>
                <button
                  type="button"
                  onClick={handleLogout}
                  className="rounded-lg border border-line px-3 py-1.5 text-sm font-medium text-gray-600 transition hover:bg-paper hover:text-danger focus:outline-none focus:ring-2 focus:ring-brand/20"
                >
                  로그아웃
                </button>
              </div>
            </header>

            <main className="flex-1">
              <RouteBoundary>
              <Routes>
                <Route path="/" element={<Navigate to="/dashboard" replace />} />
                <Route path="/dashboard" element={<DashboardPage />} />
                <Route path="/projects" element={<ProjectListPage />} />
                <Route path="/projects/new" element={<NewProjectPage />} />
                <Route path="/projects/:id/:tab?" element={<ProjectDetailPage />} />
                {/* old top-level screens now live in project tabs; mail/notification links still use these */}
                <Route path="/inbox" element={<InboxPage />} />
                <Route path="/calendar" element={<CompanyCalendarPage />} />
                <Route path="/review" element={<Navigate to="/inbox?kind=REVIEW" replace />} />
                <Route path="/laws" element={<LegacyRedirect />} />
                <Route path="/join-requests" element={<JoinRequestsPage />} />
                <Route path="/org" element={<OrgPage />} />
                <Route path="/guide" element={<GuidePage />} />
                <Route path="*" element={<Navigate to="/dashboard" replace />} />
              </Routes>
              </RouteBoundary>
            </main>
          </div>
        </div>
      )}
    </BrowserRouter>
  );
}

/** Header "?" menu: the guide and a way back to the dismissed 시작하기 checklist. */
function HelpMenu() {
  const close = (e: MouseEvent) => e.currentTarget.closest('details')?.removeAttribute('open');
  const item = 'block whitespace-nowrap px-4 py-2 text-sm text-gray-700 hover:bg-paper';
  return (
    <details className="relative">
      <summary aria-label="도움말" title="도움말"
        className="flex h-8 w-8 cursor-pointer list-none items-center justify-center rounded-full border border-line text-sm font-semibold text-gray-600 hover:bg-paper focus:outline-none focus:ring-2 focus:ring-brand/20 [&::-webkit-details-marker]:hidden">
        ?
      </summary>
      <div className="absolute right-0 z-50 mt-2 rounded-lg border border-line bg-white py-1 shadow-lg">
        <NavLink to="/guide" onClick={close} className={item}>사용 가이드</NavLink>
        <NavLink to="/dashboard" onClick={(e) => { close(e); setOnboardingDismissed(false); }} className={item}>시작하기 체크리스트 다시 보기</NavLink>
      </div>
    </details>
  );
}
