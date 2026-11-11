import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router';
import { api, apiBlob } from '../api/client';
import ComplianceReportDraft from './ComplianceReportDraft';
import type { PermitDocument, PermitItem } from '../types';

// ---------------------------------------------------------------------------
// ProjectDocuments — 프로젝트 › 문서: files of every permit in one list. Upload, AI extraction and
// versions stay in the permit drawer (PermitDocuments); rows link there.
// [이행 보고서 초안] opens ComplianceReportDraft; the generated XLSX is a document like any other.
// A 보고·제출 task links here with ?report={permitItemId}&from=&to= (ObligationTaskDrawer).
// ---------------------------------------------------------------------------

const kb = (n: number) => (n >= 1024 * 1024 ? `${(n / 1024 / 1024).toFixed(1)}MB` : `${Math.max(1, Math.round(n / 1024))}KB`);

export default function ProjectDocuments({ projectId, permits, onOpenPermit }: {
  projectId: number; permits: PermitItem[]; onOpenPermit: (p: PermitItem) => void;
}) {
  const [docs, setDocs] = useState<PermitDocument[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [params, setParams] = useSearchParams();
  const [drafting, setDrafting] = useState(params.has('report'));

  const load = () => api<PermitDocument[]>(`/api/projects/${projectId}/documents`).then(setDocs, (e: Error) => setError(e.message));
  useEffect(() => { load(); }, [projectId]);

  const download = async (d: PermitDocument) => {
    try {
      const url = URL.createObjectURL(await apiBlob(`/api/projects/${projectId}/permits/${d.permitItemId}/documents/${d.id}`));
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
        <p className="text-sm text-gray-500">모든 인허가의 첨부 문서입니다. 올리기·AI 추출·버전 비교는 인허가 이름을 눌러 상세에서 합니다.</p>
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
      {error && <p role="alert" className="mt-3 rounded-lg bg-red-50 px-3 py-2 text-sm text-[#991B1B]">{error}</p>}
      <div className="mt-4 overflow-x-auto rounded-xl border border-[#E5E3DC] bg-white shadow-sm">
        <table className="w-full min-w-[860px] whitespace-nowrap text-left text-sm">
          <thead className="bg-[#F8F7F2] text-xs text-gray-500">
            <tr>
              <th className="px-4 py-3 font-medium">파일</th>
              <th className="px-4 py-3 font-medium">인허가</th>
              <th className="px-4 py-3 font-medium">버전</th>
              <th className="px-4 py-3 font-medium">효력일</th>
              <th className="px-4 py-3 font-medium">올린 사람</th>
              <th className="px-4 py-3 font-medium">올린 날</th>
              <th className="px-4 py-3 text-right font-medium">크기</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[#E5E3DC]">
            {docs.map((d) => {
              const permit = permitById.get(d.permitItemId);
              return (
                <tr key={d.id} className="hover:bg-[#F8F7F2]">
                  <td className="max-w-[300px] px-4 py-2.5">
                    <button type="button" onClick={() => download(d)} title={`${d.fileName} 내려받기`}
                      className="block max-w-full truncate text-left font-medium text-gray-800 hover:text-[#1F4E79] hover:underline">
                      {d.fileName}
                    </button>
                  </td>
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
                </tr>
              );
            })}
          </tbody>
        </table>
        {docs.length === 0 && <p className="py-10 text-center text-sm text-gray-400">첨부 문서가 없습니다. 인허가 상세에서 허가증을 올리세요.</p>}
      </div>
    </div>
  );
}
