-- ④ version diff/01: a document may supersede one earlier document of the same permit item (app-checked);
-- the chain v0 ← v1 ← v2 is the document's version line. Live obligations get an optimistic lock and why they closed.
ALTER TABLE document
    ADD COLUMN supersedes_document_id BIGINT UNIQUE REFERENCES document (id) ON DELETE SET NULL,
    ADD COLUMN effective_date         DATE; -- shown only, never interpreted

ALTER TABLE permit_obligation
    ADD COLUMN version       BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN closed_reason VARCHAR(20), -- MANUAL / REVISION_REMOVED
    ADD COLUMN closed_at     TIMESTAMPTZ;
UPDATE permit_obligation SET closed_reason = 'MANUAL' WHERE status = 'CLOSED';
