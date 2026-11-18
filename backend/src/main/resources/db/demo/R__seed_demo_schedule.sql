-- Public demo only ("demo" profile, deploy/oracle): design schedules and meetings for the fictional dev seed projects,
-- so the read-only demo login sees full 공정표/캘린더 screens. Runs after db/dev: Flyway orders repeatables by name, and "seed" sorts after "dev".
-- Every name here is fictional. Dates are relative to the seed day; the server's weekly reset re-seeds them.
-- Rows mirror what ScheduleController/EventController write: template items, CREATED history, deadline_event.
DO $$
DECLARE
    d     CONSTANT DATE := current_date;
    co    BIGINT := (SELECT id FROM company WHERE name = '(가상) 푸른환경엔지니어링');
    u_dev BIGINT := (SELECT id FROM users WHERE email = 'dev@envpermit.local');
    p     RECORD;
    t     RECORD;
    base  DATE;
    s     DATE;
    e     DATE;
    item  BIGINT;
BEGIN
    IF co IS NULL OR u_dev IS NULL
       OR EXISTS (SELECT 1 FROM schedule_item si JOIN project pr ON pr.id = si.project_id WHERE pr.company_id = co) THEN
        RETURN; -- no dev seed, or already seeded
    END IF;

    -- 한솔 has no end date in the dev seed; the 용역 template needs one (완료일 anchor)
    UPDATE project SET expected_end_date = d + 240 WHERE company_id = co AND name = '한솔산단 폐수처리장' AND expected_end_date IS NULL;

    FOR p IN
        SELECT pr.id, pr.pm_user_id, pr.start_date, pr.expected_end_date, x.template, x.anchor, x.anchor_date, x.baseline
        FROM (VALUES ('가람시 하수처리장 증설공사', 'TURNKEY_BID', 'SUBMISSION', d + 58, TRUE),
                     ('누리시 자원회수시설 신설', 'TURNKEY_BID', 'SUBMISSION', d + 120, FALSE),
                     ('새벽시 매립가스 자원화시설', 'TURNKEY_BID', 'SUBMISSION', d + 25, TRUE),
                     ('빛고을 통합바이오가스화시설', 'TURNKEY_BID', 'SUBMISSION', d - 420, TRUE),
                     ('한솔산단 폐수처리장', 'SERVICE', 'CONTRACT', d - 60, FALSE))
             AS x(project, template, anchor, anchor_date, baseline)
        JOIN project pr ON pr.company_id = co AND pr.name = x.project
    LOOP
        INSERT INTO project_schedule_anchor (project_id, anchor, date) VALUES (p.id, p.anchor, p.anchor_date)
        ON CONFLICT (project_id, anchor) DO UPDATE SET date = EXCLUDED.date;
        UPDATE project SET schedule_base = CASE WHEN p.anchor = 'SUBMISSION' THEN 'SUBMISSION' ELSE 'KICKOFF' END,
                           schedule_baseline_no = CASE WHEN p.baseline THEN 1 ELSE 0 END
        WHERE project.id = p.id;

        FOR t IN
            SELECT ti.*, dc.id AS discipline_id
            FROM schedule_template_item ti
            JOIN schedule_template st ON st.id = ti.template_id AND st.company_id = co AND st.code = p.template
            LEFT JOIN discipline dc ON dc.company_id = co AND dc.code = ti.discipline_code
            ORDER BY ti.sort_order
        LOOP
            base := CASE t.anchor WHEN 'COMPLETION' THEN p.expected_end_date WHEN 'KICKOFF' THEN p.start_date
                                  ELSE p.anchor_date END;
            s := CASE WHEN t.offset_months IS NOT NULL THEN (base + make_interval(months => t.offset_months))::date
                      ELSE base + t.offset_days END;
            e := s + COALESCE(t.duration_days, 1) - 1;

            INSERT INTO schedule_item (project_id, discipline_id, kind, title, start_date, end_date, anchor, offset_days,
                                       offset_months, duration_days, owner_user_id, submission,
                                       progress, actual_start, actual_end, baseline_start, baseline_end)
            VALUES (p.id, t.discipline_id, t.kind, t.title, s, e, t.anchor, t.offset_days, t.offset_months,
                    t.duration_days, CASE WHEN t.kind = 'TASK' THEN p.pm_user_id END, t.submission,
                    CASE WHEN e < d THEN 100 WHEN s >= d THEN 0 ELSE (100 * (d - s) / (e - s + 1)) END,
                    CASE WHEN s < d THEN s END, CASE WHEN e < d THEN e END,
                    CASE WHEN p.baseline THEN s END, CASE WHEN p.baseline THEN e END)
            RETURNING schedule_item.id INTO item;
            INSERT INTO schedule_item_event (item_id, actor_id, at, type, data)
            VALUES (item, u_dev, now() - interval '30 days', 'CREATED',
                    jsonb_build_object('title', t.title, 'start', s, 'end', e, 'anchor', t.anchor)
                        || jsonb_strip_nulls(jsonb_build_object('offsetDays', t.offset_days, 'offsetMonths', t.offset_months)));
        END LOOP;
    END LOOP;

    -- 가람시: one late milestone (지연) and one task slipped past its baseline (기준 대비 연기)
    UPDATE schedule_item si SET actual_start = NULL, actual_end = NULL, progress = 0
    FROM project pr WHERE pr.id = si.project_id AND pr.name = '가람시 하수처리장 증설공사' AND si.title = '인허가 사전 협의';
    UPDATE schedule_item si SET end_date = end_date + 7, duration_days = duration_days + 7,
                                delay_reason = '(예시) 계측제어 사양 협의 지연'
    FROM project pr WHERE pr.id = si.project_id AND pr.name = '가람시 하수처리장 증설공사' AND si.title = '전기·계측제어 기본설계';

    -- open items are reminders (ScheduleController registers end date + owner)
    INSERT INTO deadline_event (kind, target_id, project_id, title, due_date, assignee_id)
    SELECT 'SCHEDULE_ITEM', si.id, si.project_id, si.title, si.end_date, si.owner_user_id
    FROM schedule_item si JOIN project pr ON pr.id = si.project_id
    WHERE pr.company_id = co AND si.actual_end IS NULL
    ON CONFLICT (kind, target_id) DO NOTHING;

    -- calendar: meetings, reports and a site visit around today
    INSERT INTO project_event (project_id, kind, subkind, title, starts_at, ends_at, all_day, location, counterpart_org, purpose)
    SELECT pr.id, x.kind, x.subkind, x.title, (d + x.day) + x.at, (d + x.day) + x.at + x.len, FALSE, x.location, x.org, x.purpose
    FROM (VALUES ('가람시 하수처리장 증설공사', 'MEETING', '공정회의', '(예시) 주간 공정회의', 2, time '10:00', interval '1 hour',
                  '본사 3층 회의실', '(가상) 협력설계사무소', '분야별 진행률과 지연 항목 점검'),
                 ('가람시 하수처리장 증설공사', 'TRIP', NULL, '(예시) 인허가 사전 협의 방문', 6, time '14:00', interval '3 hours',
                  '가람시청', '가람시청 환경과', '설치승인 보완 사항 협의'),
                 ('누리시 자원회수시설 신설', 'CLIENT_REPORT', '중간', '(예시) 발주처 중간보고', 12, time '15:00', interval '2 hours',
                  '누리시청 대회의실', '누리시 자원순환과', '배치 계획과 공정 계획 보고'),
                 ('새벽시 매립가스 자원화시설', 'REVIEW', NULL, '(예시) 설계도서 내부 검토회', 9, time '09:30', interval '4 hours',
                  '본사 대회의실', NULL, '입찰 설계도서 최종 검토'),
                 ('한솔산단 폐수처리장', 'MEETING', '분야 미팅', '(예시) 수처리 공정 분야 미팅', -3, time '11:00', interval '1 hour',
                  '화상회의', '(가상) 한솔산단 관리공단', '사업 재개 일정 확인'))
         AS x(project, kind, subkind, title, day, at, len, location, org, purpose)
    JOIN project pr ON pr.company_id = co AND pr.name = x.project;

    INSERT INTO deadline_event (kind, target_id, project_id, title, due_date)
    SELECT 'PROJECT_EVENT', pe.id, pe.project_id, pe.title, pe.starts_at::date
    FROM project_event pe JOIN project pr ON pr.id = pe.project_id
    WHERE pr.company_id = co
    ON CONFLICT (kind, target_id) DO NOTHING;
END $$;

-- On a fresh DB R__dev_user_demo runs before R__dev_user_teams creates 새벽시, so join the demo login to it here.
INSERT INTO project_member (project_id, user_id)
SELECT m.project_id, demo.id
FROM project_member m JOIN users dev ON dev.id = m.user_id AND dev.email = 'dev@envpermit.local'
JOIN users demo ON demo.email = 'demo@envpermit.local' AND demo.disabled_at IS NULL
ON CONFLICT DO NOTHING;
