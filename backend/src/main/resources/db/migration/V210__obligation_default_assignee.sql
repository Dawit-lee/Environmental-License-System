-- operations-compliance/02: who gets the next cycle's task. Null → project EM → PM → nobody (every member).
ALTER TABLE permit_obligation
    ADD COLUMN default_assignee_id BIGINT REFERENCES users (id) ON DELETE SET NULL;
-- GET /api/tasks/mine
CREATE INDEX obligation_task_assignee_idx ON obligation_task (assignee_id) WHERE status = 'OPEN';
