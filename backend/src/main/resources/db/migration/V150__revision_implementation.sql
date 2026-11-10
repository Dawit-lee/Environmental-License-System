-- ④ version-diff/03: on-site follow-up of an approved CHANGED/ADDED revision (approval only updates the ledger).
-- target_date, when set, is a SITE_IMPLEMENTATION deadline_event (target_id = suggestion_id).
CREATE TABLE revision_implementation (
    suggestion_id BIGINT PRIMARY KEY REFERENCES ai_suggestion (id) ON DELETE CASCADE,
    obligation_id BIGINT      NOT NULL REFERENCES permit_obligation (id) ON DELETE CASCADE,
    assignee_id   BIGINT REFERENCES users (id) ON DELETE SET NULL,
    target_date   DATE,
    note          VARCHAR(1000),
    done_by       BIGINT REFERENCES users (id) ON DELETE SET NULL,
    done_at       TIMESTAMPTZ
);
CREATE INDEX revision_implementation_obligation_idx ON revision_implementation (obligation_id);
