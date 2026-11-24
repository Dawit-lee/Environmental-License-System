import { Link } from 'react-router';
import { HELP, setOnboardingDismissed } from '../components/Onboarding';
import { PageHeader } from '../components/ui/PageHeader';

// ---------------------------------------------------------------------------
// GuidePage (/guide) — the main workflow top to bottom, then the terms that confuse people.
// ---------------------------------------------------------------------------

const FLOW: { title: string; lines: string[]; where: string }[] = [
  { title: '프로젝트 만들기', where: '프로젝트 › + 새 프로젝트', lines: [
    '시설 종류, 용량, 위치를 넣습니다.',
    '입력한 조건으로 필요한 인허가를 바로 찾아 줍니다.',
  ] },
  { title: '인허가 확인·승인', where: '프로젝트 › 설정', lines: [
    '규칙이 찾은 인허가 목록을 봅니다.',
    '맞는 항목만 승인하면 [인허가] 탭에 들어갑니다.',
  ] },
  { title: '일정 틀 잡기', where: '프로젝트 › 일정 › 템플릿', lines: [
    '템플릿으로 자주 쓰는 공정을 한 번에 넣습니다.',
    '엑셀 공정표가 있으면 [붙여넣기]로 옮깁니다.',
  ] },
  { title: '기준일 넣기', where: '프로젝트 › 일정 › 기준일', lines: [
    '입찰일이나 착수일을 넣습니다.',
    '각 항목 날짜가 이 날짜를 기준으로 계산됩니다.',
    '기준일이 바뀌면 항목도 함께 움직입니다.',
  ] },
  { title: '멤버·담당자 정하기', where: '프로젝트 › 설정 › 멤버', lines: [
    '함께 일할 사람을 넣습니다.',
    '일정 항목과 의무사항에 담당자를 지정합니다.',
  ] },
  { title: '문서에서 날짜 받기', where: '프로젝트 › 문서', lines: [
    '공고문이나 과업내용서를 올립니다.',
    '[일정 추출]을 누르면 AI가 날짜 후보를 찾습니다.',
    '확인한 후보만 일정에 들어갑니다.',
  ] },
  { title: '기준 확정', where: '프로젝트 › 일정 › 기준 확정', lines: [
    '일정이 다 잡히면 기준선으로 저장합니다.',
    '이후 늦어진 일수는 [지연 현황]에서 봅니다.',
  ] },
  { title: '협력사와 공유', where: '프로젝트 › 일정 › 공유', lines: [
    '읽기 전용 링크나 달력 구독(ICS)을 보냅니다.',
    '회의 자료는 [인쇄본]에서 PDF로 저장합니다.',
  ] },
  { title: '허가 후 관리', where: '인허가 상세 › 허가증, 프로젝트 › AI 검토·의무사항', lines: [
    '허가증을 올리면 AI가 허가조건을 뽑습니다.',
    '[AI 검토]에서 원문과 대조해 승인합니다.',
    '승인한 조건은 [의무사항]의 정기 할 일이 됩니다.',
  ] },
];

export default function GuidePage() {
  return (
    <div className="min-h-full bg-paper">
      <div className="max-w-3xl py-8">
        <PageHeader title="사용 가이드" />
        <p className="mt-2 text-sm text-body">처음 쓰는 순서대로 적었습니다.</p>
        <p className="text-sm text-body">위에서부터 하나씩 따라 하면 됩니다.</p>

        <ol className="mt-6 space-y-0">
          {FLOW.map((s, i) => (
            <li key={s.title} className="relative flex gap-4 pb-6 last:pb-0">
              {i < FLOW.length - 1 && <span aria-hidden className="absolute left-[15px] top-8 h-[calc(100%-2rem)] w-px bg-brand/20" />}
              <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-brand text-sm font-semibold text-white">{i + 1}</span>
              <div className="min-w-0 flex-1 rounded border border-line bg-white px-4 py-3">
                <h2 className="text-base font-semibold text-ink">{s.title}</h2>
                <p className="text-xs text-mute">{s.where}</p>
                <ul className="mt-2 space-y-0.5 text-sm text-body">
                  {s.lines.map((l) => <li key={l}>{l}</li>)}
                </ul>
              </div>
            </li>
          ))}
        </ol>

        <h2 className="mt-10 text-lg font-semibold text-ink">용어</h2>
        <dl className="mt-3 divide-y divide-line rounded border border-line bg-white">
          {Object.values(HELP).map((h) => (
            <div key={h.term} className="grid gap-1 px-4 py-3 sm:grid-cols-[8rem_1fr]">
              <dt className="text-sm font-medium text-ink">{h.term}</dt>
              <dd className="text-sm text-body">{h.text}</dd>
            </div>
          ))}
        </dl>

        <div className="mt-8 flex flex-wrap gap-2">
          <Link to="/dashboard" onClick={() => setOnboardingDismissed(false)}
            className="rounded bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-hover">시작하기 체크리스트 열기</Link>
          <Link to="/projects/new" className="rounded border border-line bg-white px-4 py-2 text-sm text-body hover:border-brand">새 프로젝트 만들기</Link>
        </div>
      </div>
    </div>
  );
}
