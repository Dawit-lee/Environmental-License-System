-- A dated thing to be reminded about. kind is open-ended (PERMIT_EXPIRY now; OBLIGATION, LAW_ENFORCEMENT, ... later).
-- target_id points into the table that kind implies, so it has no FK; the owner removes its events.
CREATE TABLE deadline_event (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind        VARCHAR(40)  NOT NULL,
    target_id   BIGINT       NOT NULL,
    project_id  BIGINT       NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    title       VARCHAR(300) NOT NULL,
    due_date    DATE         NOT NULL,
    assignee_id BIGINT REFERENCES users (id) ON DELETE SET NULL, -- null: every project member
    UNIQUE (kind, target_id)
);
CREATE INDEX deadline_event_project_due_idx ON deadline_event (project_id, due_date);

CREATE TABLE notification (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id           BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    deadline_event_id BIGINT REFERENCES deadline_event (id) ON DELETE CASCADE,
    due_date          DATE,
    offset_days       INT,         -- 90/60/30/7; null for non-deadline notifications
    message           VARCHAR(500) NOT NULL,
    link              VARCHAR(500),
    created_at        TIMESTAMPTZ  NOT NULL,
    read_at           TIMESTAMPTZ,
    emailed_at        TIMESTAMPTZ,
    -- due_date is part of the key so a renewed (moved) deadline is reminded again
    UNIQUE (deadline_event_id, due_date, offset_days, user_id)
);
CREATE INDEX notification_user_idx ON notification (user_id, created_at DESC);

-- existing permits
INSERT INTO deadline_event (kind, target_id, project_id, title, due_date)
SELECT 'PERMIT_EXPIRY', id, project_id, name || ' 만료', expiry_date FROM permit_item WHERE expiry_date IS NOT NULL;
