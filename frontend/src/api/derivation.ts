// Auto-derivation: project attributes → rule engine judgement (PENDING PERMIT_ITEM suggestions + full record).
import { api } from './client';
import type { SuggestionStatus } from './review';

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
  required_by_date: string | null;
  warning?: string; // set when an earlier judgement of the same rule is still awaiting approval
}

/** An approved item the current rules no longer find applicable (the live item is kept). */
export interface DerivationWarning {
  rule_key: string;
  permit_name: string;
  permit_item_id: number | null;
  message: string;
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
  warnings?: DerivationWarning[];
}

/** One line of the "추가 정보 필요" panel. */
export interface MissingInput {
  field: string;
  label: string;
  type: 'NUMBER' | 'ENUM' | 'ENUM_LIST' | 'BOOL' | 'DATE' | 'TEXT';
  unit: string;
  values: Record<string, string>;
  rules: string[];
}

/** PERMIT_ITEM suggestion: payload is the judgement row. */
export interface PermitSuggestion {
  id: number;
  runId: number;
  kind: 'PERMIT_ITEM';
  origin: 'AI' | 'MANUAL';
  payload: JudgementRow;
  editedPayload: JudgementRow | null;
  status: SuggestionStatus;
  reviewedBy: number | null;
  rejectReason: string | null;
  appliedEntityId: number | null;
  version: number;
}

export const saveAttributes = (pid: number, attrs: Attributes) =>
  api<Attributes>(`/api/projects/${pid}/attributes`, { method: 'PUT', body: JSON.stringify(attrs) });
export const derive = (pid: number) =>
  api<DerivationResult>(`/api/projects/${pid}/derivations`, { method: 'POST' });
export const latestDerivation = (pid: number) => api<DerivationResult>(`/api/projects/${pid}/derivations/latest`);
export const missingInputs = (pid: number) => api<MissingInput[]>(`/api/projects/${pid}/derivations/missing-inputs`);
export const listPermitSuggestions = (pid: number) =>
  api<{ kind: string }[]>(`/api/projects/${pid}/suggestions`).then(
    (all) => all.filter((s) => s.kind === 'PERMIT_ITEM') as PermitSuggestion[],
  );
/** Closed by a re-run (review exception 1): SYSTEM actor, reason starts with "재실행으로 대체". */
export const closedBySystem = (s: PermitSuggestion) => s.status === 'REJECTED' && s.reviewedBy === null;
