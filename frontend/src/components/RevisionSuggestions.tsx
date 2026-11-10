import { useEffect, useState } from 'react';
import { listRevisions, type ChangeType, type RevisionSuggestion } from '../api/review';

// ④ version-diff/01: revision suggestions of a project with their change type. Read-only until the
// RevisionApplier (version-diff/02) lands; the side-by-side compare screen comes with it.

const TYPE: Record<ChangeType, { label: string; cls: string }> = {
  CHANGED: { label: '변경', cls: 'bg-amber-100 text-[#B45309]' },
  REMOVED: { label: '삭제', cls: 'bg-red-100 text-[#991B1B]' },
  ADDED: { label: '추가', cls: 'bg-emerald-100 text-[#0F6E56]' },
  MINOR: { label: '문구', cls: 'bg-gray-100 text-gray-600' },
  UNCHANGED: { label: '변경 없음', cls: 'bg-gray-100 text-gray-500' },
};
const ORDER: ChangeType[] = ['CHANGED', 'REMOVED', 'ADDED', 'MINOR', 'UNCHANGED'];
const FLAG: Record<string, string> = {
  NEEDS_CONFIRM: '매칭 확인 필요', KEY_SAME_TEXT_DIFF: '같은 번호·다른 본문', DUPLICATE_KEY: '번호 중복',
};

const hidden = (s: RevisionSuggestion) => s.payload.change_type === 'UNCHANGED' && !s.payload.match.flags.length;

export default function RevisionSuggestions({ projectId }: { projectId: number }) {
  const [items, setItems] = useState<RevisionSuggestion[]>([]);
  const [showUnchanged, setShowUnchanged] = useState(false);

  useEffect(() => {
    listRevisions(projectId).then((all) => setItems(all.filter((s) => s.status === 'PENDING' || s.status === 'SUBMITTED')),
      () => setItems([]));
  }, [projectId]);

  if (!items.length) return null;
  // flagged first, then by change type (design §5.1)
  const rank = (s: RevisionSuggestion) => (s.payload.match.flags.length ? -1 : ORDER.indexOf(s.payload.change_type));
  const visible = items.filter((s) => !hidden(s)).sort((a, b) => rank(a) - rank(b));
  const unchanged = items.filter(hidden);

  return (
    <section className="mt-5 rounded-xl border border-[#E5E3DC] bg-white shadow-sm">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-[#E5E3DC] px-4 py-3">
        <h2 className="text-sm font-semibold text-gray-800">개정 검토 대기 {items.length}건</h2>
        <p className="text-xs text-gray-500">
          {ORDER.map((t) => `${TYPE[t].label} ${items.filter((s) => s.payload.change_type === t).length}`).join(' · ')}
        </p>
      </div>
      <ul className="divide-y divide-[#E5E3DC]">
        {(showUnchanged ? [...visible, ...unchanged] : visible).map((s) => <Row key={s.id} s={s} />)}
      </ul>
      {unchanged.length > 0 && (
        <button
          type="button"
          onClick={() => setShowUnchanged((v) => !v)}
          className="w-full border-t border-[#E5E3DC] px-4 py-2.5 text-left text-sm text-gray-600 hover:bg-[#F8F7F2]"
        >
          변경 없음 {unchanged.length}건 {showUnchanged ? '접기' : '보기'}
          <span className="ml-2 text-xs text-gray-400">일괄 확인은 개정 반영 기능과 함께 제공됩니다</span>
        </button>
      )}
    </section>
  );
}

function Row({ s }: { s: RevisionSuggestion }) {
  const p = s.payload;
  const row = p.new ?? p.prev;
  const t = TYPE[p.change_type];
  const key = p.prev && p.new && p.prev.citation_key !== p.new.citation_key
    ? `${p.prev.citation_key} → ${p.new.citation_key}` : row?.citation_key;
  return (
    <li className="px-4 py-2.5 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${t.cls}`}>{t.label}</span>
        <span className="font-mono text-xs text-gray-500">{key}</span>
        <span className="min-w-0 flex-1 truncate text-gray-800">{row?.summary}</span>
        {p.prev_obligation_closed && <span className="text-xs text-gray-500">종료된 의무</span>}
        {p.match.flags.map((f) => (
          <span key={f} className="rounded-full bg-sky-100 px-2 py-0.5 text-xs text-sky-800">{FLAG[f] ?? f}</span>
        ))}
      </div>
      {(p.numeric_changes.length > 0 || p.changed_fields.length > 0) && (
        <p className="mt-1 text-xs text-gray-500">
          {p.numeric_changes.map((n) => `${n.before ?? '—'} → ${n.after ?? '—'}`).join(', ')}
          {p.numeric_changes.length > 0 && p.changed_fields.length > 0 && ' · '}
          {p.changed_fields.length > 0 && `바뀐 항목: ${p.changed_fields.join(', ')}`}
        </p>
      )}
    </li>
  );
}
