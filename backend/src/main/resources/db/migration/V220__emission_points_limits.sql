-- operations-compliance/03 (F2 §3.1): emission points and permit limits. Measurements come in ticket 04.
CREATE EXTENSION IF NOT EXISTS btree_gist; -- "=" inside the no-overlap constraint below

-- Reference list of measurable items; rows come from seed migrations only (no user-defined items in v1).
CREATE TABLE pollutant (
    code   VARCHAR(20) PRIMARY KEY,
    medium VARCHAR(10) NOT NULL CHECK (medium IN ('AIR', 'WATER')),
    name   VARCHAR(50) NOT NULL,
    unit   VARCHAR(20) NOT NULL,
    sort   INT         NOT NULL
);

CREATE TABLE emission_point (
    id             BIGSERIAL PRIMARY KEY,
    project_id     BIGINT       NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    permit_item_id BIGINT REFERENCES permit_item (id) ON DELETE SET NULL,
    medium         VARCHAR(10)  NOT NULL CHECK (medium IN ('AIR', 'WATER')),
    name           VARCHAR(100) NOT NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE, -- closed points are kept, never deleted
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX emission_point_project_idx ON emission_point (project_id);

-- A limit is never edited: a change closes the row (effective_to) and adds a new one.
CREATE TABLE permit_limit (
    id                BIGSERIAL PRIMARY KEY,
    emission_point_id BIGINT        NOT NULL REFERENCES emission_point (id) ON DELETE CASCADE,
    pollutant         VARCHAR(20)   NOT NULL REFERENCES pollutant (code),
    unit              VARCHAR(20)   NOT NULL, -- copied from pollutant.unit when the limit is entered
    limit_value       NUMERIC       NOT NULL CHECK (limit_value > 0),
    warn_ratio        NUMERIC(3, 2) NOT NULL DEFAULT 0.80 CHECK (warn_ratio > 0 AND warn_ratio < 1),
    obligation_id     BIGINT REFERENCES permit_obligation (id) ON DELETE SET NULL,
    basis_text        VARCHAR(500),
    effective_from    DATE          NOT NULL,
    effective_to      DATE CHECK (effective_to >= effective_from), -- inclusive; null = still in force
    needs_review      BOOLEAN       NOT NULL DEFAULT FALSE, -- set when a revision changes a number of the linked obligation
    created_by        BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT permit_limit_no_overlap EXCLUDE USING gist (
        emission_point_id WITH =, pollutant WITH =, daterange(effective_from, effective_to, '[]') WITH &&)
);
CREATE INDEX permit_limit_obligation_idx ON permit_limit (obligation_id);
