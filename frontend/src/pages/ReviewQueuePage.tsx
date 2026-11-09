import { useState, type ReactNode } from 'react';

// ---------------------------------------------------------------------------
// ReviewQueuePage — ③ human review of ① AI-extracted permit conditions.
// AI output stays here until approved; nothing reaches the ledger or alerts
// before that. Mock data only.
// ---------------------------------------------------------------------------

type QuoteCheck = 'MISMATCH' | 'UNVERIFIABLE' | 'VERIFIED';
type Status = 'PENDING' | 'SUBMITTED' | 'ACCEPTED' | 'EDITED_ACCEPTED' | 'REJECTED';
type Role = 'lead' | 'staff';

interface Suggestion {
  id: number;
  citation: string;
  summary: string;
  category: string;
  frequency: string;
  quoteCheck: QuoteCheck;
  page: number;
  quote: string;
  pageText?: string;
  mismatchNote?: string;
  scanned?: boolean;
  status: Status;
  viewed: boolean;
  checked: boolean;
}

// TODO: replace with GET /projects/{pid}/suggestions?runId=
const SEED: Omit<Suggestion, 'status' | 'viewed' | 'checked'>[] = [
  { id: 1, citation: '대기-2', summary: '굴뚝 자동측정기기(TMS) 부착·운영', category: '대기 › 대기질', frequency: '상시', quoteCheck: 'MISMATCH', page: 4,
    quote: '소각로 굴뚝에는 굴뚝원격감시체계(TMS)를 부착하여 실시간으로 관리하고 결과를 매월 보고할 것',
    pageText: '소각로 굴뚝에는 굴뚝원격감시체계(TMS)를 부착하여 실시간으로 관리할 것.',
    mismatchNote: 'AI 인용문의 "결과를 매월 보고할 것"이 원문 4쪽에 없습니다.' },
  { id: 2, citation: '사후-1', summary: '사후환경영향조사: 소음·진동 분기 1회', category: '생활 › 소음·진동', frequency: '분기', quoteCheck: 'UNVERIFIABLE', page: 7, scanned: true,
    quote: '공사 및 운영 기간 중 부지경계 4개 지점에서 소음·진동을 분기 1회 이상 측정할 것' },
  { id: 3, citation: '대기-5', summary: '다이옥신 배출농도 반기 1회 측정', category: '대기 › 대기질', frequency: '반기', quoteCheck: 'UNVERIFIABLE', page: 5, scanned: true,
    quote: '다이옥신류 배출농도는 반기별 1회 이상 측정하고 그 결과를 관할 지자체에 제출할 것' },
  { id: 4, citation: '악취-1', summary: '부지경계 복합악취 측정 연 2회', category: '대기 › 악취', frequency: '반기', quoteCheck: 'VERIFIED', page: 6,
    quote: '부지경계선에서 복합악취를 연 2회(하절기 포함) 측정할 것' },
  { id: 5, citation: '수-3', summary: '침출수 처리수 수질 월 1회 측정', category: '수 › 수질', frequency: '월', quoteCheck: 'VERIFIED', page: 8,
    quote: '침출수 처리수의 수질을 월 1회 측정하여 기록·보존할 것' },
  { id: 6, citation: '토지-2', summary: '공사 중 세륜시설 설치·운영', category: '토지 › 토양', frequency: '1회', quoteCheck: 'VERIFIED', page: 9,
    quote: '공사차량 출입구에 세륜·세차시설을 설치하여 비산먼지 및 토사 유출을 방지할 것' },
];
const INITIAL: Suggestion[] = SEED.map((s) => ({ ...s, status: 'PENDING', viewed: false, checked: false }));

const QC: Record<QuoteCheck, { label: string; cls: string }> = {
  MISMATCH: { label: '원문 불일치', cls: 'bg-red-100 text-[#991B1B]' },
  UNVERIFIABLE: { label: '확인 불가 (스캔본)', cls: 'bg-amber-100 text-[#B45309]' },
  VERIFIED: { label: '원문 확인됨', cls: 'bg-emerald-100 text-[#0F6E56]' },
};
const ST: Record<Status, { label: string; cls: string }> = {
  PENDING: { label: '검토 대기', cls: 'bg-gray-100 text-gray-600' },
  SUBMITTED: { label: '승인 요청됨', cls: 'bg-sky-100 text-sky-800' },
  ACCEPTED: { label: '승인', cls: 'bg-emerald-100 text-[#0F6E56]' },
  EDITED_ACCEPTED: { label: '수정 후 승인', cls: 'bg-emerald-100 text-[#0F6E56]' },
  REJECTED: { label: '거절', cls: 'bg-red-100 text-[#991B1B]' },
};
const QC_ORDER: Record<QuoteCheck, number> = { MISMATCH: 0, UNVERIFIABLE: 1, VERIFIED: 2 };

// Scanned rows need a recorded page view before they can be approved.
const canApprove = (s: Suggestion) => !(s.quoteCheck === 'UNVERIFIABLE' && !s.viewed);

const btn = 'rounded-lg px-3.5 py-2 text-sm font-semibold transition focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/30 disabled:cursor-not-allowed disabled:opacity-40';
const btnPrimary = `${btn} bg-[#1F4E79] text-white hover:bg-[#173a5c]`;
const btnGhost = `${btn} border border-[#E5E3DC] text-gray-700 hover:bg-[#F8F7F2]`;

export default function ReviewQueuePage() {
  const [items, setItems] = useState(INITIAL);
  const [selectedId, setSelectedId] = useState(1);
  const [role, setRole] = useState<Role>('lead');
  const [filter, setFilter] = useState<'ALL' | QuoteCheck>('ALL');
  const [toast, setToast] = useState('');

  const update = (id: number, patch: Partial<Suggestion>) =>
    setItems((prev) => prev.map((s) => (s.id === id ? { ...s, ...patch } : s)));
  const notify = (m: string) => {
    setToast(m);
    window.setTimeout(() => setToast(''), 2400);
  };

  const rows = items
    .filter((s) => filter === 'ALL' || s.quoteCheck === filter)
    .sort((a, b) => QC_ORDER[a.quoteCheck] - QC_ORDER[b.quoteCheck]);
  const sel = items.find((s) => s.id === selectedId)!;
  const gated = !canApprove(sel);
  const open = sel.status === 'PENDING' || (sel.status === 'SUBMITTED' && role === 'lead');

  const bulkApprove = () => {
    const picked = items.filter((s) => s.checked && s.status === 'PENDING');
    if (!picked.length) return notify('일괄 승인할 항목을 체크하세요.');
    const ok = picked.filter(canApprove);
    setItems((prev) => prev.map((s) => (ok.some((o) => o.id === s.id) ? { ...s, status: 'ACCEPTED', checked: false } : s)));
    const skipped = picked.length - ok.length;
    notify(`${ok.length}건 승인${skipped ? ` · ${skipped}건 제외 (원문 미열람 스캔본)` : ''}`);
  };

  return (
    <div className="min-h-full bg-[#F8F7F2]">
      <div className="mx-auto max-w-7xl px-4 py-8 sm:px-6">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div>
            <p className="text-xs font-semibold tracking-wide text-gray-500">AI 추출 결과 검토 · 광명 자원회수시설</p>
            <h1 className="mt-1 text-2xl font-bold text-[#1F4E79]">환경영향평가 협의의견서 검토</h1>
            <p className="mt-1 max-w-2xl text-sm text-gray-500">
              AI가 뽑은 이행조건은 승인 전까지 인허가 대장과 알림에 들어가지 않습니다. 원문과 다른 항목이 맨 위에 옵니다.
            </p>
          </div>
          <label className="flex items-center gap-2 text-sm text-gray-600">
            보기 권한
            <select
              value={role}
              onChange={(e) => setRole(e.target.value as Role)}
              className="rounded-lg border border-[#E5E3DC] bg-white px-2.5 py-1.5 text-sm"
            >
              <option value="lead">과장 (혼자 승인 · 일괄 승인)</option>
              <option value="staff">대리 (승인 요청만)</option>
            </select>
          </label>
        </div>

        <div className="mt-5 flex flex-wrap gap-2">
          {(['ALL', 'MISMATCH', 'UNVERIFIABLE', 'VERIFIED'] as const).map((f) => (
            <button
              key={f}
              type="button"
              onClick={() => setFilter(f)}
              className={`rounded-full px-3.5 py-1.5 text-sm font-medium transition ${filter === f ? 'bg-[#1F4E79] text-white' : 'bg-white text-gray-600 hover:bg-[#E5E3DC]'}`}
            >
              {f === 'ALL' ? '전체' : QC[f].label} ({f === 'ALL' ? items.length : items.filter((s) => s.quoteCheck === f).length})
            </button>
          ))}
        </div>

        <div className="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-2">
          {/* List */}
          <section className="min-w-0 rounded-xl border border-[#E5E3DC] bg-white shadow-sm">
            <div className="flex items-center justify-between border-b border-[#E5E3DC] px-4 py-3">
              <h2 className="text-sm font-semibold text-gray-800">추출된 조건 {rows.length}건</h2>
              {role === 'lead' && (
                <button type="button" onClick={bulkApprove} className={btnGhost}>선택 항목 일괄 승인</button>
              )}
            </div>
            <div className="overflow-x-auto">
              <table className="w-full min-w-[640px] text-left text-sm">
                <thead className="bg-[#F8F7F2] text-xs text-gray-500">
                  <tr>
                    <th className="px-3 py-2.5" />
                    <th className="px-3 py-2.5 font-medium">조건번호</th>
                    <th className="px-3 py-2.5 font-medium">요약</th>
                    <th className="px-3 py-2.5 font-medium">주기</th>
                    <th className="px-3 py-2.5 font-medium">원문 대조</th>
                    <th className="px-3 py-2.5 font-medium">상태</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[#E5E3DC]">
                  {rows.map((s) => (
                    <tr
                      key={s.id}
                      onClick={() => setSelectedId(s.id)}
                      className={`cursor-pointer transition ${s.id === selectedId ? 'bg-[#1F4E79]/5' : 'hover:bg-[#F8F7F2]'}`}
                    >
                      <td className="px-3 py-2.5" onClick={(e) => e.stopPropagation()}>
                        <input
                          type="checkbox"
                          aria-label={`${s.citation} 선택`}
                          disabled={s.status !== 'PENDING'}
                          checked={s.checked}
                          onChange={(e) => update(s.id, { checked: e.target.checked })}
                          className="h-4 w-4 accent-[#1F4E79]"
                        />
                      </td>
                      <td className="px-3 py-2.5 font-mono text-xs">{s.citation}</td>
                      <td className="px-3 py-2.5 text-gray-800">{s.summary}</td>
                      <td className="px-3 py-2.5 text-gray-600">{s.frequency}</td>
                      <td className="px-3 py-2.5"><Badge {...QC[s.quoteCheck]} /></td>
                      <td className="px-3 py-2.5"><Badge {...ST[s.status]} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>

          {/* Detail */}
          <section className="min-w-0 rounded-xl border border-[#E5E3DC] bg-white p-5 shadow-sm">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <h2 className="text-base font-semibold text-gray-800">
                <span className="font-mono">{sel.citation}</span> · {sel.summary}
              </h2>
              <Badge {...ST[sel.status]} />
            </div>

            <div className="mt-4 space-y-3">
              {sel.quoteCheck === 'MISMATCH' && (
                <Callout tone="red" title="환각 의심">{sel.mismatchNote} 원문과 대조해 수정하거나 거절하세요.</Callout>
              )}
              {gated && (
                <Callout tone="amber" title="원문 확인 필요">
                  스캔본이라 자동 대조가 안 됩니다. 아래 {sel.page}쪽 원문을 열어야 승인할 수 있습니다.
                </Callout>
              )}
              {sel.status === 'SUBMITTED' && (
                <Callout tone="sky" title="승인 요청됨">혼자 승인 권한이 있는 사람이 최종 확정합니다.</Callout>
              )}

              <div>
                <p className="mb-1 text-xs font-semibold text-gray-500">AI가 인용한 원문</p>
                <blockquote className="rounded-r-lg border-l-4 border-[#1F4E79] bg-[#F8F7F2] px-3 py-2 text-sm">{sel.quote}</blockquote>
              </div>

              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <Info label="분야 (평가항목)" value={sel.category} />
                <Info label="주기" value={sel.frequency} />
                <label className="text-sm">
                  <span className="mb-1 block text-xs font-medium text-gray-500">첫 기한일 (선택)</span>
                  <input type="date" className="w-full rounded-lg border border-[#E5E3DC] px-3 py-1.5 text-sm" />
                </label>
                <Info label="이행 담당" value="운영사업소 환경팀" />
              </div>

              {open && (
                <div className="flex flex-wrap gap-2">
                  {role === 'lead' ? (
                    <>
                      <button type="button" disabled={gated} className={btnPrimary}
                        onClick={() => { update(sel.id, { status: 'ACCEPTED' }); notify('승인했습니다. 인허가 대장과 기한 알림에 등록됩니다.'); }}>승인</button>
                      <button type="button" disabled={gated} className={btnGhost}
                        onClick={() => { update(sel.id, { status: 'EDITED_ACCEPTED' }); notify('수정 내용으로 승인했습니다. 수정 기록이 남습니다.'); }}>수정 후 승인</button>
                      <button type="button" className={`${btnGhost} text-[#991B1B]`}
                        onClick={() => { update(sel.id, { status: 'REJECTED' }); notify('거절했습니다.'); }}>거절</button>
                    </>
                  ) : (
                    sel.status === 'PENDING' && (
                      <>
                        <button type="button" disabled={gated} className={btnPrimary}
                          onClick={() => { update(sel.id, { status: 'SUBMITTED' }); notify('승인 요청을 보냈습니다.'); }}>승인 요청</button>
                        <button type="button" className={btnGhost}
                          onClick={() => { update(sel.id, { status: 'SUBMITTED' }); notify('거절 요청을 보냈습니다.'); }}>거절 요청</button>
                      </>
                    )
                  )}
                </div>
              )}

              <details
                key={sel.id}
                open={sel.quoteCheck !== 'UNVERIFIABLE' || sel.viewed}
                onToggle={(e) => { if ((e.target as HTMLDetailsElement).open && !sel.viewed) update(sel.id, { viewed: true }); }}
              >
                <summary className="cursor-pointer text-sm text-gray-500">원문 {sel.page}쪽 {sel.viewed && '· 열람함'}</summary>
                <div className="relative mt-2 overflow-hidden rounded border border-[#E5E3DC] bg-[#F7F3EA] px-5 py-4 text-[13px] leading-7 text-[#2B2A26]">
                  <div className="mb-2 flex justify-between text-[11px] opacity-70">
                    <span>광명 자원회수시설 설치사업 환경영향평가 협의의견</span><span>- {sel.page} -</span>
                  </div>
                  <p>{sel.citation.split('-')[1]}. {sel.quoteCheck === 'VERIFIED'
                    ? <mark className="bg-amber-200/70">{sel.quote}</mark>
                    : (sel.pageText ?? sel.quote)}</p>
                  <p className="opacity-60">(이하 생략)</p>
                  {sel.scanned && (
                    <span className="absolute bottom-3 right-5 grid h-14 w-14 rotate-[-12deg] place-items-center rounded-full border-2 border-[#991B1B] text-[11px] font-bold text-[#991B1B] opacity-60">광명시</span>
                  )}
                </div>
              </details>
              <p className="text-xs text-gray-400">기록: AI 추출 (모델·프롬프트 버전 저장){sel.viewed && ' → 원문 열람'} → {ST[sel.status].label}</p>
            </div>
          </section>
        </div>
      </div>
      {toast && (
        <div role="status" className="fixed bottom-6 left-1/2 -translate-x-1/2 rounded-lg bg-gray-900 px-4 py-2 text-sm text-white shadow-lg">{toast}</div>
      )}
    </div>
  );
}

function Badge({ label, cls }: { label: string; cls: string }) {
  return <span className={`inline-block whitespace-nowrap rounded-full px-2.5 py-0.5 text-xs font-medium ${cls}`}>{label}</span>;
}

function Info({ label, value }: { label: string; value: string }) {
  return (
    <div className="text-sm">
      <span className="mb-1 block text-xs font-medium text-gray-500">{label}</span>
      <span className="text-gray-800">{value}</span>
    </div>
  );
}

const TONE = { red: 'bg-red-50 text-[#991B1B]', amber: 'bg-amber-50 text-[#B45309]', sky: 'bg-sky-50 text-sky-800' };
function Callout({ tone, title, children }: { tone: keyof typeof TONE; title: string; children: ReactNode }) {
  return (
    <div className={`flex gap-2 rounded-lg px-3 py-2 text-sm ${TONE[tone]}`}>
      <b className="shrink-0">{title}</b>
      <span className="text-gray-700">{children}</span>
    </div>
  );
}
