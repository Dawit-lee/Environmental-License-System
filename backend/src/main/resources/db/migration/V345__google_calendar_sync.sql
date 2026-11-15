-- design-schedule/06: per-user, one-way (app → Google) calendar sync into an app-created secondary calendar.
-- Off by default; a company master turns it on for the whole company.
ALTER TABLE company ADD COLUMN google_sync_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Only the refresh token is kept, AES-GCM encrypted with GOOGLE_TOKEN_ENC_KEY (never plain text in the DB).
CREATE TABLE user_google_link (
    user_id                 BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    encrypted_refresh_token TEXT         NOT NULL,
    calendar_id             VARCHAR(300) NOT NULL,
    sync_project_milestones BOOLEAN      NOT NULL DEFAULT FALSE, -- every milestone of my projects, not only mine
    sync_tasks              BOOLEAN      NOT NULL DEFAULT FALSE, -- TASK bars I own (default off)
    connected_at            TIMESTAMPTZ  NOT NULL,
    last_synced_at          TIMESTAMPTZ,
    last_error              VARCHAR(500)
);

-- One-time OAuth state: the callback is a plain browser redirect without our bearer token, so the state names the user.
CREATE TABLE google_oauth_state (
    state      VARCHAR(64) PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL
);

-- app source ↔ Google event. source_kind EVENT = project_event, ITEM = schedule_item.
CREATE TABLE calendar_sync_link (
    user_id         BIGINT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    source_kind     VARCHAR(10)   NOT NULL CHECK (source_kind IN ('EVENT', 'ITEM')),
    source_id       BIGINT        NOT NULL, -- no FK: the link must outlive a deleted source until Google is told
    google_event_id VARCHAR(1024) NOT NULL,
    etag            VARCHAR(200),
    PRIMARY KEY (user_id, source_kind, source_id)
);

-- At most one pending row per (user, source); the job reconciles to the source's current state, so op is a hint.
-- next_attempt_at null = due now. failing_since is set by the job on the first failure (gives up after 24 h).
CREATE TABLE calendar_sync_outbox (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    source_kind     VARCHAR(10) NOT NULL CHECK (source_kind IN ('EVENT', 'ITEM')),
    source_id       BIGINT      NOT NULL,
    op              VARCHAR(10) NOT NULL CHECK (op IN ('UPSERT', 'DELETE')),
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    failing_since   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, source_kind, source_id)
);

-- Triggers write the outbox in the saving transaction, so every write path (API, paste, templates, base-date
-- shifts, project delete cascade) is covered. Targets: the given users, plus whoever already has the source in Google.
CREATE FUNCTION calendar_sync_enqueue(kind TEXT, sid BIGINT, uids BIGINT[], o TEXT) RETURNS void AS $$
    INSERT INTO calendar_sync_outbox (user_id, source_kind, source_id, op)
    SELECT g.user_id, kind, sid, o FROM user_google_link g
    WHERE g.user_id = ANY (uids)
       OR EXISTS (SELECT 1 FROM calendar_sync_link l
                  WHERE l.user_id = g.user_id AND l.source_kind = kind AND l.source_id = sid)
    ON CONFLICT (user_id, source_kind, source_id) DO UPDATE SET op = EXCLUDED.op
$$ LANGUAGE sql;

CREATE FUNCTION project_event_sync() RETURNS trigger AS $$
DECLARE r project_event := COALESCE(NEW, OLD);
BEGIN
    PERFORM calendar_sync_enqueue('EVENT', r.id,
            ARRAY(SELECT user_id FROM project_event_attendee WHERE event_id = r.id AND user_id IS NOT NULL),
            CASE TG_OP WHEN 'DELETE' THEN 'DELETE' ELSE 'UPSERT' END);
    RETURN NULL;
END
$$ LANGUAGE plpgsql;
CREATE TRIGGER project_event_sync AFTER INSERT OR UPDATE OR DELETE ON project_event
    FOR EACH ROW EXECUTE FUNCTION project_event_sync();

CREATE FUNCTION project_event_attendee_sync() RETURNS trigger AS $$
DECLARE r project_event_attendee := COALESCE(NEW, OLD);
BEGIN
    IF r.user_id IS NOT NULL THEN
        PERFORM calendar_sync_enqueue('EVENT', r.event_id, ARRAY[r.user_id], 'UPSERT');
    END IF;
    RETURN NULL;
END
$$ LANGUAGE plpgsql;
CREATE TRIGGER project_event_attendee_sync AFTER INSERT OR DELETE ON project_event_attendee
    FOR EACH ROW EXECUTE FUNCTION project_event_attendee_sync();

-- Owner (old and new) and project members: the job decides per user from their toggles.
CREATE FUNCTION schedule_item_sync() RETURNS trigger AS $$
DECLARE r schedule_item := COALESCE(NEW, OLD);
BEGIN
    PERFORM calendar_sync_enqueue('ITEM', r.id,
            ARRAY(SELECT user_id FROM project_member WHERE project_id = r.project_id)
                || ARRAY[NEW.owner_user_id, OLD.owner_user_id],
            CASE TG_OP WHEN 'DELETE' THEN 'DELETE' ELSE 'UPSERT' END);
    RETURN NULL;
END
$$ LANGUAGE plpgsql;
CREATE TRIGGER schedule_item_sync AFTER INSERT OR UPDATE OR DELETE ON schedule_item
    FOR EACH ROW EXECUTE FUNCTION schedule_item_sync();
