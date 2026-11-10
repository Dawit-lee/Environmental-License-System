import { useEffect, useState } from 'react';
import {
  assignTeam, createDepartment, createTeam, deleteDepartment, deleteTeam, getCompanyUsers, getDepartments,
  renameDepartment, renameTeam, teamLabels, type CompanyUser, type Department,
} from '../api/org';

// ---------------------------------------------------------------------------
// OrgPage — company master only: departments > teams, and which team each user is in.
// ---------------------------------------------------------------------------

const btn = 'rounded-lg border border-[#E5E3DC] px-2.5 py-1 text-xs font-medium text-gray-600 transition hover:bg-[#F8F7F2]';
const input = 'rounded-lg border border-[#E5E3DC] px-3 py-1.5 text-sm outline-none focus:border-[#1F4E79] focus:ring-2 focus:ring-[#1F4E79]/20';

export default function OrgPage() {
  const [depts, setDepts] = useState<Department[]>([]);
  const [people, setPeople] = useState<CompanyUser[]>([]);
  const [newDept, setNewDept] = useState('');
  const [newTeam, setNewTeam] = useState<Record<number, string>>({});
  const [error, setError] = useState<string | null>(null);

  const load = () =>
    Promise.all([getDepartments(), getCompanyUsers()]).then(([d, u]) => {
      setDepts(d);
      setPeople(u);
    }, (e: Error) => setError(e.message));
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
  const rename = (current: string, save: (name: string) => Promise<unknown>) => {
    const name = window.prompt('새 이름', current)?.trim();
    if (name && name !== current) run(() => save(name));
  };
  const labels = teamLabels(depts);

  return (
    <div className="min-h-full bg-[#F8F7F2]">
      <div className="mx-auto max-w-5xl px-4 py-8 sm:px-6">
        <h1 className="text-2xl font-bold text-[#1F4E79]">조직 관리</h1>
        <p className="mt-1 text-sm text-gray-500">부서와 팀을 만들고 사용자를 팀에 배정합니다. 팀을 지우면 소속 사용자·프로젝트는 미지정이 됩니다.</p>
        {error && <p role="alert" className="mt-4 rounded-lg bg-red-50 px-3 py-2 text-sm text-[#991B1B]">{error}</p>}

        <section className="mt-6 space-y-4">
          {depts.map((d) => (
            <div key={d.id} className="rounded-xl border border-[#E5E3DC] bg-white p-5 shadow-sm">
              <div className="flex items-center gap-2">
                <h2 className="text-base font-semibold text-gray-800">{d.name}</h2>
                <button type="button" className={btn} onClick={() => rename(d.name, (n) => renameDepartment(d.id, n))}>이름 변경</button>
                <button type="button" className={`${btn} hover:text-[#991B1B]`}
                  onClick={() => window.confirm(`${d.name}와 소속 팀을 삭제할까요?`) && run(() => deleteDepartment(d.id))}>삭제</button>
              </div>
              <ul className="mt-3 flex flex-wrap gap-2">
                {d.teams.map((t) => (
                  <li key={t.id} className="flex items-center gap-1 rounded-full bg-[#F8F7F2] py-1 pl-3 pr-1 text-sm">
                    {t.name}
                    <span className="text-xs text-gray-400">({people.filter((u) => u.teamId === t.id).length}명)</span>
                    <button type="button" aria-label={`${t.name} 이름 변경`} className="px-1 text-gray-400 hover:text-[#1F4E79]"
                      onClick={() => rename(t.name, (n) => renameTeam(t.id, n))}>✎</button>
                    <button type="button" aria-label={`${t.name} 삭제`} className="px-1 text-gray-400 hover:text-[#991B1B]"
                      onClick={() => window.confirm(`${t.name}을 삭제할까요?`) && run(() => deleteTeam(t.id))}>✕</button>
                  </li>
                ))}
              </ul>
              <form className="mt-3 flex gap-2" onSubmit={(e) => {
                e.preventDefault();
                const name = newTeam[d.id]?.trim();
                if (name) run(() => createTeam(d.id, name)).then(() => setNewTeam((m) => ({ ...m, [d.id]: '' })));
              }}>
                <input aria-label={`${d.name} 새 팀 이름`} placeholder="새 팀 이름" className={input} value={newTeam[d.id] ?? ''}
                  onChange={(e) => setNewTeam((m) => ({ ...m, [d.id]: e.target.value }))} />
                <button type="submit" className={btn}>팀 추가</button>
              </form>
            </div>
          ))}
          <form className="flex gap-2" onSubmit={(e) => {
            e.preventDefault();
            if (newDept.trim()) run(() => createDepartment(newDept.trim())).then(() => setNewDept(''));
          }}>
            <input aria-label="새 부서 이름" placeholder="새 부서 이름" className={input} value={newDept} onChange={(e) => setNewDept(e.target.value)} />
            <button type="submit" className="rounded-lg bg-[#1F4E79] px-3.5 py-1.5 text-sm font-semibold text-white hover:bg-[#173a5c]">부서 추가</button>
          </form>
        </section>

        <section className="mt-8 overflow-x-auto rounded-xl border border-[#E5E3DC] bg-white shadow-sm">
          <table className="w-full whitespace-nowrap text-left text-sm">
            <thead className="bg-[#F8F7F2] text-xs text-gray-500">
              <tr><th className="px-4 py-3 font-medium">이름</th><th className="px-4 py-3 font-medium">이메일</th><th className="px-4 py-3 font-medium">부서/팀</th></tr>
            </thead>
            <tbody className="divide-y divide-[#E5E3DC]">
              {people.map((u) => (
                <tr key={u.id}>
                  <td className="px-4 py-2.5 font-medium text-gray-800">{u.name}</td>
                  <td className="px-4 py-2.5 text-gray-500">{u.email}</td>
                  <td className="px-4 py-2.5">
                    <select aria-label={`${u.name} 팀`} value={u.teamId ?? ''} className={input}
                      onChange={(e) => run(() => assignTeam(u.id, e.target.value ? Number(e.target.value) : null))}>
                      <option value="">미지정</option>
                      {[...labels].map(([id, label]) => <option key={id} value={id}>{label}</option>)}
                    </select>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      </div>
    </div>
  );
}
