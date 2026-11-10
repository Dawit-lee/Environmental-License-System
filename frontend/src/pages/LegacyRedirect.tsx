import { useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { getLawImpact } from '../api/lawImpacts';

/** Old /laws?id=N (amendment mail link) → that impact in its project's 법령 영향 tab; no id → 내 할 일. */
export default function LegacyRedirect() {
  const navigate = useNavigate();
  const impactId = Number(useSearchParams()[0].get('id')) || null;

  useEffect(() => {
    const inbox = () => navigate('/inbox?kind=LAW', { replace: true });
    if (!impactId) return inbox();
    getLawImpact(impactId).then((d) => navigate(`/projects/${d.impact.projectId}/laws?id=${impactId}`, { replace: true }), inbox);
  }, [navigate, impactId]);

  return <p className="p-8 text-sm text-gray-400">이동 중…</p>;
}
