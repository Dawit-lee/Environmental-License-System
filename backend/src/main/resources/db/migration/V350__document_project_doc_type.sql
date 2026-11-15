-- design-schedule/07: a document belongs to a project, and optionally to one of its permit items (공고문·과업내용서·
-- 회의록 have no permit item). doc_type picks the schedule-extraction prompt; existing documents keep null.
ALTER TABLE document
    ADD COLUMN project_id BIGINT REFERENCES project (id) ON DELETE CASCADE,
    ADD COLUMN doc_type   VARCHAR(30) CHECK (doc_type IN ('BID_NOTICE', 'SITE_BRIEFING_DOC', 'TASK_SPEC', 'MINUTES',
                                                          'OFFICIAL_LETTER', 'WEEKLY_REPORT'));

UPDATE document d SET project_id = p.project_id FROM permit_item p WHERE p.id = d.permit_item_id;

-- the project is always set; the permit item (when set) is checked to be in that project by the API
ALTER TABLE document
    ALTER COLUMN project_id SET NOT NULL,
    ALTER COLUMN permit_item_id DROP NOT NULL;

CREATE INDEX document_project_idx ON document (project_id);
