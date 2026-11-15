-- design-schedule/04: project events (현장설명회·질의 마감·제출·발주처 보고·심의·회의·출장) and their attendees.
-- Every event belongs to a project, trips included. Times are local (Asia/Seoul) wall-clock; an all-day event keeps
-- 00:00 and ends_at is its last day (inclusive), so a trip of 3 days is starts_at = day 1, ends_at = day 3.
CREATE TABLE project_event (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id           BIGINT       NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    kind                 VARCHAR(20)  NOT NULL CHECK (kind IN ('SITE_BRIEFING', 'QNA_DEADLINE', 'SUBMISSION',
                                                               'CLIENT_REPORT', 'REVIEW', 'MEETING', 'TRIP', 'OTHER')),
    subkind              VARCHAR(50), -- e.g. CLIENT_REPORT 착수·중간·최종, MEETING 공정회의·분야 미팅
    title                VARCHAR(200) NOT NULL,
    starts_at            TIMESTAMP    NOT NULL,
    ends_at              TIMESTAMP    NOT NULL,
    all_day              BOOLEAN      NOT NULL DEFAULT false,
    location             VARCHAR(200),
    counterpart_org      VARCHAR(200), -- 상대 기관·회사; required for TRIP and MEETING
    purpose              VARCHAR(500),
    discipline_id        BIGINT REFERENCES discipline (id),
    checklist            TEXT, -- one line per item
    source_suggestion_id BIGINT REFERENCES ai_suggestion (id) ON DELETE SET NULL, -- trace only, never read by queries
    version              BIGINT       NOT NULL DEFAULT 0,
    CHECK (starts_at <= ends_at),
    CHECK (kind NOT IN ('TRIP', 'MEETING') OR counterpart_org IS NOT NULL)
);
CREATE INDEX project_event_project_idx ON project_event (project_id, starts_at);
CREATE INDEX project_event_starts_idx ON project_event (starts_at);

-- An app user, or an outside person by name (협력사 담당 등). role is free text: 참석·발표·답변·운전 …
CREATE TABLE project_event_attendee (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id      BIGINT NOT NULL REFERENCES project_event (id) ON DELETE CASCADE,
    user_id       BIGINT REFERENCES users (id) ON DELETE CASCADE,
    external_name VARCHAR(100),
    role          VARCHAR(50),
    CHECK ((user_id IS NULL) <> (external_name IS NULL)),
    UNIQUE (event_id, user_id)
);
CREATE INDEX project_event_attendee_event_idx ON project_event_attendee (event_id);
CREATE INDEX project_event_attendee_user_idx ON project_event_attendee (user_id);

-- deadline_event has no FK to its target; drop an event's reminder whenever the row goes (incl. project cascade)
CREATE FUNCTION project_event_drop_deadline() RETURNS trigger AS $$
BEGIN
    DELETE FROM deadline_event WHERE kind = 'PROJECT_EVENT' AND target_id = OLD.id;
    RETURN OLD;
END
$$ LANGUAGE plpgsql;
CREATE TRIGGER project_event_drop_deadline AFTER DELETE ON project_event
    FOR EACH ROW EXECUTE FUNCTION project_event_drop_deadline();
