-- Rule set v2: wider coverage (permit-derivation coverage batch).
-- Every threshold and article below was read from the law text on 국가법령정보센터 (law.go.kr) on 2026-11-11;
-- source_ref names the law + article. Nothing here comes from a real project's documents.
--   A  4 NEW-only rules now cover every project kind (the law says 건축/설치, not 신설) + 변경승인 for an expansion
--   B  rules whose inputs were already in the field dictionary
--   C  통합허가: the permits it replaces (§10①) are named on the rule; 가동개시 신고 added
--   D  construction-phase 신고 with a statutory size trigger

-- Laws the new rules cite. law_id / mst / dates: the 현행 version shown by law.go.kr on 2026-11-11.
INSERT INTO law_catalog (law_id, name, kind, short_name, last_mst, last_promulgated_on, last_enforced_on, source) VALUES
    ('012468', '지하안전관리에 관한 특별법', 'LAW', '지하안전법', 271251, '2025-05-27', '2025-05-27', 'SEED'),
    ('013019', '지하안전관리에 관한 특별법 시행령', 'DECREE', '지하안전법 시행령', 283007, '2026-01-27', '2026-02-01', 'SEED'),
    ('001807', '건설기술 진흥법', 'LAW', NULL, 276921, '2025-10-01', '2025-10-01', 'SEED'),
    ('002111', '건설기술 진흥법 시행령', 'DECREE', NULL, 286847, '2026-06-09', '2026-06-09', 'SEED'),
    ('001766', '산업안전보건법', 'LAW', NULL, 291447, '2026-10-27', '2026-10-27', 'SEED'),
    ('003786', '산업안전보건법 시행령', 'DECREE', NULL, 288347, '2026-07-28', '2026-08-01', 'SEED'),
    ('011557', '녹색건축물 조성 지원법', 'LAW', '녹색건축법', 284351, '2026-03-17', '2026-09-18', 'SEED'),
    ('011768', '녹색건축물 조성 지원법 시행령', 'DECREE', '녹색건축법 시행령', 285225, '2026-03-31', '2026-03-31', 'SEED'),
    ('009502', '위험물안전관리법', 'LAW', NULL, 259933, '2024-02-06', '2025-08-07', 'SEED'),
    ('000162', '화학물질관리법', 'LAW', NULL, 285367, '2026-04-07', '2026-10-08', 'SEED'),
    ('001867', '에너지이용 합리화법', 'LAW', NULL, 276559, '2025-10-01', '2026-05-28', 'SEED'),
    ('001773', '대기환경보전법', 'LAW', NULL, 284159, '2026-03-17', '2026-09-18', 'SEED'),
    ('003302', '대기환경보전법 시행령', 'DECREE', NULL, 288869, '2026-08-18', '2026-09-18', 'SEED'),
    ('007038', '대기환경보전법 시행규칙', 'RULE', NULL, 291621, '2026-11-04', '2026-11-04', 'SEED'),
    ('009503', '소방시설 설치 및 관리에 관한 법률', 'LAW', '소방시설법', 236977, '2021-11-30', '2024-12-01', 'SEED'),
    ('009694', '소방시설 설치 및 관리에 관한 법률 시행령', 'DECREE', '소방시설법 시행령', 290451, '2026-09-29', '2026-10-02', 'SEED')
ON CONFLICT (law_id) DO NOTHING; -- a user search may have added one already

UPDATE law_catalog c SET parent_id = p.id
FROM law_catalog p
WHERE c.parent_id IS NULL AND p.kind = 'LAW' AND c.name IN (p.name || ' 시행령', p.name || ' 시행규칙');

CREATE OR REPLACE FUNCTION pg_temp.ref(lid TEXT, art TEXT) RETURNS JSONB AS $$
    SELECT jsonb_build_object('law_catalog_id', (SELECT id FROM law_catalog WHERE law_id = lid), 'article_no', art)
$$ LANGUAGE sql;

-- ---- A + C: new versions of existing rules (rows are immutable: retire v1, insert v2) -------------------------
CREATE TEMP TABLE rule_v2 (rule_key TEXT, criterion_text TEXT, law_refs JSONB, legal_basis_text TEXT, note TEXT,
                           source_ref TEXT, source_quote TEXT);
INSERT INTO rule_v2 VALUES
('BUILDING_PERMIT', '건축물을 건축(신축·증축·개축·재축·이전)하는 사업에 항상',
 jsonb_build_array(pg_temp.ref('001823', '제11조'), pg_temp.ref('001823', '제29조')),
 '건축법 §11 ①, §2 ① 8호 (국가·지자체는 §29 공용건축물 협의)', '공공 건축물은 공용건축물 협의(§29)로 허가를 갈음.',
 '건축법 제11조·제2조제1항제8호·제29조 (law.go.kr)',
 '“건축”이란 건축물을 신축ㆍ증축ㆍ개축ㆍ재축(再築)하거나 건축물을 이전하는 것을 말한다.'),
('BUILDING_USE_APPROVAL', '건축허가(협의)를 받은 건축물의 공사를 마친 사업에 항상',
 jsonb_build_array(pg_temp.ref('001823', '제22조'), pg_temp.ref('001823', '제29조')),
 '건축법 §22 ①', '공용건축물 협의를 한 건축물은 사용승인 대신 공사 완료 통보(§29 ③).',
 '건축법 제22조·제29조 (law.go.kr)',
 '허가를 받았거나 신고를 한 건축물의 건축공사를 완료한 후 그 건축물을 사용하려면 … 허가권자에게 사용승인을 신청하여야 한다.'),
('INCINERATOR_INSPECTION', '소각시설의 설치를 마친 경우 항상 (신설·증설 공통)',
 jsonb_build_array(pg_temp.ref('001771', '제30조'), pg_temp.ref('008567', '제41조')),
 '폐기물관리법 §30 ①, 시행규칙 §41', '변경승인·변경신고로 시설을 바꾼 경우도 부령이 정하는 범위에서 검사 대상.',
 '폐기물관리법 제30조, 시행규칙 제41조 (law.go.kr)',
 '기후에너지환경부령으로 정하는 폐기물처리시설의 설치를 마친 자는 … 폐기물처리시설 검사기관으로부터 검사를 받아야 한다.'),
('WASTE_USE_START_REPORT', '폐기물처리시설 설치공사를 끝내고 사용을 시작하는 경우 항상 (신설·증설 공통)',
 jsonb_build_array(pg_temp.ref('001771', '제29조'), pg_temp.ref('008567', '제41조')),
 '폐기물관리법 §29 ④, 시행규칙 §41 ①', '사용개시일 10일 전까지 신고.',
 '폐기물관리법 제29조제4항, 시행규칙 제41조 (law.go.kr)',
 '폐기물처리시설을 설치하는 자는 그 설치공사를 끝낸 후 그 시설의 사용을 시작하려면 … 신고하여야 한다.'),
('INTEGRATED_ENV_PERMIT', '대기오염물질 발생량 연 20톤 이상 또는 폐수 1일 700㎥ 이상',
 jsonb_build_array(pg_temp.ref('012414', '제6조'), pg_temp.ref('012414', '제10조')),
 '환경오염시설의 통합관리에 관한 법률 §6 ①, §10 ①',
 '통합허가를 받으면 대기·폐수·소음진동 배출시설 설치허가·신고, 비점오염원 설치신고, 악취배출시설 신고, 폐기물처리시설 설치승인·신고를 한 것으로 본다(§10 ①) — 따로 신청하지 않는다. 매립장 내 설치 소각시설은 제외.',
 '환경오염시설의 통합관리에 관한 법률 제6조·제10조 (law.go.kr)',
 '제6조에 따른 허가 또는 변경허가를 받거나 변경신고를 한 경우에는 … 다음 각 호의 구분에 따른 허가ㆍ변경허가 또는 승인ㆍ변경승인을 받거나 신고ㆍ변경신고를 한 것으로 보아 다음 각 호의 법률을 적용한다.');

UPDATE derivation_rule SET status = 'RETIRED', retired_in_ruleset = 2, retired_at = now()
WHERE status = 'ACTIVE' AND rule_key IN (SELECT rule_key FROM rule_v2);

INSERT INTO derivation_rule (rule_key, version, status, facility_types, project_kinds, permit_name, field, category,
                             condition, criterion_text, law_refs, legal_basis_text, approval_stage, required_by, note,
                             source_ref, source_quote, activated_in_ruleset)
SELECT o.rule_key, 2, 'ACTIVE', o.facility_types, '{}'::text[], o.permit_name, o.field, o.category, o.condition,
       n.criterion_text, n.law_refs, n.legal_basis_text, o.approval_stage, o.required_by, n.note, n.source_ref,
       n.source_quote, 2
FROM derivation_rule o JOIN rule_v2 n USING (rule_key)
WHERE o.version = 1;

-- ---- new rules -------------------------------------------------------------------------------------------------
INSERT INTO derivation_rule (rule_key, version, status, facility_types, project_kinds, permit_name, field, category,
                             condition, criterion_text, law_refs, legal_basis_text, approval_stage, required_by, note,
                             source_ref, source_quote, activated_in_ruleset)
VALUES
-- A: an expansion of an approved facility
('WASTE_INSTALL_CHANGE_APPROVAL', 1, 'ACTIVE', '{INCINERATION}', '{EXPANSION}', '폐기물처리시설 설치 변경승인', '환경', 'FACILITY',
 '{"all":[{"field":"proponent","op":"in","value":["MUNICIPAL","REGIONAL"]},{"field":"existing_capacity_tpd","op":">=","value":100},{"field":"expansion_ratio","op":">=","value":0.3}]}',
 '지자체·광역 추진, 승인받은 기존 시설(100톤/일 이상)의 처분용량이 30% 이상 증가',
 jsonb_build_array(pg_temp.ref('001771', '제29조'), pg_temp.ref('008567', '제39조')),
 '폐기물관리법 §29 ③, 시행규칙 §39 ③ 4호', 'DESIGN',
 '{"gate":"CONSTRUCTION_START","offset_days":0,"text":"변경 전"}',
 '용량 외 변경승인 사유(대상 폐기물·소재지·주요설비 변경)는 판정하지 않는다. 기존 시설을 폐쇄하고 새로 짓는 대체 증설은 설치승인(§29 ②) 대상인데 사업구분으로 구별되지 않는다.',
 '폐기물관리법 제29조제3항, 시행규칙 제39조제3항제4호 (law.go.kr)',
 '승인 또는 변경승인을 받은 처분 또는 재활용 용량의 합계 또는 누계의 100분의 30 이상의 증가', 2),

-- B: 지하안전
('UNDERGROUND_SAFETY', 1, 'ACTIVE', '{}', '{}', '지하안전평가', '토목', 'SITE',
 '{"field":"excavation_depth_m","op":">=","value":20}',
 '최대 굴착깊이 20m 이상 굴착공사를 수반하는 사업',
 jsonb_build_array(pg_temp.ref('012468', '제14조'), pg_temp.ref('013019', '제13조')),
 '지하안전관리에 관한 특별법 §14 ①, 시행령 §13 ①', 'DESIGN',
 '{"gate":"OTHER","offset_days":0,"text":"사업 승인·인가 전 협의"}',
 '굴착깊이에서 집수정·엘리베이터 피트·정화조 굴착부분은 제외. 터널공사 수반 사업은 깊이와 무관(판정하지 않음).',
 '지하안전관리에 관한 특별법 제14조, 시행령 제13조·별표1 (law.go.kr)',
 '굴착깊이 … 가 20미터 이상인 굴착공사를 수반하는 사업', 2),

('UNDERGROUND_SAFETY_SMALL', 1, 'ACTIVE', '{}', '{}', '소규모 지하안전평가', '토목', 'SITE',
 '{"all":[{"field":"excavation_depth_m","op":">=","value":10},{"field":"excavation_depth_m","op":"<","value":20}]}',
 '최대 굴착깊이 10m 이상 20m 미만 굴착공사를 수반하는 사업',
 jsonb_build_array(pg_temp.ref('012468', '제23조'), pg_temp.ref('013019', '제23조')),
 '지하안전관리에 관한 특별법 §23 ①, 시행령 §23', 'DESIGN',
 '{"gate":"OTHER","offset_days":0,"text":"사업 승인·인가 전 협의"}',
 '옛 이름은 소규모 지하안전영향평가.',
 '지하안전관리에 관한 특별법 제23조, 시행령 제23조·별표1 (law.go.kr)',
 '굴착깊이가 10미터 이상 20미터 미만인 굴착공사를 수반하는 사업으로서 별표 1에서 정하는 사업', 2),

-- B: 건설기술 진흥법
('DESIGN_VE', 1, 'ACTIVE', '{}', '{}', '설계의 경제성 검토(VE)', '공통', 'GENERAL',
 '{"all":[{"field":"proponent","op":"in","value":["MUNICIPAL","REGIONAL"]},{"field":"construction_cost_krw","op":">=","value":10000000000}]}',
 '발주청(지자체·광역) 발주, 총공사비 100억원 이상 건설공사의 기본설계·실시설계',
 jsonb_build_array(pg_temp.ref('002111', '제75조')),
 '건설기술 진흥법 시행령 §75 ① 1호', 'DESIGN',
 '{"gate":"OTHER","offset_days":0,"text":"기본설계·실시설계 시"}',
 '100억원 미만이어도 발주청이 필요하다고 인정하면 실시(판정하지 않음).',
 '건설기술 진흥법 시행령 제75조 (law.go.kr)',
 '총공사비 100억원 이상인 건설공사의 기본설계 및 실시설계를 하는 경우', 2),

('DESIGN_SAFETY_REVIEW', 1, 'ACTIVE', '{}', '{}', '설계의 안전성 검토(DFS)', '안전', 'GENERAL',
 '{"all":[{"field":"proponent","op":"in","value":["MUNICIPAL","REGIONAL"]},{"field":"excavation_depth_m","op":">=","value":10}]}',
 '발주청(지자체·광역) 발주, 안전관리계획 수립 대상 공사(지하 10m 이상 굴착)의 실시설계',
 jsonb_build_array(pg_temp.ref('002111', '제75조의2'), pg_temp.ref('002111', '제98조')),
 '건설기술 진흥법 시행령 §75의2 ①, §98 ① 2호', 'DESIGN',
 '{"gate":"CONSTRUCTION_START","offset_days":0,"text":"실시설계 시 의뢰, 착공 전 결과 제출"}',
 '굴착깊이만 본다. 1·2종시설물, 10층 이상 건축물 등 다른 안전관리계획 대상(§98 ①)은 판정하지 않는다.',
 '건설기술 진흥법 시행령 제75조의2·제98조 (law.go.kr)',
 '발주청은 제98조제1항에 따라 안전관리계획을 수립해야 하는 건설공사 … 의 실시설계를 할 때에는 … 설계의 안전성 검토를 국토안전관리원에 의뢰해야 한다.', 2),

('SAFETY_MGMT_PLAN', 1, 'ACTIVE', '{}', '{}', '안전관리계획서', '안전', 'GENERAL',
 '{"field":"excavation_depth_m","op":">=","value":10}',
 '지하 10m 이상을 굴착하는 건설공사',
 jsonb_build_array(pg_temp.ref('001807', '제62조'), pg_temp.ref('002111', '제98조')),
 '건설기술 진흥법 §62 ①, 시행령 §98 ① 2호', 'DESIGN',
 '{"gate":"CONSTRUCTION_START","offset_days":0,"text":"착공 전 제출·승인"}',
 '굴착깊이만 본다. 1·2종시설물, 10층 이상 건축물, 타워크레인·항타기 사용 공사 등 다른 대상(§98 ①)은 판정하지 않는다. 유해위험방지계획서와 통합 작성 가능.',
 '건설기술 진흥법 제62조, 시행령 제98조 (law.go.kr)',
 '지하 10미터 이상을 굴착하는 건설공사', 2),

-- B: 산업안전보건법
('DESIGN_SAFETY_LEDGER', 1, 'ACTIVE', '{}', '{}', '설계안전보건대장', '안전', 'GENERAL',
 '{"field":"construction_cost_krw","op":">=","value":5000000000}',
 '총공사금액 50억원 이상 건설공사',
 jsonb_build_array(pg_temp.ref('001766', '제67조'), pg_temp.ref('003786', '제55조')),
 '산업안전보건법 §67 ①, 시행령 §55', 'DESIGN',
 '{"gate":"OTHER","offset_days":0,"text":"설계 단계"}',
 '계획단계 기본안전보건대장, 시공단계 공사안전보건대장도 같은 기준.',
 '산업안전보건법 제67조, 시행령 제55조 (law.go.kr)',
 '“대통령령으로 정하는 건설공사”란 총공사금액이 50억원 이상인 공사를 말한다.', 2),

('HAZARD_PREVENTION_PLAN', 1, 'ACTIVE', '{}', '{}', '유해위험방지계획서', '안전', 'GENERAL',
 '{"any":[{"field":"building_height_m","op":">=","value":31},{"field":"gross_floor_area_m2","op":">=","value":30000},{"field":"excavation_depth_m","op":">=","value":10}]}',
 '지상높이 31m 이상 건축물·인공구조물, 연면적 3만㎡ 이상 건축물 또는 깊이 10m 이상 굴착공사',
 jsonb_build_array(pg_temp.ref('001766', '제42조'), pg_temp.ref('003786', '제42조')),
 '산업안전보건법 §42 ① 3호, 시행령 §42 ③', 'DESIGN',
 '{"gate":"CONSTRUCTION_START","offset_days":0,"text":"착공 전"}',
 '터널·교량·댐 등 그 밖의 대상 공사(시행령 §42 ③ 2~5호)는 판정하지 않는다.',
 '산업안전보건법 제42조, 시행령 제42조제3항 (law.go.kr)',
 '지상높이가 31미터 이상인 건축물 또는 인공구조물 / 연면적 3만제곱미터 이상인 건축물 / 깊이 10미터 이상인 굴착공사', 2),

-- B: 녹색건축물 조성 지원법
('ENERGY_SAVING_PLAN', 1, 'ACTIVE', '{}', '{}', '에너지절약계획서', '건축', 'FACILITY',
 '{"all":[{"field":"gross_floor_area_m2","op":">=","value":500},{"field":"hvac_area_m2","op":">","value":0}]}',
 '연면적 합계 500㎡ 이상이고 냉방 또는 난방 설비를 설치하는 건축물',
 jsonb_build_array(pg_temp.ref('011557', '제14조'), pg_temp.ref('011768', '제10조')),
 '녹색건축물 조성 지원법 §14 ①, 시행령 §10 ①', 'DESIGN',
 '{"gate":"OTHER","offset_days":0,"text":"건축허가(협의) 신청 시"}',
 '자원순환 관련 시설 등(건축법 시행령 별표1 17~26호)은 냉난방 설비를 모두 설치하지 않으면 제외(시행령 §10 ① 3호) — 냉난방면적을 입력해야 판정된다. 단독주택·동식물원·고시 제외 건축물은 판정하지 않는다.',
 '녹색건축물 조성 지원법 제14조, 시행령 제10조 (law.go.kr)',
 '“대통령령으로 정하는 건축물”이란 연면적의 합계가 500제곱미터 이상인 건축물을 말한다.', 2),

('GREEN_BUILDING_CERT', 1, 'ACTIVE', '{}', '{}', '녹색건축 인증', '건축', 'FACILITY',
 '{"all":[{"field":"proponent","op":"in","value":["MUNICIPAL","REGIONAL"]},{"field":"gross_floor_area_m2","op":">=","value":3000},{"field":"hvac_area_m2","op":">","value":0}]}',
 '공공(지자체·광역) 소유·관리, 연면적 3,000㎡ 이상, 에너지절약계획서 제출 대상인 신축·재축·별동 증축 건축물',
 jsonb_build_array(pg_temp.ref('011557', '제16조'), pg_temp.ref('011768', '제11조의3')),
 '녹색건축물 조성 지원법 §16 ⑦, 시행령 §11의3', 'OPERATION_START',
 '{"gate":"OPERATION_START","offset_days":0,"text":"사용승인 신청 시 인증서류 첨부"}',
 '예비인증(설계 단계)과 본인증으로 나누어 진행한다. 증축은 같은 대지에 별개의 건축물로 증축하는 경우만 해당.',
 '녹색건축물 조성 지원법 제16조제7항, 시행령 제11조의3 (law.go.kr)',
 '연면적 … 이 3천제곱미터 이상일 것 / 법 제14조제1항에 따른 에너지 절약계획서 제출 대상일 것', 2),

('ZEB_CERT', 1, 'ACTIVE', '{}', '{}', '제로에너지건축물 인증', '건축', 'FACILITY',
 '{"all":[{"field":"proponent","op":"in","value":["MUNICIPAL","REGIONAL"]},{"field":"gross_floor_area_m2","op":">=","value":500},{"field":"hvac_area_m2","op":">","value":0}]}',
 '공공(지자체·광역) 소유·관리, 연면적 500㎡ 이상, 에너지절약계획서 제출 대상인 신축·재축·전부개축·별동 증축 건축물',
 jsonb_build_array(pg_temp.ref('011557', '제17조'), pg_temp.ref('011768', '제12조'), pg_temp.ref('011768', '별표1')),
 '녹색건축물 조성 지원법 §17 ⑥, 시행령 §12 ② 별표1', 'OPERATION_START',
 '{"gate":"OPERATION_START","offset_days":0,"text":"사용승인 신청 시 인증서류 첨부"}',
 '공동부령이 정하는 인증 대상 건축물이어야 한다(별표1 3호) — 용도에 따른 제외는 판정하지 않으니 확인 필요. 건축물 에너지효율등급 인증은 이 인증으로 통합되었다(법 §17, 2024-02-20 개정).',
 '녹색건축물 조성 지원법 제17조제6항, 시행령 제12조·별표1 (law.go.kr)',
 '공동주택 외의 건축물의 경우: 연면적 5백제곱미터 이상', 2),

-- B: 위험물안전관리법
('HAZMAT_INSTALL_PERMIT', 1, 'ACTIVE', '{}', '{}', '위험물 제조소등 설치허가', '소방', 'FACILITY',
 '{"field":"has_hazardous_material_storage","op":"is_true"}',
 '지정수량 이상의 위험물을 저장·취급하는 시설(제조소등, 예: 경유 저장탱크)을 설치',
 jsonb_build_array(pg_temp.ref('009502', '제6조')),
 '위험물안전관리법 §6 ①', 'CONSTRUCTION',
 '{"gate":"OTHER","offset_days":0,"text":"제조소등 설치 전"}',
 '지정수량 미만이면 시·도 조례 적용(허가 대상 아님) — 위험물 저장 입력은 지정수량 이상일 때만 체크한다.',
 '위험물안전관리법 제6조 (law.go.kr)',
 '제조소등을 설치하고자 하는 자는 … 시ㆍ도지사 … 의 허가를 받아야 한다.', 2),

('HAZMAT_COMPLETION_INSPECTION', 1, 'ACTIVE', '{}', '{}', '위험물 제조소등 완공검사', '소방', 'FACILITY',
 '{"field":"has_hazardous_material_storage","op":"is_true"}',
 '설치허가를 받은 제조소등의 설치를 마친 때',
 jsonb_build_array(pg_temp.ref('009502', '제9조')),
 '위험물안전관리법 §9 ①', 'COMMISSIONING',
 '{"gate":"OTHER","offset_days":0,"text":"제조소등 사용 전"}', NULL,
 '위험물안전관리법 제9조 (law.go.kr)',
 '허가를 받은 자가 제조소등의 설치를 마쳤거나 … 완공검사를 받아 … 인정받은 후가 아니면 이를 사용하여서는 아니된다.', 2),

('HAZMAT_SAFETY_MANAGER', 1, 'ACTIVE', '{}', '{}', '위험물안전관리자 선임신고', '소방', 'FACILITY',
 '{"field":"has_hazardous_material_storage","op":"is_true"}',
 '허가 대상 제조소등마다 위험물안전관리자 선임',
 jsonb_build_array(pg_temp.ref('009502', '제15조')),
 '위험물안전관리법 §15 ①·③', 'OPERATION_START',
 '{"gate":"OTHER","offset_days":0,"text":"선임한 날부터 14일 이내 신고"}', NULL,
 '위험물안전관리법 제15조 (law.go.kr)',
 '안전관리자를 선임한 경우에는 선임한 날부터 14일 이내에 … 신고하여야 한다.', 2),

-- B: 화학물질관리법
('CHEM_ACCIDENT_PLAN', 1, 'ACTIVE', '{}', '{}', '화학사고예방관리계획서', '환경', 'FACILITY',
 '{"field":"uses_hazardous_chemicals","op":"is_true"}',
 '유해화학물질 취급시설을 설치·운영',
 jsonb_build_array(pg_temp.ref('000162', '제23조')),
 '화학물질관리법 §23 ①', 'DESIGN',
 '{"gate":"OTHER","offset_days":0,"text":"취급시설 설치·운영 전"}',
 '취급 수량이 부령 기준에 못 미치는 시설은 제외(§23 ① 3호) — 수량 기준은 판정하지 않는다.',
 '화학물질관리법 제23조 (law.go.kr)',
 '유해화학물질 취급시설을 설치ㆍ운영하려는 자는 사전에 … 화학사고예방관리계획서 … 를 작성하여 … 제출하여야 한다.', 2),

('CHEM_FACILITY_INSPECTION', 1, 'ACTIVE', '{}', '{}', '유해화학물질 취급시설 설치검사', '환경', 'FACILITY',
 '{"field":"uses_hazardous_chemicals","op":"is_true"}',
 '유해화학물질 취급시설의 설치를 마친 때',
 jsonb_build_array(pg_temp.ref('000162', '제24조')),
 '화학물질관리법 §24 ②', 'COMMISSIONING',
 '{"gate":"COMMISSIONING_START","offset_days":0,"text":"취급시설 가동 전"}', NULL,
 '화학물질관리법 제24조 (law.go.kr)',
 '유해화학물질 취급시설의 설치를 마친 자는 … 검사기관에서 설치검사를 받아야 하며', 2),

-- B: 에너지이용 합리화법
('BOILER_INSTALL_INSPECTION', 1, 'ACTIVE', '{}', '{}', '검사대상기기 설치검사(보일러·압력용기)', '기계', 'FACILITY',
 '{"field":"has_boiler_pressure_vessel","op":"is_true"}',
 '검사대상기기(보일러·압력용기)를 설치하여 사용',
 jsonb_build_array(pg_temp.ref('001867', '제39조')),
 '에너지이용 합리화법 §39 ②', 'COMMISSIONING',
 '{"gate":"COMMISSIONING_START","offset_days":0,"text":"사용 전"}',
 '검사대상기기의 범위(용량·압력)는 부령이 정한다 — 판정하지 않는다. 제조 단계의 용접·구조검사는 제조업자 의무(§39 ①).',
 '에너지이용 합리화법 제39조 (law.go.kr)',
 '검사대상기기를 설치하거나 개조하여 사용하려는 자 … 는 … 시ㆍ도지사의 검사를 받아야 한다.', 2),

-- C: 통합허가 사업장의 가동개시
('INTEGRATED_START_REPORT', 1, 'ACTIVE', '{INCINERATION}', '{}', '가동개시 신고(통합환경허가)', '환경', 'FACILITY',
 '{"any":[{"field":"air_emission_tpy","op":">=","value":20},{"field":"wastewater_m3pd","op":">=","value":700}]}',
 '통합허가 대상 사업장(대기 연 20톤 이상 또는 폐수 1일 700㎥ 이상)이 배출시설등의 설치·변경을 마치고 가동',
 jsonb_build_array(pg_temp.ref('012414', '제12조')),
 '환경오염시설의 통합관리에 관한 법률 §12 ①', 'COMMISSIONING',
 '{"gate":"COMMISSIONING_START","offset_days":0,"text":"시설 가동 전"}',
 '대기·폐수 배출시설의 개별 가동개시 신고를 대신한다. 폐기물처리시설 검사결과서를 함께 제출.',
 '환경오염시설의 통합관리에 관한 법률 제12조 (law.go.kr)',
 '배출시설등 및 방지시설의 전부 또는 일부의 설치 또는 변경 … 을 완료하고 해당 시설을 가동하려는 경우에는 … 가동개시 신고를 하여야 한다.', 2),

-- D: 공사 단계 신고
('FUGITIVE_DUST_REPORT', 1, 'ACTIVE', '{}', '{}', '비산먼지 발생사업 신고', '환경', 'GENERAL',
 '{"field":"gross_floor_area_m2","op":">=","value":1000}',
 '연면적 1,000㎡ 이상 건축물축조공사(증·개축·재축 포함)',
 jsonb_build_array(pg_temp.ref('001773', '제43조'), pg_temp.ref('003302', '제44조'), pg_temp.ref('007038', '별표13')),
 '대기환경보전법 §43 ①, 시행령 §44 5호, 시행규칙 §57 별표13', 'CONSTRUCTION',
 '{"gate":"CONSTRUCTION_START","offset_days":0,"text":"착공 전"}',
 '연면적만 본다. 토목공사(구조물 용적 1,000㎥·공사면적 1,000㎡ 이상 등)·조경·지반조성공사 기준은 판정하지 않는다. 최초 수급인이 신고.',
 '대기환경보전법 제43조, 시행령 제44조, 시행규칙 별표13 (law.go.kr)',
 '건축물축조공사: 「건축법」에 따른 건축물의 증·개축, 재축 및 대수선을 포함하고, 연면적이 1,000제곱미터 이상인 공사', 2),

('FIRE_CONSENT', 1, 'ACTIVE', '{}', '{}', '건축허가등의 소방 동의', '소방', 'FACILITY',
 '{"field":"gross_floor_area_m2","op":">=","value":400}',
 '연면적 400㎡ 이상 건축물의 건축허가·협의',
 jsonb_build_array(pg_temp.ref('009503', '제6조'), pg_temp.ref('009694', '제7조')),
 '소방시설 설치 및 관리에 관한 법률 §6 ①, 시행령 §7 ① 1호', 'DESIGN',
 '{"gate":"OTHER","offset_days":0,"text":"건축허가(협의) 시"}',
 '연면적만 본다. 지하층·무창층, 주차시설 등 다른 동의 대상(시행령 §7 ① 2호 이하)은 판정하지 않는다. 허가권자가 소방서장에게 동의를 요청한다.',
 '소방시설 설치 및 관리에 관한 법률 제6조, 시행령 제7조 (law.go.kr)',
 '연면적 … 이 400제곱미터 이상인 건축물이나 시설', 2);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM derivation_rule, jsonb_array_elements(law_refs) r WHERE r->>'law_catalog_id' IS NULL) THEN
        RAISE EXCEPTION 'rule points at a law missing from law_catalog';
    END IF;
END
$$;

UPDATE ruleset_state SET version = 2;
