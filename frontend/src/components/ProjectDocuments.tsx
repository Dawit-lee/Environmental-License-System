import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { api, apiBlob } from '../api/client';
import { getAiProvider, getCompany, getRun, startScheduleExtraction, type AiRun } from '../api/extraction';
import ComplianceReportDraft from './ComplianceReportDraft';
import type { DocType, PermitDocument, PermitItem } from '../types';

// ---------------------------------------------------------------------------
// ProjectDocuments — 프로젝트 › 문서: files of every permit in one list. Upload, AI extraction and
// versions stay in the permit drawer (PermitDocuments); rows link there.
// [이행 보고서 초안] opens ComplianceReportDraft; the generated XLSX is a document like any other.
// A 보고·제출 task links here with ?report={permitItemId}&from=&to= (ObligationTaskDrawer).
// design-schedule/07: [문서 올리기] adds a project document without a permit item (공고문·과업내용서·공문·회의록) with
// its 종류; [일정 추출] on such a PDF sends it to AI and its dates wait in the AI 검토 tab until approved.
// ---------------------------------------------------------------------------

export const DOC_TYPE: Record<DocType, string> = {
  BID_NOTICE: '입찰공고문', SITE_BRIEFING_DOC: '현장설명서', TASK_SPEC: '과업내용서', MINUTES: '회의록',
  OFFICIAL_LETTER: '공문', WEEKLY_REPORT: '주간공정보고',
};
const EXTRACTABLE: DocType[] = ['BID_NOTICE', 'SITE_BRIEFING_DOC', 'TASK_SPEC', 'MINUTES', 'OFFICIAL_LETTER'];

const kb = (n: number) => (n >= 1024 * 1024 ? `${(n / 1024 / 1024).toFixed(1)}MB` : `${Math.max(1, Math.round(n / 1024))}KB`);

export default function ProjectDocuments({ projectId, permits, onOpenPermit }: {
  projectId: number; permits: PermitItem[]; onOpenPermit: (p: PermitItem) => void;
}) {
  const [docs, setDocs] = useState<PermitDocument[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [params, setParams] = useSearchParams();
  const [drafting, setDrafting] = useState(params.has('report'));
  const [docType, setDocType] = useState<DocType>('BID_NOTICE');
  const [busy, setBusy] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const [aiOn, setAiOn] = useState(false);
  const [service, setService] = useState('외부 AI');
  const [runs, setRuns] = useState<Record<number, AiRun>>({}); // schedule run started here, per document id

  useEffect(() => {
    getCompany().then((c) => setAiOn(c.aiExtractionEnabled), () => setAiOn(false));
    getAiProvider().then((p) => setService(p.label), () => {});
  }, []);

  // poll running schedule extractions every 2s
  useEffect(() => {
    const running = Object.entries(runs).filter(([, r]) => r.status === 'RUNNING');
    if (!running.length) return;
    const t = window.setTimeout(async () => {
      const next = await Promise.all(running.map(([d, r]) => getRun(r.id).then((n) => [d, n] as const, () => [d, r] as const)));
      setRuns((prev) => ({ ...prev, ...Object.fromEntries(next) }));
    }, 2000);
    return () => window.clearTimeout(t);
  }, [runs]);

  const upload = async (file: File) => {
    setError(null);
    const form = new FormData();
    form.append('file', file);
    form.append('docType', docType);
    setBusy(true);
    try {
      await api<PermitDocument>(`/api/projects/${projectId}/documents`, { method: 'POST', body: form });
      await load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
      if (fileInput.current) fileInput.current.value = '';
    }
  };

  const extract = async (d: PermitDocument) => {
    if (!window.confirm(`AI 추출을 실행하면 이 문서가 외부 AI 서비스(${service})로 전송됩니다.\n\n'${d.fileName}'에서 일정 날짜를 찾을까요?`)) return;
    setError(null);
    try {
      const { runId } = await startScheduleExtraction(projectId, d.id);
      setRuns((r) => ({ ...r, [d.id]: { id: runId, status: 'RUNNING', errorMessage: null, inputRef: null, provider: null,
        model: null, startedAt: '', finishedAt: null } }));
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const load = () => api<PermitDocument[]>(`/api/projects/${projectId}/documents`).then(setDocs, (e: Error) => setError(e.message));
  useEffect(() => { load(); }, [projectId]);

  const download = async (d: PermitDocument) => {
    try {
      const url = URL.createObjectURL(await apiBlob(`/api/projects/${projectId}/documents/${d.id}`));
      const a = document.createElement('a');
      a.href = url;
      a.download = d.fileName;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  if (!docs) return <p className="text-sm text-gray-400">{error ?? '불러오는 중…'}</p>;

  const permitById = new Map(permits.map((p) => [p.id, p]));
  const superseded = new Set(docs.map((d) => d.supersedesDocumentId).filter((id) => id !== null));

  return (
    <div>
      <div className="flex items-center justify-between gap-3">
        <p className="text-sm text-gray-500">프로젝트의 모든 문서입니다. 허가증의 의무 추출·버전 비교는 인허가 이름을 눌러 상세에서 합니다.</p>
        <button type="button" disabled={drafting || permits.length === 0} onClick={() => setDrafting(true)}
          className="shrink-0 rounded-lg border border-[#E5E3DC] bg-white px-3 py-1.5 text-xs font-medium text-gray-700 hover:border-[#1F4E79] hover:text-[#1F4E79] disabled:opacity-40">
          이행 보고서 초안
        </button>
      </div>
      {drafting && permits.length > 0 && (
        <ComplianceReportDraft projectId={projectId} permits={permits} onCreated={load}
          initial={{ permitId: Number(params.get('report')) || null, from: params.get('from'), to: params.get('to') }}
          onClose={() => { setDrafting(false); setParams({}, { replace: true }); }} />
      )}
      <div className="mt-3 flex flex-wrap items-center gap-2 rounded-xl border border-[#E5E3DC] bg-white px-4 py-3 text-sm">
        <span className="text-gray-600">인허가 항목 없이 프로젝트 문서로 올리기</span>
        <select aria-label="문서 종류" value={docType} onChange={(e) => setDocType(e.target.value as DocType)}
          className="rounded-lg border border-[#E5E3DC] px-2 py-1 text-sm">
          {Object.entries(DOC_TYPE).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
        <input ref={fileInput} type="file" accept=".pdf,.jpg,.jpeg,.png,.xlsx" className="hidden"
          onChange={(e) => { const f = e.target.files?.[0]; if (f) upload(f); }} />
        <button type="button" disabled={busy} onClick={() => fileInput.current?.click()}
          className="rounded-lg bg-[#1F4E79] px-3 py-1.5 text-xs font-semibold text-white hover:bg-[#173a5c] disabled:opacity-40">
          {busy ? '올리는 중…' : '문서 올리기'}
        </button>
        {!aiOn && <span className="text-xs text-gray-400">회사 설정에서 AI 추출이 꺼져 있어 [일정 추출]을 쓸 수 없습니다.</span>}
      </div>
      {error && <p role="alert" className="mt-3 rounded-lg bg-red-50 px-3 py-2 text-sm text-[#991B1B]">{error}</p>}
      <div className="mt-4 overflow-x-auto rounded-xl border border-[#E5E3DC] bg-white shadow-sm">
        <table className="w-full min-w-[980px] whitespace-nowrap text-left text-sm">
          <thead className="bg-[#F8F7F2] text-xs text-gray-500">
            <tr>
              <th className="px-4 py-3 font-medium">파일</th>
              <th className="px-4 py-3 font-medium">종류</th>
              <th className="px-4 py-3 font-medium">인허가</th>
              <th className="px-4 py-3 font-medium">버전</th>
              <th className="px-4 py-3 font-medium">효력일</th>
              <th className="px-4 py-3 font-medium">올린 사람</th>
              <th className="px-4 py-3 font-medium">올린 날</th>
              <th className="px-4 py-3 text-right font-medium">크기</th>
              <th className="px-4 py-3 font-medium">일정</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[#E5E3DC]">
            {docs.map((d) => {
              const permit = d.permitItemId != null ? permitById.get(d.permitItemId) : undefined;
              const run = runs[d.id];
              const extractable = d.docType != null && EXTRACTABLE.includes(d.docType) && d.contentType === 'application/pdf';
              return (
                <tr key={d.id} className="hover:bg-[#F8F7F2]">
                  <td className="max-w-[300px] px-4 py-2.5">
                    <button type="button" onClick={() => download(d)} title={`${d.fileName} 내려받기`}
                      className="block max-w-full truncate text-left font-medium text-gray-800 hover:text-[#1F4E79] hover:underline">
                      {d.fileName}
                    </button>
                  </td>
                  <td className="px-4 py-2.5 text-gray-600">{d.docType ? DOC_TYPE[d.docType] : '—'}</td>
                  <td className="max-w-[220px] px-4 py-2.5">
                    {permit ? (
                      <button type="button" onClick={() => onOpenPermit(permit)} title={permit.name}
                        className="block max-w-full truncate text-[#1F4E79] hover:underline">{permit.name}</button>
                    ) : '—'}
                  </td>
                  <td className="px-4 py-2.5 text-gray-600">
                    {superseded.has(d.id) ? '이전 버전' : d.supersedesDocumentId ? '개정본' : '최신'}
                  </td>
                  <td className="px-4 py-2.5 text-gray-600">{d.effectiveDate ?? '—'}</td>
                  <td className="px-4 py-2.5 text-gray-600">{d.uploadedBy}</td>
                  <td className="px-4 py-2.5 text-gray-600">{d.uploadedAt.slice(0, 10)}</td>
                  <td className="px-4 py-2.5 text-right text-gray-500">{kb(d.size)}</td>
                  <td className="px-4 py-2.5 text-xs">
                    {run?.status === 'RUNNING' ? <span className="text-gray-500">추출 중…</span>
                      : run?.status === 'FAILED' ? <span className="text-[#991B1B]" title={run.errorMessage ?? undefined}>실패</span>
                      : run?.status === 'SUCCEEDED' ? <Link to={`/projects/${projectId}/review`} className="text-[#1F4E79] hover:underline">AI 검토에서 확인</Link>
                      : extractable ? (
                        <button type="button" disabled={!aiOn} onClick={() => extract(d)}
                          title={aiOn ? undefined : '회사 설정에서 AI 추출이 꺼져 있습니다'}
                          className="rounded-md border border-[#E5E3DC] px-2 py-1 font-medium text-gray-700 hover:border-[#1F4E79] hover:text-[#1F4E79] disabled:opacity-40">
                          일정 추출
                        </button>
                      ) : <span className="text-gray-300">—</span>}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {docs.length === 0 && <p className="py-10 text-center text-sm text-gray-400">문서가 없습니다. 공고문·과업내용서는 위에서, 허가증은 인허가 상세에서 올리세요.</p>}
      </div>
    </div>
  );
}
