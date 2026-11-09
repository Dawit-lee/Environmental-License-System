// Core domain types for the Environmental Facility Permit Management System.
// These describe projects (engineering jobs) and the permits derived for them.

export type FacilityType =
  | 'INCINERATION' // 소각시설
  | 'LANDFILL' // 매립시설
  | 'BIOGAS' // 통합바이오가스
  | 'SEWAGE' // 하수처리
  | 'WASTEWATER'; // 폐수처리

export type PermitCategory =
  | 'LOCATION' // 입지
  | 'FACILITY' // 시설
  | 'CONSTRUCTION' // 건축
  | 'ELECTRICAL' // 전기
  | 'ENVIRONMENT'; // 환경평가

export type PermitStatus =
  | 'NOT_APPLIED' // 미신청
  | 'IN_PROGRESS' // 진행중
  | 'COMPLETED' // 취득완료
  | 'NEAR_EXPIRY' // 만료임박
  | 'EXPIRED'; // 만료

export type ProjectStatus = 'IN_PROGRESS' | 'COMPLETED' | 'ON_HOLD';

export interface Project {
  id: number;
  name: string;
  facilityType: FacilityType;
  location: string;
  capacity: number | null;
  capacityUnit: string | null;
  startDate: string | null;
  expectedEndDate: string | null;
  status: ProjectStatus;
  permitCount: number;
  acquiredCount: number;
  nearExpiryCount: number;
}

export interface PermitItem {
  id: number;
  projectId: number;
  name: string;
  category: PermitCategory;
  regulation: string | null;
  regulationUrl: string | null;
  authority: string | null;
  authorityUrl: string | null;
  reason: string | null;
  approvalDate: string | null; // free-text timing, e.g. '착공 30일 전'
  applicationDate: string | null;
  acquisitionDate: string | null;
  expiryDate: string | null;
  status: PermitStatus;
  nextAction: string | null;
  nextActionUrl: string | null;
  dDay: number | null;
}

export interface PermitHistory {
  id: number;
  fromStatus: PermitStatus | null; // null = created
  toStatus: PermitStatus;
  changedBy: string;
  changedAt: string;
}
