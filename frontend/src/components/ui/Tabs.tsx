import type { KeyboardEvent, ReactNode } from 'react';
import { cn, focusRing } from './cn';

export interface TabItem { key: string; label: ReactNode; count?: number }

/** Index after a key press in a tablist (wraps around), or null when the key is not a tab key. */
export function nextTab(i: number, key: string, n: number): number | null {
  if (key === 'ArrowRight') return (i + 1) % n;
  if (key === 'ArrowLeft') return (i - 1 + n) % n;
  if (key === 'Home') return 0;
  if (key === 'End') return n - 1;
  return null;
}

/**
 * DESIGN.md 탭: 48px, 17px; selected = 3px blue underline + 700, others text-body 400; 1px line under the row.
 * In-page tabs only. Route tabs (NavLink) are navigation, not a tablist; they move in step 4.
 */
export function Tabs({ label, tabs, value, onChange, className }: {
  label: string;
  tabs: TabItem[];
  value: string;
  onChange: (key: string) => void;
  className?: string;
}) {
  const onKey = (e: KeyboardEvent<HTMLButtonElement>, i: number) => {
    const j = nextTab(i, e.key, tabs.length);
    if (j === null) return;
    e.preventDefault();
    onChange(tabs[j].key);
    (e.currentTarget.parentElement?.children[j] as HTMLElement | undefined)?.focus();
  };
  return (
    <div role="tablist" aria-label={label} className={cn('flex overflow-x-auto border-b border-line', className)}>
      {tabs.map((t, i) => {
        const on = t.key === value;
        return (
          <button
            key={t.key}
            type="button"
            role="tab"
            aria-selected={on}
            tabIndex={on ? 0 : -1}
            onClick={() => onChange(t.key)}
            onKeyDown={(e) => onKey(e, i)}
            className={cn(
              '-mb-px h-12 shrink-0 whitespace-nowrap border-b-[3px] px-4 text-[17px]', focusRing,
              on ? 'border-brand font-bold text-ink' : 'border-transparent text-body hover:text-ink',
            )}
          >
            {t.label}{t.count !== undefined && ` (${t.count})`}
          </button>
        );
      })}
    </div>
  );
}
