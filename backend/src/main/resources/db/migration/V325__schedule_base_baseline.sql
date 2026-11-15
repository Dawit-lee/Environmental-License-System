-- design-schedule/03: the project's main base date, its other base dates, and the baseline counter (설계 §2.3·§2.4).
-- 착수일 = project.start_date and 완료일 = project.expected_end_date; only the other three live in project_schedule_anchor.
ALTER TABLE project
    ADD COLUMN schedule_base        VARCHAR(20) NOT NULL DEFAULT 'KICKOFF' CHECK (schedule_base IN ('SUBMISSION', 'KICKOFF')),
    ADD COLUMN schedule_baseline_no INT         NOT NULL DEFAULT 0;

CREATE TABLE project_schedule_anchor (
    project_id BIGINT      NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    anchor     VARCHAR(20) NOT NULL CHECK (anchor IN ('CONTRACT', 'SUBMISSION', 'REVIEW')),
    date       DATE        NOT NULL,
    note       VARCHAR(200),
    PRIMARY KEY (project_id, anchor)
);

-- A relative item whose base date is not known yet (e.g. right after applying a template) has no dates.
ALTER TABLE schedule_item
    ALTER COLUMN start_date DROP NOT NULL,
    ALTER COLUMN end_date DROP NOT NULL,
    ADD CHECK ((start_date IS NULL) = (end_date IS NULL)),
    ADD CHECK (end_date IS NOT NULL OR anchor IS NOT NULL),
    ADD CHECK (anchor IS NULL OR offset_days IS NOT NULL OR offset_months IS NOT NULL),
    ADD CHECK (duration_days IS NULL OR duration_days >= 1);
