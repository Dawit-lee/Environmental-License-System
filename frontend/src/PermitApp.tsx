import { useState } from 'react';
import { BrowserRouter, NavLink, Navigate, Route, Routes } from 'react-router';
import DashboardPage from './pages/DashboardPage';
import ProjectListPage from './pages/ProjectListPage';
import NewProjectPage from './pages/NewProjectPage';
import ProjectDetailPage from './pages/ProjectDetailPage';
import LoginPage from './pages/LoginPage';
import SignupPage from './pages/SignupPage';
import JoinRequestsPage from './pages/JoinRequestsPage';
import LegacyRedirect from './pages/LegacyRedirect';
import NotificationBell from './components/NotificationBell';
import type { Session } from './api/auth';
import { isMaster } from './api/client';

// ---------------------------------------------------------------------------
// PermitApp — shell for the permit management UI. Login is real (JWT session in
// localStorage); the routed screens still use mock data.
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
];
const ADMIN_NAV = [{ to: '/join-requests', label: '가입 요청', icon: '👥' }];

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
              <div className="flex items-center gap-3 sm:hidden">
                <span className="text-lg">🌿</span>
                <nav className="flex gap-1">
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
              <div className="ml-auto flex items-center gap-3">
                <NotificationBell />
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
              <Routes>
                <Route path="/" element={<Navigate to="/dashboard" replace />} />
                <Route path="/dashboard" element={<DashboardPage />} />
                <Route path="/projects" element={<ProjectListPage />} />
                <Route path="/projects/new" element={<NewProjectPage />} />
                <Route path="/projects/:id/:tab?" element={<ProjectDetailPage />} />
                {/* old top-level screens now live in project tabs; mail/notification links still use these */}
                <Route path="/calendar" element={<LegacyRedirect tab="schedule" />} />
                <Route path="/review" element={<LegacyRedirect tab="review" />} />
                <Route path="/laws" element={<LegacyRedirect tab="laws" />} />
                <Route path="/join-requests" element={<JoinRequestsPage />} />
                <Route path="*" element={<Navigate to="/dashboard" replace />} />
              </Routes>
            </main>
          </div>
        </div>
      )}
    </BrowserRouter>
  );
}
