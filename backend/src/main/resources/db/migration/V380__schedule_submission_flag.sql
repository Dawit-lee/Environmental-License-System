-- 제출·보고 마일스톤 flag (follow-up of design-schedule/06): the Google sync option sends only these, not every milestone.
ALTER TABLE schedule_item ADD COLUMN submission BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE schedule_item ADD CONSTRAINT schedule_item_submission_milestone CHECK (NOT submission OR kind = 'MILESTONE');
ALTER TABLE schedule_template_item ADD COLUMN submission BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE schedule_template_item ADD CONSTRAINT schedule_template_item_submission_milestone
    CHECK (NOT submission OR kind = 'MILESTONE');

-- The obvious ones in the seeded templates: 제출 and 보고(회) milestones.
CREATE FUNCTION flag_submission_template_items(cid BIGINT) RETURNS void AS $$
    UPDATE schedule_template_item ti SET submission = TRUE
    FROM schedule_template t
    WHERE t.id = ti.template_id AND t.company_id = cid AND t.code IN ('TURNKEY_BID', 'SERVICE')
      AND ti.kind = 'MILESTONE'
      AND ti.title IN ('입찰 설계도서 제출', '과업수행계획서 제출', '착수보고', '중간보고', '최종보고');
$$ LANGUAGE sql;

CREATE FUNCTION company_flag_submission_template_items() RETURNS trigger AS $$
BEGIN
    PERFORM flag_submission_template_items(NEW.id);
    RETURN NEW;
END
$$ LANGUAGE plpgsql;
-- Name sorts after company_seed_schedule_templates, so it fires after the seed (same-event triggers run by name).
CREATE TRIGGER company_seed_schedule_templates_submission AFTER INSERT ON company
    FOR EACH ROW EXECUTE FUNCTION company_flag_submission_template_items();

SELECT flag_submission_template_items(id) FROM company;

-- Existing milestones with those titles.
UPDATE schedule_item SET submission = TRUE
WHERE kind = 'MILESTONE' AND deleted_at IS NULL
  AND title IN ('입찰 설계도서 제출', '과업수행계획서 제출', '착수보고', '중간보고', '최종보고');
