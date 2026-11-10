// Company org chart (department > team) and company users (team assignment, PM/EM pickers).
import { api } from './client';

export interface Team { id: number; name: string }
export interface Department { id: number; name: string; teams: Team[] }
export interface CompanyUser { id: number; name: string; email: string; teamId: number | null }

const send = (method: string, body?: unknown) => ({ method, body: body === undefined ? undefined : JSON.stringify(body) });

export const getDepartments = () => api<Department[]>('/api/departments');
export const getCompanyUsers = () => api<CompanyUser[]>('/api/company/users');
export const createDepartment = (name: string) => api<Team>('/api/departments', send('POST', { name }));
export const renameDepartment = (id: number, name: string) => api<Team>(`/api/departments/${id}`, send('PUT', { name }));
export const deleteDepartment = (id: number) => api<void>(`/api/departments/${id}`, send('DELETE'));
export const createTeam = (departmentId: number, name: string) =>
  api<Team>(`/api/departments/${departmentId}/teams`, send('POST', { name }));
export const renameTeam = (id: number, name: string) => api<Team>(`/api/teams/${id}`, send('PUT', { name }));
export const deleteTeam = (id: number) => api<void>(`/api/teams/${id}`, send('DELETE'));
export const assignTeam = (userId: number, teamId: number | null) =>
  api<CompanyUser>(`/api/company/users/${userId}/team`, send('PUT', { teamId }));

/** "에너지환경사업부 / 1팀" for each team id. */
export const teamLabels = (depts: Department[]) =>
  new Map(depts.flatMap((d) => d.teams.map((t) => [t.id, `${d.name} / ${t.name}`] as const)));
