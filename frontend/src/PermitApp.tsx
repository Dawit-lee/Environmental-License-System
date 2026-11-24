import { lazy, useEffect, useMemo, useState, type MouseEvent } from 'react';
import { BrowserRouter, Link, NavLink, Navigate, Route, Routes, useLocation } from 'react-router';
import LoginPage from './pages/LoginPage';
import SignupPage from './pages/SignupPage';
import SharePage from './pages/SharePage';
import { setOnboardingDismissed } from './components/Onboarding';
import NotificationBell from './components/NotificationBell';
import { NavDrawer, OpenProjectContext, SideNav, navFor, projectTabs } from './components/SideNav';
import { focusRing } from './components/ui/cn';
import { RouteBoundary } from './components/ErrorBoundary';
import { logout, type Session } from './api/auth';
import { api, isMaster } from './api/client';
import { loadTodos, type Todo } from './api/todos';
import type { Project } from './types';
import { getCompany } from './api/extraction';

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
// dev only: the shared parts on one page; the branch is dropped from production builds
const UiGalleryPage = import.meta.env.DEV ? lazy(() => import('./pages/UiGalleryPage')) : null;

// ---------------------------------------------------------------------------
// PermitApp — shell for the permit management UI (JWT session in localStorage).
// ---------------------------------------------------------------------------

const STORAGE_KEY = 'permit_session';
// the folding sidebar is gone; drop its remembered state from old browsers
try { localStorage.removeItem('permit_sidebar_collapsed'); } catch { /* storage blocked: nothing to drop */ }

function loadSession(): Session | null {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) ?? 'null');
  } catch {
    return null;
  }
}

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
  const [menuOpen, setMenuOpen] = useState(false);
  const [company, setCompany] = useState<string | null>(null);
  const [mine, setMine] = useState<Project[]>([]);
  const [todos, setTodos] = useState<Todo[]>([]);
  const [openProject, setOpenProject] = useState<Project | null>(null);
  const user = session?.user.email ?? null;
  const nav = navFor(isMaster(), todos.length);
  // company name for the utility bar, my projects and open to-dos for the left menu; best effort, the shell works without them
  // shortcut: counts load once per sign-in (2 calls per project); add a server count endpoint if they must stay live
  useEffect(() => {
    if (!user) return;
    getCompany().then((c) => setCompany(c.name), () => setCompany(null));
    api<Project[]>('/api/projects').then((ps) => { setMine(ps); return loadTodos(ps); }).then(setTodos, () => setTodos([]));
  }, [user]);
  // shortcut: no visit history yet, so 최근 프로젝트 = my newest projects (highest id)
  const recent = useMemo(() => [...mine].sort((a, b) => b.id - a.id).slice(0, 3), [mine]);
  const navProject = openProject && {
    id: openProject.id,
    name: openProject.name,
    tabs: projectTabs(openProject, todos.filter((t) => t.kind === 'REVIEW' && t.projectId === openProject.id).length),
  };

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
        <OpenProjectContext.Provider value={setOpenProject}>
        <div className="flex min-h-screen flex-col bg-paper text-ink">
          <PageTitle nav={nav} />
          {/* Utility bar — service name, then company · user, bell, help and logout on every screen */}
          <div className="bg-inverse text-sm text-inverse-subtle">
            <div className="flex h-10 items-center justify-between gap-3 px-4 sm:px-6">
              <span className="hidden truncate sm:block">환경시설 인허가 관리 시스템</span>
              <div className="ml-auto flex shrink-0 items-center gap-1 whitespace-nowrap">
                <span className="mr-2 hidden max-w-[360px] truncate md:inline" title={user}>
                  {company && `${company} · `}{session?.user.name || user} 님
                </span>
                <NotificationBell />
                <HelpMenu />
                <button type="button" onClick={handleLogout}
                  className={`h-10 rounded px-3 text-sm text-inverse-subtle hover:bg-white/10 hover:text-white ${focusRing}`}>
                  로그아웃
                </button>
              </div>
            </div>
          </div>

          {/* Below lg: logo and a 메뉴 button that opens the left menu as a drawer */}
          <header className="flex h-14 items-center justify-between border-b border-line bg-white px-4 sm:px-6 lg:hidden">
            <Link to="/dashboard" className={`flex items-center gap-2.5 rounded ${focusRing}`}>
              <svg width="26" height="26" viewBox="0 0 28 28" aria-hidden="true" className="text-brand">
                <rect x="1" y="1" width="26" height="26" rx="4" fill="currentColor" />
                <path d="M8 15l4 4 8-9" stroke="#fff" strokeWidth="2.5" fill="none" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
              <span className="text-lg font-bold text-ink">인허가 관리</span>
            </Link>
            <button type="button" onClick={() => setMenuOpen(true)} aria-expanded={menuOpen} aria-controls="side-nav-drawer"
              className={`flex h-11 items-center gap-2 rounded border border-line px-3 text-[17px] font-bold text-ink ${focusRing}`}>
              <span aria-hidden>☰</span>메뉴
            </button>
          </header>
          <NavDrawer open={menuOpen} onClose={() => setMenuOpen(false)}>
            <SideNav items={nav} project={navProject} recent={recent} onNavigate={() => setMenuOpen(false)} />
          </NavDrawer>

          <div className="flex flex-1">
            {/* Left menu — lg and up; sticks while the page scrolls */}
            <aside className="hidden w-[248px] shrink-0 border-r border-line bg-white lg:block">
              <div className="sticky top-0 h-[calc(100vh-2.5rem)] overflow-y-auto">
                <SideNav items={nav} project={navProject} recent={recent} />
              </div>
            </aside>

          <main className="mx-auto w-full min-w-0 max-w-[1200px] flex-1 px-4 pb-14 sm:px-6 lg:px-8">
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
              {UiGalleryPage && <Route path="/dev/ui" element={<UiGalleryPage />} />}
              <Route path="*" element={<Navigate to="/dashboard" replace />} />
            </Routes>
            </RouteBoundary>
          </main>
          </div>
        </div>
        </OpenProjectContext.Provider>
      )}
    </BrowserRouter>
  );
}

/** Header "?" menu: the guide and a way back to the dismissed 시작하기 checklist. */
function HelpMenu() {
  const close = (e: MouseEvent) => e.currentTarget.closest('details')?.removeAttribute('open');
  const item = `block whitespace-nowrap px-4 py-2 text-sm text-body hover:bg-paper ${focusRing} focus-visible:-outline-offset-2`;
  return (
    <details className="relative" onKeyDown={(e) => {
      if (e.key !== 'Escape' || !e.currentTarget.open) return;
      e.currentTarget.removeAttribute('open');
      e.currentTarget.querySelector('summary')?.focus();
    }}>
      <summary aria-label="도움말" title="도움말"
        className={`flex h-10 w-10 cursor-pointer list-none items-center justify-center rounded text-base font-bold text-inverse-subtle hover:bg-white/10 hover:text-white ${focusRing} [&::-webkit-details-marker]:hidden`}>
        ?
      </summary>
      <div className="absolute right-0 z-50 mt-2 rounded border border-line bg-white py-1 shadow-lg">
        <NavLink to="/guide" onClick={close} className={item}>사용 가이드</NavLink>
        <NavLink to="/dashboard" onClick={(e) => { close(e); setOnboardingDismissed(false); }} className={item}>시작하기 체크리스트 다시 보기</NavLink>
      </div>
    </details>
  );
}
