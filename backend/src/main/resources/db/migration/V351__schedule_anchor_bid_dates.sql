-- design-schedule/07b: base dates that 현장설명서·입찰안내서 count from — 현장설명일 (SITE_BRIEFING),
-- 실시설계적격자 통보일 (NOTICE) and 착공일 (CONSTRUCTION_START). All three live in project_schedule_anchor.
ALTER TABLE schedule_item DROP CONSTRAINT schedule_item_anchor_check;
ALTER TABLE schedule_item ADD CONSTRAINT schedule_item_anchor_check CHECK (anchor IN (
    'CONTRACT', 'KICKOFF', 'SUBMISSION', 'COMPLETION', 'REVIEW', 'SITE_BRIEFING', 'NOTICE', 'CONSTRUCTION_START'));

ALTER TABLE schedule_template_item DROP CONSTRAINT schedule_template_item_anchor_check;
ALTER TABLE schedule_template_item ADD CONSTRAINT schedule_template_item_anchor_check CHECK (anchor IN (
    'CONTRACT', 'KICKOFF', 'SUBMISSION', 'COMPLETION', 'REVIEW', 'SITE_BRIEFING', 'NOTICE', 'CONSTRUCTION_START'));

ALTER TABLE project_schedule_anchor DROP CONSTRAINT project_schedule_anchor_anchor_check;
ALTER TABLE project_schedule_anchor ADD CONSTRAINT project_schedule_anchor_anchor_check CHECK (anchor IN (
    'CONTRACT', 'SUBMISSION', 'REVIEW', 'SITE_BRIEFING', 'NOTICE', 'CONSTRUCTION_START'));
