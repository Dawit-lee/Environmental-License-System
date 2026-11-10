// Auto-derivation: project attributes → rule engine judgement (PENDING PERMIT_ITEM suggestions + full record).
import { api } from './client';

export type Determination = 'APPLICABLE' | 'ON_HOLD' | 'NOT_APPLICABLE' | 'ERROR';
export type RuleCategory = 'SITE' | 'FACILITY' | 'GENERAL';

/** Attribute object keyed by the field dictionary (backend `Fields`). */
export type Attributes = Record<string, string | number | boolean | string[]>;

/** One judgement row (= PERMIT_ITEM payload, snake_case). */
export interface JudgementRow {
  rule_key: string;
  permit_name: string;
  field: string;
  category: RuleCategory;
  determination: Determination;
  reason: string;
  approval_stage: string;
  required_by_text: string | null;
  legal_basis: string[];
  law_names: string[];
  criterion: string;
  hold_info: { missing_inputs: string[]; missing_fields: string[]; criterion: string } | null;
}

export interface DerivationResult {
  runId: number;
  rulesetVersion: number;
  ranAt: string;
  applicable: number;
  onHold: number;
  notApplicable: number;
  errors: number;
  results: JudgementRow[];
}

export const saveAttributes = (pid: number, attrs: Attributes) =>
  api<Attributes>(`/api/projects/${pid}/attributes`, { method: 'PUT', body: JSON.stringify(attrs) });
export const derive = (pid: number) =>
  api<DerivationResult>(`/api/projects/${pid}/derivations`, { method: 'POST' });
