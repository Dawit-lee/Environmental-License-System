-- operations-compliance/04 (F2 §3.1): measured values, judged against the permit limit in force on the measured day.
-- limit_id / limit_value / warn_ratio / level are a snapshot taken when the row is entered and never recomputed,
-- so a later limit change leaves past judgements as they were. A row is never edited: a wrong entry is
-- soft-deleted with a reason and entered again.
CREATE TABLE measurement (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    emission_point_id BIGINT      NOT NULL REFERENCES emission_point (id) ON DELETE CASCADE,
    pollutant         VARCHAR(20) NOT NULL REFERENCES pollutant (code),
    measured_on       DATE        NOT NULL,
    value             NUMERIC     NOT NULL CHECK (value >= 0), -- below_detection: the quantitation limit
    unit              VARCHAR(20) NOT NULL,
    below_detection   BOOLEAN     NOT NULL DEFAULT FALSE,
    source            VARCHAR(10) NOT NULL CHECK (source IN ('MANUAL', 'PASTE')),
    task_id           BIGINT REFERENCES obligation_task (id) ON DELETE SET NULL, -- the task this value fulfils (O2)
    limit_id          BIGINT REFERENCES permit_limit (id) ON DELETE SET NULL,
    limit_value       NUMERIC,
    warn_ratio        NUMERIC(3, 2),
    level             VARCHAR(10) NOT NULL CHECK (level IN ('NORMAL', 'NEAR', 'EXCEEDED', 'NO_LIMIT')),
    entered_by        BIGINT REFERENCES users (id) ON DELETE SET NULL,
    entered_at        TIMESTAMPTZ NOT NULL,
    deleted_at        TIMESTAMPTZ,
    deleted_by        BIGINT REFERENCES users (id) ON DELETE SET NULL,
    delete_reason     VARCHAR(300),
    CHECK ((deleted_at IS NULL) = (delete_reason IS NULL)),
    CHECK ((level = 'NO_LIMIT') = (limit_value IS NULL))
);
CREATE INDEX measurement_point_idx ON measurement (emission_point_id, pollutant, measured_on) WHERE deleted_at IS NULL;
CREATE INDEX measurement_task_idx ON measurement (task_id) WHERE deleted_at IS NULL;
