import { useState } from 'react';
import { useNavigate } from 'react-router';
import { api } from '../api/client';
import { usePrompt } from './PromptDialog';
import type { Project } from '../types';
import { Alert } from './ui/Alert';
import { Button } from './ui/Button';

// 프로젝트 › 설정 맨 아래: delete the project after the name is typed again. Project admins only (the server answers 403).
export default function ProjectDelete({ project }: { project: Project }) {
  const [ask, promptUi] = usePrompt();
  const [error, setError] = useState<string | null>(null);
  const navigate = useNavigate();

  const remove = async () => {
    const typed = await ask(`삭제하려면 프로젝트 이름 "${project.name}"을 그대로 입력하세요.`);
    if (typed === null) return;
    if (typed.trim() !== project.name) return setError('이름이 달라 삭제하지 않았습니다.');
    setError(null);
    try {
      await api(`/api/projects/${project.id}`, { method: 'DELETE' });
      navigate('/projects', { replace: true });
    } catch (e) {
      setError((e as Error).message);
    }
  };

  return (
    <section aria-labelledby="danger-zone-title" className="mt-10 max-w-5xl rounded border border-danger-border bg-white p-6">
      {promptUi}
      <h2 id="danger-zone-title" className="text-[19px] font-bold text-danger">위험 구역</h2>
      <div className="mt-4 flex flex-wrap items-center justify-between gap-4">
        <div className="min-w-0 flex-1 basis-80">
          <p className="text-[17px] font-bold text-ink">프로젝트 삭제</p>
          <p className="mt-1 break-keep text-[15px] text-body">인허가·일정·문서·이력이 모두 지워지고 되돌릴 수 없습니다. 프로젝트 어드민만 지울 수 있습니다.</p>
        </div>
        <Button variant="danger" onClick={remove}>프로젝트 삭제</Button>
      </div>
      {error && <Alert tone="danger" className="mt-4">{error}</Alert>}
    </section>
  );
}
