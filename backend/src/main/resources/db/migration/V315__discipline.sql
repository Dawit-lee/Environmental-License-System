-- design-schedule/02: company-wide discipline list (분야). Edited by the company master (users.role = ADMIN).
CREATE TABLE discipline (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id BIGINT       NOT NULL REFERENCES company (id) ON DELETE CASCADE,
    code       VARCHAR(40), -- seeded rows only; ones added by the admin have none
    name       VARCHAR(100) NOT NULL,
    sort_order INT          NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT true,
    UNIQUE (company_id, code),
    UNIQUE (company_id, name)
);

-- The default list (practitioner schedules), copied to every company: existing ones now, new ones by trigger.
CREATE FUNCTION seed_disciplines(cid BIGINT) RETURNS void AS $$
    INSERT INTO discipline (company_id, code, name, sort_order)
    SELECT cid, code, name, sort_order
    FROM (VALUES ('COMMON', '공통', 10), ('PROCESS', '공정·기계', 20), ('ARCH', '건축', 30), ('STRUCT', '구조', 40),
                 ('MEP', '건축설비', 50), ('CIVIL', '토목', 60), ('LANDSCAPE', '조경', 70), ('ELEC', '전기·계측제어', 80),
                 ('PERMIT', '인허가', 90), ('CONSTRUCTION', '시공·VE', 100), ('DELIVERABLE', '성과품(편집·인쇄)', 110))
         AS d (code, name, sort_order);
$$ LANGUAGE sql;

CREATE FUNCTION company_seed_disciplines() RETURNS trigger AS $$
BEGIN
    PERFORM seed_disciplines(NEW.id);
    RETURN NEW;
END
$$ LANGUAGE plpgsql;
CREATE TRIGGER company_seed_disciplines AFTER INSERT ON company
    FOR EACH ROW EXECUTE FUNCTION company_seed_disciplines();

SELECT seed_disciplines(id) FROM company;
