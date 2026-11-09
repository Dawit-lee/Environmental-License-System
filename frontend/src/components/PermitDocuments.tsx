import { useEffect, useRef, useState } from 'react';
import type { PermitDocument, PermitItem } from '../types';
import { api, apiBlob } from '../api/client';

// Attached files of one permit: list, upload, download, delete. Lives in PermitDetailDrawer.

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
              </div>
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
    </div>
  );
}
