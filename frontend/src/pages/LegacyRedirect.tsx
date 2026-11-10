import { useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { api } from '../api/client';
import { getLawImpact } from '../api/lawImpacts';
import type { Project } from '../types';

/** Old /review, /laws, /calendar URLs → that tab of the right project (or the first one). */
export default function LegacyRedirect({ tab }: { tab: string }) {
  const navigate = useNavigate();
  const impactId = Number(useSearchParams()[0].get('id')) || null;

  useEffect(() => {
    const go = (projectId: number | undefined) =>
      navigate(projectId ? `/projects/${projectId}/${tab}${impactId ? `?id=${impactId}` : ''}` : '/projects', { replace: true });
    // /laws?id=N comes from the amendment mail: open the impact's own project
    const target = impactId && tab === 'laws'
      ? getLawImpact(impactId).then((d) => d.impact.projectId)
      : api<Project[]>('/api/projects').then((ps) => ps[0]?.id);
    target.then(go, () => go(undefined));
  }, [navigate, tab, impactId]);

  return <p className="p-8 text-sm text-gray-400">이동 중…</p>;
}
