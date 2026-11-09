import { useState } from 'react';
import { BrowserRouter, NavLink, Navigate, Route, Routes } from 'react-router';
import DashboardPage from './pages/DashboardPage';
import ProjectListPage from './pages/ProjectListPage';
import NewProjectPage from './pages/NewProjectPage';
import ProjectDetailPage from './pages/ProjectDetailPage';
import CalendarPage from './pages/CalendarPage';
import LoginPage from './pages/LoginPage';

// ---------------------------------------------------------------------------
// PermitApp — standalone, mock-data-only shell for the permit management UI.
// Mock auth (localStorage) gates the routed screens. No real API.
// ---------------------------------------------------------------------------

const STORAGE_KEY = 'permit_user';

const NAV = [
  { to: '/dashboard', label: '대시보드', icon: '📊' },
  { to: '/projects', label: '프로젝트', icon: '📁' },
  { to: '/calendar', label: '캘린더', icon: '🗓' },
];

export default function PermitApp() {
  const [user, setUser] = useState<string | null>(() => localStorage.getItem(STORAGE_KEY));

  const handleLogin = (email: string) => {
    localStorage.setItem(STORAGE_KEY, email);
    setUser(email);
  };

  const handleLogout = () => {
    localStorage.removeItem(STORAGE_KEY);
    setUser(null);
  };

  return (
    <BrowserRouter>
      {!user ? (
        <Routes>
          <Route path="/login" element={<LoginPage onLogin={handleLogin} />} />
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
              {NAV.map((item) => (
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
                  {NAV.map((item) => (
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

              {/* Right: user + logout */}
              <div className="ml-auto flex items-center gap-3">
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
                <Route path="/projects/:id" element={<ProjectDetailPage />} />
                <Route path="/calendar" element={<CalendarPage />} />
                <Route path="*" element={<Navigate to="/dashboard" replace />} />
              </Routes>
            </main>
          </div>
        </div>
      )}
    </BrowserRouter>
  );
}
