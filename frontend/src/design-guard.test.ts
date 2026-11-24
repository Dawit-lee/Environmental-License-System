import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

// DESIGN.md: colors come from theme tokens, cards and tables are split by borders (no shadow), no emoji.
// Overlays (menus, drawers, dialogs, toasts) keep shadow-lg/xl, so only the bare and small card shadows are banned.
const files = (dir: string): string[] => readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
  e.isDirectory() ? files(join(dir, e.name)) : /\.tsx?$/.test(e.name) && !/\.test\./.test(e.name) ? [join(dir, e.name)] : []);
const src = files(join(__dirname)).map((f) => [f.replace(__dirname, 'src'), readFileSync(f, 'utf8')] as const);
const hits = (re: RegExp) => src.flatMap(([f, text]) => text.split('\n').flatMap((l, i) => (re.test(l) ? [`${f}:${i + 1}`] : [])));

describe('design guard', () => {
  it('uses theme tokens instead of raw 6-digit hex colors', () => {
    expect(hits(/#[0-9a-fA-F]{6}\b/)).toEqual([]);
  });

  it('puts no shadow on cards and tables', () => {
    expect(hits(/["'` ]shadow(-sm)?["'` ]/)).toEqual([]);
  });

  it('uses no emoji as icons (arrows such as ↗ are text)', () => {
    expect(hits(/(?![\u2190-\u21ff])\p{Extended_Pictographic}/u)).toEqual([]);
  });
});
