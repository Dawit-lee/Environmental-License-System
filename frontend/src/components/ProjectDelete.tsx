import { useState } from 'react';
import { useNavigate } from 'react-router';
import { api } from '../api/client';
import { usePrompt } from './PromptDialog';
import type { Project } from '../types';

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
    <section className="mt-8 max-w-5xl">
      {promptUi}
      <h2 className="text-lg font-bold text-[#991B1B]">프로젝트 삭제</h2>
      <p className="mt-1 text-sm text-gray-500">인허가·일정·문서·이력이 모두 지워지고 되돌릴 수 없습니다. 프로젝트 어드민만 지울 수 있습니다.</p>
      {error && <p role="alert" className="mt-3 rounded-lg bg-red-50 px-3 py-2 text-sm text-[#991B1B]">{error}</p>}
      <button type="button" onClick={remove}
        className="mt-4 rounded-lg border border-[#991B1B] px-4 py-2 text-sm font-medium text-[#991B1B] hover:bg-red-50">
        프로젝트 삭제
      </button>
    </section>
  );
}
