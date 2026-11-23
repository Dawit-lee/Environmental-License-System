import { useEffect, useState } from 'react';
import { api, sessionUserId } from '../api/client';
import { getCompanyUsers, type CompanyUser } from '../api/org';
import { Alert } from './ui/Alert';
import { Button } from './ui/Button';
import { Select } from './ui/Field';
import { Table } from './ui/Table';
import { cn, focusRing } from './ui/cn';

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
    <section aria-labelledby="members-title" className="mt-10 max-w-5xl rounded border border-line bg-white p-6">
      <h2 id="members-title" className="text-[19px] font-bold text-ink">멤버와 권한</h2>
      <p className="mt-1 break-keep text-[15px] text-body">
        {admin ? '멤버를 추가하고 승인 권한을 정합니다.' : '프로젝트 어드민만 바꿀 수 있습니다.'}
      </p>
      <dl className="mt-3 grid gap-1 text-[15px] sm:grid-cols-[max-content_1fr] sm:gap-x-3">
        {FLAGS.map((f) => (
          <div key={f.key} className="contents">
            <dt className="font-bold text-ink">{f.label}</dt>
            <dd className="mb-1 text-body sm:mb-0">{f.hint}</dd>
          </div>
        ))}
      </dl>
      {error && <Alert tone="danger" className="mt-4">{error}</Alert>}

      <div className="mt-4">
        <Table caption="프로젝트 멤버와 권한" className="min-w-[720px] whitespace-nowrap">
          <thead>
            <tr>
              <th>이름</th>
              <th>이메일</th>
              {FLAGS.map((f) => (
                <th key={f.key} className="text-center" title={f.hint}>{f.label}</th>
              ))}
              <th aria-label="작업" />
            </tr>
          </thead>
          <tbody>
            {members.map((m) => (
              <tr key={m.userId}>
                <td className="font-bold">{m.name}</td>
                <td className="text-body">{m.email}</td>
                {FLAGS.map((f) => (
                  <td key={f.key} className="text-center">
                    <input
                      type="checkbox"
                      aria-label={`${m.name} ${f.label}`}
                      checked={m[f.key]}
                      disabled={!admin}
                      onChange={(e) => run(() => api(`${base}/${m.userId}`, { method: 'PATCH', body: JSON.stringify({ [f.key]: e.target.checked }) }))}
                      className={cn('size-5 align-middle accent-brand', focusRing)}
                    />
                  </td>
                ))}
                <td className="text-right">
                  {admin && (
                    <Button
                      variant="danger"
                      size="sm"
                      aria-label={`${m.name} 멤버에서 빼기`}
                      onClick={() => window.confirm(`${m.name}을(를) 멤버에서 뺄까요?`) && run(() => api(`${base}/${m.userId}`, { method: 'DELETE' }))}
                    >
                      빼기
                    </Button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </Table>
      </div>

      {admin && candidates.length > 0 && (
        <form
          className="mt-4 flex flex-wrap items-end gap-3"
          onSubmit={(e) => {
            e.preventDefault();
            if (adding) run(() => api(base, { method: 'POST', body: JSON.stringify({ userId: Number(adding) }) })).then(() => setAdding(''));
          }}
        >
          <Select label="추가할 사용자" className="w-full sm:w-80" value={adding} onChange={(e) => setAdding(e.target.value)}>
            <option value="">같은 회사 사용자 선택</option>
            {candidates.map((u) => <option key={u.id} value={u.id}>{u.name} ({u.email})</option>)}
          </Select>
          <Button type="submit" disabled={!adding} className="w-full sm:w-auto">멤버 추가</Button>
        </form>
      )}
    </section>
  );
}
