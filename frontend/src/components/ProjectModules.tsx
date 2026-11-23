import { useState } from 'react';
import { api } from '../api/client';
import type { Project } from '../types';
import { Alert } from './ui/Alert';
import { cn, focusRing } from './ui/cn';

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
    <section aria-labelledby="modules-title" className="mt-10 max-w-5xl rounded border border-line bg-white p-6">
      <h2 id="modules-title" className="text-[19px] font-bold text-ink">모듈</h2>
      <p className="mt-1 break-keep text-[15px] text-body">프로젝트 어드민·PM·EM만 바꿀 수 있습니다. 꺼도 입력한 데이터는 지워지지 않습니다.</p>
      {error && <Alert tone="danger" className="mt-4">{error}</Alert>}
      <label className="mt-4 flex min-h-11 items-start gap-3 text-[15px] text-ink">
        <input type="checkbox" className={cn('mt-0.5 size-5 shrink-0 accent-brand', focusRing)} checked={project.operationsEnabled} disabled={saving}
          onChange={(e) => toggle(e.target.checked)} />
        <span className="break-keep">
          <span className="font-bold">운영단계 관리</span>
          <span className="mt-1 block text-body">배출구·측정값·초과 판정·시정 업무·이행 보고서. 운영사 업무용으로 기본 꺼져 있습니다.</span>
        </span>
      </label>
    </section>
  );
}
