import { useState } from 'react';
import { Alert } from '../components/ui/Alert';
import { Badge, dueBadge } from '../components/ui/Badge';
import { Button } from '../components/ui/Button';
import { Input, Select, Textarea } from '../components/ui/Field';
import { SummaryStrip } from '../components/ui/SummaryStrip';
import { Table } from '../components/ui/Table';
import { Tabs } from '../components/ui/Tabs';

// Dev-only gallery (/dev/ui, not in any menu): every shared part from DESIGN.md 4 at a glance.
const Section = ({ title, children }: { title: string; children: React.ReactNode }) => (
  <section className="mt-10">
    <h2 className="mb-4 text-[19px] font-bold text-ink">{title}</h2>
    {children}
  </section>
);

const ROWS = [
  { name: '대기 배출시설 설치허가', status: dueBadge(-3), due: '2026-11-19', count: 12 },
  { name: '폐수 배출시설 설치신고', status: dueBadge(4), due: '2026-11-26', count: 3 },
  { name: '개발행위 허가', status: dueBadge(45), due: '2027-01-06', count: 1 },
  { name: '건축 허가', status: dueBadge(0, true), due: '2026-10-02', count: 128 },
];

export default function UiGalleryPage() {
  const [tab, setTab] = useState('permit');
  return (
    <div className="py-8">
      <h1 className="text-2xl font-bold text-ink">공통 부품</h1>
      <p className="mt-1 text-body">DESIGN.md 「4. 컴포넌트 규칙」의 부품 모음입니다. 개발 서버에서만 열립니다.</p>

      <Section title="버튼">
        <div className="flex flex-wrap items-center gap-3">
          <Button variant="text">자세히 보기</Button>
          <Button variant="danger">반려</Button>
          <Button variant="tertiary">고쳐서 승인</Button>
          <Button variant="secondary">임시 저장</Button>
          <Button>승인하고 다음</Button>
          <Button disabled>저장</Button>
        </div>
        <div className="mt-3 flex flex-wrap items-center gap-3">
          <Button size="sm" variant="tertiary">내보내기</Button>
          <Button size="sm">추가</Button>
        </div>
      </Section>

      <Section title="입력">
        <div className="grid max-w-xl gap-6">
          <Input label="허가명" required placeholder="예: 대기 배출시설 설치허가" />
          <Input label="신청일" type="date" hint="허가 신청서를 낸 날입니다." className="max-w-60" />
          <Input label="만료일" defaultValue="2026-13-01" error="날짜 형식이 아닙니다. 2026-12-01처럼 쓰세요." className="max-w-60" />
          <Select label="분류"><option>대기</option><option>수질</option></Select>
          <Input label="담당 부서" disabled defaultValue="환경팀" />
          <Textarea label="메모" hint="500자까지 쓸 수 있습니다." />
        </div>
      </Section>

      <Section title="상태 배지">
        <div className="flex flex-wrap gap-2">
          <Badge tone="danger">경과 3일</Badge>
          <Badge tone="warning">D-4</Badge>
          <Badge tone="success">완료</Badge>
          <Badge tone="info">검토 중</Badge>
          <Badge>시작 전</Badge>
        </div>
      </Section>

      <Section title="안내 상자">
        <div className="grid gap-3">
          <Alert title="안내">프로젝트를 만들면 필요한 허가가 자동으로 채워집니다.</Alert>
          <Alert tone="warning">허가 2건의 만료일이 30일 안에 다가옵니다.</Alert>
          <Alert tone="danger">저장하지 못했습니다. 잠시 뒤 다시 시도하세요.</Alert>
          <Alert tone="success">저장했습니다.</Alert>
        </div>
      </Section>

      <Section title="요약 숫자 띠">
        <SummaryStrip items={[
          { label: '기한 지남', value: 3, unit: '건', tone: 'danger' },
          { label: '7일 안 마감', value: 5, unit: '건', tone: 'warning' },
          { label: '검토 대기', value: 8, unit: '건' },
          { label: '진행 중 프로젝트', value: 4, unit: '개' },
        ]} />
      </Section>

      <Section title="탭">
        <Tabs label="프로젝트 메뉴" value={tab} onChange={setTab} tabs={[
          { key: 'permit', label: '허가', count: 12 },
          { key: 'obligation', label: '의무', count: 4 },
          { key: 'law', label: '법령' },
        ]} />
      </Section>

      <Section title="표">
        {(['default', 'compact'] as const).map((d) => (
          <div key={d} className="mt-4">
            <p className="mb-2 text-[15px] text-body">{d === 'default' ? '기본 48px' : '촘촘 40px'}</p>
            <Table density={d} caption="허가 목록 예시" className="min-w-[720px]">
              <thead><tr><th>허가명</th><th>상태</th><th className="text-right">기한</th><th className="text-right">첨부</th></tr></thead>
              <tbody>
                {ROWS.map((r, i) => (
                  <tr key={r.name} className={i === 1 ? 'bg-brand-subtle' : undefined}>
                    <td><a href="#" className="text-brand-text hover:underline">{r.name}</a></td>
                    <td><Badge tone={r.status.tone}>{r.status.text}</Badge></td>
                    <td className="text-right tabular-nums">{r.due}</td>
                    <td className="text-right tabular-nums">{r.count}</td>
                  </tr>
                ))}
              </tbody>
            </Table>
          </div>
        ))}
      </Section>
    </div>
  );
}
