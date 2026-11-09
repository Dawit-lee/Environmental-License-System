-- ③ human review: AI (and manually added) output waits in ai_suggestion until a person approves it.
-- Only a kind's Applier writes live rows. Deadlines/notifications/dashboard never read these tables.

CREATE TABLE ai_run (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id       BIGINT       NOT NULL REFERENCES company (id) ON DELETE CASCADE,
    project_id       BIGINT       NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    kind             VARCHAR(40)  NOT NULL,
    input_ref        JSONB,
    model            VARCHAR(100),
    prompt_version   VARCHAR(100),
    preparer         VARCHAR(40)  NOT NULL, -- CLAUDE_PDF_DIRECT / CLOVA_OCR / MANUAL (the "manual entry" run)
    raw_response_key VARCHAR(500),
    input_tokens     INT,
    output_tokens    INT,
    status           VARCHAR(20)  NOT NULL, -- RUNNING / SUCCEEDED / FAILED
    error_message    TEXT,
    started_by       BIGINT REFERENCES users (id) ON DELETE SET NULL,
    started_at       TIMESTAMPTZ  NOT NULL,
    finished_at      TIMESTAMPTZ
);
CREATE INDEX ai_run_project_idx ON ai_run (project_id);

CREATE TABLE ai_suggestion (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id        BIGINT REFERENCES company (id) ON DELETE CASCADE,
    project_id        BIGINT REFERENCES project (id) ON DELETE CASCADE,
    run_id            BIGINT      NOT NULL REFERENCES ai_run (id) ON DELETE CASCADE,
    kind              VARCHAR(40) NOT NULL,
    target_parent_id  BIGINT,      -- OBLIGATION: permit_item id; meaning depends on kind, so no FK
    origin            VARCHAR(10) NOT NULL, -- AI / MANUAL
    payload           JSONB       NOT NULL, -- immutable original
    edited_payload    JSONB,
    status            VARCHAR(20) NOT NULL,
    proposed_decision VARCHAR(10),
    submitted_by      BIGINT REFERENCES users (id) ON DELETE SET NULL,
    submitted_at      TIMESTAMPTZ,
    reviewed_by       BIGINT REFERENCES users (id) ON DELETE SET NULL,
    reviewed_at       TIMESTAMPTZ,
    reject_reason     VARCHAR(500),
    applied_entity_id BIGINT,
    version           BIGINT      NOT NULL DEFAULT 0,
    -- only DERIVATION_RULE suggestions are company/project-less (auto-derivation spec §5.4)
    CHECK (kind = 'DERIVATION_RULE' OR (company_id IS NOT NULL AND project_id IS NOT NULL))
);
CREATE INDEX ai_suggestion_project_idx ON ai_suggestion (project_id, status);
CREATE INDEX ai_suggestion_run_idx ON ai_suggestion (run_id);

CREATE TABLE ai_suggestion_event (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    suggestion_id BIGINT      NOT NULL REFERENCES ai_suggestion (id) ON DELETE CASCADE,
    actor_id      BIGINT REFERENCES users (id), -- null = SYSTEM
    at            TIMESTAMPTZ NOT NULL,
    type          VARCHAR(20) NOT NULL,
    data          JSONB
);
CREATE INDEX ai_suggestion_event_suggestion_idx ON ai_suggestion_event (suggestion_id, id);

-- Append-only. The app user owns the table, so REVOKE alone would not stop it; a trigger does.
-- A DELETE is allowed only as the cascade of its suggestion being deleted (project/run removal).
CREATE FUNCTION ai_suggestion_event_append_only() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' AND NOT EXISTS (SELECT 1 FROM ai_suggestion WHERE id = OLD.suggestion_id) THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'ai_suggestion_event is append-only';
END
$$ LANGUAGE plpgsql;
CREATE TRIGGER ai_suggestion_event_append_only BEFORE UPDATE OR DELETE ON ai_suggestion_event
    FOR EACH ROW EXECUTE FUNCTION ai_suggestion_event_append_only();

-- Live obligations under a permit item (design §3.7). Fields mirror the OBLIGATION payload at approval time.
CREATE TABLE permit_obligation (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    permit_item_id     BIGINT       NOT NULL REFERENCES permit_item (id) ON DELETE CASCADE,
    source_document_id BIGINT REFERENCES document (id) ON DELETE SET NULL,
    suggestion_id      BIGINT REFERENCES ai_suggestion (id) ON DELETE SET NULL, -- null: entered directly
    citation_raw       VARCHAR(200),
    citation_key       VARCHAR(100),
    citation_synthetic BOOLEAN      NOT NULL DEFAULT FALSE,
    requirement_text   TEXT         NOT NULL,
    summary            VARCHAR(200) NOT NULL,
    type               VARCHAR(30)  NOT NULL,
    category           VARCHAR(30)  NOT NULL,
    responsible_party  VARCHAR(200),
    frequency          VARCHAR(20)  NOT NULL,
    frequency_text     VARCHAR(200),
    period_text        VARCHAR(200),
    legal_basis        JSONB        NOT NULL DEFAULT '[]',
    page_no            INT,
    source_quote       TEXT,
    quote_check        VARCHAR(20),
    first_due_date     DATE,
    next_due_date      DATE,
    status             VARCHAR(10)  NOT NULL -- ACTIVE / CLOSED
);
CREATE INDEX permit_obligation_permit_idx ON permit_obligation (permit_item_id);

-- deadline_event has no FK to its target; drop an obligation's deadline whenever the row goes (incl. permit cascade)
CREATE FUNCTION permit_obligation_drop_deadline() RETURNS trigger AS $$
BEGIN
    DELETE FROM deadline_event WHERE kind = 'OBLIGATION' AND target_id = OLD.id;
    RETURN OLD;
END
$$ LANGUAGE plpgsql;
CREATE TRIGGER permit_obligation_drop_deadline AFTER DELETE ON permit_obligation
    FOR EACH ROW EXECUTE FUNCTION permit_obligation_drop_deadline();
