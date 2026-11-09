// ② law amendment impacts ("법령 개정" screen, header bell).
import { api } from './client';

export type ImpactLevel = 'DIRECT' | 'REFERENCE';
export type ImpactStatus = 'NEW' | 'REVIEWED';

export interface LawImpact {
  id: number;
  level: ImpactLevel;
  status: ImpactStatus;
  projectId: number;
  projectName: string;
  permitItemId: number;
  permitName: string;
  lawCatalogId: number;
  lawName: string;
  revisionType: string;
  promulgatedOn: string | null;
  enforcedOn: string | null;
  replacementNeeded: boolean; // 폐지 → 대체 법령 확인 필요
  createdAt: string;
  reviewedAt: string | null;
}

export interface LawImpactDetail {
  impact: LawImpact;
  reason: string | null;
  articles: { articleNo: string; changeReason: string | null; enforcedOn: string | null; mine: boolean }[];
  linkedArticles: (string | null)[]; // links on the amended law itself; empty = via the 시행령·시행규칙 rule
  oldNew: { old: string; next: string }[] | null; // null = 준비 중
  sourceUrl: string;
  deadline: string | null;
  note: string | null;
  reviewedByName: string | null;
  source: string;
}

export const getLawImpacts = () => api<LawImpact[]>('/api/law-impacts');
export const getLawImpact = (id: number) => api<LawImpactDetail>(`/api/law-impacts/${id}`);
export const reviewLawImpact = (id: number, note: string) =>
  api<LawImpactDetail>(`/api/law-impacts/${id}/review`, { method: 'POST', body: JSON.stringify({ note }) });
export const getNewLawImpactCount = () => api<{ count: number }>('/api/law-impacts/count');
