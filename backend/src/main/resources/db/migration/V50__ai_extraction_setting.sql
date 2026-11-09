-- ① AI extraction is opt-in per company (documents leave for Anthropic). Only a master (ADMIN) turns it on;
-- who changed it and when is kept for security audits.
ALTER TABLE company
    ADD COLUMN ai_extraction_enabled    BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN ai_extraction_changed_by BIGINT REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN ai_extraction_changed_at TIMESTAMPTZ;
