-- 프로젝트 폼의 "착공예정일"은 그동안 project.start_date(일정의 착수일 KICKOFF)에 저장됐다.
-- 착공예정일은 일정 기준일 CONSTRUCTION_START로 따로 두므로, 기존 값을 그대로 복사한다(이미 있으면 둔다).
-- start_date는 지우지 않는다: 지금 일정 항목들이 이 날짜로 계산돼 있어서, 실제 착수일로 고칠 때까지 그대로 둔다.
INSERT INTO project_schedule_anchor (project_id, anchor, date)
SELECT id, 'CONSTRUCTION_START', start_date
FROM project
WHERE start_date IS NOT NULL
ON CONFLICT (project_id, anchor) DO NOTHING;
