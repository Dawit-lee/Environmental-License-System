import type { ReactNode } from 'react';
import { Button } from './ui/Button';
import { cn, focusRing } from './ui/cn';
import { READ_ONLY, type Action } from './reviewActions';

// DESIGN.md 검토·비교 화면: candidate list on the left, detail on the right, a button row fixed at the bottom.

/** One candidate in the left list. The selected one gets a blue left bar and a light blue background. */
export function ReviewItem({ active, onClick, kind, where, title, sub, leading, muted }: {
  active: boolean;
  onClick: () => void;
  kind: ReactNode;
  where?: ReactNode;
  title: ReactNode;
  sub?: ReactNode;
  leading?: ReactNode;
  muted?: boolean;
}) {
  return (
    <li className={cn('flex items-start border-b border-l-4 border-b-line-soft',
      active ? 'border-l-brand bg-brand-subtle' : 'border-l-transparent', muted && 'opacity-70')}>
      {leading}
      <button type="button" aria-current={active ? 'true' : undefined} onClick={onClick}
        className={cn('flex min-w-0 flex-1 flex-col gap-1 px-4 py-3 text-left', !active && 'hover:bg-paper', focusRing)}>
        <span className="flex flex-wrap items-center gap-x-2 gap-y-1 text-[13px]">
          {kind}
          {where && <span className="text-mute">{where}</span>}
        </span>
        <span className="text-[15px] font-bold text-ink">{title}</span>
        {sub && <span className="text-sm text-body">{sub}</span>}
      </button>
    </li>
  );
}

/** Detail header: state words on top, the title, and the 원문 대조 badge on the right. */
export function DetailHead({ state, title, badge }: { state: ReactNode; title: ReactNode; badge?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 border-b border-line px-5 py-4">
      <div className="flex min-w-0 flex-col gap-1">
        <span className="text-[13px] font-bold">{state}</span>
        <h2 className="text-[21px] font-bold leading-snug text-ink">{title}</h2>
      </div>
      {badge}
    </div>
  );
}

/** Highlight for the words that changed inside a quote. */
export const MARK = 'rounded-sm bg-warn-border text-ink';

/** 원문 인용: grey box; changed words are passed in already wrapped in <mark className={MARK}>. */
export function QuoteBox({ caption, children }: { caption: ReactNode; children: ReactNode }) {
  return (
    <figure className="flex flex-col gap-2">
      <figcaption className="text-sm text-body">{caption}</figcaption>
      <blockquote className="whitespace-pre-wrap break-words rounded border border-line bg-paper px-4 py-3 text-[15px] leading-relaxed text-ink">
        {children}
      </blockquote>
    </figure>
  );
}

/** Bottom button row: remaining count on the left, buttons on the right. Sticky from lg up only, so a phone keeps its screen. */
export function ActionBar({ remaining, actions, readOnly }: { remaining: number; actions: Action[]; readOnly: boolean }) {
  return (
    <div className="mt-auto flex flex-wrap items-center justify-end gap-2 border-t border-line bg-paper px-5 py-4 lg:sticky lg:bottom-0">
      <span className="mr-auto text-[15px] text-body">남은 검토 {remaining}건</span>
      {actions.map((a) => (
        <Button key={a.label} variant={a.variant} onClick={a.onClick}
          disabled={readOnly || a.disabled} title={readOnly ? READ_ONLY : a.title}>
          {a.label}
        </Button>
      ))}
    </div>
  );
}
