-- Local dev only: sample org chart (에너지환경사업부 / 1·2·3팀), team members and project PM/EM (runs after R__dev_user).
DO $$
DECLARE
    co   BIGINT := (SELECT id FROM company WHERE name = '(가상) 푸른환경엔지니어링');
    dept BIGINT;
    t1 BIGINT; t2 BIGINT; t3 BIGINT;
    u_dev BIGINT := (SELECT id FROM users WHERE email = 'dev@envpermit.local');
    u_kim BIGINT := (SELECT id FROM users WHERE email = 'kim@envpermit.local');
BEGIN
    IF co IS NULL OR EXISTS (SELECT 1 FROM department WHERE company_id = co) THEN
        RETURN;
    END IF;
    INSERT INTO department (company_id, name) VALUES (co, '에너지환경사업부') RETURNING id INTO dept;
    INSERT INTO team (department_id, name) VALUES (dept, '1팀') RETURNING id INTO t1;
    INSERT INTO team (department_id, name) VALUES (dept, '2팀') RETURNING id INTO t2;
    INSERT INTO team (department_id, name) VALUES (dept, '3팀') RETURNING id INTO t3;

    UPDATE users SET team_id = t1 WHERE id = u_dev;
    UPDATE users SET team_id = t2 WHERE id = u_kim;

    UPDATE project SET team_id = t1, pm_user_id = u_dev, em_user_id = u_kim WHERE company_id = co AND name = '가람시 하수처리장 증설공사';
    UPDATE project SET team_id = t1, pm_user_id = u_dev WHERE company_id = co AND name = '누리시 자원회수시설 신설';
    UPDATE project SET team_id = t2, pm_user_id = u_kim, em_user_id = u_dev WHERE company_id = co AND name = '빛고을 통합바이오가스화시설';
    UPDATE project SET team_id = t3, pm_user_id = u_dev WHERE company_id = co AND name = '한솔산단 폐수처리장';
END $$;
