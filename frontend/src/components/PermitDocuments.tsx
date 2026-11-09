import { useEffect, useRef, useState } from 'react';
import type { PermitDocument, PermitItem } from '../types';
import { Link } from 'react-router';
import { api, apiBlob, isMaster, type ApiError } from '../api/client';
import { getCompany, getRun, listRuns, setAiExtraction, startExtraction, type AiRun } from '../api/extraction';
import { listSuggestions } from '../api/review';

// Attached files of one permit: list, upload, download, delete, and AI extraction of PDFs. Lives in PermitDetailDrawer.

const TRANSFER_NOTICE = 'AI 추출을 실행하면 이 문서가 외부 AI 서비스(Anthropic)로 전송됩니다.';

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
  const [runs, setRuns] = useState<Record<number, AiRun>>({}); // latest run per document id
  const [found, setFound] = useState<Record<number, number>>({}); // run id → suggestions extracted

  useEffect(() => {
    getCompany().then((c) => setAiOn(c.aiExtractionEnabled), () => setAiOn(false));
    listRuns(permit.projectId).then((rs) => {
      const latest: Record<number, AiRun> = {};
      for (const r of rs) {
        const d = r.inputRef?.document_id;
        if (d != null && !(d in latest)) latest[d] = r; // newest first
      }
      setRuns(latest);
    }, () => setRuns({}));
  }, [permit.projectId]);

  // poll running extractions every 2s; count the rows of finished ones
  useEffect(() => {
    const all = Object.values(runs);
    all.filter((r) => r.status === 'SUCCEEDED' && !(r.id in found)).forEach((r) =>
      listSuggestions(permit.projectId, r.id).then((s) => setFound((f) => ({ ...f, [r.id]: s.length })), () => {}));
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
    if (!window.confirm(`${TRANSFER_NOTICE}\n\n'${doc.fileName}'에서 이행 의무를 추출할까요?`)) return;
    setError(null);
    try {
      let res;
      try {
        res = await startExtraction(permit.projectId, permit.id, doc.id);
      } catch (e) {
        if ((e as ApiError).status !== 409) throw e;
        if (!window.confirm(`${(e as Error).message}\n\n다시 추출할까요?`)) return;
        res = await startExtraction(permit.projectId, permit.id, doc.id, true);
      }
      setRuns((r) => ({ ...r, [doc.id]: { id: res.runId, status: 'RUNNING', errorMessage: null, model: null,
        inputRef: { document_id: doc.id, permit_item_id: permit.id, sha256: doc.sha256 }, startedAt: '', finishedAt: null } }));
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
    setBusy(true);
    try {
      const doc = await api<PermitDocument>(base, { method: 'POST', body: form });
      setDocs((d) => [doc, ...d]);
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
        <div className="rounded-lg border border-dashed border-[#E5E3DC] p-4 text-center text-sm text-gray-400">
          첨부된 문서가 없습니다
        </div>
      ) : (
        <ul className="divide-y divide-[#E5E3DC] rounded-lg border border-[#E5E3DC]">
          {docs.map((d) => (
            <li key={d.id} className="flex items-center gap-3 px-3 py-2.5">
              <div className="min-w-0 flex-1">
                <button
                  type="button"
                  onClick={() => download(d)}
                  className="block max-w-full truncate text-left text-sm font-medium text-[#1F4E79] hover:underline focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/30"
                >
                  {d.fileName}
                </button>
                <p className="text-xs text-gray-400">
                  {sizeLabel(d.size)} · {d.uploadedBy} · {new Date(d.uploadedAt).toLocaleDateString('sv-SE')}
                </p>
                {runs[d.id] && <RunStatus run={runs[d.id]} count={found[runs[d.id].id]} onRetry={() => extract(d)} />}
              </div>
              {d.contentType === 'application/pdf' && (
                <button
                  type="button"
                  onClick={() => extract(d)}
                  disabled={!aiOn || runs[d.id]?.status === 'RUNNING'}
                  title={aiOn ? TRANSFER_NOTICE : '회사 설정에서 AI 추출이 꺼져 있습니다'}
                  className="whitespace-nowrap rounded-lg border border-[#E5E3DC] px-2 py-1 text-xs font-medium text-[#1F4E79] transition hover:bg-[#F8F7F2] focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/30 disabled:cursor-not-allowed disabled:opacity-40"
                >
                  AI 추출
                </button>
              )}
              <button
                type="button"
                onClick={() => remove(d)}
                aria-label={`${d.fileName} 삭제`}
                className="rounded-lg px-2 py-1 text-xs text-gray-500 transition hover:bg-[#F8F7F2] hover:text-[#991B1B] focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/30"
              >
                삭제
              </button>
            </li>
          ))}
        </ul>
      )}
      {error && (
        <p role="alert" className="text-sm text-[#991B1B]">
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
      <button
        type="button"
        disabled={busy}
        onClick={() => input.current?.click()}
        className="w-full rounded-lg border border-[#E5E3DC] px-3 py-2 text-sm font-medium text-gray-700 transition hover:bg-[#F8F7F2] focus:outline-none focus:ring-2 focus:ring-[#1F4E79]/20 disabled:opacity-50"
      >
        {busy ? '올리는 중…' : '문서 올리기'}
      </button>
      <p className="text-xs text-gray-400">PDF, JPG, PNG, XLSX · 파일당 10MB 이하</p>
      {aiOn ? (
        <p className="rounded-lg bg-amber-50 px-3 py-2 text-xs text-[#B45309]">
          PDF는 'AI 추출'로 이행 의무를 뽑아 검토 목록에 올릴 수 있습니다. {TRANSFER_NOTICE}
        </p>
      ) : (
        <p className="flex flex-wrap items-center gap-2 rounded-lg bg-gray-50 px-3 py-2 text-xs text-gray-500">
          AI 추출이 회사 설정에서 꺼져 있습니다{isMaster() ? '.' : '. 회사 마스터가 켤 수 있습니다.'}
          {isMaster() && (
            <button type="button" onClick={turnOnAi} className="font-medium text-[#1F4E79] hover:underline">AI 추출 켜기</button>
          )}
        </p>
      )}
    </div>
  );
}

function RunStatus({ run, count, onRetry }: { run: AiRun; count?: number; onRetry: () => void }) {
  if (run.status === 'RUNNING') return <p className="text-xs text-[#1F4E79]">AI 추출 중…</p>;
  if (run.status === 'FAILED') {
    return (
      <p className="text-xs text-[#991B1B]">
        추출 실패: {run.errorMessage}{' '}
        <button type="button" onClick={onRetry} className="font-medium underline">다시 실행</button>
      </p>
    );
  }
  if (count === undefined) return null;
  return count > 0 ? (
    <p className="text-xs text-[#0F6E56]">
      의무사항 {count}건 추출 · <Link to="/review" className="font-medium underline">검토하기</Link>
    </p>
  ) : (
    <p className="text-xs text-gray-500">
      추출된 의무사항 없음 · <Link to="/review" className="underline">검토 화면에서 직접 추가</Link>
    </p>
  );
}
