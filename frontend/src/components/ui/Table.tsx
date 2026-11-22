import type { ReactNode, TableHTMLAttributes } from 'react';
import { cn } from './cn';

// DESIGN.md 표 (Carbon): one row height per table, header as tall as rows, gray header, horizontal dividers only.
const DENSITY = { default: '[&_tr]:h-12', compact: '[&_tr]:h-10' } as const;

/**
 * Write plain <thead>/<tbody>/<th>/<td> inside; the styling reaches them from here.
 * Number and date cells: add `text-right tabular-nums`. Selected row: add `bg-brand-subtle`.
 */
export function Table({ density = 'default', caption, className, children, ...rest }: TableHTMLAttributes<HTMLTableElement> & {
  density?: keyof typeof DENSITY;
  /** read by screen readers only */
  caption?: ReactNode;
}) {
  return (
    <div className="overflow-x-auto rounded border border-line bg-white">
      <table
        className={cn(
          'w-full border-collapse text-left text-[15px] text-ink', DENSITY[density],
          '[&_th]:bg-paper [&_th]:px-4 [&_th]:font-bold [&_th]:whitespace-nowrap [&_td]:px-4',
          '[&_tbody_tr]:border-t [&_tbody_tr]:border-line-soft [&_tbody_tr:hover]:bg-paper',
          className,
        )}
        {...rest}
      >
        {caption && <caption className="sr-only">{caption}</caption>}
        {children}
      </table>
    </div>
  );
}
