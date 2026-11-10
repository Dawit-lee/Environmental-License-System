import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { getNotifications, markAllRead, markRead, type AppNotification } from '../api/notifications';
import { getNewLawImpactCount } from '../api/lawImpacts';

// Header bell: unread count + dropdown of the newest notifications. Polls every minute.
export default function NotificationBell() {
  const [items, setItems] = useState<AppNotification[]>([]);
  const [lawNew, setLawNew] = useState(0); // NEW law impacts on projects I am 책임자 of
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
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

  const unread = items.filter((n) => !n.readAt).length + lawNew;

  const openItem = async (n: AppNotification) => {
    setOpen(false);
    if (!n.readAt) await markRead(n.id).then(load, () => {});
    if (n.link) navigate(n.link);
  };

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-label={`알림 ${unread}건 읽지 않음`}
        aria-expanded={open}
        className="relative rounded-lg px-2.5 py-1.5 text-lg transition hover:bg-[#F8F7F2] focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/30"
      >
        <span aria-hidden>🔔</span>
        {unread > 0 && (
          <span className="absolute -right-0.5 -top-0.5 min-w-[18px] rounded-full bg-[#991B1B] px-1 text-center text-[11px] font-semibold leading-[18px] text-white">
            {unread > 99 ? '99+' : unread}
          </span>
        )}
      </button>

      {open && (
        <div className="absolute right-0 top-full z-30 mt-2 w-80 rounded-xl border border-[#E5E3DC] bg-white shadow-lg">
          <div className="flex items-center justify-between border-b border-[#E5E3DC] px-4 py-2.5">
            <span className="text-sm font-semibold text-gray-900">알림</span>
            {unread > lawNew && (
              <button
                type="button"
                onClick={() => markAllRead().then(load, () => {})}
                className="text-xs font-medium text-[#1F4E79] hover:underline"
              >
                모두 읽음
              </button>
            )}
          </div>
          {lawNew > 0 && (
            <button
              type="button"
              onClick={() => { setOpen(false); navigate('/inbox?kind=LAW'); }}
              className="flex w-full gap-2 border-b border-[#E5E3DC] px-4 py-2.5 text-left text-sm font-medium text-gray-800 transition hover:bg-[#F8F7F2]"
            >
              <span aria-hidden>⚖️</span>
              <span>법령 개정 미확인 {lawNew}건</span>
            </button>
          )}
          {items.length === 0 && lawNew === 0 ? (
            <p className="px-4 py-6 text-center text-sm text-gray-400">알림이 없습니다.</p>
          ) : (
            <ul className="max-h-96 divide-y divide-[#E5E3DC] overflow-y-auto">
              {items.map((n) => (
                <li key={n.id}>
                  <button
                    type="button"
                    onClick={() => openItem(n)}
                    className={`flex w-full gap-2 px-4 py-2.5 text-left text-sm transition hover:bg-[#F8F7F2] ${
                      n.readAt ? 'text-gray-400' : 'text-gray-800'
                    }`}
                  >
                    {n.offsetDays != null && (
                      <span
                        className={`shrink-0 font-semibold ${
                          n.readAt ? '' : n.offsetDays <= 7 ? 'text-[#991B1B]' : 'text-[#B45309]'
                        }`}
                      >
                        D-{n.offsetDays}
                      </span>
                    )}
                    <span>{n.message}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
