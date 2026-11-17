-- Local dev only: demo rows for the 의무사항, AI 검토 and 문서 tabs of 가람시 (runs after R__dev_user).
-- Every name and condition text here is fictional. Documents are list rows only: no file bytes in storage.
-- Approved rows mirror what ObligationApplier writes on accept (obligation + first task + deadline).
DO $$
DECLARE
    d   CONSTANT DATE := current_date;
    pid BIGINT; co BIGINT; u_dev BIGINT; em BIGINT;
    eia BIGINT; waste BIGINT;
    doc BIGINT; run BIGINT; s BIGINT; o BIGINT; t BIGINT;
    r   RECORD;
BEGIN
    SELECT id, company_id, em_user_id INTO pid, co, em FROM project WHERE name = '가람시 하수처리장 증설공사';
    IF pid IS NULL OR EXISTS (SELECT 1 FROM document WHERE storage_key LIKE 'dev-seed/%') THEN
        RETURN;
    END IF;
    SELECT id INTO u_dev FROM users WHERE email = 'dev@envpermit.local';
    SELECT id INTO eia FROM permit_item WHERE project_id = pid AND name = '환경영향평가 협의';
    SELECT id INTO waste FROM permit_item WHERE project_id = pid AND name = '폐기물처리시설 설치승인';
    IF eia IS NULL OR waste IS NULL THEN
        RETURN;
    END IF;

    -- 문서: the permit document the conditions came from, plus two project documents
    INSERT INTO document (permit_item_id, project_id, file_name, content_type, size_bytes, sha256, storage_key,
                          uploaded_by, uploaded_at, effective_date)
    VALUES (eia, pid, '(예시) 가람시 하수처리장 환경영향평가 협의의견서.pdf', 'application/pdf', 482113,
            repeat('a1', 32), 'dev-seed/eia-opinion.pdf', u_dev, now() - interval '20 days', d - 21)
    RETURNING id INTO doc;
    INSERT INTO document (permit_item_id, project_id, doc_type, file_name, content_type, size_bytes, sha256, storage_key,
                          uploaded_by, uploaded_at)
    VALUES (NULL, pid, 'MINUTES', '(예시) 착수보고회 회의록.pdf', 'application/pdf', 213004, repeat('b2', 32),
            'dev-seed/kickoff-minutes.pdf', u_dev, now() - interval '12 days'),
           (NULL, pid, 'OFFICIAL_LETTER', '(예시) 가람시 공문 - 설계 협의 요청.pdf', 'application/pdf', 98517,
            repeat('c3', 32), 'dev-seed/official-letter.pdf', u_dev, now() - interval '5 days');

    -- AI 검토: one finished extraction run on that document
    INSERT INTO ai_run (company_id, project_id, kind, input_ref, model, prompt_version, preparer, provider, status,
                        started_by, started_at, finished_at)
    VALUES (co, pid, 'OBLIGATION', jsonb_build_object('document_id', doc, 'sha256', repeat('a1', 32)),
            'claude-sonnet-5-5', 'obligation-v1', 'CLAUDE_PDF_DIRECT', 'CLAUDE', 'SUCCEEDED',
            u_dev, now() - interval '20 days', now() - interval '20 days')
    RETURNING id INTO run;

    -- page_no stays null: with a page the review screen would render the (absent) file
    FOR r IN
        SELECT * FROM (VALUES
            (1, 'ACCEPTED', 'MEASURE_SURVEY', 'ODOR', 'QUARTERLY', '분기 1회', '공사 기간',
             '부지 경계 악취 측정', '공사 기간 중 부지 경계 2개 지점에서 복합악취를 분기 1회 측정하고 결과를 보관할 것.', 25),
            (2, 'ACCEPTED', 'MEASURE_SURVEY', 'NOISE_VIBRATION', 'MONTHLY', '월 1회', '공사 기간',
             '인근 주거지 소음·진동 측정', '공사 기간 중 인근 주거지 1개 지점에서 소음·진동을 월 1회 측정할 것.', 9),
            (3, 'ACCEPTED', 'REPORT_SUBMIT', 'WATER_QUALITY', 'SEMIANNUAL', '반기 1회', '준공 후 3년',
             '방류수 수질 사후환경조사 보고', '준공 후 3년간 방류수 수질 사후환경영향조사 결과를 반기마다 협의기관에 통보할 것.', 60),
            (4, 'PENDING', 'FACILITY_OPERATION', 'AIR_QUALITY', 'OTHER', '공사 기간 상시', '공사 기간',
             '비산먼지 저감시설 운영', '토공 작업 시 살수차를 운영하고 방진막(높이 3m 이상)을 설치할 것.', NULL),
            (5, 'PENDING', 'RECORD_KEEP', 'RESOURCE_CIRCULATION', 'OTHER', '발생 시', '공사 기간',
             '건설폐기물 처리 기록 보존', '건설폐기물 위탁 처리 실적을 기록하고 3년간 보존할 것.', NULL),
            (6, 'PENDING', 'MEASURE_SURVEY', 'FLORA_FAUNA', 'ANNUAL', '연 1회', '준공 후 2년',
             '법정보호종 서식 모니터링', '사업지 주변 하천에서 법정보호종 서식 여부를 연 1회 조사할 것.', NULL)
        ) AS x(no, status, type, category, frequency, frequency_text, period_text, summary, requirement, due_in)
    LOOP
        INSERT INTO ai_suggestion (company_id, project_id, run_id, kind, target_parent_id, origin, payload, status,
                                   reviewed_by, reviewed_at)
        VALUES (co, pid, run, 'OBLIGATION', eia, 'AI', jsonb_build_object(
                    'citation_raw', '협의의견 ' || r.no, 'citation_key', r.no::text, 'citation_synthetic', false,
                    'requirement_text', r.requirement, 'summary', r.summary, 'type', r.type, 'category', r.category,
                    'responsible_party', '사업자', 'frequency', r.frequency, 'frequency_text', r.frequency_text,
                    'period_text', r.period_text, 'legal_basis', '[]'::jsonb, 'page_no', NULL,
                    'source_quote', NULL, 'quote_check', NULL),
                r.status,
                CASE WHEN r.status = 'ACCEPTED' THEN u_dev END,
                CASE WHEN r.status = 'ACCEPTED' THEN now() - interval '18 days' END)
        RETURNING id INTO s;
        INSERT INTO ai_suggestion_event (suggestion_id, actor_id, at, type)
        VALUES (s, NULL, now() - interval '20 days', 'CREATED');
        CONTINUE WHEN r.status <> 'ACCEPTED';

        -- 의무사항: the live obligation the approval made
        INSERT INTO permit_obligation (permit_item_id, source_document_id, suggestion_id, citation_raw, citation_key,
                                       requirement_text, summary, type, category, responsible_party, frequency,
                                       frequency_text, period_text, first_due_date, next_due_date, status,
                                       default_assignee_id)
        VALUES (eia, doc, s, '협의의견 ' || r.no, r.no::text, r.requirement, r.summary, r.type, r.category, '사업자',
                r.frequency, r.frequency_text, r.period_text, d + r.due_in, d + r.due_in, 'ACTIVE', em)
        RETURNING id INTO o;
        UPDATE ai_suggestion SET applied_entity_id = o WHERE id = s;
        INSERT INTO ai_suggestion_event (suggestion_id, actor_id, at, type, data)
        VALUES (s, u_dev, now() - interval '18 days', 'ACCEPTED',
                jsonb_build_object('first_due_date', (d + r.due_in)::text, 'bulk', false, 'confirm_absent', false));

        INSERT INTO obligation_task (project_id, kind, obligation_id, title, due_date, assignee_id, status)
        VALUES (pid, 'OBLIGATION', o, r.summary, d + r.due_in, em, 'OPEN') RETURNING id INTO t;
        INSERT INTO task_event (task_id, actor_id, at, type, data)
        VALUES (t, u_dev, now() - interval '18 days', 'CREATED', '{"source": "ACCEPT"}');
        INSERT INTO deadline_event (kind, target_id, project_id, title, due_date, assignee_id)
        VALUES ('OBLIGATION', o, pid, '환경영향평가 협의 · ' || r.summary, d + r.due_in, em);
    END LOOP;
END $$;
