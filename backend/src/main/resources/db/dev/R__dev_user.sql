-- Local dev only (runs with the "dev" profile). All names are fictional.
-- Logins (password dev1234! for all):
--   dev@envpermit.local   개발자  (회사 A, ADMIN, creator of the A projects)
--   kim@envpermit.local   김담당  (회사 A, member of project 1 without approval flags)
--   other@envpermit.local 타회사  (회사 B; cannot see A's projects → 403)
INSERT INTO users (email, password_hash, name, role)
VALUES ('dev@envpermit.local', '$2a$10$LmSvVrFIpIK00B6QLKhF9uj7n.mYazuToeyq6LsET0JtD8qcZMz3S', '개발자', 'ADMIN')
ON CONFLICT (email) DO NOTHING;

-- Databases seeded before the addresses were made fully fictional: rename in place (the seed below runs once).
UPDATE project SET location = '가람시 서구 새터동 1234' WHERE name = '가람시 하수처리장 증설공사' AND location LIKE '가람시 서구 %';
UPDATE project SET location = '누리시 중구 솔밭동 88' WHERE name = '누리시 자원회수시설 신설' AND location LIKE '누리시 %구 % 88';
UPDATE project SET location = '빛고을시 남구 달빛동 55-1' WHERE name = '빛고을 통합바이오가스화시설' AND location LIKE '빛고을시 %구 % 55-1';
UPDATE project SET location = '한솔시 북구 물빛동 700' WHERE name = '한솔산단 폐수처리장' AND location LIKE '한솔시 %구 % 700';

DO $$
DECLARE
    pw    CONSTANT TEXT := '$2a$10$LmSvVrFIpIK00B6QLKhF9uj7n.mYazuToeyq6LsET0JtD8qcZMz3S';
    co_a  BIGINT; co_b BIGINT;
    u_dev BIGINT; u_kim BIGINT; u_other BIGINT;
    p1 BIGINT; p2 BIGINT; p3 BIGINT; p4 BIGINT; p5 BIGINT;
    d  CONSTANT DATE := current_date;
BEGIN
    IF EXISTS (SELECT 1 FROM company WHERE name = '(가상) 푸른환경엔지니어링') THEN
        RETURN; -- already seeded
    END IF;

    INSERT INTO company (name) VALUES ('(가상) 푸른환경엔지니어링') RETURNING id INTO co_a;
    INSERT INTO company (name) VALUES ('(가상) 다른건설') RETURNING id INTO co_b;

    UPDATE users SET company_id = co_a WHERE email = 'dev@envpermit.local' RETURNING id INTO u_dev;
    INSERT INTO users (email, password_hash, name, role, company_id)
    VALUES ('kim@envpermit.local', pw, '김담당', 'USER', co_a) ON CONFLICT (email) DO UPDATE SET company_id = co_a
    RETURNING id INTO u_kim;
    INSERT INTO users (email, password_hash, name, role, company_id)
    VALUES ('other@envpermit.local', pw, '타회사', 'USER', co_b) ON CONFLICT (email) DO UPDATE SET company_id = co_b
    RETURNING id INTO u_other;

    INSERT INTO project (company_id, name, facility_type, location, capacity, capacity_unit, start_date, expected_end_date, status)
    VALUES (co_a, '가람시 하수처리장 증설공사', 'SEWAGE', '가람시 서구 새터동 1234', 150, '톤/일', d + 120, d + 780, 'IN_PROGRESS')
    RETURNING id INTO p1;
    INSERT INTO project (company_id, name, facility_type, location, capacity, capacity_unit, start_date, expected_end_date, status)
    VALUES (co_a, '누리시 자원회수시설 신설', 'INCINERATION', '누리시 중구 솔밭동 88', 220, '톤/일', d + 60, d + 900, 'IN_PROGRESS')
    RETURNING id INTO p2;
    INSERT INTO project (company_id, name, facility_type, location, capacity, capacity_unit, start_date, expected_end_date, status)
    VALUES (co_a, '빛고을 통합바이오가스화시설', 'BIOGAS', '빛고을시 남구 달빛동 55-1', 100, '톤/일', d - 400, d - 30, 'COMPLETED')
    RETURNING id INTO p3;
    INSERT INTO project (company_id, name, facility_type, location, capacity, capacity_unit, start_date, expected_end_date, status)
    VALUES (co_a, '한솔산단 폐수처리장', 'WASTEWATER', '한솔시 북구 물빛동 700', 80, '톤/일', d + 200, NULL, 'ON_HOLD')
    RETURNING id INTO p4;
    INSERT INTO project (company_id, name, facility_type, location, capacity, capacity_unit, start_date, expected_end_date, status)
    VALUES (co_b, '(B사) 매립시설 확장', 'LANDFILL', '어딘가군 매립로 1', 300, '톤/일', d + 30, NULL, 'IN_PROGRESS')
    RETURNING id INTO p5;

    INSERT INTO project_member (project_id, user_id, is_admin, can_self_approve, can_bulk_approve) VALUES
        (p1, u_dev, TRUE, TRUE, TRUE), (p2, u_dev, TRUE, TRUE, TRUE), (p3, u_dev, TRUE, TRUE, TRUE),
        (p4, u_dev, TRUE, TRUE, TRUE), (p1, u_kim, FALSE, FALSE, FALSE), (p5, u_other, TRUE, TRUE, TRUE);

    INSERT INTO permit_item (project_id, name, category, regulation, regulation_url, authority, authority_url, reason,
                             approval_date, application_date, acquisition_date, expiry_date, status, next_action, next_action_url) VALUES
        (p1, '폐기물처리시설 설치승인', 'FACILITY', '폐기물관리법 제29조', 'https://www.law.go.kr/법령/폐기물관리법', '○○유역환경청', 'https://www.me.go.kr',
         '시설용량 100톤/일 이상으로 설치승인 대상입니다.', '착공 전', d - 120, d - 90, d + 1600, 'COMPLETED', '추가 조치가 필요하지 않습니다.', 'https://www.me.go.kr'),
        (p1, '건축허가', 'CONSTRUCTION', '건축법 제11조', 'https://www.law.go.kr/법령/건축법', '세움터', 'https://www.eais.go.kr',
         '착공 전에 건축허가를 받아야 합니다.', '착공 30일 전', d + 10, NULL, d + 22, 'IN_PROGRESS', '30일 이내에 세움터에 건축허가를 신청해야 합니다.', 'https://www.eais.go.kr'),
        (p1, '개발행위허가', 'LOCATION', '국토의 계획 및 이용에 관한 법률 제56조', 'https://www.law.go.kr/법령/국토의계획및이용에관한법률', '가람시 서구청', 'https://www.law.go.kr',
         '토지형질변경 면적 5,000㎡ 이상으로 개발행위허가 대상입니다.', '착공 전', d - 150, d - 110, d + 270, 'COMPLETED', '추가 조치가 필요하지 않습니다.', NULL),
        (p1, '환경영향평가 협의', 'ENVIRONMENT', '환경영향평가법 제22조', 'https://www.law.go.kr/법령/환경영향평가법', '○○유역환경청', 'https://www.me.go.kr',
         '처리용량 100톤/일 이상 시설로 환경영향평가 대상사업입니다.', '인허가 신청 전', d - 220, d - 150, NULL, 'COMPLETED', '추가 조치가 필요하지 않습니다.', NULL),
        (p1, '전기사업 공사계획 신고', 'ELECTRICAL', '전기사업법 제61조', 'https://www.law.go.kr/법령/전기사업법', '한국전기안전공사', 'https://www.kesco.or.kr',
         '수전설비 용량 1,000kW 이상으로 공사계획 신고 대상입니다.', '전기공사 착공 전', NULL, NULL, d + 5, 'NOT_APPLIED', '전기공사 착공 전 공사계획을 신고해야 합니다.', 'https://www.kesco.or.kr'),
        (p1, '대기배출시설 설치허가', 'FACILITY', '대기환경보전법 제23조', 'https://www.law.go.kr/법령/대기환경보전법', '○○유역환경청', 'https://www.me.go.kr',
         '특정대기유해물질 배출시설에 해당하여 설치허가가 필요합니다.', '가동 전', d - 5, NULL, d + 50, 'IN_PROGRESS', '배출시설 설치명세서를 제출해야 합니다.', 'https://www.me.go.kr'),
        (p1, '수질오염물질 배출시설 설치허가', 'FACILITY', '물환경보전법 제33조', 'https://www.law.go.kr/법령/물환경보전법', '가람시청', NULL,
         '폐수 배출시설 설치에 따른 허가 대상입니다.', '가동 전', NULL, NULL, d + 73, 'NOT_APPLIED', '배출시설 및 방지시설 설치계획을 제출해야 합니다.', NULL),
        (p1, '소방시설 착공신고', 'CONSTRUCTION', '소방시설공사업법 제13조', 'https://www.law.go.kr/법령/소방시설공사업법', '가람소방서', NULL,
         '연면적 기준 초과로 소방시설 착공신고 대상입니다.', '착공 전', d - 20, NULL, d + 27, 'NEAR_EXPIRY', '소방시설 착공신고서를 제출해야 합니다.', NULL),
        (p2, '폐기물처리시설 설치승인', 'FACILITY', '폐기물관리법 제29조', 'https://www.law.go.kr/법령/폐기물관리법', '○○유역환경청', 'https://www.me.go.kr',
         '소각용량 기준 이상으로 설치승인 대상입니다.', '착공 전', d - 30, NULL, d + 40, 'IN_PROGRESS', '보완서류를 제출해야 합니다.', NULL),
        (p2, '환경영향평가 협의', 'ENVIRONMENT', '환경영향평가법 제22조', 'https://www.law.go.kr/법령/환경영향평가법', '○○유역환경청', 'https://www.me.go.kr',
         '소각시설 환경영향평가 대상사업입니다.', '인허가 신청 전', d - 200, d - 100, NULL, 'COMPLETED', '추가 조치가 필요하지 않습니다.', NULL),
        (p2, '건축허가', 'CONSTRUCTION', '건축법 제11조', 'https://www.law.go.kr/법령/건축법', '세움터', 'https://www.eais.go.kr',
         '착공 전에 건축허가를 받아야 합니다.', '착공 30일 전', NULL, NULL, d + 20, 'NEAR_EXPIRY', '건축허가를 신청해야 합니다.', NULL),
        (p3, '폐기물처리시설 사용개시 신고', 'FACILITY', '폐기물관리법 제29조', 'https://www.law.go.kr/법령/폐기물관리법', '빛고을시청', NULL,
         '시설 준공 후 사용개시 신고 대상입니다.', '가동 전', d - 60, d - 40, NULL, 'COMPLETED', '추가 조치가 필요하지 않습니다.', NULL),
        (p3, '건축물 사용승인', 'CONSTRUCTION', '건축법 제22조', 'https://www.law.go.kr/법령/건축법', '세움터', 'https://www.eais.go.kr',
         '건축물 준공에 따른 사용승인 대상입니다.', '준공 시', d - 70, d - 50, NULL, 'COMPLETED', '추가 조치가 필요하지 않습니다.', NULL),
        (p4, '수질오염물질 배출시설 설치허가', 'FACILITY', '물환경보전법 제33조', 'https://www.law.go.kr/법령/물환경보전법', '한솔시청', NULL,
         '폐수 배출시설 설치에 따른 허가 대상입니다.', '가동 전', NULL, NULL, NULL, 'NOT_APPLIED', '사업 재개 시 신청합니다.', NULL),
        (p4, '공장설립 승인', 'LOCATION', '산업집적활성화 및 공장설립에 관한 법률 제13조', 'https://www.law.go.kr/법령/산업집적활성화및공장설립에관한법률', '한솔시청', NULL,
         '산업단지 내 시설 설립 승인 대상입니다.', '착공 전', d - 300, d - 260, d - 3, 'EXPIRED', '승인 기간 연장을 신청해야 합니다.', NULL),
        (p5, '매립시설 변경승인', 'FACILITY', '폐기물관리법 제29조', 'https://www.law.go.kr/법령/폐기물관리법', '○○유역환경청', NULL,
         'B사 프로젝트 — A사 사용자에게 보이면 안 됩니다.', '착공 전', NULL, NULL, d + 90, 'NOT_APPLIED', NULL, NULL);

    -- creation entries for the status history
    INSERT INTO permit_status_history (permit_item_id, from_status, to_status, changed_by, changed_at)
    SELECT pi.id, NULL, pi.status, CASE WHEN pi.project_id = p5 THEN u_other ELSE u_dev END, now() - interval '30 days'
    FROM permit_item pi;
    -- one realistic progression on project 1 건축허가
    UPDATE permit_status_history SET to_status = 'NOT_APPLIED'
    WHERE permit_item_id = (SELECT id FROM permit_item WHERE project_id = p1 AND name = '건축허가');
    INSERT INTO permit_status_history (permit_item_id, from_status, to_status, changed_by, changed_at)
    SELECT id, 'NOT_APPLIED', 'IN_PROGRESS', u_kim, now() - interval '3 days'
    FROM permit_item WHERE project_id = p1 AND name = '건축허가';
END $$;

-- deadline events for the seeded permits (V20 backfills only permits that existed before it ran)
INSERT INTO deadline_event (kind, target_id, project_id, title, due_date)
SELECT 'PERMIT_EXPIRY', id, project_id, name || ' 만료', expiry_date FROM permit_item WHERE expiry_date IS NOT NULL
ON CONFLICT (kind, target_id) DO NOTHING;
