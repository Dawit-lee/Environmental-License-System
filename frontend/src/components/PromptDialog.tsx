import { useRef, useState } from 'react';
import { Modal } from './ScheduleTools';

const btn = 'rounded border border-line px-3 py-1.5 text-xs font-medium text-body hover:border-brand hover:text-brand';
const primary = 'rounded bg-brand px-3.5 py-1.5 text-xs font-semibold text-white hover:bg-brand-hover';

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
        <input autoFocus aria-label={q.label} maxLength={500} value={q.value}
          onChange={(e) => setQ({ ...q, value: e.target.value })}
          className="w-full rounded border border-line px-3 py-2 text-sm focus:border-brand focus:outline-none" />
        <div className="flex justify-end gap-2">
          <button type="button" className={btn} onClick={() => close(null)}>취소</button>
          <button type="submit" className={primary}>확인</button>
        </div>
      </form>
    </Modal>
  );
  return [ask, ui] as const;
}
