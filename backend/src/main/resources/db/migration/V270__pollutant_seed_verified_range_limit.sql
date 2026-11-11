-- operations-compliance/09: the V221 draft seed checked against the law text (국가법령정보센터, 2026-11-11).
-- The 12 draft items and their units stand; the items below were missing. Sources:
--   AIR   대기환경보전법 시행규칙 별표 8 제2호 (폐수·폐기물·폐가스 소각처리시설 행이 있는 항목)
--   WATER 물환경보전법 시행규칙 별표 13 제2호 (폐수 배출허용기준; 하수도법 방류수수질기준은 공공하수처리시설용)
--   다이옥신 (대기·폐수) 잔류성오염물질 관리법 시행규칙 별표 3 제4호
-- Not seeded: 구리·니켈·아연화합물 (no incinerator row), 연간 총량(kg/년), 악취·소음·진동.

-- A range limit (pH 5.8~8.6 in the law) has a lower bound too; limit_value stays the upper bound.
ALTER TABLE pollutant ADD COLUMN range_limit BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE permit_limit ADD COLUMN limit_lower NUMERIC CHECK (limit_lower >= 0 AND limit_lower < limit_value);
ALTER TABLE measurement ADD COLUMN limit_lower NUMERIC CHECK (limit_lower IS NULL OR limit_value IS NOT NULL); -- snapshot, like limit_value

INSERT INTO pollutant (code, medium, name, unit, sort, range_limit) VALUES
    ('NH3', 'AIR', '암모니아', 'ppm', 65, FALSE),
    ('HF', 'AIR', '불소화합물', 'ppm', 70, FALSE),
    ('HG', 'AIR', '수은화합물', 'mg/S㎥', 75, FALSE),
    ('AS', 'AIR', '비소화합물', 'ppm', 80, FALSE), -- ppm in the law, unlike the other metals
    ('CD', 'AIR', '카드뮴화합물', 'mg/S㎥', 85, FALSE),
    ('PB', 'AIR', '납화합물', 'mg/S㎥', 90, FALSE),
    ('CR', 'AIR', '크롬화합물', 'mg/S㎥', 95, FALSE),
    ('PH', 'WATER', '수소이온농도(pH)', 'pH', 105, TRUE), -- the law gives no unit; "pH" is shown where a unit is expected
    ('NHEX_MINERAL', 'WATER', '노말헥산추출물질(광유류)', 'mg/L', 170, FALSE),
    ('NHEX_ANIMAL', 'WATER', '노말헥산추출물질(동식물유지류)', 'mg/L', 180, FALSE),
    ('ECOTOX', 'WATER', '생태독성', 'TU', 190, FALSE),
    ('DIOXIN_W', 'WATER', '다이옥신', 'pg-TEQ/L', 200, FALSE);

COMMENT ON TABLE pollutant IS 'Verified against the law text on 2026-11-11 (operations-compliance/09); see V270 for sources.';
