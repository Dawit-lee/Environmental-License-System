import { useState } from 'react';

// ---------------------------------------------------------------------------
// LawAmendmentsPage — ② law amendment alerts for my permits.
// DIRECT = linked article or a sub-law (시행령·시행규칙) changed;
// REFERENCE = only another article of the same law changed. Mock data only.
// ---------------------------------------------------------------------------

type Level = 'DIRECT' | 'REFERENCE';

interface Impact {
  id: number;
  level: Level;
  law: string;
  revisionType: string;
  promulgatedOn: string;
  enforcedOn: string;
  permit: string;
  why: string;
  articles: { no: string; what: string; mine?: boolean }[];
  diff?: { old: string; next: string };
  reason: string;
  reviewed: boolean;
  memo: string;
}

// Sample amendments — not real legal changes.
// TODO: replace with GET /api/law-impacts
const INITIAL: Impact[] = [
  { id: 1, level: 'DIRECT', law: '폐기물관리법 시행규칙', revisionType: '일부개정', promulgatedOn: '2026-11-03', enforcedOn: '2027-03-01',
    permit: '폐기물처리시설 설치승인', why: '연결한 폐기물관리법의 하위 법령이 개정됨',
    articles: [{ no: '별표 9', what: '처리시설 설치기준 중 소각시설 항목' }],
    diff: { old: '연소실 출구 온도는 섭씨 850도 이상을 유지할 것', next: '연소실 출구 온도는 섭씨 850도 이상을 유지하고, 온도 측정 기록을 3년간 보존할 것' },
    reason: '소각시설 운전 기록의 보존 의무를 명확히 하려는 것', reviewed: false, memo: '' },
  { id: 2, level: 'DIRECT', law: '환경영향평가법 시행령', revisionType: '일부개정', promulgatedOn: '2026-10-28', enforcedOn: '2026-12-01',
    permit: '환경영향평가 협의', why: '지정한 조문(제31조)이 바뀜',
    articles: [{ no: '제31조', what: '협의 내용의 관리', mine: true }],
    diff: { old: '협의 내용 관리대장을 작성하여 비치하여야 한다', next: '협의 내용 관리대장을 전자적 방법으로 작성하여 비치하여야 한다' },
    reason: '협의 내용 관리의 전자화', reviewed: false, memo: '' },
  { id: 3, level: 'REFERENCE', law: '건축법', revisionType: '일부개정', promulgatedOn: '2026-10-15', enforcedOn: '2027-04-16',
    permit: '건축허가', why: '같은 법의 다른 조문만 바뀜 (연결 조문: 제11조)',
    articles: [{ no: '제48조', what: '구조내력 등' }], reason: '구조 안전 확인 대상 정비', reviewed: false, memo: '' },
];

const LEVEL = {
  DIRECT: { label: '직접 영향', cls: 'bg-red-100 text-[#991B1B]' },
  REFERENCE: { label: '참고', cls: 'bg-gray-100 text-gray-600' },
};

export default function LawAmendmentsPage() {
  const [items, setItems] = useState(INITIAL);
  const [selectedId, setSelectedId] = useState(1);
  const [filter, setFilter] = useState<'ALL' | Level | 'NEW'>('ALL');
  const [memoError, setMemoError] = useState(false);

  const rows = items
    .filter((i) => filter === 'ALL' || i.level === filter || (filter === 'NEW' && !i.reviewed))
    .sort((a, b) => (a.level === b.level ? a.enforcedOn.localeCompare(b.enforcedOn) : a.level === 'DIRECT' ? -1 : 1));
  const sel = items.find((i) => i.id === selectedId)!;
  const unread = items.filter((i) => !i.reviewed).length;

  const setMemo = (memo: string) => setItems((prev) => prev.map((i) => (i.id === sel.id ? { ...i, memo } : i)));
  const markReviewed = () => {
    if (!sel.memo.trim()) return setMemoError(true);
    setMemoError(false);
    setItems((prev) => prev.map((i) => (i.id === sel.id ? { ...i, reviewed: true } : i)));
  };

  const chips: { key: typeof filter; label: string; n: number }[] = [
    { key: 'ALL', label: '전체', n: items.length },
    { key: 'DIRECT', label: '직접 영향', n: items.filter((i) => i.level === 'DIRECT').length },
    { key: 'REFERENCE', label: '참고', n: items.filter((i) => i.level === 'REFERENCE').length },
    { key: 'NEW', label: '미확인', n: unread },
  ];

  return (
    <div className="min-h-full bg-[#F8F7F2]">
      <div className="mx-auto max-w-7xl px-4 py-8 sm:px-6">
        <p className="text-xs font-semibold tracking-wide text-gray-500">매일 06:00 법제처 개정 목록 확인</p>
        <h1 className="mt-1 text-2xl font-bold text-[#1F4E79]">법령 개정 알림</h1>
        <p className="mt-1 max-w-2xl text-sm text-gray-500">
          연결한 조문이나 하위 법령이 바뀌면 직접 영향, 같은 법의 다른 조문만 바뀌면 참고로 분류합니다.
        </p>
        <p className="mt-2 inline-block rounded bg-amber-50 px-2 py-0.5 text-xs text-[#B45309]">예시 데이터 · 실제 개정 내용 아님</p>

        <div className="mt-5 flex flex-wrap gap-2">
          {chips.map((c) => (
            <button key={c.key} type="button" onClick={() => setFilter(c.key)}
              className={`rounded-full px-3.5 py-1.5 text-sm font-medium transition ${filter === c.key ? 'bg-[#1F4E79] text-white' : 'bg-white text-gray-600 hover:bg-[#E5E3DC]'}`}>
              {c.label} ({c.n})
            </button>
          ))}
        </div>

        <div className="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-2">
          <section className="min-w-0 overflow-x-auto rounded-xl border border-[#E5E3DC] bg-white shadow-sm">
            <table className="w-full min-w-[560px] text-left text-sm">
              <thead className="bg-[#F8F7F2] text-xs text-gray-500">
                <tr>
                  <th className="px-3 py-2.5 font-medium">구분</th>
                  <th className="px-3 py-2.5 font-medium">법령</th>
                  <th className="px-3 py-2.5 font-medium">영향받는 인허가</th>
                  <th className="px-3 py-2.5 font-medium">시행일</th>
                  <th className="px-3 py-2.5 font-medium">상태</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[#E5E3DC]">
                {rows.map((i) => (
                  <tr key={i.id} onClick={() => { setSelectedId(i.id); setMemoError(false); }}
                    className={`cursor-pointer transition ${i.id === selectedId ? 'bg-[#1F4E79]/5' : 'hover:bg-[#F8F7F2]'}`}>
                    <td className="px-3 py-2.5"><Badge {...LEVEL[i.level]} /></td>
                    <td className="px-3 py-2.5">
                      <div className="font-medium text-gray-800">{i.law}</div>
                      <div className="text-xs text-gray-500">{i.revisionType} · 공포 {i.promulgatedOn}</div>
                    </td>
                    <td className="px-3 py-2.5 text-gray-700">{i.permit}</td>
                    <td className="px-3 py-2.5 font-mono text-xs">{i.enforcedOn}</td>
                    <td className="px-3 py-2.5">
                      <Badge label={i.reviewed ? '확인함' : '미확인'} cls={i.reviewed ? 'bg-emerald-100 text-[#0F6E56]' : 'bg-amber-100 text-[#B45309]'} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>

          <section className="min-w-0 space-y-4 rounded-xl border border-[#E5E3DC] bg-white p-5 shadow-sm">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <h2 className="text-base font-semibold text-gray-800">{sel.law}</h2>
              <Badge {...LEVEL[sel.level]} />
            </div>
            <dl className="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1.5 text-sm">
              <dt className="text-gray-500">공포 / 시행</dt><dd className="font-mono text-xs leading-5">{sel.promulgatedOn} / {sel.enforcedOn}</dd>
              <dt className="text-gray-500">왜 알림이 왔나</dt><dd>{sel.why}</dd>
              <dt className="text-gray-500">영향 인허가</dt><dd>{sel.permit}</dd>
              <dt className="text-gray-500">개정 이유</dt><dd>{sel.reason}</dd>
            </dl>
            <div>
              <p className="mb-1 text-xs font-semibold text-gray-500">바뀐 조문</p>
              {sel.articles.map((a) => (
                <div key={a.no} className="flex flex-wrap items-center gap-2 text-sm">
                  <span className="font-mono">{a.no}</span><span>{a.what}</span>
                  {a.mine && <Badge label="내가 지정한 조문" cls="bg-sky-100 text-sky-800" />}
                </div>
              ))}
            </div>
            {sel.diff ? (
              <div className="grid grid-cols-1 overflow-hidden rounded-lg border border-[#E5E3DC] text-sm sm:grid-cols-2">
                <div className="border-b border-[#E5E3DC] bg-red-50/60 p-3 sm:border-b-0 sm:border-r">
                  <p className="mb-1 text-xs font-semibold text-gray-500">현행</p>{sel.diff.old}
                </div>
                <div className="bg-emerald-50/60 p-3">
                  <p className="mb-1 text-xs font-semibold text-gray-500">개정</p>{sel.diff.next}
                </div>
              </div>
            ) : (
              <p className="text-sm text-gray-500">참고 알림은 신구조문 비교 대신 법제처 원문 링크를 제공합니다.</p>
            )}
            <div className="rounded-lg bg-sky-50 px-3 py-2 text-sm text-sky-900">
              <b>시행일 일정</b> · {sel.enforcedOn}을 이 인허가의 기한으로 등록했습니다. D-90 · D-30 · D-7에 다시 알립니다.
            </div>
            <label className="block text-sm">
              <span className="mb-1 block text-xs font-medium text-gray-500">확인 메모</span>
              <textarea value={sel.memo} disabled={sel.reviewed} onChange={(e) => setMemo(e.target.value)}
                placeholder="예: 운전기록 보존기간 3년으로 운영 매뉴얼 수정 필요"
                className="min-h-[72px] w-full rounded-lg border border-[#E5E3DC] px-3 py-2 text-sm outline-none focus:border-[#1F4E79] focus:ring-2 focus:ring-[#1F4E79]/20" />
              {memoError && <span className="text-xs text-[#991B1B]">확인 메모를 적어 주세요. 예: 영향 없음 / 조치 필요</span>}
            </label>
            <div className="flex flex-wrap gap-2">
              <button type="button" onClick={markReviewed} disabled={sel.reviewed}
                className="rounded-lg bg-[#1F4E79] px-3.5 py-2 text-sm font-semibold text-white transition hover:bg-[#173a5c] disabled:cursor-not-allowed disabled:opacity-40">
                {sel.reviewed ? '확인함' : '확인 처리'}
              </button>
              <a href="https://www.law.go.kr" target="_blank" rel="noreferrer"
                className="rounded-lg border border-[#E5E3DC] px-3.5 py-2 text-sm font-semibold text-gray-700 transition hover:bg-[#F8F7F2]">
                법제처 원문 보기
              </a>
            </div>
            <p className="border-t border-[#E5E3DC] pt-3 text-xs text-gray-400">출처: 법제처 국가법령정보센터</p>
          </section>
        </div>
      </div>
    </div>
  );
}

function Badge({ label, cls }: { label: string; cls: string }) {
  return <span className={`inline-block whitespace-nowrap rounded-full px-2.5 py-0.5 text-xs font-medium ${cls}`}>{label}</span>;
}
