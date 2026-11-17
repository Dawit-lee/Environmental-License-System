import { lazy, useState, type MouseEvent } from 'react';
import { BrowserRouter, NavLink, Navigate, Route, Routes } from 'react-router';
import LoginPage from './pages/LoginPage';
import SignupPage from './pages/SignupPage';
import SharePage from './pages/SharePage';
import { setOnboardingDismissed } from './components/Onboarding';
import NotificationBell from './components/NotificationBell';
import { RouteBoundary } from './components/ErrorBoundary';
import type { Session } from './api/auth';
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

export default function PermitApp() {
  const [session, setSession] = useState<Session | null>(loadSession);
  const user = session?.user.email ?? null;
  const nav = isMaster() ? [...NAV, ...ADMIN_NAV] : NAV;

  const handleLogin = (s: Session) => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(s));
    setSession(s);
  };

  const handleLogout = () => {
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
          className="flex min-h-screen bg-[#F8F7F2] text-gray-900"
          style={{ fontFamily: 'system-ui, sans-serif' }}
        >
          {/* Sidebar */}
          <aside className="hidden w-60 shrink-0 flex-col border-r border-[#E5E3DC] bg-white sm:flex">
            <div className="flex h-16 items-center gap-2 border-b border-[#E5E3DC] px-5">
              <span className="text-xl">🌿</span>
              <span className="text-sm font-bold text-[#1F4E79]">환경시설 인허가 관리</span>
            </div>
            <nav className="flex flex-col gap-1 p-3">
              {nav.map((item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  className={({ isActive }) =>
                    `flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/30 ${
                      isActive ? 'bg-[#1F4E79] text-white' : 'text-gray-600 hover:bg-[#F8F7F2]'
                    }`
                  }
                >
                  <span aria-hidden>{item.icon}</span>
                  {item.label}
                </NavLink>
              ))}
            </nav>
          </aside>

          {/* Main */}
          <div className="flex min-w-0 flex-1 flex-col">
            {/* Top header — logout lives at the top-right on every screen size */}
            <header className="flex h-16 items-center justify-between gap-3 border-b border-[#E5E3DC] bg-white px-4 sm:px-6">
              {/* Left: mobile-only logo + nav (desktop nav is in the sidebar) */}
              <div className="flex min-w-0 items-center gap-3 sm:hidden">
                <span className="text-lg">🌿</span>
                <nav className="flex gap-1 overflow-x-auto whitespace-nowrap">
                  {nav.map((item) => (
                    <NavLink
                      key={item.to}
                      to={item.to}
                      className={({ isActive }) =>
                        `rounded-lg px-2.5 py-1.5 text-xs font-medium transition ${
                          isActive ? 'bg-[#1F4E79] text-white' : 'text-gray-600'
                        }`
                      }
                    >
                      {item.label}
                    </NavLink>
                  ))}
                </nav>
              </div>

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
                  className="rounded-lg border border-[#E5E3DC] px-3 py-1.5 text-sm font-medium text-gray-600 transition hover:bg-[#F8F7F2] hover:text-[#991B1B] focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/20"
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
  const item = 'block whitespace-nowrap px-4 py-2 text-sm text-gray-700 hover:bg-[#F8F7F2]';
  return (
    <details className="relative">
      <summary aria-label="도움말" title="도움말"
        className="flex h-8 w-8 cursor-pointer list-none items-center justify-center rounded-full border border-[#E5E3DC] text-sm font-semibold text-gray-600 hover:bg-[#F8F7F2] focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/20 [&::-webkit-details-marker]:hidden">
        ?
      </summary>
      <div className="absolute right-0 z-50 mt-2 rounded-lg border border-[#E5E3DC] bg-white py-1 shadow-lg">
        <NavLink to="/guide" onClick={close} className={item}>사용 가이드</NavLink>
        <NavLink to="/dashboard" onClick={(e) => { close(e); setOnboardingDismissed(false); }} className={item}>시작하기 체크리스트 다시 보기</NavLink>
      </div>
    </details>
  );
}
