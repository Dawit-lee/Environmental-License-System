import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { PermitItem, Project } from '../types';
import { PERMIT_STATUS, PROJECT_STATUS, filterPermits, filterProjects } from './listFilters';
import { Badge, dueBadge } from '../components/ui/Badge';
import { EmptyBlock, FilterChips } from '../components/ListParts';

const project = (o: Partial<Project>) => ({ id: 1, name: '가람 하수처리장', location: '가람시', facilityType: 'SEWAGE', status: 'IN_PROGRESS', teamId: 1, ...o }) as Project;
const permit = (o: Partial<PermitItem>) => ({ id: 1, name: '건축허가', category: 'CONSTRUCTION', status: 'IN_PROGRESS', dDay: 10, ...o }) as PermitItem;

describe('status badges', () => {
  it('says the project state in words with its fixed colour', () => {
    expect(PROJECT_STATUS.IN_PROGRESS).toEqual({ text: '진행중', tone: 'info' });
    expect(PROJECT_STATUS.COMPLETED).toEqual({ text: '완료', tone: 'success' });
    expect(PROJECT_STATUS.ON_HOLD).toEqual({ text: '보류', tone: 'neutral' });
  });

  it('maps permit states to DESIGN.md colours: expired danger, near expiry warning, acquired success', () => {
    expect(PERMIT_STATUS.EXPIRED).toEqual({ text: '만료', tone: 'danger' });
    expect(PERMIT_STATUS.NEAR_EXPIRY).toEqual({ text: '만료임박', tone: 'warning' });
    expect(PERMIT_STATUS.COMPLETED).toEqual({ text: '취득완료', tone: 'success' });
    expect(PERMIT_STATUS.IN_PROGRESS.tone).toBe('info');
    expect(PERMIT_STATUS.NOT_APPLIED).toEqual({ text: '미신청', tone: 'neutral' });
  });

  it('renders the badge with words, not colour alone', () => {
    const s = PERMIT_STATUS.NEAR_EXPIRY;
    const out = renderToStaticMarkup(<Badge tone={s.tone}>{s.text}</Badge>);
    expect(out).toContain('>만료임박</span>');
    expect(out).toContain('bg-warn-bg');
  });

  it('shows the remaining time of an expiry date as 경과 N일 / D-N', () => {
    expect(dueBadge(-9)).toEqual({ tone: 'danger', text: '경과 9일' });
    expect(dueBadge(0).text).toBe('D-DAY');
    expect(dueBadge(200)).toEqual({ tone: 'neutral', text: 'D-200' });
  });
});

describe('filterProjects', () => {
  const all = () => true;
  const ps = [
    project({ id: 1, name: '가람 하수처리장', location: '가람시', facilityType: 'SEWAGE', status: 'IN_PROGRESS', teamId: 1 }),
    project({ id: 2, name: '누리 소각장', location: '누리군', facilityType: 'INCINERATION', status: 'COMPLETED', teamId: 2 }),
    project({ id: 3, name: '다온 매립장', location: '가람시 북구', facilityType: 'LANDFILL', status: 'ON_HOLD', teamId: 1 }),
  ];
  const ids = (r: Project[]) => r.map((p) => p.id);

  it('keeps everything with no filter on', () => {
    expect(ids(filterProjects(ps, { query: '', facility: 'ALL', status: 'ALL', inScope: all }))).toEqual([1, 2, 3]);
  });

  it('searches name or location, ignoring case and spaces around', () => {
    expect(ids(filterProjects(ps, { query: ' 가람시 ', facility: 'ALL', status: 'ALL', inScope: all }))).toEqual([1, 3]);
    expect(ids(filterProjects(ps, { query: '소각', facility: 'ALL', status: 'ALL', inScope: all }))).toEqual([2]);
  });

  it('combines facility, status and scope filters', () => {
    expect(ids(filterProjects(ps, { query: '', facility: 'LANDFILL', status: 'ALL', inScope: all }))).toEqual([3]);
    expect(ids(filterProjects(ps, { query: '', facility: 'ALL', status: 'COMPLETED', inScope: all }))).toEqual([2]);
    expect(ids(filterProjects(ps, { query: '가람', facility: 'ALL', status: 'ALL', inScope: (p) => p.teamId === 1 }))).toEqual([1, 3]);
    expect(ids(filterProjects(ps, { query: '', facility: 'SEWAGE', status: 'ON_HOLD', inScope: all }))).toEqual([]);
  });
});

describe('filterPermits', () => {
  const ps = [
    permit({ id: 1, category: 'CONSTRUCTION', status: 'IN_PROGRESS' }),
    permit({ id: 2, category: 'FACILITY', status: 'EXPIRED' }),
    permit({ id: 3, category: 'FACILITY', status: 'COMPLETED' }),
  ];
  const ids = (r: PermitItem[]) => r.map((p) => p.id);

  it('filters by 분야 and 상태 together', () => {
    expect(ids(filterPermits(ps, { category: 'ALL', status: 'ALL' }))).toEqual([1, 2, 3]);
    expect(ids(filterPermits(ps, { category: 'FACILITY', status: 'ALL' }))).toEqual([2, 3]);
    expect(ids(filterPermits(ps, { category: 'FACILITY', status: 'EXPIRED' }))).toEqual([2]);
    expect(ids(filterPermits(ps, { category: 'ELECTRICAL', status: 'ALL' }))).toEqual([]);
  });
});

describe('list parts', () => {
  it('shows applied filters as removable chips plus 필터 모두 지우기, and nothing when none', () => {
    const noop = () => {};
    expect(renderToStaticMarkup(<FilterChips filters={[]} onClearAll={noop} />)).toBe('');
    const out = renderToStaticMarkup(<FilterChips filters={[{ key: 's', label: '상태: 만료', clear: noop }]} onClearAll={noop} />);
    expect(out).toContain('aria-label="상태: 만료 필터 지우기"');
    expect(out).toContain('필터 모두 지우기');
  });

  it('empty state has a title, a reason and one action', () => {
    const out = renderToStaticMarkup(<EmptyBlock title="조건에 맞는 항목이 없습니다." description="필터를 바꿔 보세요." action={<button>필터 모두 지우기</button>} />);
    expect(out).toContain('조건에 맞는 항목이 없습니다.');
    expect(out).toContain('필터를 바꿔 보세요.');
    expect(out).toContain('<button>필터 모두 지우기</button>');
  });
});
