import { useEffect, useState } from 'react';
import { createDiscipline, getDisciplines, updateDiscipline, type Discipline } from '../api/schedule';
import { usePrompt } from './PromptDialog';
import { Alert } from './ui/Alert';

// ---------------------------------------------------------------------------
// DisciplineSettings — 조직 관리 › 분야 (company master): add, rename, reorder, deactivate. No delete: an inactive
// discipline is hidden from new schedule items but stays on existing ones.
// ---------------------------------------------------------------------------

const btn = 'rounded border border-line px-2.5 py-1 text-xs font-medium text-body transition hover:bg-paper disabled:opacity-30';

export default function DisciplineSettings() {
  const [ask, promptUi] = usePrompt();
  const [list, setList] = useState<Discipline[]>([]);
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);

  const load = () => getDisciplines().then(setList, (e: Error) => setError(e.message));
  useEffect(() => {
    load();
  }, []);
  const run = async (action: () => Promise<unknown>) => {
    setError(null);
    try {
      await action();
    } catch (e) {
      setError((e as Error).message);
    }
    await load();
  };
  // swap with the neighbour; equal orders (none seeded) would not move, so fall back to the index
  const swap = (i: number, j: number) => run(async () => {
    const a = list[i], b = list[j];
    const [oa, ob] = a.sortOrder === b.sortOrder ? [j * 10, i * 10] : [b.sortOrder, a.sortOrder];
    await updateDiscipline(a.id, { sortOrder: oa });
    await updateDiscipline(b.id, { sortOrder: ob });
  });

  return (
    <section className="mt-6">
      {promptUi}
      <p className="text-sm text-mute">프로젝트 일정의 분야 목록입니다. 비활성 분야는 새 항목에서만 숨고, 기존 항목에는 그대로 보입니다.</p>
      {error && <Alert tone="danger" className="mt-4">{error}</Alert>}
      <div className="mt-4 overflow-x-auto rounded border border-line bg-white shadow-sm">
        <table className="w-full whitespace-nowrap text-left text-sm">
          <thead className="bg-paper text-xs text-body">
            <tr>
              <th className="w-24 px-4 py-3 font-medium">순서</th>
              <th className="px-4 py-3 font-medium">분야</th>
              <th className="w-24 px-4 py-3 font-medium">상태</th>
              <th className="w-48 px-4 py-3 font-medium" />
            </tr>
          </thead>
          <tbody className="divide-y divide-line">
            {list.map((d, i) => (
              <tr key={d.id}>
                <td className="px-4 py-2">
                  <button type="button" aria-label={`${d.name} 위로`} className={btn} disabled={i === 0} onClick={() => swap(i, i - 1)}>↑</button>{' '}
                  <button type="button" aria-label={`${d.name} 아래로`} className={btn} disabled={i === list.length - 1} onClick={() => swap(i, i + 1)}>↓</button>
                </td>
                <td className={`max-w-xs truncate px-4 py-2 ${d.active ? 'font-medium text-ink' : 'text-mute'}`} title={d.name}>{d.name}</td>
                <td className="px-4 py-2 text-mute">{d.active ? '사용' : '비활성'}</td>
                <td className="px-4 py-2 text-right">
                  <button type="button" className={btn} onClick={async () => {
                    const next = (await ask('새 이름', d.name))?.trim();
                    if (next && next !== d.name) run(() => updateDiscipline(d.id, { name: next }));
                  }}>이름 변경</button>{' '}
                  <button type="button" className={btn} onClick={() => run(() => updateDiscipline(d.id, { active: !d.active }))}>
                    {d.active ? '비활성' : '다시 사용'}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <form className="mt-4 flex gap-2" onSubmit={(e) => {
        e.preventDefault();
        if (name.trim()) run(() => createDiscipline(name.trim())).then(() => setName(''));
      }}>
        <input aria-label="새 분야 이름" placeholder="새 분야 이름" maxLength={100} value={name} onChange={(e) => setName(e.target.value)}
          className="rounded border border-line px-3 py-1.5 text-sm outline-none focus:border-brand focus:ring-2 focus:ring-brand/20" />
        <button type="submit" className="rounded bg-brand px-3.5 py-1.5 text-sm font-semibold text-white hover:bg-brand-hover">분야 추가</button>
      </form>
    </section>
  );
}
