import { useState } from 'react';
import { api } from '../api/client';
import type { Project } from '../types';

// ---------------------------------------------------------------------------
// ProjectModules — 프로젝트 › 설정 › 모듈 (design-schedule §8.1). The 운영 module is an operator's job, so it is
// off by default; switching it off hides the 운영 tab and the measurement numbers but keeps every value.
// Project admins, the PM and the EM switch it (the server answers 403 to anyone else).
// ---------------------------------------------------------------------------

export default function ProjectModules({ project, onSaved }: { project: Project; onSaved: () => void }) {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const toggle = async (operations: boolean) => {
    setSaving(true);
    setError(null);
    try {
      await api(`/api/projects/${project.id}/modules`, { method: 'PATCH', body: JSON.stringify({ operations }) });
      onSaved();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <section className="mt-8 max-w-5xl">
      <h2 className="text-lg font-bold text-brand">모듈</h2>
      <p className="mt-1 text-sm text-gray-500">프로젝트 어드민·PM·EM만 바꿀 수 있습니다. 꺼도 입력한 데이터는 지워지지 않습니다.</p>
      {error && <p role="alert" className="mt-3 rounded-lg bg-red-50 px-3 py-2 text-sm text-danger">{error}</p>}
      <label className="mt-4 flex items-start gap-3 rounded-xl border border-line bg-white p-4 shadow-sm">
        <input type="checkbox" className="mt-1" checked={project.operationsEnabled} disabled={saving}
          onChange={(e) => toggle(e.target.checked)} />
        <span className="text-sm text-gray-700">
          <span className="font-medium text-gray-900">운영단계 관리</span>: 배출구·측정값·초과 판정·시정 업무·이행 보고서.
          운영사 업무용으로 기본 꺼져 있습니다.
        </span>
      </label>
    </section>
  );
}
