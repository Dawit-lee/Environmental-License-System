// @vitest-environment jsdom
import { act, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { expect, it } from 'vitest';
import { useDialogFocus } from './useEscape';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

function Dialog({ onClose }: { onClose: () => void }) {
  const box = useRef<HTMLDivElement>(null);
  useDialogFocus(box);
  return <div ref={box} role="dialog"><button type="button" onClick={onClose}>닫기</button><input aria-label="사유" /></div>;
}
function Page() {
  const [open, setOpen] = useState(false);
  return <><button type="button" onClick={() => setOpen(true)}>열기</button>{open && <Dialog onClose={() => setOpen(false)} />}</>;
}

it('moves focus to the first field on open and back to the opener on close', () => {
  const host = document.body.appendChild(document.createElement('div'));
  const root = createRoot(host);
  act(() => root.render(<Page />));
  const opener = host.querySelector('button')!;
  opener.focus();
  act(() => opener.click());
  expect(document.activeElement?.getAttribute('aria-label')).toBe('사유');
  act(() => host.querySelector<HTMLButtonElement>('[role=dialog] button')!.click());
  expect(document.activeElement).toBe(opener);
  act(() => root.unmount());
  host.remove();
});
