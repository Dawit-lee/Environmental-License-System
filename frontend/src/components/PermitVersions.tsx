import { useEffect, useState } from 'react';
import type { PermitDocument, PermitItem } from '../types';
import { api } from '../api/client';
import { listObligations, revisionLog, type Obligation, type RevisionLogEntry } from '../api/revisions';

// ④ version-diff/03, permit detail: document version timeline (v1 → v2, effective date) and per-obligation 변경 이력.

const CHANGE: Record<RevisionLogEntry['changeType'], string> = {
  CHANGED: '변경', MINOR: '문구', REMOVED: '삭제', UNCHANGED: '변경 없음',
};

/** Version lines oldest first: a line starts at a document that supersedes nothing. */
function lines(docs: PermitDocument[]): PermitDocument[][] {
  return docs.filter((d) => d.supersedesDocumentId == null).map((root) => {
    const line = [root];
    for (let next = docs.find((d) => d.supersedesDocumentId === root.id); next;
      next = docs.find((d) => d.supersedesDocumentId === line[line.length - 1].id)) line.push(next);
    return line;
  });
}

export default function PermitVersions({ permit }: { permit: PermitItem }) {
  const [docs, setDocs] = useState<PermitDocument[]>([]);
  const [obligations, setObligations] = useState<Obligation[]>([]);
  const [openId, setOpenId] = useState<number | null>(null);
  const [log, setLog] = useState<RevisionLogEntry[]>([]);

  useEffect(() => {
    api<PermitDocument[]>(`/api/projects/${permit.projectId}/permits/${permit.id}/documents`).then(setDocs, () => setDocs([]));
    listObligations(permit.projectId, permit.id).then(setObligations, () => setObligations([]));
  }, [permit.projectId, permit.id]);
  useEffect(() => {
    if (openId != null) revisionLog(openId).then(setLog, () => setLog([]));
  }, [openId]);

  const versioned = lines(docs).filter((l) => l.length > 1);
  const versionOf = (docId: number | null) => {
    for (const l of lines(docs)) {
      const i = l.findIndex((d) => d.id === docId);
      if (i >= 0) return `v${i + 1}`;
    }
    return null;
  };
  if (!versioned.length && !obligations.length) return <p className="text-sm text-mute">개정된 문서나 의무사항이 없습니다.</p>;

  return (
    <div className="space-y-3 text-sm">
      {versioned.map((l) => (
        <ol key={l[0].id} aria-label="문서 버전 타임라인" className="flex flex-wrap items-center gap-1.5">
          {l.map((d, i) => (
            <li key={d.id} className="flex items-center gap-1.5">
              {i > 0 && <span aria-hidden className="text-mute">→</span>}
              <span title={d.fileName}
                className={`rounded border px-2 py-1 ${i === l.length - 1 ? 'border-brand bg-brand-subtle text-brand-text' : 'border-line text-body'}`}>
                <b>v{i + 1}</b> <span className="text-xs">{d.effectiveDate ? `시행 ${d.effectiveDate}` : d.uploadedAt.slice(0, 10)}</span>
              </span>
            </li>
          ))}
        </ol>
      ))}
      {obligations.length > 0 && (
        <ul className="divide-y divide-line rounded border border-line">
          {obligations.map((o) => (
            <li key={o.id}>
              <button type="button" aria-expanded={openId === o.id} onClick={() => setOpenId(openId === o.id ? null : o.id)}
                className="flex w-full items-center gap-2 px-3 py-2 text-left hover:bg-paper">
                <span className="font-mono text-xs text-mute">{o.citationKey}</span>
                <span className={`min-w-0 flex-1 truncate ${o.status === 'CLOSED' ? 'text-mute line-through' : 'text-ink'}`}>{o.summary}</span>
                {versionOf(o.sourceDocumentId) && (
                  <span className="whitespace-nowrap text-xs text-violet-800">{versionOf(o.sourceDocumentId)} 근거</span>
                )}
              </button>
              {openId === o.id && (
                <div className="bg-paper px-3 py-2 text-xs text-body">
                  <p className="font-semibold text-body">변경 이력</p>
                  {log.length === 0 ? <p className="mt-1">개정으로 바뀐 적이 없습니다.</p> : (
                    <ul className="mt-1 space-y-1">
                      {log.map((e) => (
                        <li key={e.id}>
                          {e.at.slice(0, 10)} · <b>{CHANGE[e.changeType]}</b> · {e.byName ?? '—'} — 이전:{' '}
                          {String(e.before.summary ?? '')} (주기 {String(e.before.frequency ?? '—')}
                          {e.before.next_due_date ? `, 기한 ${String(e.before.next_due_date)}` : ''}
                          {versionOf(Number(e.before.source_document_id)) ? `, ${versionOf(Number(e.before.source_document_id))}` : ''})
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
