-- Impact of one amendment on one permit item (law-monitoring/03). The unit of law alerts.
CREATE TABLE permit_law_impact (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    amendment_id   BIGINT      NOT NULL REFERENCES law_amendment (id) ON DELETE CASCADE,
    permit_item_id BIGINT      NOT NULL REFERENCES permit_item (id) ON DELETE CASCADE,
    level          VARCHAR(10) NOT NULL CHECK (level IN ('DIRECT', 'REFERENCE')),
    status         VARCHAR(10) NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW', 'REVIEWED')),
    reviewed_by    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    reviewed_at    TIMESTAMPTZ,
    note           TEXT,
    created_at     TIMESTAMPTZ NOT NULL, -- KPI "time to review" = reviewed_at - created_at
    emailed_at     TIMESTAMPTZ,          -- null = not in a daily mail yet
    UNIQUE (amendment_id, permit_item_id)
);
CREATE INDEX permit_law_impact_permit_idx ON permit_law_impact (permit_item_id);
