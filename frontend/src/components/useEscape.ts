import { useEffect, useRef } from 'react';

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
