-- 날짜에 따른 자동 상태 변경(만료임박·만료)은 변경자가 없다.
ALTER TABLE permit_status_history ALTER COLUMN changed_by DROP NOT NULL;
