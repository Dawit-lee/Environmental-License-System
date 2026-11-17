import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { getNotifications, markAllRead, markRead, type AppNotification } from '../api/notifications';
import { getNewLawImpactCount } from '../api/lawImpacts';
import { differenceInCalendarDays, parseISO } from 'date-fns';
import { useEscape } from './useEscape';

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
        className="relative rounded-lg px-2.5 py-1.5 text-lg transition hover:bg-paper focus:outline-none focus:ring-2 focus:ring-brand/30"
      >
        <span aria-hidden>🔔</span>
        {unread > 0 && (
          <span className="absolute -right-0.5 -top-0.5 min-w-[18px] rounded-full bg-danger px-1 text-center text-[11px] font-semibold leading-[18px] text-white">
            {unread > 99 ? '99+' : unread}
          </span>
        )}
      </button>

      {open && (
        <div className="absolute right-0 top-full z-30 mt-2 w-80 max-w-[calc(100vw-2rem)] overflow-hidden whitespace-normal rounded-xl border border-line bg-white shadow-lg">
          <div className="flex items-center justify-between border-b border-line px-4 py-2.5">
            <span className="text-sm font-semibold text-gray-900">알림</span>
            {unread > lawNew && (
              <button
                type="button"
                onClick={() => markAllRead().then(load, () => {})}
                className="text-xs font-medium text-brand hover:underline"
              >
                모두 읽음
              </button>
            )}
          </div>
          {lawNew > 0 && (
            <button
              type="button"
              onClick={() => { setOpen(false); navigate('/inbox?kind=LAW'); }}
              className="flex w-full gap-2 border-b border-line px-4 py-2.5 text-left text-sm font-medium text-gray-800 transition hover:bg-paper"
            >
              <span aria-hidden>⚖️</span>
              <span>법령 개정 미확인 {lawNew}건</span>
            </button>
          )}
          {items.length === 0 && lawNew === 0 ? (
            <p className="px-4 py-6 text-center text-sm text-gray-400">알림이 없습니다.</p>
          ) : (
            <ul className="max-h-96 divide-y divide-line overflow-y-auto">
              {items.map((n) => { const due = dueLabel(n); return (
                <li key={n.id}>
                  <button
                    type="button"
                    onClick={() => openItem(n)}
                    className={`block w-full px-4 py-2.5 text-left text-sm transition hover:bg-paper ${
                      n.readAt ? 'text-gray-500' : 'text-gray-800'
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
