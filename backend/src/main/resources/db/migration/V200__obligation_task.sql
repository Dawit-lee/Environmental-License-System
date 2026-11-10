-- operations-compliance/01: one task per obligation due date, closed with evidence (design §2.1–2.3).
CREATE TABLE obligation_task (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id       BIGINT       NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    kind             VARCHAR(20)  NOT NULL CHECK (kind IN ('OBLIGATION', 'CORRECTIVE')), -- CORRECTIVE: ticket 06
    obligation_id    BIGINT REFERENCES permit_obligation (id) ON DELETE CASCADE,
    title            VARCHAR(300) NOT NULL, -- OBLIGATION tasks show the obligation's current summary instead
    due_date         DATE         NOT NULL,
    assignee_id      BIGINT REFERENCES users (id) ON DELETE SET NULL, -- null = every project member
    status           VARCHAR(10)  NOT NULL CHECK (status IN ('OPEN', 'DONE', 'CANCELLED')),
    note             TEXT,
    done_by          BIGINT REFERENCES users (id) ON DELETE SET NULL,
    done_at          TIMESTAMPTZ,
    cancelled_reason VARCHAR(30), -- OBLIGATION_CLOSED / REVISION_REMOVED / MANUAL
    version          BIGINT       NOT NULL DEFAULT 0,
    CHECK (kind <> 'OBLIGATION' OR obligation_id IS NOT NULL)
);
CREATE INDEX obligation_task_project_idx ON obligation_task (project_id, status, due_date);
CREATE INDEX obligation_task_obligation_idx ON obligation_task (obligation_id);
-- invariant 1: at most one open cycle per obligation
CREATE UNIQUE INDEX obligation_task_one_open_idx ON obligation_task (obligation_id)
    WHERE kind = 'OBLIGATION' AND status = 'OPEN';

-- document_id has no ON DELETE action and is checked at commit: a document used as evidence cannot be deleted
-- on its own, but goes together with its task when the permit item or project is removed.
CREATE TABLE task_evidence (
    task_id     BIGINT      NOT NULL REFERENCES obligation_task (id) ON DELETE CASCADE,
    document_id BIGINT      NOT NULL REFERENCES document (id) DEFERRABLE INITIALLY DEFERRED,
    added_by    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    added_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (task_id, document_id)
);
CREATE INDEX task_evidence_document_idx ON task_evidence (document_id);

CREATE TABLE task_event (
    id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id  BIGINT      NOT NULL REFERENCES obligation_task (id) ON DELETE CASCADE,
    actor_id BIGINT REFERENCES users (id), -- null = SYSTEM (migration)
    at       TIMESTAMPTZ NOT NULL,
    type     VARCHAR(20) NOT NULL, -- CREATED / DUE_CHANGED / EVIDENCE_ADDED / EVIDENCE_REMOVED / COMPLETED / CANCELLED
    data     JSONB
);
CREATE INDEX task_event_task_idx ON task_event (task_id, id);

-- Append-only like ai_suggestion_event: the app user owns the table, so a trigger (not REVOKE) rejects
-- UPDATE/DELETE; a DELETE passes only as the cascade of its task being deleted.
CREATE FUNCTION task_event_append_only() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' AND NOT EXISTS (SELECT 1 FROM obligation_task WHERE id = OLD.task_id) THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'task_event is append-only';
END
$$ LANGUAGE plpgsql;
CREATE TRIGGER task_event_append_only BEFORE UPDATE OR DELETE ON task_event
    FOR EACH ROW EXECUTE FUNCTION task_event_append_only();

-- Existing live obligations with a due date get their current cycle; their deadline_event rows stay as they are.
INSERT INTO obligation_task (project_id, kind, obligation_id, title, due_date, status)
SELECT p.project_id, 'OBLIGATION', o.id, o.summary, o.next_due_date, 'OPEN'
FROM permit_obligation o JOIN permit_item p ON p.id = o.permit_item_id
WHERE o.status = 'ACTIVE' AND o.next_due_date IS NOT NULL;

INSERT INTO task_event (task_id, at, type, data)
SELECT id, now(), 'CREATED', '{"source": "MIGRATION"}' FROM obligation_task;
