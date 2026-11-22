import type { HTMLAttributes, ReactNode } from 'react';
import { cn } from './cn';

// DESIGN.md 안내 상자: subtle bg + 1px border + icon on the left, 4px radius, 16px padding.
const TONE = {
  info: { box: 'bg-brand-subtle border-brand-border text-ink', icon: 'bg-brand', glyph: 'i' },
  success: { box: 'bg-ok-bg border-ok-border text-ok', icon: 'bg-ok', glyph: '✓' },
  warning: { box: 'bg-warn-bg border-warn-border text-warn', icon: 'bg-warn', glyph: '!' },
  danger: { box: 'bg-danger-bg border-danger-border text-danger', icon: 'bg-danger', glyph: '!' },
} as const;

export function Alert({ tone = 'info', title, className, children, ...rest }: Omit<HTMLAttributes<HTMLDivElement>, 'title'> & {
  tone?: keyof typeof TONE;
  title?: ReactNode;
}) {
  const t = TONE[tone];
  return (
    // errors interrupt the screen reader; everything else waits its turn
    <div role={tone === 'danger' ? 'alert' : 'status'} className={cn('flex items-start gap-3 rounded border p-4 text-[15px] leading-normal', t.box, className)} {...rest}>
      <span aria-hidden="true" className={cn('mt-0.5 flex size-5 shrink-0 items-center justify-center rounded-full text-xs font-bold text-white', t.icon)}>
        {t.glyph}
      </span>
      <div className="min-w-0 flex-1">
        {title && <p className="font-bold">{title}</p>}
        {children}
      </div>
    </div>
  );
}
