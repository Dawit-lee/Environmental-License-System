-- Hand-written v1 rule set: 소각 + common rules (permit-derivation/01). Thresholds only from the private wiki
-- threshold pages named in source_ref; no threshold is added that the wiki does not state.
-- Rules whose basis law is not in law_catalog yet (VE·건설기술심의: 건설기술 진흥법, 배출시설 설치신고:
-- 대기환경보전법, 도시계획시설결정: 국토계획법, 에너지절약계획서 …) are left out: every ACTIVE rule needs a law_ref.

CREATE FUNCTION pg_temp.ref(lid TEXT, art TEXT) RETURNS JSONB AS $$
    SELECT jsonb_build_object('law_catalog_id', (SELECT id FROM law_catalog WHERE law_id = lid), 'article_no', art)
$$ LANGUAGE sql;

INSERT INTO derivation_rule (rule_key, version, status, facility_types, project_kinds, permit_name, field, category,
                             condition, criterion_text, law_refs, legal_basis_text, approval_stage, required_by, note,
                             source_ref, source_quote, activated_in_ruleset)
VALUES
('WASTE_INSTALL_APPROVAL', 1, 'ACTIVE', '{INCINERATION}', '{NEW}', '폐기물처리시설 설치승인', '환경', 'FACILITY',
 '{"all":[{"field":"proponent","op":"in","value":["MUNICIPAL","REGIONAL"]},{"field":"capacity_tpd","op":">=","value":100}]}',
 '지자체·광역 추진, 일반소각 1일 처리능력 100톤 이상 (민간은 폐기물처분업 허가)',
 jsonb_build_array(pg_temp.ref('001771', '제29조'), pg_temp.ref('008567', '제39조')),
 '폐기물관리법 §29 ②, 시행규칙 §39', 'DESIGN',
 '{"gate":"CONSTRUCTION_START","offset_days":0,"text":"착공 전"}',
 '고온소각·열분해·고온용융·열처리조합시설은 시간당 100kg 기준(별도 검토). 통합환경허가에 포함될 수 있음.',
 'wiki/폐기물처리시설-설치승인과-설치신고.md', '설치승인 | 100톤/일 이상 | 폐기물관리법 §29 2항, 시행규칙 §39', 1),

('WASTE_INSTALL_REPORT', 1, 'ACTIVE', '{INCINERATION}', '{NEW}', '폐기물처리시설 설치신고', '환경', 'FACILITY',
 '{"all":[{"field":"proponent","op":"in","value":["MUNICIPAL","REGIONAL"]},{"field":"capacity_tpd","op":"<","value":100}]}',
 '지자체·광역 추진, 1일 처리능력 100톤 미만',
 jsonb_build_array(pg_temp.ref('001771', '제29조'), pg_temp.ref('008567', '제38조')),
 '폐기물관리법 §29 ③, 시행규칙 §38', 'DESIGN',
 '{"gate":"CONSTRUCTION_START","offset_days":0,"text":"착공 전"}', NULL,
 'wiki/폐기물처리시설-설치승인과-설치신고.md', '설치신고 | 100톤/일 미만 | 폐기물관리법 §29 3항, 시행규칙 §38', 1),

('WASTE_DISPOSAL_BUSINESS', 1, 'ACTIVE', '{INCINERATION}', '{NEW}', '폐기물처분업 허가', '환경', 'FACILITY',
 '{"field":"proponent","op":"==","value":"PRIVATE"}',
 '민간 추진',
 jsonb_build_array(pg_temp.ref('001771', '제25조')),
 '폐기물관리법 §25', 'DESIGN', NULL, NULL,
 'wiki/폐기물처리시설-설치승인과-설치신고.md', '민간 | 폐기물처분업 허가', 1),

('EIA_MAIN', 1, 'ACTIVE', '{}', '{NEW}', '환경영향평가', '공통', 'SITE',
 '{"any":[{"field":"capacity_tpd","op":">=","value":100},{"all":[{"field":"sido","op":"==","value":"경기"},{"field":"capacity_tpd","op":">=","value":50}]}]}',
 '처리용량 1일 100톤 이상 (경기도는 50톤 이상)',
 jsonb_build_array(pg_temp.ref('002016', '제22조'), pg_temp.ref('009161', '별표3')),
 '환경영향평가법 §22, 시행령 §31 별표3', 'DESIGN',
 '{"gate":"CONSTRUCTION_START","offset_days":0,"text":"착공 전"}',
 '소요 8개월 안팎(임계경로). 규모 30% 이상 변경 시 재협의.',
 'wiki/환경영향평가.md', '경기도는 위 기준의 50% 용량에서도 시행', 1),

('EIA_SMALL', 1, 'ACTIVE', '{}', '{NEW}', '소규모환경영향평가', '공통', 'SITE',
 '{"all":[{"not":{"any":[{"field":"capacity_tpd","op":">=","value":100},{"all":[{"field":"sido","op":"==","value":"경기"},{"field":"capacity_tpd","op":">=","value":50}]}]}},
   {"any":[{"all":[{"field":"zoning","op":"==","value":"URBAN"},{"field":"site_area_m2","op":">=","value":60000}]},
           {"all":[{"field":"zoning","op":"==","value":"MGMT_CONSERVATION"},{"field":"site_area_m2","op":">=","value":5000}]},
           {"all":[{"field":"zoning","op":"==","value":"MGMT_PRODUCTION"},{"field":"site_area_m2","op":">=","value":7500}]},
           {"all":[{"field":"zoning","op":"==","value":"MGMT_PLANNING"},{"field":"site_area_m2","op":">=","value":10000}]},
           {"all":[{"field":"zoning","op":"==","value":"AGRI_FOREST"},{"field":"site_area_m2","op":">=","value":7500}]},
           {"all":[{"field":"zoning","op":"==","value":"NATURE_CONSERVATION"},{"field":"site_area_m2","op":">=","value":5000}]},
           {"all":[{"field":"zoning","op":"==","value":"GREENBELT"},{"field":"site_area_m2","op":">=","value":5000}]}]}]}',
 '본평가 비대상이고 용도지역별 면적 이상 (도시 6만㎡, 관리 보전 5천·생산 7,500·계획 1만㎡, 농림 7,500㎡, 자연환경보전·개발제한구역 5천㎡)',
 jsonb_build_array(pg_temp.ref('002016', '제43조'), pg_temp.ref('009161', '별표4')),
 '환경영향평가법 §43, 시행령 §59 별표4', 'PLANNING',
 '{"gate":"OTHER","offset_days":0,"text":"사업계획 수립 시"}',
 '전략·본평가 대상이면 중복 평가 제외(법 §43 ①).',
 'wiki/환경영향평가.md', '전략·본평가와 소규모평가는 중복 평가 제외 (법 §43 1항)', 1),

('EIA_STRATEGIC', 1, 'ACTIVE', '{INCINERATION}', '{NEW}', '전략환경영향평가', '공통', 'SITE',
 '{"any":[{"all":[{"field":"proponent","op":"in","value":["MUNICIPAL","REGIONAL"]},{"field":"capacity_tpd","op":">=","value":50}]},{"field":"construction_cost_krw","op":">=","value":50000000000}]}',
 '폐촉법상 입지선정 대상(지자체 소각 50톤/일 이상) 또는 총공사비 500억원 이상 건설공사계획',
 jsonb_build_array(pg_temp.ref('002016', '제9조')),
 '환경영향평가법 §9 별표2', 'PLANNING',
 '{"gate":"OTHER","offset_days":0,"text":"기본계획 수립 시"}',
 '도시·군관리계획 수립 시에도 대상(이 규칙은 판정하지 않음).',
 'wiki/환경영향평가.md', '개발기본계획: 도시·군관리계획 수립, 총공사비 500억 이상 건설공사계획, 폐촉법상 입지선정', 1),

('SITE_SELECTION', 1, 'ACTIVE', '{INCINERATION}', '{NEW}', '입지선정 결정고시', '공통', 'SITE',
 '{"all":[{"field":"proponent","op":"in","value":["MUNICIPAL","REGIONAL"]},{"field":"capacity_tpd","op":">=","value":50}]}',
 '설치기관(지자체) 추진, 소각시설 50톤/일 이상',
 jsonb_build_array(pg_temp.ref('000156', NULL)),
 '폐기물시설촉진법', 'PLANNING',
 '{"gate":"OTHER","offset_days":0,"text":"입지 확정 시"}',
 '주변영향지역 고시 포함.',
 'wiki/폐기물처리시설-설치승인과-설치신고.md', '소각시설 50톤/일 이상 → 입지선정 결정고시 + 주변영향지역 고시 대상', 1),

('DISASTER_IA', 1, 'ACTIVE', '{}', '{}', '재해영향평가', '공통', 'SITE',
 '{"field":"site_area_m2","op":">=","value":50000}',
 '개발사업 부지 5만㎡ 이상',
 jsonb_build_array(pg_temp.ref('000959', '제4조'), pg_temp.ref('004607', '별표1')),
 '자연재해대책법 §4·§5, 시행령 §6 별표1', 'DESIGN',
 '{"gate":"OTHER","offset_days":0,"text":"실시계획 인가 전"}', '협의기간 45일.',
 'wiki/재해영향평가.md', '재해영향평가 | 개발사업 — 부지 5만㎡ 이상 또는 길이 10km 이상 | 45일', 1),

('DISASTER_IA_SMALL', 1, 'ACTIVE', '{}', '{}', '소규모 재해영향평가', '공통', 'SITE',
 '{"all":[{"field":"site_area_m2","op":">=","value":5000},{"field":"site_area_m2","op":"<","value":50000}]}',
 '개발사업 부지 5천~5만㎡',
 jsonb_build_array(pg_temp.ref('000959', '제4조'), pg_temp.ref('004607', '별표1')),
 '자연재해대책법 §4·§5, 시행령 §6 별표1', 'DESIGN',
 '{"gate":"OTHER","offset_days":0,"text":"실시계획 인가 전"}', '협의기간 30일.',
 'wiki/재해영향평가.md', '소규모 재해영향평가 | 개발사업 — 부지 5천~5만㎡ 또는 길이 2~10km | 30일', 1),

('INTEGRATED_ENV_PERMIT', 1, 'ACTIVE', '{INCINERATION}', '{}', '통합환경허가', '환경', 'FACILITY',
 '{"any":[{"field":"air_emission_tpy","op":">=","value":20},{"field":"wastewater_m3pd","op":">=","value":700}]}',
 '대기오염물질 발생량 연 20톤 이상 또는 폐수 1일 700㎥ 이상',
 jsonb_build_array(pg_temp.ref('012414', '제6조')),
 '환경오염시설의 통합관리에 관한 법률 §6', 'DESIGN',
 '{"gate":"CONSTRUCTION_START","offset_days":0,"text":"착공 전"}',
 '매립장 내 설치 소각시설은 제외. 비대상이면 대기·수질 등 배출시설 설치신고를 개별 이행.',
 'wiki/통합환경허가.md', '대기 | 대기오염물질 발생량 연간 20톤 이상 / 수질 | 폐수 1일 700㎥ 이상 배출', 1),

('WASTE_USE_START_REPORT', 1, 'ACTIVE', '{INCINERATION}', '{NEW}', '폐기물처리시설 사용개시신고', '기계', 'FACILITY',
 NULL, '소각시설 신설 시 항상',
 jsonb_build_array(pg_temp.ref('001771', NULL)),
 '폐기물관리법', 'OPERATION_START',
 '{"gate":"OPERATION_START","offset_days":0,"text":"사용개시 전"}', NULL,
 'wiki/광명-소각시설-인허가.md, wiki/인허가-생애주기-단계.md', '폐기물(소각)처리시설 사용개시신고', 1),

('INCINERATOR_INSPECTION', 1, 'ACTIVE', '{INCINERATION}', '{NEW}', '소각시설 설치검사', '기계', 'FACILITY',
 NULL, '소각시설 신설 시 항상',
 jsonb_build_array(pg_temp.ref('001771', NULL)),
 '폐기물관리법', 'COMMISSIONING',
 '{"gate":"COMMISSIONING_START","offset_days":0,"text":"시운전 시"}', NULL,
 'wiki/광명-소각시설-인허가.md, wiki/인허가-생애주기-단계.md', '시운전 단계 — 소각시설 설치검사', 1),

('BUILDING_PERMIT', 1, 'ACTIVE', '{}', '{NEW}', '건축허가', '건축', 'FACILITY',
 NULL, '시설 신설 시 항상',
 jsonb_build_array(pg_temp.ref('001823', NULL)),
 '건축법', 'DESIGN',
 '{"gate":"CONSTRUCTION_START","offset_days":0,"text":"착공 전"}', '공공 건축물은 공용건축물 협의.',
 'wiki/광명-소각시설-인허가.md, wiki/인허가-생애주기-단계.md', '건축인허가(공용건축물 협의)', 1),

('BUILDING_USE_APPROVAL', 1, 'ACTIVE', '{}', '{NEW}', '건축물 사용승인', '건축', 'FACILITY',
 NULL, '시설 신설 시 항상',
 jsonb_build_array(pg_temp.ref('001823', NULL)),
 '건축법', 'OPERATION_START',
 '{"gate":"OPERATION_START","offset_days":0,"text":"사용개시 전"}', NULL,
 'wiki/광명-소각시설-인허가.md, wiki/인허가-생애주기-단계.md', '사용개시 | 사용개시신고 | 폐기물처리시설 사용개시신고, 건축물 사용승인', 1);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM derivation_rule, jsonb_array_elements(law_refs) r WHERE r->>'law_catalog_id' IS NULL) THEN
        RAISE EXCEPTION 'seed rule points at a law missing from law_catalog';
    END IF;
END
$$;

UPDATE ruleset_state SET version = 1;
