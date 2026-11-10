-- DRAFT seed (design §3.2). Items and units are NOT yet checked against the law text
-- (대기환경보전법 시행규칙 별표 8, 하수도법 시행규칙 별표 1). Ticket operations-compliance/09 confirms them
-- with a follow-up seed migration; until then the 운영 tab says so.
INSERT INTO pollutant (code, medium, name, unit, sort) VALUES
    ('DUST', 'AIR', '먼지', 'mg/S㎥', 10),
    ('SOX', 'AIR', '황산화물', 'ppm', 20),
    ('NOX', 'AIR', '질소산화물', 'ppm', 30),
    ('CO', 'AIR', '일산화탄소', 'ppm', 40),
    ('HCL', 'AIR', '염화수소', 'ppm', 50),
    ('DIOXIN', 'AIR', '다이옥신', 'ng-TEQ/S㎥', 60),
    ('BOD', 'WATER', 'BOD', 'mg/L', 110),
    ('TOC', 'WATER', 'TOC', 'mg/L', 120),
    ('SS', 'WATER', 'SS', 'mg/L', 130),
    ('TN', 'WATER', '총질소', 'mg/L', 140),
    ('TP', 'WATER', '총인', 'mg/L', 150),
    ('COLIFORM', 'WATER', '총대장균군', '개/mL', 160);
