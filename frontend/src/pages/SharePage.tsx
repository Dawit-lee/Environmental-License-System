import { useEffect, useState } from 'react';
import { useParams } from 'react-router';
import { getShared, webcal, type SharedSchedule } from '../api/share';
import ReadOnlySchedule from '../components/ReadOnlySchedule';

// ---------------------------------------------------------------------------
// SharePage — /share/:token, no login (설계 §6.3). Read-only schedule for a partner, plus the .ics subscription.
// ---------------------------------------------------------------------------

export default function SharePage() {
  const { token = '' } = useParams();
  const [data, setData] = useState<SharedSchedule | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const robots = document.createElement('meta'); // the server also sends X-Robots-Tag on the data
    robots.name = 'robots';
    robots.content = 'noindex';
    document.head.appendChild(robots);
    getShared(token).then(setData, (e: Error) => setError(e.message));
    return () => robots.remove();
  }, [token]);

  return (
    <div className="min-h-screen bg-paper px-4 py-8" style={{ fontFamily: 'system-ui, sans-serif' }}>
      <div className="mx-auto max-w-5xl space-y-3">
        {error && <p role="alert" className="rounded bg-red-50 px-3 py-2 text-sm text-danger">{error}</p>}
        {data === null && (
          <p className="rounded border border-line bg-white px-6 py-12 text-center text-sm text-body">
            링크가 만료되었거나 폐기되었습니다. 일정을 보내 준 담당자에게 새 링크를 요청하세요.
          </p>
        )}
        {data && (
          <>
            <div className="flex flex-wrap items-center gap-2 text-xs">
              <span className="text-mute">읽기 전용 · {data.expiresAt.slice(0, 10)}까지 열림</span>
              <a href={webcal(`/share/${token}.ics`)} className="ml-auto rounded border border-line bg-white px-3 py-1.5 font-medium text-body hover:border-brand">달력 구독(.ics)</a>
              <button type="button" onClick={() => window.print()} className="rounded border border-line bg-white px-3 py-1.5 font-medium text-body hover:border-brand">인쇄 · PDF</button>
            </div>
            <ReadOnlySchedule title={`${data.projectName} 일정`} items={data.items} events={data.events} />
          </>
        )}
      </div>
    </div>
  );
}
