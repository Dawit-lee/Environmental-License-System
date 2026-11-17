-- design-schedule/05: read-only partner links (웹 보기 + .ics) and personal calendar subscription tokens.
-- Only SHA-256 (hex) of a token is stored; the token itself is shown once, at creation.
CREATE TABLE project_share_link (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id       BIGINT      NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    token_hash       CHAR(64)    NOT NULL UNIQUE,
    discipline_ids   BIGINT[], -- null = every discipline incl. 공통; else only these (공통 excluded)
    include_events   BOOLEAN     NOT NULL DEFAULT true,
    expires_at       TIMESTAMPTZ NOT NULL,
    revoked_at       TIMESTAMPTZ, -- kept, not deleted, so the list shows what was shared
    created_by       BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    last_accessed_at TIMESTAMPTZ,
    CHECK (discipline_ids IS NULL OR cardinality(discipline_ids) > 0)
);
CREATE INDEX project_share_link_project_idx ON project_share_link (project_id);

-- One per user; reissuing replaces the hash, so the old address stops working.
CREATE TABLE user_calendar_token (
    user_id    BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    token_hash CHAR(64)    NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL
);
