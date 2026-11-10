-- Auto-derivation (permit-derivation/01). Project attributes = one JSON object keyed by the field dictionary.
ALTER TABLE project ADD COLUMN attributes JSONB NOT NULL DEFAULT '{}';

-- One row. +1 whenever the ACTIVE rule set changes (same transaction).
CREATE TABLE ruleset_state (
    id      INT PRIMARY KEY CHECK (id = 1),
    version INT NOT NULL
);
INSERT INTO ruleset_state (id, version) VALUES (1, 0);

-- System-wide (no company_id). Rows are immutable: an edit is a new version row, the old one goes RETIRED.
CREATE TABLE derivation_rule (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule_key             VARCHAR(80)  NOT NULL,
    version              INT          NOT NULL,
    status               VARCHAR(10)  NOT NULL CHECK (status IN ('ACTIVE', 'RETIRED')),
    facility_types       TEXT[]       NOT NULL DEFAULT '{}', -- empty = common rule
    project_kinds        TEXT[]       NOT NULL DEFAULT '{}', -- empty = all
    permit_name          VARCHAR(200) NOT NULL,
    field                VARCHAR(10)  NOT NULL CHECK (field IN ('공통', '환경', '건축', '토목', '기계', '전기', '소방', '통신', '안전')),
    category             VARCHAR(10)  NOT NULL CHECK (category IN ('SITE', 'FACILITY', 'GENERAL')),
    condition            JSONB,                               -- null = always applies
    criterion_text       VARCHAR(500) NOT NULL,
    law_refs             JSONB        NOT NULL CHECK (jsonb_typeof(law_refs) = 'array' AND jsonb_array_length(law_refs) >= 1),
    legal_basis_text     VARCHAR(500),
    approval_stage       VARCHAR(20)  NOT NULL CHECK (approval_stage IN ('PLANNING', 'DESIGN', 'CONSTRUCTION', 'COMMISSIONING', 'OPERATION_START')),
    required_by          JSONB,                               -- {gate, offset_days, text}
    note                 TEXT,
    source_ref           VARCHAR(300),
    source_quote         TEXT,
    suggestion_id        BIGINT, -- null = hand-written seed. No FKs here: system rows outlive projects/users
    activated_in_ruleset INT          NOT NULL,
    retired_in_ruleset   INT,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by           BIGINT,
    retired_at           TIMESTAMPTZ,
    UNIQUE (rule_key, version)
);
CREATE UNIQUE INDEX derivation_rule_one_active ON derivation_rule (rule_key) WHERE status = 'ACTIVE';
