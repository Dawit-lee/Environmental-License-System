import { useRef, useState } from 'react';
import { Modal } from './ScheduleTools';
import { Button } from './ui/Button';
import { cn, focusRing } from './ui/cn';

/**
 * window.prompt in the app's Modal: `const [ask, promptUi] = usePrompt()`, render `{promptUi}`,
 * then `await ask('거절 사유')` resolves the typed text, or null on 취소 / ×.
 */
export function usePrompt() {
  const [q, setQ] = useState<{ label: string; value: string } | null>(null);
  const resolve = useRef<(v: string | null) => void>(() => {});
  const ask = (label: string, initial = '') =>
    new Promise<string | null>((r) => {
      resolve.current(null); // a still-open question is cancelled
      resolve.current = r;
      setQ({ label, value: initial });
    });
  const close = (v: string | null) => {
    setQ(null);
    resolve.current(v);
    resolve.current = () => {};
  };
  const ui = q && (
    <Modal title={q.label} onClose={() => close(null)}>
      <form className="space-y-4" onSubmit={(e) => { e.preventDefault(); close(q.value); }}>
        {/* the dialog title already shows the label */}
        <input autoFocus aria-label={q.label} maxLength={500} value={q.value}
          onChange={(e) => setQ({ ...q, value: e.target.value })}
          className={cn('h-12 w-full rounded border border-input bg-white px-4 text-base text-ink focus:border-brand', focusRing)} />
        <div className="flex justify-end gap-2">
          <Button variant="tertiary" onClick={() => close(null)}>취소</Button>
          <Button type="submit">확인</Button>
        </div>
      </form>
    </Modal>
  );
  return [ask, ui] as const;
}
