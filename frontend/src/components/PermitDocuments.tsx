import { useEffect, useRef, useState } from 'react';
import type { DocType, PermitDocument, PermitItem } from '../types';
import { DOC_TYPE } from './ProjectDocuments';
import { Link } from 'react-router';
import { api, apiBlob, isMaster, type ApiError } from '../api/client';
import { getAiProvider, getCompany, getRun, listRuns, setAiExtraction, startExtraction, startRevision, type AiRun } from '../api/extraction';
import { listRevisions, listSuggestions } from '../api/review';

// Attached files of one permit: list, upload, download, delete, and AI extraction of PDFs. Lives in PermitDetailDrawer.

const notice = (service: string) => `AI 추출을 실행하면 이 문서가 외부 AI 서비스(${service})로 전송됩니다.`;

const MAX_BYTES = 10 * 1024 * 1024;
const ACCEPT = '.pdf,.jpg,.jpeg,.png,.xlsx';

function sizeLabel(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

export default function PermitDocuments({ permit }: { permit: PermitItem }) {
  const base = `/api/projects/${permit.projectId}/permits/${permit.id}/documents`;
  const [docs, setDocs] = useState<PermitDocument[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const [aiOn, setAiOn] = useState(false);
  const [service, setService] = useState('외부 AI');
  const TRANSFER_NOTICE = notice(service);
  const [runs, setRuns] = useState<Record<number, AiRun>>({}); // latest run per document id
  const [found, setFound] = useState<Record<number, number>>({}); // run id → suggestions extracted
  const [docType, setDocType] = useState<DocType | ''>(''); // optional; 일정 추출 runs from the 문서 tab only
  const [supersedes, setSupersedes] = useState(''); // ④ "이전 버전 대체": id of the document the upload replaces
  const [effectiveDate, setEffectiveDate] = useState('');
  const latest = docs.filter((d) => !docs.some((x) => x.supersedesDocumentId === d.id)); // one per version line

  useEffect(() => {
    getCompany().then((c) => setAiOn(c.aiExtractionEnabled), () => setAiOn(false));
    getAiProvider().then((p) => setService(p.label), () => {});
    listRuns(permit.projectId).then((rs) => {
      const latest: Record<number, AiRun> = {};
      for (const r of rs) {
        const d = r.inputRef?.document_id;
        if (r.inputRef?.doc_type) continue; // schedule extraction (프로젝트 › 문서), not this drawer's
        if (d != null && !(d in latest)) latest[d] = r; // newest first
      }
      setRuns(latest);
    }, () => setRuns({}));
  }, [permit.projectId]);

  // poll running extractions every 2s; count the rows of finished ones
  useEffect(() => {
    const all = Object.values(runs);
    all.filter((r) => r.status === 'SUCCEEDED' && !(r.id in found)).forEach((r) =>
      (r.inputRef?.prev_document_id ? listRevisions : listSuggestions)(permit.projectId, r.id)
        .then((s) => setFound((f) => ({ ...f, [r.id]: s.length })), () => {}));
    const running = all.filter((r) => r.status === 'RUNNING');
    if (!running.length) return;
    const t = window.setTimeout(async () => {
      const next = await Promise.all(running.map((r) => getRun(r.id).catch(() => r)));
      setRuns((prev) => {
        const m = { ...prev };
        next.forEach((r) => { if (r.inputRef) m[r.inputRef.document_id] = r; });
        return m;
      });
    }, 2000);
    return () => window.clearTimeout(t);
  }, [runs, found, permit.projectId]);

  async function extract(doc: PermitDocument) {
    const revision = doc.supersedesDocumentId != null;
    const ask = revision ? '이전 버전과 비교해 바뀐 조건을 찾을까요?' : '이행 의무를 추출할까요?';
    if (!window.confirm(`${TRANSFER_NOTICE}\n\n'${doc.fileName}'에서 ${ask}`)) return;
    setError(null);
    try {
      let res;
      if (revision) res = await startRevision(permit.projectId, permit.id, doc.id);
      else try {
        res = await startExtraction(permit.projectId, permit.id, doc.id);
      } catch (e) {
        if ((e as ApiError).status !== 409) throw e;
        if (!window.confirm(`${(e as Error).message}\n\n다시 추출할까요?`)) return;
        res = await startExtraction(permit.projectId, permit.id, doc.id, true);
      }
      setRuns((r) => ({ ...r, [doc.id]: { id: res.runId, status: 'RUNNING', errorMessage: null, provider: null, model: null,
        inputRef: { document_id: doc.id, permit_item_id: permit.id, sha256: doc.sha256,
          prev_document_id: doc.supersedesDocumentId ?? undefined }, startedAt: '', finishedAt: null } }));
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function turnOnAi() {
    if (!window.confirm(`회사 전체에서 AI 추출을 켭니다. ${TRANSFER_NOTICE} 켤까요?`)) return;
    try {
      setAiOn((await setAiExtraction(true)).aiExtractionEnabled);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  useEffect(() => {
    setDocs([]);
    setError(null);
    api<PermitDocument[]>(base).then(setDocs, (e) => setError(e.message));
  }, [base]);

  async function upload(file: File) {
    setError(null);
    // same checks as the server, so the user is told before a 10MB+ upload starts
    const ext = file.name.split('.').pop()?.toLowerCase() ?? '';
    if (!ACCEPT.split(',').includes(`.${ext}`)) return setError('PDF, JPG, PNG, XLSX 파일만 올릴 수 있습니다.');
    if (file.size > MAX_BYTES) return setError('파일은 10MB 이하만 올릴 수 있습니다.');
    const form = new FormData();
    form.append('file', file);
    if (docType) form.append('docType', docType);
    if (supersedes) form.append('supersedesDocumentId', supersedes);
    if (supersedes && effectiveDate) form.append('effectiveDate', effectiveDate);
    setBusy(true);
    try {
      const doc = await api<PermitDocument>(base, { method: 'POST', body: form });
      setDocs((d) => [doc, ...d]);
      setSupersedes('');
      setEffectiveDate('');
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function download(doc: PermitDocument) {
    setError(null);
    try {
      const url = URL.createObjectURL(await apiBlob(`${base}/${doc.id}`));
      const a = document.createElement('a');
      a.href = url;
      a.download = doc.fileName;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function remove(doc: PermitDocument) {
    if (!window.confirm(`'${doc.fileName}' 문서를 삭제할까요?`)) return;
    setError(null);
    try {
      await api(`${base}/${doc.id}`, { method: 'DELETE' });
      setDocs((d) => d.filter((x) => x.id !== doc.id));
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <div className="space-y-2">
      {docs.length === 0 ? (
        <div className="rounded border border-dashed border-line p-4 text-center text-sm text-mute">
          첨부된 문서가 없습니다
        </div>
      ) : (
        <ul className="divide-y divide-line rounded border border-line">
          {docs.map((d) => (
            <li key={d.id} className="flex items-center gap-3 px-3 py-2.5">
              <div className="min-w-0 flex-1">
                <button
                  type="button"
                  onClick={() => download(d)}
                  className="block max-w-full truncate text-left text-sm font-medium text-brand hover:underline focus:outline-none focus:ring-2 focus:ring-brand/30"
                >
                  {d.fileName}
                </button>
                <p className="text-xs text-mute">
                  {sizeLabel(d.size)} · {d.uploadedBy} · {new Date(d.uploadedAt).toLocaleDateString('sv-SE')}
                  {d.supersedesDocumentId != null &&
                    ` · ${docs.find((x) => x.id === d.supersedesDocumentId)?.fileName ?? '이전 문서'} 대체`}
                  {d.effectiveDate && ` · 시행 ${d.effectiveDate}`}
                </p>
                {runs[d.id] && <RunStatus projectId={permit.projectId} run={runs[d.id]} count={found[runs[d.id].id]} onRetry={() => extract(d)} />}
              </div>
              {d.contentType === 'application/pdf' && (
                <button
                  type="button"
                  onClick={() => extract(d)}
                  disabled={!aiOn || runs[d.id]?.status === 'RUNNING'}
                  title={aiOn ? TRANSFER_NOTICE : '회사 설정에서 AI 추출이 꺼져 있습니다'}
                  className="whitespace-nowrap rounded border border-line px-2 py-1 text-xs font-medium text-brand transition hover:bg-paper focus:outline-none focus:ring-2 focus:ring-brand/30 disabled:cursor-not-allowed disabled:opacity-40"
                >
                  {d.supersedesDocumentId != null ? '개정 비교' : 'AI 추출'}
                </button>
              )}
              <button
                type="button"
                onClick={() => remove(d)}
                aria-label={`${d.fileName} 삭제`}
                className="rounded px-2 py-1 text-xs text-mute transition hover:bg-paper hover:text-danger focus:outline-none focus:ring-2 focus:ring-brand/30"
              >
                삭제
              </button>
            </li>
          ))}
        </ul>
      )}
      {error && (
        <p role="alert" className="text-sm text-danger">
          {error}
        </p>
      )}
      <input
        ref={input}
        type="file"
        accept={ACCEPT}
        className="hidden"
        onChange={(e) => {
          const file = e.target.files?.[0];
          e.target.value = ''; // allow picking the same file again
          if (file) upload(file);
        }}
      />
      <div className="flex flex-wrap items-center gap-2 text-xs text-body">
        <label className="flex items-center gap-1.5">
          문서 종류
          <select aria-label="문서 종류" value={docType} onChange={(e) => setDocType(e.target.value as DocType | '')}
            className="rounded border border-line bg-white px-2 py-1 text-xs">
            <option value="">지정 안 함</option>
            {Object.entries(DOC_TYPE).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
        </label>
      </div>
      {latest.length > 0 && (
        <div className="flex flex-wrap items-center gap-2 text-xs text-body">
          <label className="flex items-center gap-1.5">
            이전 버전 대체
            <select value={supersedes} onChange={(e) => setSupersedes(e.target.value)}
              className="max-w-[12rem] rounded border border-line bg-white px-2 py-1 text-xs">
              <option value="">없음 (새 문서)</option>
              {latest.map((d) => <option key={d.id} value={d.id}>{d.fileName}</option>)}
            </select>
          </label>
          {supersedes && (
            <label className="flex items-center gap-1.5">
              시행일(선택)
              <input type="date" value={effectiveDate} onChange={(e) => setEffectiveDate(e.target.value)}
                className="rounded border border-line px-2 py-1 text-xs" />
            </label>
          )}
        </div>
      )}
      <button
        type="button"
        disabled={busy}
        onClick={() => input.current?.click()}
        className="w-full rounded border border-line px-3 py-2 text-sm font-medium text-body transition hover:bg-paper focus:outline-none focus:ring-2 focus:ring-brand/20 disabled:opacity-50"
      >
        {busy ? '올리는 중…' : '문서 올리기'}
      </button>
      <p className="text-xs text-mute">PDF, JPG, PNG, XLSX · 파일당 10MB 이하</p>
      {aiOn ? (
        <p className="rounded bg-amber-50 px-3 py-2 text-xs text-warn">
          PDF는 'AI 추출'로 이행 의무를 뽑아 검토 목록에 올릴 수 있습니다. {TRANSFER_NOTICE}
        </p>
      ) : (
        <p className="flex flex-wrap items-center gap-2 rounded bg-paper px-3 py-2 text-xs text-body">
          AI 추출이 회사 설정에서 꺼져 있습니다{isMaster() ? '.' : '. 회사 마스터가 켤 수 있습니다.'}
          {isMaster() && (
            <button type="button" onClick={turnOnAi} className="font-medium text-brand hover:underline">AI 추출 켜기</button>
          )}
        </p>
      )}
    </div>
  );
}

function RunStatus({ projectId, run, count, onRetry }: { projectId: number; run: AiRun; count?: number; onRetry: () => void }) {
  const review = `/projects/${projectId}/review`;
  const revision = run.inputRef?.prev_document_id != null;
  if (run.status === 'RUNNING') return <p className="text-xs text-brand">{revision ? '개정 비교 중…' : 'AI 추출 중…'}</p>;
  if (run.status === 'FAILED') {
    return (
      <p className="text-xs text-danger">
        추출 실패: {run.errorMessage}{' '}
        <button type="button" onClick={onRetry} className="font-medium underline">다시 실행</button>
      </p>
    );
  }
  if (count === undefined) return null;
  if (revision) {
    return (
      <p className="text-xs text-ok">
        비교 결과 {count}건 · <Link to={review} className="font-medium underline">검토하기</Link>
      </p>
    );
  }
  return count > 0 ? (
    <p className="text-xs text-ok">
      의무사항 {count}건 추출 · <Link to={review} className="font-medium underline">검토하기</Link>
    </p>
  ) : (
    <p className="text-xs text-mute">
      추출된 의무사항 없음 · <Link to={review} className="underline">검토 화면에서 직접 추가</Link>
    </p>
  );
}
