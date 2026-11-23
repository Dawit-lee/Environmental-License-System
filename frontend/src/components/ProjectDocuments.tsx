import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { api, apiBlob } from '../api/client';
import { getAiProvider, getCompany, getRun, startScheduleExtraction, type AiRun } from '../api/extraction';
import ComplianceReportDraft from './ComplianceReportDraft';
import { HELP } from './Onboarding';
import { EmptyBlock } from './ListParts';
import type { DocType, PermitDocument, PermitItem } from '../types';
import { Alert } from './ui/Alert';
import { Badge, type Tone } from './ui/Badge';
import { Button } from './ui/Button';
import { Select } from './ui/Field';
import { Table } from './ui/Table';
import { cn, focusRing } from './ui/cn';

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

// 이전 버전은 회색, 지금 쓰는 판(최신·개정본)은 파랑.
const VERSION: Record<'latest' | 'revised' | 'old', { tone: Tone; text: string }> = {
  latest: { tone: 'info', text: '최신' }, revised: { tone: 'info', text: '개정본' }, old: { tone: 'neutral', text: '이전 버전' },
};

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

  if (!docs) return error ? <Alert tone="danger">{error}</Alert> : <p className="text-[15px] text-body">불러오는 중…</p>;

  const permitById = new Map(permits.map((p) => [p.id, p]));
  const superseded = new Set(docs.map((d) => d.supersedesDocumentId).filter((id) => id !== null));

  return (
    <div>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <p className="min-w-0 flex-1 break-keep text-[15px] text-body">
          프로젝트의 모든 문서입니다. 허가증의 의무 추출·버전 비교는 인허가 이름을 눌러 상세에서 합니다.
        </p>
        <Button variant="tertiary" disabled={drafting || permits.length === 0} onClick={() => setDrafting(true)}>
          이행 보고서 초안
        </Button>
      </div>
      {drafting && permits.length > 0 && (
        <ComplianceReportDraft projectId={projectId} permits={permits} onCreated={load}
          initial={{ permitId: Number(params.get('report')) || null, from: params.get('from'), to: params.get('to') }}
          onClose={() => { setDrafting(false); setParams({}, { replace: true }); }} />
      )}

      <section aria-labelledby="doc-upload-title" className="mt-6 rounded border border-line bg-white p-6">
        <h2 id="doc-upload-title" className="text-[19px] font-bold text-ink">프로젝트 문서 올리기</h2>
        <p className="mt-1 break-keep text-[15px] text-body">
          공고문·과업내용서·공문·회의록처럼 인허가 항목에 딸리지 않는 문서를 올립니다. 허가증은 인허가 상세에서 올립니다.
        </p>
        <div className="mt-4 flex flex-wrap items-end gap-3">
          <Select label="문서 종류" className="w-full sm:w-56" value={docType} onChange={(e) => setDocType(e.target.value as DocType)}>
            {Object.entries(DOC_TYPE).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </Select>
          <input ref={fileInput} type="file" accept=".pdf,.jpg,.jpeg,.png,.xlsx" className="hidden"
            onChange={(e) => { const f = e.target.files?.[0]; if (f) upload(f); }} />
          <Button disabled={busy} onClick={() => fileInput.current?.click()} className="w-full sm:w-auto">
            {busy ? '올리는 중…' : '문서 올리기'}
          </Button>
        </div>
        <p className="mt-2 text-[15px] text-body">PDF·JPG·PNG·XLSX 파일을 올릴 수 있습니다.</p>
        {!aiOn && <p className="mt-1 text-[15px] text-body">회사 설정에서 AI 추출이 꺼져 있어 [일정 추출]을 쓸 수 없습니다.</p>}
      </section>

      {error && <Alert tone="danger" className="mt-4">{error}</Alert>}

      <div className="mt-6">
        {docs.length === 0 ? (
          <EmptyBlock title="올린 문서가 없습니다."
            description="위에서 공고문·과업내용서를 올리면 [일정 추출]로 날짜 후보를 받아 일정에 넣습니다." />
        ) : (
          <Table caption="프로젝트 문서 목록" className="min-w-[980px] whitespace-nowrap">
            <thead>
              <tr>
                <th>파일</th>
                <th>종류</th>
                <th>인허가</th>
                <th>버전</th>
                <th className="text-right">효력일</th>
                <th>올린 사람</th>
                <th className="text-right">올린 날</th>
                <th className="text-right">크기</th>
                <th>일정</th>
              </tr>
            </thead>
            <tbody>
              {docs.map((d) => {
                const permit = d.permitItemId != null ? permitById.get(d.permitItemId) : undefined;
                const run = runs[d.id];
                const extractable = d.docType != null && EXTRACTABLE.includes(d.docType) && d.contentType === 'application/pdf';
                const version = superseded.has(d.id) ? VERSION.old : d.supersedesDocumentId ? VERSION.revised : VERSION.latest;
                return (
                  <tr key={d.id}>
                    <td className="max-w-[300px]">
                      <button type="button" onClick={() => download(d)} title={`${d.fileName} 내려받기`}
                        className={cn('block max-w-full truncate text-left font-bold text-brand-text hover:underline', focusRing)}>
                        {d.fileName}
                      </button>
                    </td>
                    <td className="text-body">{d.docType ? DOC_TYPE[d.docType] : '—'}</td>
                    <td className="max-w-[220px]">
                      {permit ? (
                        <button type="button" onClick={() => onOpenPermit(permit)} title={permit.name}
                          className={cn('block max-w-full truncate text-left text-brand-text hover:underline', focusRing)}>{permit.name}</button>
                      ) : '—'}
                    </td>
                    <td><Badge tone={version.tone}>{version.text}</Badge></td>
                    <td className="text-right tabular-nums">{d.effectiveDate ?? '—'}</td>
                    <td className="text-body">{d.uploadedBy}</td>
                    <td className="text-right tabular-nums">{d.uploadedAt.slice(0, 10)}</td>
                    <td className="text-right tabular-nums text-body">{kb(d.size)}</td>
                    <td>
                      {run?.status === 'RUNNING' ? <Badge tone="info">추출 중</Badge>
                        : run?.status === 'FAILED' ? <Badge tone="danger" title={run.errorMessage ?? undefined}>추출 실패</Badge>
                        : run?.status === 'SUCCEEDED' ? <Link to={`/projects/${projectId}/review`} className={cn('text-brand-text hover:underline', focusRing)}>AI 검토에서 확인</Link>
                        : extractable ? (
                          <Button variant="tertiary" size="sm" disabled={!aiOn} onClick={() => extract(d)}
                            title={aiOn ? HELP.extract.text : '회사 설정에서 AI 추출이 꺼져 있습니다'}>
                            일정 추출
                          </Button>
                        ) : <span className="text-body">—</span>}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </Table>
        )}
      </div>
    </div>
  );
}
