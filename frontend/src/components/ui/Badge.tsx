import type { HTMLAttributes } from 'react';
import { isDueSoon } from '../../terms';
import { cn } from './cn';

/**
 * DESIGN.md 상태 배지 colours, fixed per meaning:
 *   danger  기한 지남·만료·반려   warning 기한 임박·조치 필요   success 완료·취득·승인
 *   info    진행 중·검토 중        neutral 시작 전·초안·해당 없음
 * Always put the state in words ("경과 3일", "D-4"); never colour alone.
 */
const TONE = {
  danger: 'bg-danger-bg border-danger-border text-danger',
  warning: 'bg-warn-bg border-warn-border text-warn',
  success: 'bg-ok-bg border-ok-border text-ok',
  info: 'bg-information-bg border-information-border text-information',
  neutral: 'bg-paper border-line text-body',
} as const;

export type Tone = keyof typeof TONE;

export function Badge({ tone = 'neutral', className, ...rest }: HTMLAttributes<HTMLSpanElement> & { tone?: Tone }) {
  return (
    <span
      className={cn('inline-flex h-6 shrink-0 items-center whitespace-nowrap rounded-sm border px-2 text-[15px] leading-none', TONE[tone], className)}
      {...rest}
    />
  );
}

/** Deadline → badge words and colour: 경과 N일 / D-DAY·D-N within the due-soon window / D-N / 완료. */
export function dueBadge(dDay: number, done = false): { tone: Tone; text: string } {
  if (done) return { tone: 'success', text: '완료' };
  if (dDay < 0) return { tone: 'danger', text: `경과 ${-dDay}일` };
  return { tone: isDueSoon(dDay) ? 'warning' : 'neutral', text: dDay === 0 ? 'D-DAY' : `D-${dDay}` };
}
