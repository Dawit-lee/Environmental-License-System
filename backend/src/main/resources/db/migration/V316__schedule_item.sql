-- design-schedule/02: per-discipline schedule items (작업·마일스톤), FS dependencies and their append-only history.
-- anchor / offset_* / duration_days / baseline_* exist for ticket 03 and stay null here (fixed dates only).
CREATE TABLE schedule_item (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id           BIGINT       NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    discipline_id        BIGINT REFERENCES discipline (id), -- null: 공통
    parent_id            BIGINT REFERENCES schedule_item (id), -- one level only (checked by the API)
    kind                 VARCHAR(20)  NOT NULL CHECK (kind IN ('TASK', 'MILESTONE')),
    title                VARCHAR(200) NOT NULL,
    start_date           DATE         NOT NULL, -- current plan
    end_date             DATE         NOT NULL,
    baseline_start       DATE,
    baseline_end         DATE,
    actual_start         DATE,
    actual_end           DATE, -- set = 완료
    anchor               VARCHAR(20) CHECK (anchor IN ('CONTRACT', 'KICKOFF', 'SUBMISSION', 'COMPLETION', 'REVIEW')),
    offset_days          INT,
    offset_months        INT,
    duration_days        INT,
    owner_user_id        BIGINT REFERENCES users (id) ON DELETE SET NULL,
    partner_name         VARCHAR(100),
    weight               NUMERIC(5, 2),
    progress             INT          NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    delay_reason         VARCHAR(500),
    source_suggestion_id BIGINT REFERENCES ai_suggestion (id) ON DELETE SET NULL, -- trace only, never read by queries
    version              BIGINT       NOT NULL DEFAULT 0,
    deleted_at           TIMESTAMPTZ, -- soft delete keeps the history
    CHECK (start_date <= end_date),
    CHECK (kind = 'TASK' OR start_date = end_date),
    CHECK (offset_days IS NULL OR offset_months IS NULL)
);
CREATE INDEX schedule_item_project_idx ON schedule_item (project_id) WHERE deleted_at IS NULL;

-- Finish-to-start only. Same project and no cycles are checked by the API on insert.
CREATE TABLE schedule_item_dependency (
    predecessor_id BIGINT NOT NULL REFERENCES schedule_item (id) ON DELETE CASCADE,
    successor_id   BIGINT NOT NULL REFERENCES schedule_item (id) ON DELETE CASCADE,
    PRIMARY KEY (predecessor_id, successor_id),
    CHECK (predecessor_id <> successor_id)
);
CREATE INDEX schedule_item_dependency_successor_idx ON schedule_item_dependency (successor_id);

CREATE TABLE schedule_item_event (
    id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    item_id  BIGINT      NOT NULL REFERENCES schedule_item (id) ON DELETE CASCADE,
    at       TIMESTAMPTZ NOT NULL,
    actor_id BIGINT REFERENCES users (id) ON DELETE SET NULL,
    -- CREATED / UPDATED / DATES_CHANGED / BASELINED / COMPLETED / REOPENED / OWNER_CHANGED / DELETED / APPLIED_FROM_SUGGESTION
    type     VARCHAR(30) NOT NULL,
    data     JSONB
);
CREATE INDEX schedule_item_event_item_idx ON schedule_item_event (item_id, id);

-- Append-only like task_event: the app user owns the table, so a trigger (not REVOKE) rejects UPDATE/DELETE;
-- a DELETE passes only as the cascade of its item (i.e. its project) being deleted.
CREATE FUNCTION schedule_item_event_append_only() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' AND NOT EXISTS (SELECT 1 FROM schedule_item WHERE id = OLD.item_id) THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'schedule_item_event is append-only';
END
$$ LANGUAGE plpgsql;
CREATE TRIGGER schedule_item_event_append_only BEFORE UPDATE OR DELETE ON schedule_item_event
    FOR EACH ROW EXECUTE FUNCTION schedule_item_event_append_only();
