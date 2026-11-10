-- ④ version-diff/02: what an approved revision overwrote. Append-only like ai_suggestion_event: the app user owns
-- the table, so a trigger (not REVOKE) rejects UPDATE/DELETE; a DELETE passes only as its obligation's cascade.
CREATE TABLE permit_obligation_revision_log (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    obligation_id BIGINT      NOT NULL REFERENCES permit_obligation (id) ON DELETE CASCADE,
    suggestion_id BIGINT REFERENCES ai_suggestion (id) ON DELETE SET NULL,
    change_type   VARCHAR(10) NOT NULL, -- CHANGED / MINOR / REMOVED / UNCHANGED (ADDED has no "before")
    before        JSONB       NOT NULL,
    at            TIMESTAMPTZ NOT NULL,
    by            BIGINT REFERENCES users (id) ON DELETE SET NULL
);
CREATE INDEX permit_obligation_revision_log_obligation_idx ON permit_obligation_revision_log (obligation_id, id);

CREATE FUNCTION permit_obligation_revision_log_append_only() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' AND NOT EXISTS (SELECT 1 FROM permit_obligation WHERE id = OLD.obligation_id) THEN
        RETURN OLD;
    END IF;
    -- SET NULL from a deleted suggestion/user is the only update allowed
    IF TG_OP = 'UPDATE' AND NEW.obligation_id = OLD.obligation_id AND NEW.change_type = OLD.change_type
            AND NEW.before = OLD.before AND NEW.at = OLD.at
            AND (NEW.suggestion_id IS NULL AND NEW."by" IS NOT DISTINCT FROM OLD."by"
                 OR NEW."by" IS NULL AND NEW.suggestion_id IS NOT DISTINCT FROM OLD.suggestion_id) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'permit_obligation_revision_log is append-only';
END
$$ LANGUAGE plpgsql;
CREATE TRIGGER permit_obligation_revision_log_append_only BEFORE UPDATE OR DELETE ON permit_obligation_revision_log
    FOR EACH ROW EXECUTE FUNCTION permit_obligation_revision_log_append_only();
