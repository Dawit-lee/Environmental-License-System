-- design-schedule/03: schedule templates (설계 §5). Company-wide like disciplines; the company master edits the offsets.
CREATE TABLE schedule_template (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id BIGINT       NOT NULL REFERENCES company (id) ON DELETE CASCADE,
    code       VARCHAR(40),
    name       VARCHAR(100) NOT NULL,
    UNIQUE (company_id, code)
);

CREATE TABLE schedule_template_item (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    template_id     BIGINT       NOT NULL REFERENCES schedule_template (id) ON DELETE CASCADE,
    discipline_code VARCHAR(40), -- matched to the company's discipline on apply; null = 공통
    kind            VARCHAR(20)  NOT NULL CHECK (kind IN ('TASK', 'MILESTONE')),
    title           VARCHAR(200) NOT NULL,
    anchor          VARCHAR(20)  NOT NULL CHECK (anchor IN ('CONTRACT', 'KICKOFF', 'SUBMISSION', 'COMPLETION', 'REVIEW')),
    offset_days     INT,
    offset_months   INT,
    duration_days   INT CHECK (duration_days >= 1),
    sort_order      INT          NOT NULL,
    CHECK ((offset_days IS NULL) <> (offset_months IS NULL)),
    CHECK (kind = 'MILESTONE' OR duration_days IS NOT NULL)
);
CREATE INDEX schedule_template_item_template_idx ON schedule_template_item (template_id, sort_order);

-- Two fictional defaults, copied to every company: existing ones now, new ones by trigger.
CREATE FUNCTION seed_schedule_templates(cid BIGINT) RETURNS void AS $$
DECLARE
    turnkey BIGINT;
    service BIGINT;
BEGIN
    INSERT INTO schedule_template (company_id, code, name) VALUES (cid, 'TURNKEY_BID', '턴키 기본(입찰)설계')
        RETURNING id INTO turnkey;
    INSERT INTO schedule_template_item (template_id, discipline_code, kind, title, anchor, offset_days, duration_days, sort_order)
    VALUES (turnkey, 'COMMON', 'MILESTONE', '합사 개설', 'SUBMISSION', -150, NULL, 10),
           (turnkey, 'COMMON', 'MILESTONE', '설계 착수 회의', 'SUBMISSION', -145, NULL, 20),
           (turnkey, 'PROCESS', 'MILESTONE', '공정 계획 확정', 'SUBMISSION', -120, NULL, 30),
           (turnkey, 'ARCH', 'MILESTONE', '배치 계획 확정', 'SUBMISSION', -105, NULL, 40),
           (turnkey, 'PROCESS', 'MILESTONE', 'P&ID 1차', 'SUBMISSION', -90, NULL, 50),
           (turnkey, 'PERMIT', 'MILESTONE', '인허가 사전 협의', 'SUBMISSION', -75, NULL, 60),
           (turnkey, 'COMMON', 'MILESTONE', '분야별 기본도 완료', 'SUBMISSION', -60, NULL, 70),
           (turnkey, 'CONSTRUCTION', 'MILESTONE', '물량·내역 산출', 'SUBMISSION', -40, NULL, 80),
           (turnkey, 'COMMON', 'MILESTONE', '설계도서 내부 검토', 'SUBMISSION', -25, NULL, 90),
           (turnkey, 'DELIVERABLE', 'MILESTONE', '성과품 편집·인쇄', 'SUBMISSION', -7, NULL, 100),
           (turnkey, 'COMMON', 'MILESTONE', '입찰 설계도서 제출', 'SUBMISSION', 0, NULL, 110),
           (turnkey, 'PROCESS', 'TASK', '공정·기계 기본설계', 'SUBMISSION', -60, 85, 120),
           (turnkey, 'ARCH', 'TASK', '건축 기본설계', 'SUBMISSION', -60, 70, 130),
           (turnkey, 'CIVIL', 'TASK', '토목 기본설계', 'SUBMISSION', -60, 70, 140),
           (turnkey, 'ELEC', 'TASK', '전기·계측제어 기본설계', 'SUBMISSION', -50, 60, 150);

    INSERT INTO schedule_template (company_id, code, name) VALUES (cid, 'SERVICE', '용역(실시설계·연구)')
        RETURNING id INTO service;
    INSERT INTO schedule_template_item (template_id, discipline_code, kind, title, anchor, offset_days, offset_months, sort_order)
    VALUES (service, 'COMMON', 'MILESTONE', '과업수행계획서 제출', 'CONTRACT', 10, NULL, 10),
           (service, 'COMMON', 'MILESTONE', '착수보고', 'CONTRACT', 15, NULL, 20),
           (service, 'COMMON', 'MILESTONE', '중간보고', 'CONTRACT', NULL, 4, 30),
           (service, 'COMMON', 'MILESTONE', '최종보고', 'COMPLETION', -15, NULL, 40),
           (service, 'DELIVERABLE', 'MILESTONE', '최종보고서 초안', 'COMPLETION', -5, NULL, 50);
END
$$ LANGUAGE plpgsql;

CREATE FUNCTION company_seed_schedule_templates() RETURNS trigger AS $$
BEGIN
    PERFORM seed_schedule_templates(NEW.id);
    RETURN NEW;
END
$$ LANGUAGE plpgsql;
CREATE TRIGGER company_seed_schedule_templates AFTER INSERT ON company
    FOR EACH ROW EXECUTE FUNCTION company_seed_schedule_templates();

SELECT seed_schedule_templates(id) FROM company;
