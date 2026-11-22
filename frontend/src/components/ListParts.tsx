import type { ReactNode } from 'react';
import { Button } from './ui/Button';
import { cn, focusRing } from './ui/cn';

// DESIGN.md 목록 화면 조각: 걸린 필터 칩, 빈 상태, 불러오는 중 뼈대. Candidates for ui/ once a third screen needs them.

export interface ActiveFilter { key: string; label: string; clear: () => void }

/** Applied filters as chips, each removable, plus 필터 모두 지우기. Renders nothing when no filter is on. */
export function FilterChips({ filters, onClearAll }: { filters: ActiveFilter[]; onClearAll: () => void }) {
  if (filters.length === 0) return null;
  return (
    <ul aria-label="걸린 필터" className="mt-3 flex flex-wrap items-center gap-2">
      {filters.map((f) => (
        <li key={f.key}>
          <button
            type="button"
            onClick={f.clear}
            aria-label={`${f.label} 필터 지우기`}
            className={cn('inline-flex h-8 items-center gap-1.5 rounded-sm border border-line bg-paper px-2 text-[15px] text-ink hover:border-input', focusRing)}
          >
            {f.label}<span aria-hidden className="text-body">✕</span>
          </button>
        </li>
      ))}
      <li><Button variant="text" size="sm" onClick={onClearAll}>필터 모두 지우기</Button></li>
    </ul>
  );
}

/** Centered title + one-line reason + one action, in place of the table. No illustration. */
export function EmptyBlock({ title, description, action }: { title: string; description?: string; action?: ReactNode }) {
  return (
    <div className="rounded border border-line bg-white px-6 py-12 text-center">
      <p className="break-keep text-[17px] font-bold text-ink">{title}</p>
      {description && <p className="mt-1 break-keep text-[15px] text-body">{description}</p>}
      {action && <div className="mt-4 flex justify-center">{action}</div>}
    </div>
  );
}

/** Row-shaped placeholders while the list loads (no spinner). */
export function SkeletonRows({ cols, rows = 5 }: { cols: number; rows?: number }) {
  return (
    <tbody aria-busy="true" aria-label="불러오는 중">
      {Array.from({ length: rows }, (_, r) => (
        <tr key={r}>
          {Array.from({ length: cols }, (_, c) => (
            <td key={c}><span className="block h-3 w-3/4 animate-pulse rounded-sm bg-line-soft" /></td>
          ))}
        </tr>
      ))}
    </tbody>
  );
}
