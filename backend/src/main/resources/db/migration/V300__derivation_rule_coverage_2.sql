-- Rule set v3: 14 more rules (permit-derivation coverage batch 2).
-- Every threshold and article was read from the law text on 국가법령정보센터 (law.go.kr) on 2026-11-11;
-- source_ref names the law + article. Nothing here comes from a real project's documents.
--   no new input: 소방시설공사 착공신고·완공검사, 가설건축물, 도시·군계획시설 결정·실시계획, 정보통신공사, TMS
--   new inputs (sells_electricity, receiving_capacity_kw, has_elevator): 발전사업허가, 전기설비 3건, 승강기

INSERT INTO law_catalog (law_id, name, kind, short_name, last_mst, last_promulgated_on, last_enforced_on, source) VALUES
    ('009500', '소방시설공사업법', 'LAW', NULL, 259473, '2024-01-30', '2025-01-31', 'SEED'),
    ('009695', '소방시설공사업법 시행령', 'DECREE', NULL, 287253, '2026-06-23', '2026-07-01', 'SEED'),
    ('001458', '승강기 안전관리법', 'LAW', NULL, 259475, '2024-01-30', '2025-01-31', 'SEED'),
    ('009294', '국토의 계획 및 이용에 관한 법률', 'LAW', '국토계획법', 284013, '2026-03-05', '2026-07-01', 'SEED'),
    ('009419', '국토의 계획 및 이용에 관한 법률 시행령', 'DECREE', '국토계획법 시행령', 288857, '2026-08-18', '2026-09-18', 'SEED'),
    ('009469', '국토의 계획 및 이용에 관한 법률 시행규칙', 'RULE', '국토계획법 시행규칙', 289249, '2026-08-31', '2026-09-18', 'SEED'),
    ('001445', '정보통신공사업법', 'LAW', NULL, 285469, '2026-04-21', '2026-10-22', 'SEED'),
    ('004704', '정보통신공사업법 시행령', 'DECREE', NULL, 291429, '2026-10-21', '2026-10-22', 'SEED'),
    ('001854', '전기사업법', 'LAW', NULL, 284383, '2026-03-17', '2026-09-18', 'SEED'),
    ('008038', '전기사업법 시행규칙', 'RULE', NULL, 291405, '2026-10-16', '2026-10-16', 'SEED'),
    ('013718', '전기안전관리법', 'LAW', NULL, 284385, '2026-03-17', '2026-09-18', 'SEED'),
    ('014058', '전기안전관리법 시행규칙', 'RULE', NULL, 289265, '2026-08-31', '2026-09-18', 'SEED'),
    ('010302', '환경분야 시험ㆍ검사 등에 관한 법률', 'LAW', '환경시험검사법', 284109, '2026-03-05', '2026-07-01', 'SEED')
ON CONFLICT (law_id) DO NOTHING;

UPDATE law_catalog c SET parent_id = p.id
FROM law_catalog p
WHERE c.parent_id IS NULL AND p.kind = 'LAW' AND c.name IN (p.name || ' 시행령', p.name || ' 시행규칙');

CREATE OR REPLACE FUNCTION pg_temp.ref(lid TEXT, art TEXT) RETURNS JSONB AS $$
    SELECT jsonb_build_object('law_catalog_id', (SELECT id FROM law_catalog WHERE law_id = lid), 'article_no', art)
$$ LANGUAGE sql;

INSERT INTO derivation_rule (rule_key, version, status, facility_types, project_kinds, permit_name, field, category,
                             condition, criterion_text, law_refs, legal_basis_text, approval_stage, required_by, note,
                             source_ref, source_quote, activated_in_ruleset)
VALUES
-- 소방시설공사: 연면적 400㎡ 이상이면 비상경보설비 설치 의무 → 그 신설 공사가 착공신고 대상
('FIRE_WORK_START_REPORT', 1, 'ACTIVE', '{}', '{}', '소방시설공사 착공신고', '소방', 'FACILITY',
 '{"field":"gross_floor_area_m2","op":">=","value":400}',
 '연면적 400㎡ 이상 건축물(비상경보설비 설치 의무)에 소방시설을 신설하는 공사',
 jsonb_build_array(pg_temp.ref('009500', '제13조'), pg_temp.ref('009695', '제4조'), pg_temp.ref('009694', '별표4')),
 '소방시설공사업법 §13 ①, 시행령 §4 1호; 소방시설법 시행령 별표4', 'CONSTRUCTION',
 '{"gate":"OTHER","offset_days":0,"text":"소방시설공사 착공 전"}',
 '연면적만 본다. 지하층·무창층 150㎡ 이상 등 다른 설치 의무 기준은 판정하지 않는다. 불연재료 공장·창고 등 비상경보설비 제외 대상은 확인 필요. 공사업자가 신고.',
 '소방시설공사업법 제13조, 시행령 제4조; 소방시설 설치 및 관리에 관한 법률 시행령 별표4 (law.go.kr)',
 '특정소방대상물에 다음 각 목의 어느 하나에 해당하는 설비를 신설하는 공사 … 비상경보설비, 자동화재탐지설비 … / 연면적 400㎡ 이상인 것은 모든 층', 3),

('FIRE_WORK_COMPLETION', 1, 'ACTIVE', '{}', '{}', '소방시설공사 완공검사', '소방', 'FACILITY',
 '{"field":"gross_floor_area_m2","op":">=","value":400}',
 '연면적 400㎡ 이상 건축물(비상경보설비 설치 의무)의 소방시설공사를 완공한 때',
 jsonb_build_array(pg_temp.ref('009500', '제14조'), pg_temp.ref('009695', '제5조')),
 '소방시설공사업법 §14 ①·②', 'COMMISSIONING',
 '{"gate":"OTHER","offset_days":0,"text":"소방시설공사 완공 시"}',
 '감리자가 지정된 공사는 감리 결과보고서로 갈음(연면적 1만㎡ 이상 등은 현장확인, 시행령 §5). 일부만 먼저 쓰려면 부분완공검사.',
 '소방시설공사업법 제14조, 시행령 제5조 (law.go.kr)',
 '공사업자는 소방시설공사를 완공하면 소방본부장 또는 소방서장의 완공검사를 받아야 한다.', 3),

('TEMP_BUILDING_REPORT', 1, 'ACTIVE', '{}', '{}', '가설건축물 축조신고', '건축', 'GENERAL',
 NULL, '공사용 가설건축물(현장사무소 등)을 축조하는 경우 — 건설공사에 통상 해당',
 jsonb_build_array(pg_temp.ref('001823', '제20조'), pg_temp.ref('002118', '제15조')),
 '건축법 §20 ③, 시행령 §15 ⑤ 3호', 'CONSTRUCTION',
 '{"gate":"OTHER","offset_days":0,"text":"가설건축물 착공 전"}',
 '공사용 가설건축물을 두지 않으면 해당 없음 — 입력으로 판정하지 않고 항상 낸다. 도시·군계획시설 부지의 그 밖의 가설건축물은 허가 대상(§20 ①).',
 '건축법 제20조제3항, 시행령 제15조제5항제3호 (law.go.kr)',
 '공사용 가설건축물 등 대통령령으로 정하는 용도의 가설건축물을 축조하려는 자는 … 신고한 후 착공하여야 한다. / 공사에 필요한 규모의 공사용 가설건축물 및 공작물', 3),

-- 국토계획법: 도시지역에서 폐기물처리시설(재활용시설 제외)은 도시·군관리계획 결정 없이 설치할 수 없다
('URBAN_FACILITY_DECISION', 1, 'ACTIVE', '{INCINERATION}', '{}', '도시·군계획시설 결정', '공통', 'SITE',
 '{"all":[{"field":"zoning","op":"==","value":"URBAN"},{"not":{"field":"urban_facility_decided","op":"is_true"}}]}',
 '도시지역에 설치하고 아직 도시·군계획시설로 결정되지 않은 폐기물처리시설',
 jsonb_build_array(pg_temp.ref('009294', '제43조'), pg_temp.ref('009419', '제35조'), pg_temp.ref('009469', '제6조')),
 '국토계획법 §43 ①, 시행령 §35 ①, 시행규칙 §6 ①', 'PLANNING',
 '{"gate":"OTHER","offset_days":0,"text":"실시계획 인가 전"}',
 '도시지역 밖은 결정 없이 설치할 수 있는 시설 범위가 다르다(시행령 §35 ① 2호) — 판정하지 않는다. 발전시설 등 함께 설치하는 다른 기반시설의 결정은 따로 확인.',
 '국토의 계획 및 이용에 관한 법률 제43조, 시행령 제35조, 시행규칙 제6조 (law.go.kr)',
 '기반시설을 설치하려면 그 시설의 종류ㆍ명칭ㆍ위치ㆍ규모 등을 미리 도시ㆍ군관리계획으로 결정하여야 한다. / (결정 없이 설치할 수 있는 시설) 폐기물처리 및 재활용시설 중 재활용시설', 3),

('URBAN_FACILITY_IMPL_PLAN', 1, 'ACTIVE', '{INCINERATION}', '{}', '도시·군계획시설사업 실시계획 인가', '공통', 'SITE',
 '{"any":[{"field":"urban_facility_decided","op":"is_true"},{"field":"zoning","op":"==","value":"URBAN"}]}',
 '도시·군계획시설로 결정된(또는 도시지역이라 결정해야 하는) 시설을 설치하는 사업',
 jsonb_build_array(pg_temp.ref('009294', '제88조')),
 '국토계획법 §88 ①·②', 'DESIGN',
 '{"gate":"CONSTRUCTION_START","offset_days":0,"text":"착공 전(건축허가 전)"}',
 '시행자가 국토교통부장관·시도지사·대도시 시장이면 인가 없이 작성·고시.',
 '국토의 계획 및 이용에 관한 법률 제88조 (law.go.kr)',
 '도시ㆍ군계획시설사업의 시행자는 … 실시계획을 작성하면 … 인가를 받아야 한다.', 3),

-- 정보통신공사업법: 건축허가 대상 건축물의 구내통신선로설비 등 공사
('TELECOM_DESIGN_CHECK', 1, 'ACTIVE', '{}', '{}', '정보통신공사 착공 전 설계도 확인', '통신', 'FACILITY',
 '{"field":"gross_floor_area_m2","op":">","value":150}',
 '연면적 150㎡를 넘는 건축허가 대상 건축물의 구내통신선로·이동통신구내선로·방송공동수신설비 공사',
 jsonb_build_array(pg_temp.ref('001445', '제36조'), pg_temp.ref('004704', '제35조')),
 '정보통신공사업법 §36 ①, 시행령 §35 ①', 'DESIGN',
 '{"gate":"OTHER","offset_days":0,"text":"정보통신공사 착공 전"}',
 '건축신고(건축법 §14) 대상 건축물은 제외.',
 '정보통신공사업법 제36조, 시행령 제35조 (law.go.kr)',
 '해당 공사를 시작하기 전에 설계도를 … 제출하여 … 기술기준에 적합한지를 확인받아야 하며 / (제외) 연면적 150제곱미터 이하인 건축물에 설치되는 공사', 3),

('TELECOM_PRE_USE_INSPECTION', 1, 'ACTIVE', '{}', '{}', '정보통신공사 사용전검사', '통신', 'FACILITY',
 '{"field":"gross_floor_area_m2","op":">","value":150}',
 '연면적 150㎡를 넘는 건축허가 대상 건축물의 구내통신선로·이동통신구내선로·방송공동수신설비 공사를 끝낸 때',
 jsonb_build_array(pg_temp.ref('001445', '제36조'), pg_temp.ref('004704', '제35조')),
 '정보통신공사업법 §36 ①, 시행령 §35 ①', 'COMMISSIONING',
 '{"gate":"OPERATION_START","offset_days":0,"text":"설비 사용 전"}',
 '감리를 실시한 공사는 사용전검사 대신 감리결과보고서 사본 제출(시행령 §35 ① 2호·②).',
 '정보통신공사업법 제36조, 시행령 제35조 (law.go.kr)',
 '그 공사를 끝냈을 때에는 … 사용전검사를 받고 정보통신설비를 사용하여야 한다.', 3),

-- 대기환경보전법: 1~3종 사업장(연 10톤 이상)의 소각시설
('TMS_INSTALL', 1, 'ACTIVE', '{INCINERATION}', '{}', '굴뚝 자동측정기기(TMS) 부착', '환경', 'FACILITY',
 '{"all":[{"field":"air_emission_tpy","op":">=","value":10},{"any":[{"field":"capacity_tpd","op":">=","value":24},{"all":[{"field":"waste_types","op":"contains_any","value":["INDUSTRIAL","DESIGNATED"]},{"field":"capacity_tpd","op":">=","value":9.6}]}]}]}',
 '대기오염물질 발생량 연 10톤 이상(1~3종 사업장)이고 소각용량 시간당 1톤 이상(사업장폐기물은 0.4톤 이상)인 연속식 소각시설',
 jsonb_build_array(pg_temp.ref('001773', '제32조'), pg_temp.ref('003302', '제17조'), pg_temp.ref('003302', '별표3')),
 '대기환경보전법 §32 ①, 시행령 §17 ⑤ 별표1의3·별표3', 'COMMISSIONING',
 '{"gate":"COMMISSIONING_START","offset_days":0,"text":"가동개시 신고 전"}',
 '시간당 용량은 1일 용량을 24시간으로 나눈 값으로 본다(연속식 가정: 1톤/h = 24톤/일, 0.4톤/h = 9.6톤/일). 방지시설 설치 면제 등 부착 면제 사유(별표3 2호)는 판정하지 않는다.',
 '대기환경보전법 제32조, 시행령 제17조·별표1의3·별표3 (law.go.kr)',
 '굴뚝 자동측정기기를 부착하여야 하는 사업장은 별표 1의3에 따른 1종부터 3종까지의 사업장 / 소각용량이 시간당 1톤 이상인 연속식 또는 준연속식 생활폐기물 소각시설', 3),

('TMS_ACCURACY_CHECK', 1, 'ACTIVE', '{INCINERATION}', '{}', '환경측정기기 정도검사', '환경', 'FACILITY',
 '{"all":[{"field":"air_emission_tpy","op":">=","value":10},{"any":[{"field":"capacity_tpd","op":">=","value":24},{"all":[{"field":"waste_types","op":"contains_any","value":["INDUSTRIAL","DESIGNATED"]},{"field":"capacity_tpd","op":">=","value":9.6}]}]}]}',
 '굴뚝 자동측정기기 부착 대상 시설(형식승인 측정기기를 사용)',
 jsonb_build_array(pg_temp.ref('010302', '제11조')),
 '환경분야 시험·검사 등에 관한 법률 §11 ①', 'COMMISSIONING',
 '{"gate":"OTHER","offset_days":0,"text":"측정기기 사용 전, 이후 주기적으로"}',
 '판정 기준은 TMS 부착 규칙과 같다.',
 '환경분야 시험ㆍ검사 등에 관한 법률 제11조 (law.go.kr)',
 '형식승인을 받았거나 수입신고를 한 측정기기 … 를 사용하거나 사용하려는 자는 … 정도검사(精度檢査)를 받아야 한다.', 3),

-- 전기: 새 입력 sells_electricity, receiving_capacity_kw
('POWER_BUSINESS_PERMIT', 1, 'ACTIVE', '{}', '{}', '발전사업허가', '전기', 'FACILITY',
 '{"all":[{"field":"has_power_generation","op":"is_true"},{"field":"sells_electricity","op":"is_true"}]}',
 '발전설비로 생산한 전기를 전력시장을 통해 판매',
 jsonb_build_array(pg_temp.ref('001854', '제7조')),
 '전기사업법 §7 ①, §2 3호', 'DESIGN',
 '{"gate":"OTHER","offset_days":0,"text":"발전설비 공사계획 인가·신고 전"}',
 '생산 전기를 시설 안에서만 쓰면(자가용) 허가 대상이 아니다.',
 '전기사업법 제7조·제2조제3호 (law.go.kr)',
 '전기사업을 하려는 자는 … 허가를 받아야 한다. / “발전사업”이란 전기를 생산하여 이를 전력시장을 통하여 전기판매사업자에게 공급하는 것을 주된 목적으로 하는 사업', 3),

('ELEC_WORK_PLAN', 1, 'ACTIVE', '{}', '{}', '전기설비 공사계획 인가·신고', '전기', 'FACILITY',
 '{"any":[{"field":"has_power_generation","op":"is_true"},{"field":"receiving_capacity_kw","op":">=","value":75}]}',
 '발전설비를 설치하거나 수전 용량 75kW 이상(자가용전기설비)인 전기설비의 설치공사',
 jsonb_build_array(pg_temp.ref('013718', '제8조'), pg_temp.ref('014058', '별표1'), pg_temp.ref('001854', '제61조'), pg_temp.ref('008038', '제3조')),
 '전기안전관리법 §8 ①·②, 시행규칙 별표1; 전기사업법 §61, 시행규칙 §3', 'CONSTRUCTION',
 '{"gate":"OTHER","offset_days":0,"text":"전기설비 공사 착수 전"}',
 '인가: 출력 1만kW 이상 발전설비, 수전전압 20만V 이상. 그 미만은 신고. 전력을 판매하는 발전설비는 전기사업법 §61 적용. 고압 이상으로 수전하면 75kW 미만이어도 자가용전기설비다(판정하지 않음).',
 '전기안전관리법 제8조, 시행규칙 별표1; 전기사업법 제61조, 시행규칙 제3조 (law.go.kr)',
 '저압에 해당하는 용량 75킬로와트 … 미만의 전력을 타인으로부터 수전하여 … 사용하기 위한 전기설비(일반용) / “자가용전기설비”란 전기사업용전기설비 및 일반용전기설비 외의 전기설비', 3),

('ELEC_PRE_USE_INSPECTION', 1, 'ACTIVE', '{}', '{}', '전기설비 사용전검사', '전기', 'FACILITY',
 '{"any":[{"field":"has_power_generation","op":"is_true"},{"field":"receiving_capacity_kw","op":">=","value":75}]}',
 '공사계획 인가·신고를 한 전기설비(발전설비, 수전 용량 75kW 이상)의 설치공사를 한 때',
 jsonb_build_array(pg_temp.ref('013718', '제9조'), pg_temp.ref('001854', '제63조')),
 '전기안전관리법 §9; 전기사업법 §63', 'COMMISSIONING',
 '{"gate":"OTHER","offset_days":0,"text":"전기설비 사용 전(수전 전)"}',
 '발전설비(증기터빈 등)는 공정별로 나누어 검사한다.',
 '전기안전관리법 제9조; 전기사업법 제63조 (law.go.kr)',
 '자가용전기설비의 설치공사 또는 변경공사를 한 자는 … 검사에 합격한 후에 이를 사용하여야 한다.', 3),

('ELEC_SAFETY_MANAGER', 1, 'ACTIVE', '{}', '{}', '전기안전관리자 선임', '전기', 'FACILITY',
 '{"any":[{"field":"has_power_generation","op":"is_true"},{"field":"receiving_capacity_kw","op":">=","value":75}]}',
 '전기사업자 또는 자가용전기설비(발전설비, 수전 용량 75kW 이상)의 소유자·점유자',
 jsonb_build_array(pg_temp.ref('013718', '제22조')),
 '전기안전관리법 §22 ①', 'COMMISSIONING',
 '{"gate":"OTHER","offset_days":0,"text":"사용전검사 신청 전"}',
 '안전관리업무 위탁·대행이 가능한 규모는 부령이 정한다(§22 ②·③) — 판정하지 않는다.',
 '전기안전관리법 제22조 (law.go.kr)',
 '전기사업자나 자가용전기설비의 소유자 또는 점유자는 … 전기안전관리자를 선임하여야 한다.', 3),

-- 승강기: 새 입력 has_elevator
('ELEVATOR_INSTALL_INSPECTION', 1, 'ACTIVE', '{}', '{}', '승강기 설치검사', '건축', 'FACILITY',
 '{"field":"has_elevator","op":"is_true"}',
 '승강기를 설치',
 jsonb_build_array(pg_temp.ref('001458', '제28조')),
 '승강기 안전관리법 §28 ①', 'COMMISSIONING',
 '{"gate":"OPERATION_START","offset_days":0,"text":"승강기 운행 전"}',
 '옛 이름은 승강기 완성검사. 제조·수입업자가 받는다.',
 '승강기 안전관리법 제28조 (law.go.kr)',
 '승강기의 제조ㆍ수입업자는 설치를 끝낸 승강기 … 에 대하여 … 설치검사 … 를 받아야 한다.', 3);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM derivation_rule, jsonb_array_elements(law_refs) r WHERE r->>'law_catalog_id' IS NULL) THEN
        RAISE EXCEPTION 'rule points at a law missing from law_catalog';
    END IF;
END
$$;

UPDATE ruleset_state SET version = 3;
