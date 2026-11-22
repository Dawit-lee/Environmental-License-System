import type { ReactNode } from 'react';
import type { Tone } from './Badge';
import { cn } from './cn';

const NUMBER: Partial<Record<Tone, string>> = { danger: 'text-danger', warning: 'text-warn', success: 'text-ok', info: 'text-information' };

export interface SummaryItem {
  label: ReactNode;
  value: ReactNode;
  unit?: ReactNode;
  /** colour the number only when it means a state (기한 지남 3건) */
  tone?: Tone;
  /** a cell that needs attention now (기한 경과 1건 이상): light red ground */
  alert?: boolean;
}

/** DESIGN.md 요약 숫자: one bordered strip, cells split by 1px lines, label (15px) above number (24px 700). */
export function SummaryStrip({ items, className }: { items: SummaryItem[]; className?: string }) {
  return (
    <dl className={cn('flex flex-wrap divide-x divide-line rounded border border-line bg-white', className)}>
      {items.map((it, i) => (
        <div key={i} className={cn('min-w-0 flex-1 basis-40 px-5 py-4', it.alert && 'bg-danger-bg')}>
          <dt className={cn('truncate text-[15px]', it.alert ? 'text-danger' : 'text-body')}>{it.label}</dt>
          <dd className="mt-1 tabular-nums">
            <span className={cn('text-2xl font-bold', (it.tone && NUMBER[it.tone]) || 'text-ink')}>{it.value}</span>
            {it.unit && <span className="ml-1 text-[15px] text-body">{it.unit}</span>}
          </dd>
        </div>
      ))}
    </dl>
  );
}
