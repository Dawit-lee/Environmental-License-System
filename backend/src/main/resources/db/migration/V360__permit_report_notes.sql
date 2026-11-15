-- design-schedule/08: 인허가 진행 현황 보고 1쪽의 보고용 문구 (항목별, 계속 남음)
ALTER TABLE permit_item
    ADD COLUMN progress_note       VARCHAR(200), -- 완료 여부 칸의 진행 상황 문장
    ADD COLUMN expected_completion VARCHAR(50),  -- 예상완료 시점(자유 문구)
    ADD COLUMN issue_note          VARCHAR(300); -- 현안사항
