import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { LawCandidate, LawCatalogItem, LawKind, PermitLawLink } from '../types';

// "관련 법령" area of the permit drawer: linked laws (+ optional article), add from the
// catalog autocomplete, or from a 법제처 search when the catalog has no match.

const KIND_LABEL: Record<LawKind, string> = { LAW: '법률', DECREE: '시행령', RULE: '시행규칙', ORDINANCE: '조례' };
const SOURCE_LABEL: Record<PermitLawLink['source'], string> = {
  MANUAL: '직접 입력',
  AI_EXTRACTED: 'AI 추출',
  DERIVED: '자동 도출',
};

const today = () => new Date().toLocaleDateString('sv-SE');

function Revision({ promulgated, enforced }: { promulgated: string | null; enforced: string | null }) {
  const upcoming = enforced !== null && enforced > today();
  return (
    <span className="text-xs text-gray-500">
      최근 개정 {promulgated ?? '—'} ·{' '}
      <span className={upcoming ? 'font-semibold text-[#B45309]' : ''}>
        {upcoming ? '시행 예정' : '시행'} {enforced ?? '—'}
      </span>
    </span>
  );
}

const lawUrl = (name: string) => `https://www.law.go.kr/법령/${encodeURIComponent(name)}`;

export default function PermitLawLinks({ permitId }: { permitId: number }) {
  const base = `/api/permit-items/${permitId}/law-links`;
  const [links, setLinks] = useState<PermitLawLink[]>([]);
  const [query, setQuery] = useState('');
  const [suggestions, setSuggestions] = useState<LawCatalogItem[]>([]);
  const [suggestedFor, setSuggestedFor] = useState(''); // query the suggestions belong to
  const [candidates, setCandidates] = useState<LawCandidate[] | null>(null);
  const [selected, setSelected] = useState<LawCatalogItem | null>(null);
  const [articleNo, setArticleNo] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setLinks([]);
    api<PermitLawLink[]>(base).then(setLinks, (e) => setError(e.message));
  }, [base]);

  useEffect(() => {
    if (!query.trim() || selected) return setSuggestions([]);
    const t = setTimeout(() => {
      api<LawCatalogItem[]>(`/api/laws?query=${encodeURIComponent(query)}`).then((r) => {
        setSuggestions(r);
        setSuggestedFor(query);
      }, () => {});
    }, 200);
    return () => clearTimeout(t);
  }, [query, selected]);

  async function run(task: () => Promise<void>) {
    setBusy(true);
    setError('');
    try {
      await task();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const pick = (law: LawCatalogItem) => {
    setSelected(law);
    setQuery(law.name);
    setCandidates(null);
  };

  const reset = () => {
    setSelected(null);
    setQuery('');
    setArticleNo('');
    setCandidates(null);
  };

  const searchLawGoKr = () =>
    run(async () => {
      setCandidates(await api<LawCandidate[]>(`/api/laws/search?query=${encodeURIComponent(query.trim())}`, { method: 'POST' }));
    });

  const addCandidate = (c: LawCandidate) =>
    run(async () => {
      pick(await api<LawCatalogItem>('/api/laws', { method: 'POST', body: JSON.stringify({ lawId: c.lawId, query: query.trim() }) }));
    });

  const link = () =>
    run(async () => {
      const l = await api<PermitLawLink>(base, {
        method: 'POST',
        body: JSON.stringify({ lawCatalogId: selected!.id, articleNo: articleNo || null }),
      });
      setLinks((prev) => (prev.some((p) => p.id === l.id) ? prev : [...prev, l]));
      reset();
    });

  const unlink = (id: number) =>
    run(async () => {
      await api<void>(`${base}/${id}`, { method: 'DELETE' });
      setLinks((prev) => prev.filter((l) => l.id !== id));
    });

  const input = 'w-full rounded-lg border border-[#E5E3DC] px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/30';

  return (
    <div className="space-y-3">
      {links.length === 0 ? (
        <p className="text-sm text-gray-400">연결된 법령이 없습니다.</p>
      ) : (
        <ul className="space-y-2">
          {links.map((l) => (
            <li key={l.id} className="flex items-start justify-between gap-2 rounded-lg border border-[#E5E3DC] px-3 py-2">
              <div className="min-w-0">
                <a href={lawUrl(l.name)} target="_blank" rel="noreferrer" className="text-sm font-medium text-[#1F4E79] hover:underline">
                  {l.name}
                  {l.articleNo && ` ${l.articleNo}`}
                </a>
                <span className="ml-2 rounded-full bg-gray-100 px-2 py-0.5 text-xs text-gray-600">{SOURCE_LABEL[l.source]}</span>
                <div>
                  <Revision promulgated={l.lastPromulgatedOn} enforced={l.lastEnforcedOn} />
                </div>
              </div>
              <button
                type="button"
                onClick={() => unlink(l.id)}
                disabled={busy}
                aria-label={`${l.name} 연결 삭제`}
                className="shrink-0 rounded p-1 text-gray-400 hover:bg-[#F8F7F2] hover:text-[#991B1B]"
              >
                ✕
              </button>
            </li>
          ))}
        </ul>
      )}

      {/* Add */}
      <div className="relative">
        <input
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            setSelected(null);
            setCandidates(null);
          }}
          placeholder="법령 추가: 법령명 입력 (예: 폐기물관리법)"
          aria-label="법령명"
          className={input}
        />
        {query.trim() && !selected && candidates === null && (
          <div className="absolute z-10 mt-1 w-full rounded-lg border border-[#E5E3DC] bg-white shadow-lg">
            <ul role="listbox" aria-label="카탈로그 법령">
              {suggestions.map((s) => (
                <li key={s.id}>
                  <button type="button" onClick={() => pick(s)} className="flex w-full justify-between px-3 py-2 text-left text-sm hover:bg-[#F8F7F2]">
                    <span>{s.name}</span>
                    <span className="text-xs text-gray-400">{KIND_LABEL[s.kind]}</span>
                  </button>
                </li>
              ))}
            </ul>
            {suggestions.length === 0 && suggestedFor === query && <p className="px-3 pt-2 text-xs text-gray-400">카탈로그에 없는 법령입니다.</p>}
            <button
              type="button"
              onClick={searchLawGoKr}
              disabled={busy}
              className="w-full border-t border-[#E5E3DC] px-3 py-2 text-left text-sm font-medium text-[#1F4E79] hover:bg-[#F8F7F2]"
            >
              법제처에서 '{query.trim()}' 검색
            </button>
          </div>
        )}
      </div>

      {candidates !== null && (
        <div className="rounded-lg border border-[#E5E3DC] p-2">
          <p className="px-1 pb-1 text-xs text-gray-500">
            법제처 검색 결과 (이름이 정확히 같은 현행 법령과 그 시행령·시행규칙)
          </p>
          {candidates.length === 0 ? (
            <p className="px-1 py-2 text-sm text-gray-400">정확히 일치하는 현행 법령이 없습니다. 법령명 전체를 입력해 보세요.</p>
          ) : (
            <ul className="space-y-1">
              {candidates.map((c) => (
                <li key={c.lawId} className="flex items-center justify-between gap-2 rounded px-1 py-1 hover:bg-[#F8F7F2]">
                  <div className="min-w-0">
                    <div className="text-sm text-gray-800">
                      {c.name} <span className="text-xs text-gray-400">{KIND_LABEL[c.kind]}</span>
                    </div>
                    <Revision promulgated={c.promulgatedOn} enforced={c.enforcedOn} />
                  </div>
                  <button
                    type="button"
                    onClick={() => addCandidate(c)}
                    disabled={busy}
                    className="shrink-0 rounded-lg border border-[#1F4E79] px-2.5 py-1 text-xs font-medium text-[#1F4E79] hover:bg-[#1F4E79] hover:text-white"
                  >
                    {c.catalogId ? '선택' : '카탈로그에 추가'}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      {selected && (
        <div className="space-y-2 rounded-lg bg-[#F8F7F2] p-3">
          <div>
            <div className="text-sm font-medium text-gray-900">
              {selected.name} <span className="text-xs font-normal text-gray-400">{KIND_LABEL[selected.kind]}</span>
            </div>
            <Revision promulgated={selected.lastPromulgatedOn} enforced={selected.lastEnforcedOn} />
          </div>
          <input
            value={articleNo}
            onChange={(e) => setArticleNo(e.target.value)}
            placeholder="조문 (선택, 예: 제29조, 제29조의2, 별표3)"
            aria-label="조문"
            className={`${input} bg-white`}
          />
          <div className="flex gap-2">
            <button
              type="button"
              onClick={link}
              disabled={busy}
              className="rounded-lg bg-[#1F4E79] px-3.5 py-2 text-sm font-semibold text-white hover:bg-[#173b5c] disabled:opacity-50"
            >
              연결
            </button>
            <button type="button" onClick={reset} className="rounded-lg px-3 py-2 text-sm text-gray-600 hover:bg-white">
              취소
            </button>
          </div>
        </div>
      )}

      {error && <p className="text-sm text-[#991B1B]">{error}</p>}
      <p className="text-xs text-gray-400">출처: 법제처 국가법령정보센터</p>
    </div>
  );
}
