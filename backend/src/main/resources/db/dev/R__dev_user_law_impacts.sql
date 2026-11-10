-- Local dev only: sample law amendments + impacts for the /laws screen (runs after R__dev_user).
-- MST 99000x and every text here are fictional, not real legal changes.
DO $$
DECLARE
    d  CONSTANT DATE := current_date;
    a1 BIGINT; a2 BIGINT; a3 BIGINT;
    i  BIGINT;
    r  RECORD;
BEGIN
    IF EXISTS (SELECT 1 FROM law_amendment WHERE mst = 990001)
       OR NOT EXISTS (SELECT 1 FROM project WHERE name = '누리시 자원회수시설 신설') THEN
        RETURN;
    END IF;

    -- links (made before the amendments)
    INSERT INTO permit_law_link (permit_item_id, law_catalog_id, article_no, source, created_at)
    SELECT p.id, c.id, x.article_no, 'MANUAL', now() - interval '30 days'
    FROM (VALUES ('누리시 자원회수시설 신설', '폐기물처리시설 설치승인', '001771', NULL),
                 ('가람시 하수처리장 증설공사', '건축허가', '001823', '제11조'),
                 ('가람시 하수처리장 증설공사', '환경영향평가 협의', '002016', '제22조'))
         AS x(project, permit, law_id, article_no)
    JOIN project pr ON pr.name = x.project JOIN permit_item p ON p.project_id = pr.id AND p.name = x.permit
    JOIN law_catalog c ON c.law_id = x.law_id
    ON CONFLICT DO NOTHING;

    INSERT INTO law_amendment (law_catalog_id, mst, promulgated_on, enforced_on, revision_type, reason, old_new_raw, detected_on)
    SELECT id, 990001, d - 3, d + 110, '일부개정', '(예시) 소각시설 운전 기록의 보존 의무를 명확히 하려는 것',
           '{"OldAndNewService":{"구조문목록":{"조문":[{"no":"1","content":"제10조(예시) 연소실 출구 온도는 섭씨 850도 이상을 유지할 것"}]},"신조문목록":{"조문":[{"no":"1","content":"제10조(예시) 연소실 출구 온도는 섭씨 850도 이상을 유지하고, <P>온도 측정 기록을 3년간 보존할 것</P>"}]}}}',
           d - 3
    FROM law_catalog WHERE law_id = '008567' RETURNING id INTO a1;
    INSERT INTO law_amendment_article (amendment_id, article_no, jo_code, change_reason, article_enforced_on)
    VALUES (a1, '제10조', '001000', '개정', d + 110);

    INSERT INTO law_amendment (law_catalog_id, mst, promulgated_on, enforced_on, revision_type, reason, old_new_raw, detected_on)
    SELECT id, 990002, d - 10, d + 150, '일부개정', '(예시) 구조 안전 확인 대상 정비', NULL, d - 10
    FROM law_catalog WHERE law_id = '001823' RETURNING id INTO a2;
    INSERT INTO law_amendment_article (amendment_id, article_no, jo_code, change_reason, article_enforced_on)
    VALUES (a2, '제48조', '004800', '개정', d + 150);

    INSERT INTO law_amendment (law_catalog_id, mst, promulgated_on, enforced_on, revision_type, reason, old_new_raw, detected_on)
    SELECT id, 990003, d - 1, d + 20, '일부개정', '(예시) 협의 내용 관리의 전자화', NULL, d - 1
    FROM law_catalog WHERE law_id = '009169' RETURNING id INTO a3;
    INSERT INTO law_amendment_article (amendment_id, article_no, jo_code, change_reason, article_enforced_on)
    VALUES (a3, '제5조', '000500', '개정', d + 20);

    FOR r IN
        SELECT x.amendment_id, x.level, p.id AS permit_id, p.project_id, pr.name AS project, p.name AS permit,
               c.name AS law, a.enforced_on
        FROM (VALUES (a1, 'DIRECT', '누리시 자원회수시설 신설', '폐기물처리시설 설치승인'),
                     (a2, 'REFERENCE', '가람시 하수처리장 증설공사', '건축허가'),
                     (a3, 'DIRECT', '가람시 하수처리장 증설공사', '환경영향평가 협의'))
             AS x(amendment_id, level, project, permit)
        JOIN project pr ON pr.name = x.project JOIN permit_item p ON p.project_id = pr.id AND p.name = x.permit
        JOIN law_amendment a ON a.id = x.amendment_id JOIN law_catalog c ON c.id = a.law_catalog_id
    LOOP
        INSERT INTO permit_law_impact (amendment_id, permit_item_id, level, created_at, emailed_at)
        VALUES (r.amendment_id, r.permit_id, r.level, now(), now()) RETURNING id INTO i;
        INSERT INTO deadline_event (kind, target_id, project_id, title, due_date)
        VALUES ('LAW_ENFORCEMENT', i, r.project_id, '[법령 시행] ' || r.law || ' — ' || r.project || ' / ' || r.permit,
                r.enforced_on);
    END LOOP;
END $$;
