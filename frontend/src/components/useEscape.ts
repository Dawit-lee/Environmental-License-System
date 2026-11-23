import { useEffect, useRef, type RefObject } from 'react';

// open dialogs in mount order; Esc closes only the top one (a prompt inside a drawer, not both)
const stack: object[] = [];

/** Close a drawer/modal on Esc while `active`. */
export function useEscape(onClose: () => void, active = true) {
  const close = useRef(onClose);
  close.current = onClose;
  useEffect(() => {
    if (!active) return;
    const me = {};
    stack.push(me);
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && stack[stack.length - 1] === me) close.current();
    };
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
      stack.splice(stack.indexOf(me), 1);
    };
  }, [active]);
}

const FIELD = 'input:not([type=hidden]):not([disabled]), select:not([disabled]), textarea:not([disabled])';
const FOCUSABLE = `${FIELD}, button:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])`;

/**
 * Dialog focus: on open, focus moves into `box` (its first field, else its first control, else the box);
 * on close, back to whatever had focus when it opened (the button that opened it).
 */
export function useDialogFocus(box: RefObject<HTMLElement | null>, active = true) {
  const opener = useRef<HTMLElement | null>(null);
  const wasActive = useRef(false);
  // read during render: by the time effects run, an autoFocus child has already taken focus
  if (active && !wasActive.current) opener.current = document.activeElement as HTMLElement | null;
  wasActive.current = active;
  useEffect(() => {
    if (!active) return;
    const el = box.current;
    if (el && !el.contains(document.activeElement)) (el.querySelector<HTMLElement>(FIELD) ?? el.querySelector<HTMLElement>(FOCUSABLE) ?? el).focus();
    return () => { if (opener.current?.isConnected) opener.current.focus(); };
  }, [active, box]);
}
