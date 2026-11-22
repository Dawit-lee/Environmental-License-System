import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { Button } from './Button';
import { Input, Select, Textarea } from './Field';
import { Badge, dueBadge } from './Badge';
import { Alert } from './Alert';
import { SummaryStrip } from './SummaryStrip';
import { Tabs, nextTab } from './Tabs';
import { Table } from './Table';

// Rendered markup is the seam: what a screen reader and the browser get from each part.
const html = (el: React.ReactElement) => renderToStaticMarkup(el);
const classOf = (markup: string, tag: string) => new RegExp(`<${tag}[^>]*class="([^"]*)"`).exec(markup)?.[1].replaceAll('&amp;', '&').split(' ') ?? [];

describe('Button', () => {
  it('is a 48px primary button with a 4px radius and a visible focus ring by default', () => {
    const out = html(<Button>저장</Button>);
    expect(out).toContain('type="button"');
    expect(out).toContain('>저장</button>');
    expect(classOf(out, 'button')).toEqual(expect.arrayContaining(['h-12', 'rounded', 'bg-brand', 'text-white', 'focus-visible:outline-2']));
  });

  it('has secondary, tertiary and danger looks from DESIGN.md', () => {
    expect(classOf(html(<Button variant="secondary">a</Button>), 'button')).toEqual(expect.arrayContaining(['bg-brand-subtle', 'border-brand', 'text-brand-text']));
    expect(classOf(html(<Button variant="tertiary">a</Button>), 'button')).toEqual(expect.arrayContaining(['bg-white', 'border-input', 'text-ink']));
    expect(classOf(html(<Button variant="danger">a</Button>), 'button')).toEqual(expect.arrayContaining(['bg-white', 'border-danger', 'text-danger']));
    expect(classOf(html(<Button variant="text">a</Button>), 'button')).toEqual(expect.arrayContaining(['bg-transparent', 'text-brand-text']));
  });

  it('has a 40px small size for tables and toolbars', () => {
    const c = classOf(html(<Button size="sm">a</Button>), 'button');
    expect(c).toContain('h-10');
    expect(c).not.toContain('h-12');
  });

  it('passes through native props and extra classes', () => {
    const out = html(<Button type="submit" disabled aria-label="닫기" className="w-full">x</Button>);
    expect(out).toContain('type="submit"');
    expect(out).toContain('disabled=""');
    expect(out).toContain('aria-label="닫기"');
    expect(classOf(out, 'button')).toContain('w-full');
  });
});

describe('Input, Select, Textarea', () => {
  const idOf = (markup: string, tag: string) => new RegExp(`<${tag}[^>]* id="([^"]+)"`).exec(markup)?.[1];

  it('ties the label above the input to it, and marks required in words', () => {
    const out = html(<Input label="허가명" required />);
    const id = idOf(out, 'input');
    expect(id).toBeTruthy();
    expect(out).toContain(`for="${id}"`);
    expect(out).toContain('(필수)');
    expect(out.indexOf('<label')).toBeLessThan(out.indexOf('<input'));
    expect(classOf(out, 'input')).toEqual(expect.arrayContaining(['h-12', 'rounded', 'border-input']));
  });

  it('shows help text and links it with aria-describedby', () => {
    const out = html(<Input label="기한" hint="YYYY-MM-DD" />);
    const hintId = /<p[^>]*id="([^"]+)"[^>]*>YYYY-MM-DD/.exec(out)?.[1];
    expect(hintId).toBeTruthy();
    expect(out).toContain(`aria-describedby="${hintId}"`);
  });

  it('shows an error below the input, red border, aria-invalid, described by the error', () => {
    const out = html(<Input label="기한" hint="도움말" error="날짜 형식이 아닙니다. 2026-01-31처럼 쓰세요." />);
    expect(out).toContain('aria-invalid="true"');
    const errId = /<p[^>]*id="([^"]+)"[^>]*>.*날짜 형식이 아닙니다/.exec(out)?.[1];
    expect(errId).toBeTruthy();
    expect(/aria-describedby="([^"]+)"/.exec(out)?.[1].split(' ')).toContain(errId);
    expect(classOf(out, 'input')).toContain('border-danger');
    expect(out).not.toContain('>도움말<'); // the error replaces the hint
  });

  it('keeps the caller id and passes disabled through', () => {
    const out = html(<Input label="이름" id="name" disabled />);
    expect(out).toContain('id="name"');
    expect(out).toContain('for="name"');
    expect(out).toContain('disabled=""');
    expect(classOf(out, 'input')).toContain('disabled:bg-line');
  });

  it('select and textarea share the label and error wiring', () => {
    const sel = html(<Select label="분류" error="고르세요"><option>대기</option></Select>);
    expect(sel).toContain(`for="${idOf(sel, 'select')}"`);
    expect(sel).toContain('aria-invalid="true"');
    expect(sel).toContain('<option>대기</option>');
    const ta = html(<Textarea label="메모" />);
    expect(ta).toContain(`for="${idOf(ta, 'textarea')}"`);
    expect(classOf(ta, 'textarea')).not.toContain('h-12');
  });
});

describe('Badge', () => {
  it('is a light badge: 24px, 2px radius, status bg + border + text, words always present', () => {
    const out = html(<Badge tone="danger">경과 3일</Badge>);
    expect(out).toContain('>경과 3일</span>');
    expect(classOf(out, 'span')).toEqual(expect.arrayContaining(['h-6', 'rounded-sm', 'bg-danger-bg', 'border-danger-border', 'text-danger']));
  });

  it('maps every DESIGN.md status colour', () => {
    expect(classOf(html(<Badge tone="warning">D-4</Badge>), 'span')).toEqual(expect.arrayContaining(['bg-warn-bg', 'text-warn']));
    expect(classOf(html(<Badge tone="success">완료</Badge>), 'span')).toEqual(expect.arrayContaining(['bg-ok-bg', 'text-ok']));
    expect(classOf(html(<Badge tone="info">검토 중</Badge>), 'span')).toEqual(expect.arrayContaining(['bg-information-bg', 'text-information']));
    expect(classOf(html(<Badge>시작 전</Badge>), 'span')).toEqual(expect.arrayContaining(['bg-paper', 'border-line', 'text-body']));
  });

  it('dueBadge words and colours a deadline: overdue, due soon, normal, done', () => {
    expect(dueBadge(-3)).toEqual({ tone: 'danger', text: '경과 3일' });
    expect(dueBadge(0)).toEqual({ tone: 'warning', text: 'D-DAY' });
    expect(dueBadge(4)).toEqual({ tone: 'warning', text: 'D-4' });
    expect(dueBadge(30)).toEqual({ tone: 'warning', text: 'D-30' });
    expect(dueBadge(31)).toEqual({ tone: 'neutral', text: 'D-31' });
    expect(dueBadge(-3, true)).toEqual({ tone: 'success', text: '완료' });
  });
});

describe('Alert', () => {
  it('error box: danger colours, role=alert, hidden icon, message text', () => {
    const out = html(<Alert tone="danger">저장하지 못했습니다.</Alert>);
    expect(out).toContain('role="alert"');
    expect(out).toContain('aria-hidden="true"');
    expect(out).toContain('저장하지 못했습니다.');
    expect(classOf(out, 'div')).toEqual(expect.arrayContaining(['rounded', 'border', 'bg-danger-bg', 'border-danger-border', 'text-danger']));
  });

  it('info is the default: blue subtle box with role=status', () => {
    const out = html(<Alert title="안내">내용</Alert>);
    expect(out).toContain('role="status"');
    expect(out).toContain('안내');
    expect(classOf(out, 'div')).toEqual(expect.arrayContaining(['bg-brand-subtle', 'border-brand-border']));
  });

  it('warning and success tones; extra classes pass through', () => {
    expect(classOf(html(<Alert tone="warning">a</Alert>), 'div')).toEqual(expect.arrayContaining(['bg-warn-bg', 'text-warn']));
    expect(classOf(html(<Alert tone="success" className="mt-3">a</Alert>), 'div')).toEqual(expect.arrayContaining(['bg-ok-bg', 'text-ok', 'mt-3']));
  });
});

describe('SummaryStrip', () => {
  const items = [
    { label: '기한 지남', value: 3, unit: '건', tone: 'danger' as const },
    { label: '7일 안 마감', value: 5, unit: '건' },
  ];

  it('is one bordered strip with cells split by 1px lines, label above number', () => {
    const out = html(<SummaryStrip items={items} />);
    expect(classOf(out, 'dl')).toEqual(expect.arrayContaining(['border', 'border-line', 'rounded', 'divide-x', 'divide-line']));
    expect(out.match(/<dt/g)).toHaveLength(2);
    expect(out.indexOf('기한 지남')).toBeLessThan(out.indexOf('>3<'));
    expect(out).toContain('건');
  });

  it('colours only the number of a status cell', () => {
    const out = html(<SummaryStrip items={items} />);
    expect(/<span class="([^"]*)">3<\/span>/.exec(out)?.[1]).toContain('text-danger');
    expect(/<span class="([^"]*)">5<\/span>/.exec(out)?.[1]).toContain('text-ink');
  });
});

describe('Tabs', () => {
  const tabs = [{ key: 'permit', label: '허가', count: 12 }, { key: 'law', label: '법령' }];

  it('is a tablist; the selected tab has aria-selected, a 3px blue underline and bold text', () => {
    const out = html(<Tabs label="프로젝트 메뉴" tabs={tabs} value="permit" onChange={() => {}} />);
    expect(out).toContain('role="tablist"');
    expect(out).toContain('aria-label="프로젝트 메뉴"');
    expect(out.match(/role="tab"/g)).toHaveLength(2);
    const [sel, other] = [...out.matchAll(/<button([^>]*)>/g)].map((m) => m[1]);
    expect(sel).toContain('aria-selected="true"');
    expect(sel).toContain('tabindex="0"');
    expect(sel).toMatch(/border-b-\[3px\]/);
    expect(sel).toMatch(/border-brand.*font-bold|font-bold.*border-brand/);
    expect(other).toContain('aria-selected="false"');
    expect(other).toContain('tabindex="-1"');
    expect(other).toContain('text-body');
  });

  it('writes the count in brackets next to the name', () => {
    expect(html(<Tabs label="t" tabs={tabs} value="law" onChange={() => {}} />)).toContain('허가 (12)');
  });

  it('arrow keys move between tabs and wrap around', () => {
    expect(nextTab(0, 'ArrowRight', 3)).toBe(1);
    expect(nextTab(2, 'ArrowRight', 3)).toBe(0);
    expect(nextTab(0, 'ArrowLeft', 3)).toBe(2);
    expect(nextTab(1, 'Home', 3)).toBe(0);
    expect(nextTab(1, 'End', 3)).toBe(2);
    expect(nextTab(1, 'Enter', 3)).toBeNull();
  });
});

describe('Table', () => {
  const t = (density?: 'default' | 'compact') => html(
    <Table density={density} caption="허가 목록"><thead><tr><th>이름</th></tr></thead><tbody><tr><td>a</td></tr></tbody></Table>,
  );

  it('sits in a horizontal scroll box with a gray header and row dividers only', () => {
    const out = t();
    expect(classOf(out, 'div')).toEqual(expect.arrayContaining(['overflow-x-auto', 'rounded', 'border', 'border-line']));
    const c = classOf(out, 'table');
    expect(c).toEqual(expect.arrayContaining(['[&_th]:bg-paper', '[&_th]:font-bold', '[&_tbody_tr]:border-t', '[&_tbody_tr]:border-line-soft', '[&_tbody_tr:hover]:bg-paper']));
    expect(out).toContain('<caption class="sr-only">허가 목록</caption>');
  });

  it('rows are 48px by default and 40px compact, header included', () => {
    expect(classOf(t(), 'table')).toContain('[&_tr]:h-12');
    expect(classOf(t('compact'), 'table')).toContain('[&_tr]:h-10');
  });
});
