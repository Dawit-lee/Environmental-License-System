import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { getNotifications, markAllRead, markRead, type AppNotification } from '../api/notifications';
import { getNewLawImpactCount } from '../api/lawImpacts';
import { differenceInCalendarDays, parseISO } from 'date-fns';
import { useEscape } from './useEscape';
import { focusRing } from './ui/cn';

/** D-day counted now, not when the notice was made (yesterday's D-1 is today's D-DAY). */
function dueLabel(n: AppNotification): { text: string; days: number } | null {
  if (!n.dueDate) return n.offsetDays != null && n.offsetDays < 0 ? { text: '기한 경과', days: -1 } : null;
  const days = differenceInCalendarDays(parseISO(n.dueDate), new Date());
  return { text: days > 0 ? `D-${days}` : days === 0 ? 'D-DAY' : `D+${-days} 지남`, days };
}

// Header bell: unread count + dropdown of the newest notifications. Polls every minute.
export default function NotificationBell() {
  const [items, setItems] = useState<AppNotification[]>([]);
  const [lawNew, setLawNew] = useState(0); // NEW law impacts on projects I am 책임자 of
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const bell = useRef<HTMLButtonElement>(null);
  const navigate = useNavigate();

  const load = () => {
    getNotifications().then(setItems, () => {});
    getNewLawImpactCount().then((r) => setLawNew(r.count), () => {});
  };
  useEffect(() => {
    load();
    const t = setInterval(load, 60_000);
    return () => clearInterval(t);
  }, []);

  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [open]);

  useEscape(() => { setOpen(false); bell.current?.focus(); }, open);

  const unread = items.filter((n) => !n.readAt).length + lawNew;

  const openItem = async (n: AppNotification) => {
    setOpen(false);
    if (!n.readAt) await markRead(n.id).then(load, () => {});
    if (n.link) navigate(n.link);
  };

  return (
    <div ref={ref} className="relative">
      <button
        ref={bell}
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-label={`알림 ${unread}건 읽지 않음`}
        aria-expanded={open}
        className={`relative flex h-10 w-10 items-center justify-center rounded text-inverse-subtle transition hover:bg-white/10 hover:text-white ${focusRing}`}
      >
        <svg width="18" height="18" viewBox="0 0 18 18" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round">
          <path d="M4 13V8a5 5 0 0 1 10 0v5l1.5 1.5h-13zM7.5 16.5h3" />
        </svg>
        {unread > 0 && (
          <span className="absolute -right-0.5 -top-0.5 min-w-[18px] rounded-full bg-danger px-1 text-center text-[11px] font-semibold leading-[18px] text-white">
            {unread > 99 ? '99+' : unread}
          </span>
        )}
      </button>

      {open && (
        <div className="fixed inset-x-4 top-12 z-30 overflow-hidden sm:absolute sm:inset-x-auto sm:right-0 sm:top-full sm:mt-2 sm:w-80 whitespace-normal rounded border border-line bg-white shadow-lg">
          <div className="flex items-center justify-between border-b border-line px-4 py-2.5">
            <span className="text-sm font-semibold text-ink">알림</span>
            {unread > lawNew && (
              <button
                type="button"
                onClick={() => markAllRead().then(load, () => {})}
                className="rounded text-xs font-medium text-brand-text hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand"
              >
                모두 읽음
              </button>
            )}
          </div>
          {lawNew > 0 && (
            <button
              type="button"
              onClick={() => { setOpen(false); navigate('/inbox?kind=LAW'); }}
              className="flex w-full gap-2 border-b border-line px-4 py-2.5 text-left text-sm font-medium text-ink transition hover:bg-paper focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-brand"
            >
              <span>법령 개정 미확인 {lawNew}건</span>
            </button>
          )}
          {items.length === 0 && lawNew === 0 ? (
            <p className="px-4 py-6 text-center text-sm text-mute">알림이 없습니다.</p>
          ) : (
            <ul className="max-h-96 divide-y divide-line overflow-y-auto">
              {items.map((n) => { const due = dueLabel(n); return (
                <li key={n.id}>
                  <button
                    type="button"
                    onClick={() => openItem(n)}
                    className={`block w-full px-4 py-2.5 text-left text-sm transition hover:bg-paper focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-brand ${
                      n.readAt ? 'text-mute' : 'text-ink'
                    }`}
                  >
                    {due && (
                      <span className={`block text-xs font-semibold ${n.readAt ? '' : due.days <= 7 ? 'text-danger' : 'text-warn'}`}>
                        {due.text}
                      </span>
                    )}
                    {/* the server writes "기한 경과 · …"; the label above already says it */}
                    <span className="block break-words">
                      {!n.readAt && <><span aria-hidden className="mr-1.5 inline-block h-1.5 w-1.5 rounded-full bg-brand align-middle" /><span className="sr-only">읽지 않음 · </span></>}
                      {n.message.replace(/^기한 경과 · /, '')}</span>
                  </button>
                </li>
              ); })}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
