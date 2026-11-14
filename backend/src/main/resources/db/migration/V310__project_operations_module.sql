-- design-schedule/01: the 운영 module (emission points, measured values, exceedance, corrective tasks, report)
-- is an operator's job, so it is opt-in per project. Every existing project starts with it off; no data is removed.
ALTER TABLE project ADD COLUMN operations_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- who switched a module on or off, and when (append-only)
CREATE TABLE project_module_event (
    id          BIGSERIAL PRIMARY KEY,
    project_id  BIGINT      NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    module      VARCHAR(30) NOT NULL,
    enabled     BOOLEAN     NOT NULL,
    actor_id    BIGINT      NOT NULL REFERENCES users (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX project_module_event_project ON project_module_event (project_id, id);
