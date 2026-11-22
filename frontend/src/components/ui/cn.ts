/** Join class names, skipping falsy ones. Later classes do not override earlier ones; pass non-conflicting extras. */
export const cn = (...c: (string | false | null | undefined)[]) => c.filter(Boolean).join(' ');

/** DESIGN.md focus: 2px blue outline, 2px offset, keyboard only. */
export const focusRing = 'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand';
