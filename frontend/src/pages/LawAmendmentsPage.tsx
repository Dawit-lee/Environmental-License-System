import { Fragment, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router';
import {
  getLawImpact,
  getLawImpacts,
  reviewLawImpact,
  type ImpactLevel,
  type LawImpact,
  type LawImpactDetail,
} from '../api/lawImpacts';

// ---------------------------------------------------------------------------
// LawAmendmentsPage — ② law amendment alerts for my permits.
// DIRECT = linked article, the whole law, or a sub-law (시행령·시행규칙) changed;
// REFERENCE = only another article of the same law changed.
// ---------------------------------------------------------------------------

const LEVEL = {
  DIRECT: { label: '직접 영향', cls: 'bg-red-100 text-[#991B1B]' },
  REFERENCE: { label: '참고', cls: 'bg-gray-100 text-gray-600' },
};

const WHOLE_LAW = ['폐지', '타법개정', '일괄개정', '전부개정'];

/** Mirrors the backend rule (ImpactRule) to say why this alert came. */
function why(d: LawImpactDetail): string {
  const i = d.impact;
  const linked = d.linkedArticles.filter((a): a is string => !!a);
  if (i.replacementNeeded) return '연결 법령이 폐지됨 · 대체 법령 확인 필요';
  if (d.linkedArticles.length === 0) return '연결한 법령의 하위 법령(시행령·시행규칙)이 개정됨';
  if (i.level === 'REFERENCE') return `같은 법의 다른 조문만 바뀜 (연결 조문: ${linked.join(', ')})`;
  const mine = d.articles.filter((a) => a.mine).map((a) => a.articleNo);
  if (mine.length) return `지정한 조문(${mine.join(', ')})이 바뀜`;
  if (WHOLE_LAW.some((w) => i.revisionType.includes(w))) return `${i.revisionType} — 조문과 관계없이 직접 영향`;
  return '조문을 지정하지 않았거나 별표를 지정한 연결이라 법령 개정 전체가 대상';
}

/** 법제처 marks changed text with <P>…</P>. */
function Marked({ text }: { text: string }) {
  return (
    <>
      {text.split(/<\/?P>/).map((s, k) =>
        k % 2 ? <mark key={k} className="rounded bg-yellow-100 px-0.5">{s}</mark> : <Fragment key={k}>{s}</Fragment>,
      )}
    </>
  );
}

/** With `projectId` (project tab) only that project's impacts are listed. */
export default function LawAmendmentsPage({ projectId }: { projectId?: number }) {
  const [params, setParams] = useSearchParams();
  const [items, setItems] = useState<LawImpact[] | null>(null);
  const [detail, setDetail] = useState<LawImpactDetail | null>(null);
  const [filter, setFilter] = useState<'ALL' | ImpactLevel | 'NEW'>('ALL');
  const [project, setProject] = useState<number | 'ALL'>('ALL');
  const [memo, setMemo] = useState('');
  const [memoError, setMemoError] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const selectedId = Number(params.get('id')) || items?.[0]?.id || null;

  const load = () => getLawImpacts().then((l) => setItems(projectId ? l.filter((i) => i.projectId === projectId) : l), (e) => setError(e.message));
  useEffect(() => {
    load();
  }, []);

  useEffect(() => {
    if (!selectedId) return setDetail(null);
    setMemoError(false);
    getLawImpact(selectedId).then(
      (d) => {
        setDetail(d);
        setMemo(d.note ?? '');
      },
      (e) => setError(e.message),
    );
  }, [selectedId]);

  if (!items) {
    return <div className={`text-sm text-gray-500 ${projectId ? '' : 'min-h-full bg-[#F8F7F2] p-8'}`}>{error ?? '불러오는 중…'}</div>;
  }

  const projects = [...new Map(items.map((i) => [i.projectId, i.projectName])).entries()];
  const inProject = items.filter((i) => project === 'ALL' || i.projectId === project);
  // the server already sorts DIRECT first, then nearest 시행일
  const rows = inProject.filter((i) => filter === 'ALL' || i.level === filter || (filter === 'NEW' && i.status === 'NEW'));
  const sel = detail?.impact;
  const reviewed = sel?.status === 'REVIEWED';

  const markReviewed = async () => {
    if (!sel) return;
    if (!memo.trim()) return setMemoError(true);
    setMemoError(false);
    setSaving(true);
    try {
      const d = await reviewLawImpact(sel.id, memo.trim());
      setDetail(d);
      setItems((prev) => prev!.map((i) => (i.id === d.impact.id ? d.impact : i)));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const chips: { key: typeof filter; label: string; n: number }[] = [
    { key: 'ALL', label: '전체', n: inProject.length },
    { key: 'DIRECT', label: '직접 영향', n: inProject.filter((i) => i.level === 'DIRECT').length },
    { key: 'REFERENCE', label: '참고', n: inProject.filter((i) => i.level === 'REFERENCE').length },
    { key: 'NEW', label: '미확인', n: inProject.filter((i) => i.status === 'NEW').length },
  ];

  return (
    <div className="min-h-full bg-[#F8F7F2]">
      <div className={projectId ? '' : 'mx-auto max-w-7xl px-4 py-8 sm:px-6'}>
        <p className="text-xs font-semibold tracking-wide text-gray-500">매일 06:00 법제처 개정 목록 확인</p>
        <h1 className="mt-1 text-2xl font-bold text-[#1F4E79]">법령 개정 알림</h1>
        <p className="mt-1 max-w-2xl text-sm text-gray-500">
          연결한 조문이나 하위 법령이 바뀌면 직접 영향, 같은 법의 다른 조문만 바뀌면 참고로 분류합니다.
        </p>
        {error && <p className="mt-2 text-sm text-[#991B1B]">{error}</p>}

        <div className="mt-5 flex flex-wrap items-center gap-2">
          {chips.map((c) => (
            <button key={c.key} type="button" onClick={() => setFilter(c.key)}
              className={`rounded-full px-3.5 py-1.5 text-sm font-medium transition ${filter === c.key ? 'bg-[#1F4E79] text-white' : 'bg-white text-gray-600 hover:bg-[#E5E3DC]'}`}>
              {c.label} ({c.n})
            </button>
          ))}
          {projects.length > 1 && (
            <select aria-label="프로젝트" value={project} onChange={(e) => setProject(e.target.value === 'ALL' ? 'ALL' : Number(e.target.value))}
              className="rounded-full border border-[#E5E3DC] bg-white px-3 py-1.5 text-sm text-gray-600">
              <option value="ALL">모든 프로젝트</option>
              {projects.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
            </select>
          )}
        </div>

        {items.length === 0 ? (
          <p className="mt-6 rounded-xl border border-[#E5E3DC] bg-white p-8 text-center text-sm text-gray-500">
            아직 감지된 법령 개정 영향이 없습니다. 인허가 항목에 근거 법령을 연결하면 개정 시 알려 드립니다.
          </p>
        ) : (
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
                  <tr key={i.id} onClick={() => setParams({ id: String(i.id) })}
                    className={`cursor-pointer transition ${i.id === selectedId ? 'bg-[#1F4E79]/5' : 'hover:bg-[#F8F7F2]'}`}>
                    <td className="px-3 py-2.5"><Badge {...LEVEL[i.level]} /></td>
                    <td className="px-3 py-2.5">
                      <div className="font-medium text-gray-800">{i.lawName}</div>
                      <div className="text-xs text-gray-500">{i.revisionType} · 공포 {i.promulgatedOn ?? '-'}</div>
                    </td>
                    <td className="px-3 py-2.5 text-gray-700">
                      {i.permitName}
                      <div className="text-xs text-gray-400">{i.projectName}</div>
                    </td>
                    <td className="px-3 py-2.5 font-mono text-xs">{i.enforcedOn ?? '-'}</td>
                    <td className="px-3 py-2.5">
                      <Badge label={i.status === 'REVIEWED' ? '확인함' : '미확인'} cls={i.status === 'REVIEWED' ? 'bg-emerald-100 text-[#0F6E56]' : 'bg-amber-100 text-[#B45309]'} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>

          {detail && sel ? (
          <section className="min-w-0 space-y-4 rounded-xl border border-[#E5E3DC] bg-white p-5 shadow-sm">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <h2 className="text-base font-semibold text-gray-800">{sel.lawName}</h2>
              <div className="flex gap-1.5">
                {sel.replacementNeeded && <Badge label="대체 법령 확인 필요" cls="bg-amber-100 text-[#B45309]" />}
                <Badge {...LEVEL[sel.level]} />
              </div>
            </div>
            <dl className="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1.5 text-sm">
              <dt className="text-gray-500">제개정 구분</dt><dd>{sel.revisionType}</dd>
              <dt className="text-gray-500">공포 / 시행</dt><dd className="font-mono text-xs leading-5">{sel.promulgatedOn ?? '-'} / {sel.enforcedOn ?? '-'}</dd>
              <dt className="text-gray-500">왜 알림이 왔나</dt><dd>{why(detail)}</dd>
              <dt className="text-gray-500">영향 인허가</dt><dd>{sel.projectName} / {sel.permitName}</dd>
              <dt className="text-gray-500">개정 이유</dt><dd className="whitespace-pre-line">{detail.reason ?? '-'}</dd>
            </dl>
            <div>
              <p className="mb-1 text-xs font-semibold text-gray-500">바뀐 조문</p>
              {detail.articles.length === 0 && <p className="text-sm text-gray-500">조문 정보 없음</p>}
              {detail.articles.map((a) => (
                <div key={a.articleNo} className="flex flex-wrap items-center gap-2 text-sm">
                  <span className="font-mono">{a.articleNo}</span><span>{a.changeReason}</span>
                  {a.enforcedOn && <span className="text-xs text-gray-500">시행 {a.enforcedOn}</span>}
                  {a.mine && <Badge label="내가 지정한 조문" cls="bg-sky-100 text-sky-800" />}
                </div>
              ))}
            </div>
            {detail.oldNew ? (
              <div className="overflow-hidden rounded-lg border border-[#E5E3DC] text-sm">
                <div className="grid grid-cols-2 bg-[#F8F7F2] text-xs font-semibold text-gray-500">
                  <p className="border-r border-[#E5E3DC] px-3 py-1.5">현행</p><p className="px-3 py-1.5">개정</p>
                </div>
                {detail.oldNew.map((r, k) => (
                  <div key={k} className="grid grid-cols-2 border-t border-[#E5E3DC]">
                    <div className="border-r border-[#E5E3DC] bg-red-50/60 p-3"><Marked text={r.old} /></div>
                    <div className="bg-emerald-50/60 p-3"><Marked text={r.next} /></div>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-sm text-gray-500">신구조문 비교표 준비 중입니다. 다음 배치에서 다시 받아 옵니다.</p>
            )}
            {detail.deadline && (
              <div className="rounded-lg bg-sky-50 px-3 py-2 text-sm text-sky-900">
                <b>시행일 일정</b> · {detail.deadline}을 이 인허가의 기한으로 등록했습니다. D-90 · D-60 · D-30 · D-7에 다시 알립니다.
              </div>
            )}
            <label className="block text-sm">
              <span className="mb-1 block text-xs font-medium text-gray-500">
                확인 메모{reviewed && detail.reviewedByName ? ` · ${detail.reviewedByName}, ${sel.reviewedAt ? new Date(sel.reviewedAt).toLocaleDateString('ko-KR') : ''}` : ''}
              </span>
              <textarea value={memo} disabled={reviewed} onChange={(e) => setMemo(e.target.value)}
                placeholder="예: 운전기록 보존기간 3년으로 운영 매뉴얼 수정 필요"
                className="min-h-[72px] w-full rounded-lg border border-[#E5E3DC] px-3 py-2 text-sm outline-none focus:border-[#1F4E79] focus:ring-2 focus:ring-[#1F4E79]/20" />
              {memoError && <span className="text-xs text-[#991B1B]">확인 메모를 적어 주세요. 예: 영향 없음 / 조치 필요</span>}
            </label>
            <div className="flex flex-wrap gap-2">
              <button type="button" onClick={markReviewed} disabled={reviewed || saving}
                className="rounded-lg bg-[#1F4E79] px-3.5 py-2 text-sm font-semibold text-white transition hover:bg-[#173a5c] disabled:cursor-not-allowed disabled:opacity-40">
                {reviewed ? '확인함' : '확인 처리'}
              </button>
              <a href={detail.sourceUrl} target="_blank" rel="noreferrer"
                className="rounded-lg border border-[#E5E3DC] px-3.5 py-2 text-sm font-semibold text-gray-700 transition hover:bg-[#F8F7F2]">
                법제처 원문 보기
              </a>
            </div>
            <p className="border-t border-[#E5E3DC] pt-3 text-xs text-gray-400">{detail.source}</p>
          </section>
          ) : (
            <section className="rounded-xl border border-[#E5E3DC] bg-white p-5 text-sm text-gray-500 shadow-sm">불러오는 중…</section>
          )}
        </div>
        )}
      </div>
    </div>
  );
}

function Badge({ label, cls }: { label: string; cls: string }) {
  return <span className={`inline-block whitespace-nowrap rounded-full px-2.5 py-0.5 text-xs font-medium ${cls}`}>{label}</span>;
}
