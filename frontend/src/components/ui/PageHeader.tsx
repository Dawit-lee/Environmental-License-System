import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { cn, focusRing } from './cn';

/** DESIGN.md 페이지 머리: 위치 표시(홈 › 제목) → 제목(24px, 700) → 한 줄 설명; 주요 작업은 제목 오른쪽 끝. */
export function PageHeader({ title, description, actions, className }: {
  title: string;
  description?: ReactNode;
  actions?: ReactNode;
  className?: string;
}) {
  return (
    <div className={className}>
      <nav aria-label="현재 위치"><ol className="flex flex-wrap items-center gap-1.5 text-sm text-mute">
        <li><Link to="/dashboard" className={cn('rounded hover:text-ink hover:underline', focusRing)}>홈</Link></li>
        <li className="flex items-center gap-1.5"><span aria-hidden="true">›</span><span aria-current="page">{title}</span></li>
      </ol></nav>
      <div className="mt-2 flex flex-wrap items-center justify-between gap-3">
        <h1 className="break-keep text-2xl font-bold text-ink">{title}</h1>
        {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
      </div>
      {description && <p className="mt-1 break-keep text-base text-body">{description}</p>}
    </div>
  );
}
