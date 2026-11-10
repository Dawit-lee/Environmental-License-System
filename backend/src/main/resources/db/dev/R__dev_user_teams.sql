-- Local dev only: sample org chart, team members and project PM/EM (runs after R__dev_user). All names are fictional.
--   에너지환경사업부 1·2·3팀 / 수처리사업부 1·2팀 / 플랜트사업부 1팀
-- Extra logins (password dev1234!): lee@envpermit.local 이담당, choi@envpermit.local 최담당 (회사 A, no project membership)
DO $$
DECLARE
    pw  CONSTANT TEXT := '$2a$10$LmSvVrFIpIK00B6QLKhF9uj7n.mYazuToeyq6LsET0JtD8qcZMz3S';
    co  BIGINT := (SELECT id FROM company WHERE name = '(가상) 푸른환경엔지니어링');
    energy BIGINT; water BIGINT; plant BIGINT;
    e1 BIGINT; e2 BIGINT; e3 BIGINT; w1 BIGINT; w2 BIGINT; p1 BIGINT;
    u_dev BIGINT := (SELECT id FROM users WHERE email = 'dev@envpermit.local');
    u_kim BIGINT := (SELECT id FROM users WHERE email = 'kim@envpermit.local');
    u_lee BIGINT; u_choi BIGINT;
BEGIN
    IF co IS NULL OR EXISTS (SELECT 1 FROM department WHERE company_id = co) THEN
        RETURN;
    END IF;
    INSERT INTO department (company_id, name) VALUES (co, '에너지환경사업부') RETURNING id INTO energy;
    INSERT INTO department (company_id, name) VALUES (co, '수처리사업부') RETURNING id INTO water;
    INSERT INTO department (company_id, name) VALUES (co, '플랜트사업부') RETURNING id INTO plant;
    INSERT INTO team (department_id, name) VALUES (energy, '1팀') RETURNING id INTO e1;
    INSERT INTO team (department_id, name) VALUES (energy, '2팀') RETURNING id INTO e2;
    INSERT INTO team (department_id, name) VALUES (energy, '3팀') RETURNING id INTO e3;
    INSERT INTO team (department_id, name) VALUES (water, '1팀') RETURNING id INTO w1;
    INSERT INTO team (department_id, name) VALUES (water, '2팀') RETURNING id INTO w2;
    INSERT INTO team (department_id, name) VALUES (plant, '1팀') RETURNING id INTO p1;

    INSERT INTO users (email, password_hash, name, role, company_id)
    VALUES ('lee@envpermit.local', pw, '이담당', 'USER', co) ON CONFLICT (email) DO UPDATE SET company_id = co
    RETURNING id INTO u_lee;
    INSERT INTO users (email, password_hash, name, role, company_id)
    VALUES ('choi@envpermit.local', pw, '최담당', 'USER', co) ON CONFLICT (email) DO UPDATE SET company_id = co
    RETURNING id INTO u_choi;

    UPDATE users SET team_id = e1 WHERE id = u_dev;
    UPDATE users SET team_id = w1 WHERE id = u_kim;
    UPDATE users SET team_id = w2 WHERE id = u_lee;
    UPDATE users SET team_id = p1 WHERE id = u_choi;

    UPDATE project SET team_id = w1, pm_user_id = u_kim, em_user_id = u_dev WHERE company_id = co AND name = '가람시 하수처리장 증설공사';
    UPDATE project SET team_id = w2, pm_user_id = u_lee, em_user_id = u_kim WHERE company_id = co AND name = '한솔산단 폐수처리장';
    UPDATE project SET team_id = p1, pm_user_id = u_choi, em_user_id = u_dev WHERE company_id = co AND name = '누리시 자원회수시설 신설';
    UPDATE project SET team_id = e1, pm_user_id = u_dev, em_user_id = u_choi WHERE company_id = co AND name = '빛고을 통합바이오가스화시설';

    -- one more project so 에너지환경사업부 has more than one team in use
    INSERT INTO project (company_id, name, facility_type, location, capacity, capacity_unit, start_date, expected_end_date,
                         status, team_id, pm_user_id, em_user_id)
    VALUES (co, '새벽시 매립가스 자원화시설', 'LANDFILL', '새벽시 동구 매립로 12', 60, '톤/일', current_date + 150,
            current_date + 720, 'IN_PROGRESS', e2, u_dev, u_lee);
    INSERT INTO project_member (project_id, user_id, is_admin, can_self_approve, can_bulk_approve)
    SELECT id, u_dev, TRUE, TRUE, TRUE FROM project WHERE company_id = co AND name = '새벽시 매립가스 자원화시설';
END $$;
