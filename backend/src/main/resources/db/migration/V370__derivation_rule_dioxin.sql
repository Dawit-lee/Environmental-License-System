-- Rule set v4: 다이옥신 측정 (잔류성오염물질 관리법), one rule per measuring-cycle band.
-- Article text and 별표 1·6 read on 국가법령정보센터 (law.go.kr) on 2026-11-16 (법 시행 2025-10-01, 시행규칙 시행 2025-10-01).
-- Hourly capacity = capacity_tpd / 24 (continuous operation, as TMS_INSTALL in V300): 25 kg/h = 0.6, 200 kg/h = 4.8, 2 t/h = 48 t/d.
-- The three rules share one permit name; at most one of them is APPLICABLE for a given capacity.

INSERT INTO law_catalog (law_id, name, kind, short_name, last_mst, last_promulgated_on, last_enforced_on, source) VALUES
    ('010383', '잔류성오염물질 관리법', 'LAW', '잔류성물질법', 276787, '2025-10-01', '2025-10-01', 'SEED'),
    ('010642', '잔류성오염물질 관리법 시행규칙', 'RULE', '잔류성물질법 시행규칙', 278983, '2025-10-01', '2025-10-01', 'SEED')
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
SELECT b.rule_key, 1, 'ACTIVE', '{INCINERATION}', '{}', '다이옥신 측정', '환경', 'FACILITY',
       b.condition::jsonb, b.criterion,
       jsonb_build_array(pg_temp.ref('010383', '제19조'), pg_temp.ref('010642', '제14조'),
                         pg_temp.ref('010642', '별표1'), pg_temp.ref('010642', '별표6')),
       '잔류성오염물질 관리법 §19 ①, 시행규칙 §2 별표1 제8호·§14 ③ 별표6', 'OPERATION_START',
       jsonb_build_object('gate', 'OPERATION_START', 'offset_days', 0,
                          'text', '최초 가동일부터 ' || b.months || '개월마다, 주기 종료일 전후 30일 내'),
       '시간당 처리능력은 1일 용량을 24시간으로 나눈 값으로 본다(연속식 가정). 최초 측정 뒤에는 마지막 측정일이 기산일. '
           || '조업 중지 등으로 측정기간에 가동하지 않으면 재가동일부터 30일 내. 결과는 5년 보존(시행규칙 §14 ②). '
           || '폐수 매체(12개월마다)와 통합허가 조건의 더 짧은 주기는 판정하지 않는다.',
       '잔류성오염물질 관리법 제19조, 시행규칙 제2조·별표1, 제14조·별표6 (law.go.kr)',
       '배출사업자는 해당 배출시설에서 배출되는 잔류성오염물질을 … 측정하거나 … 측정하게 하고 / '
           || '8. 소각시설 — 시간당 소각능력이 25킬로그램 이상인 폐기물소각시설 / ' || b.quote,
       4
FROM (VALUES
    ('DIOXIN_MEASURE_6M', 6,
     '{"field":"capacity_tpd","op":">=","value":48}',
     '시간당 처리능력 2톤 이상(1일 48톤 이상) 소각시설 — 배기가스 다이옥신 6개월마다 1회 이상',
     '시간당 처리능력이 2톤 이상인 시설의 경우 : 6개월마다 1회 이상'),
    ('DIOXIN_MEASURE_12M', 12,
     '{"all":[{"field":"capacity_tpd","op":">=","value":4.8},{"field":"capacity_tpd","op":"<","value":48}]}',
     '시간당 처리능력 200kg 이상 2톤 미만(1일 4.8톤 이상 48톤 미만) 소각시설 — 배기가스 다이옥신 12개월마다 1회 이상',
     '시간당 처리능력이 200킬로그램 이상 2톤 미만인 시설의 경우 : 12개월마다 1회 이상'),
    ('DIOXIN_MEASURE_24M', 24,
     '{"all":[{"field":"capacity_tpd","op":">=","value":0.6},{"field":"capacity_tpd","op":"<","value":4.8}]}',
     '시간당 처리능력 25kg 이상 200kg 미만(1일 0.6톤 이상 4.8톤 미만) 소각시설 — 배기가스 다이옥신 24개월마다 1회 이상',
     '시간당 처리능력이 25킬로그램 이상 200킬로그램 미만인 시설의 경우 : 24개월마다 1회 이상')
) AS b(rule_key, months, condition, criterion, quote);

UPDATE ruleset_state SET version = 4;
