import type { FacilityType, PermitCategory, PermitItem, PermitStatus, Project, ProjectStatus } from '../types';
import type { Tone } from '../components/ui/Badge';

// Labels, status badges and filters shared by the project list and the project's 인허가 tab.

export const FACILITY_LABEL: Record<FacilityType, string> = {
  INCINERATION: '소각시설',
  LANDFILL: '매립시설',
  BIOGAS: '통합바이오가스',
  SEWAGE: '하수처리',
  WASTEWATER: '폐수처리',
};

export const CATEGORY_LABEL: Record<PermitCategory, string> = {
  LOCATION: '입지',
  FACILITY: '시설',
  CONSTRUCTION: '건축',
  ELECTRICAL: '전기',
  ENVIRONMENT: '환경평가',
};

/** DESIGN.md 상태 배지: colour fixed per meaning, the state always in words. */
export const PROJECT_STATUS: Record<ProjectStatus, { text: string; tone: Tone }> = {
  IN_PROGRESS: { text: '진행중', tone: 'info' },
  COMPLETED: { text: '완료', tone: 'success' },
  ON_HOLD: { text: '보류', tone: 'neutral' },
};

export const PERMIT_STATUS: Record<PermitStatus, { text: string; tone: Tone }> = {
  NOT_APPLIED: { text: '미신청', tone: 'neutral' },
  IN_PROGRESS: { text: '진행중', tone: 'info' },
  COMPLETED: { text: '취득완료', tone: 'success' },
  NEAR_EXPIRY: { text: '만료임박', tone: 'warning' },
  EXPIRED: { text: '만료', tone: 'danger' },
};

export function filterProjects(
  ps: Project[],
  f: { query: string; facility: 'ALL' | FacilityType; status: 'ALL' | ProjectStatus; inScope: (p: Project) => boolean },
): Project[] {
  const q = f.query.trim().toLowerCase();
  return ps.filter(
    (p) =>
      f.inScope(p) &&
      (q === '' || p.name.toLowerCase().includes(q) || p.location.toLowerCase().includes(q)) &&
      (f.facility === 'ALL' || p.facilityType === f.facility) &&
      (f.status === 'ALL' || p.status === f.status),
  );
}

export function filterPermits(ps: PermitItem[], f: { category: 'ALL' | PermitCategory; status: 'ALL' | PermitStatus }): PermitItem[] {
  return ps.filter((p) => (f.category === 'ALL' || p.category === f.category) && (f.status === 'ALL' || p.status === f.status));
}
