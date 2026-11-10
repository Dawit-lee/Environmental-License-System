import { useEffect, useState } from 'react';
import { api, sessionUserId } from '../api/client';
import { getCompanyUsers, type CompanyUser } from '../api/org';

// ---------------------------------------------------------------------------
// ProjectMembers — 프로젝트 › 설정: who works on the project and what they may approve.
// Project admins change it; the server keeps at least one admin.
// ---------------------------------------------------------------------------

interface Member {
  userId: number;
  name: string;
  email: string;
  isAdmin: boolean;
  canSelfApprove: boolean;
  canBulkApprove: boolean;
}
type Flag = 'isAdmin' | 'canSelfApprove' | 'canBulkApprove';

const FLAGS: { key: Flag; label: string; hint: string }[] = [
  { key: 'isAdmin', label: '어드민', hint: '멤버·권한을 바꾸고 프로젝트를 삭제할 수 있음' },
  { key: 'canSelfApprove', label: '혼자 승인', hint: 'AI 검토 항목을 승인 요청 없이 바로 승인' },
  { key: 'canBulkApprove', label: '일괄 승인', hint: '여러 검토 항목을 한 번에 승인' },
];

export default function ProjectMembers({ projectId }: { projectId: number }) {
  const [members, setMembers] = useState<Member[]>([]);
  const [people, setPeople] = useState<CompanyUser[]>([]);
  const [adding, setAdding] = useState('');
  const [error, setError] = useState<string | null>(null);
  const base = `/api/projects/${projectId}/members`;

  const load = () => api<Member[]>(base).then(setMembers, (e: Error) => setError(e.message));
  useEffect(() => {
    load();
    getCompanyUsers().then(setPeople, () => {});
  }, [projectId]);

  const run = async (action: () => Promise<unknown>) => {
    setError(null);
    try {
      await action();
    } catch (e) {
      setError((e as Error).message);
    }
    await load();
  };

  const admin = !!members.find((m) => m.userId === sessionUserId())?.isAdmin;
  const candidates = people.filter((u) => !members.some((m) => m.userId === u.id));

  return (
    <section className="mt-8 max-w-5xl">
      <h2 className="text-lg font-bold text-[#1F4E79]">멤버와 권한</h2>
      <p className="mt-1 text-sm text-gray-500">
        {admin ? '멤버를 추가하고 승인 권한을 정합니다.' : '프로젝트 어드민만 바꿀 수 있습니다.'}
      </p>
      {error && <p role="alert" className="mt-3 rounded-lg bg-red-50 px-3 py-2 text-sm text-[#991B1B]">{error}</p>}

      <div className="mt-4 overflow-x-auto rounded-xl border border-[#E5E3DC] bg-white shadow-sm">
        <table className="w-full min-w-[720px] whitespace-nowrap text-left text-sm">
          <thead className="bg-[#F8F7F2] text-xs text-gray-500">
            <tr>
              <th className="px-4 py-3 font-medium">이름</th>
              <th className="px-4 py-3 font-medium">이메일</th>
              {FLAGS.map((f) => (
                <th key={f.key} className="px-4 py-3 text-center font-medium" title={f.hint}>{f.label}</th>
              ))}
              <th className="px-4 py-3" />
            </tr>
          </thead>
          <tbody className="divide-y divide-[#E5E3DC]">
            {members.map((m) => (
              <tr key={m.userId}>
                <td className="px-4 py-2.5 font-medium text-gray-800">{m.name}</td>
                <td className="px-4 py-2.5 text-gray-500">{m.email}</td>
                {FLAGS.map((f) => (
                  <td key={f.key} className="px-4 py-2.5 text-center">
                    <input
                      type="checkbox"
                      aria-label={`${m.name} ${f.label}`}
                      checked={m[f.key]}
                      disabled={!admin}
                      onChange={(e) => run(() => api(`${base}/${m.userId}`, { method: 'PATCH', body: JSON.stringify({ [f.key]: e.target.checked }) }))}
                      className="h-4 w-4 accent-[#1F4E79]"
                    />
                  </td>
                ))}
                <td className="px-4 py-2.5 text-right">
                  {admin && (
                    <button
                      type="button"
                      onClick={() => window.confirm(`${m.name}을(를) 멤버에서 뺄까요?`) && run(() => api(`${base}/${m.userId}`, { method: 'DELETE' }))}
                      className="text-xs text-gray-500 hover:text-[#991B1B]"
                    >
                      빼기
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {admin && candidates.length > 0 && (
        <form
          className="mt-3 flex gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            if (adding) run(() => api(base, { method: 'POST', body: JSON.stringify({ userId: Number(adding) }) })).then(() => setAdding(''));
          }}
        >
          <select aria-label="추가할 사용자" value={adding} onChange={(e) => setAdding(e.target.value)}
            className="rounded-lg border border-[#E5E3DC] bg-white px-3 py-1.5 text-sm">
            <option value="">같은 회사 사용자 선택</option>
            {candidates.map((u) => <option key={u.id} value={u.id}>{u.name} ({u.email})</option>)}
          </select>
          <button type="submit" disabled={!adding}
            className="rounded-lg bg-[#1F4E79] px-3.5 py-1.5 text-sm font-semibold text-white hover:bg-[#173a5c] disabled:opacity-40">
            멤버 추가
          </button>
        </form>
      )}
    </section>
  );
}
