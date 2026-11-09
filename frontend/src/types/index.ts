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
  capacity: number;
  capacityUnit: string;
  startDate: string;
  status: ProjectStatus;
  permitCount: number;
  nearExpiryCount: number;
}

export interface PermitItem {
  id: number;
  projectId: number;
  name: string;
  category: PermitCategory;
  regulation: string;
  regulationUrl: string;
  authority: string;
  authorityUrl: string;
  reason: string;
  approvalDate: string;
  applicationDate: string;
  acquisitionDate: string | null;
  expiryDate: string | null;
  status: PermitStatus;
  nextAction: string;
  nextActionUrl: string;
  dDay: number | null;
}
