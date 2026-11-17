package com.envpermit.core.permit;

import static com.envpermit.core.permit.PermitItem.Status.COMPLETED;
import static com.envpermit.core.permit.PermitItem.Status.EXPIRED;
import static com.envpermit.core.permit.PermitItem.Status.IN_PROGRESS;
import static com.envpermit.core.permit.PermitItem.Status.NEAR_EXPIRY;
import static com.envpermit.core.permit.PermitItem.Status.NOT_APPLIED;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PermitStatusRuleTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    static PermitItem.Status derive(PermitItem.Status chosen, LocalDate applied, LocalDate acquired, LocalDate expiry) {
        return PermitStatusRule.derive(chosen, applied, acquired, expiry, TODAY);
    }

    @Test
    void acquired_date_decides_completed_near_expiry_and_expired() {
        LocalDate got = TODAY.minusDays(100);
        assertThat(derive(NOT_APPLIED, null, got, null)).isEqualTo(COMPLETED);
        assertThat(derive(IN_PROGRESS, null, got, TODAY.plusDays(31))).isEqualTo(COMPLETED);
        assertThat(derive(COMPLETED, null, got, TODAY.plusDays(30))).isEqualTo(NEAR_EXPIRY);
        assertThat(derive(COMPLETED, null, got, TODAY)).isEqualTo(NEAR_EXPIRY);
        assertThat(derive(NEAR_EXPIRY, null, got, TODAY.minusDays(1))).isEqualTo(EXPIRED);
    }

    @Test
    void application_date_decides_before_acquisition() {
        assertThat(derive(NOT_APPLIED, TODAY, null, null)).isEqualTo(IN_PROGRESS);
        assertThat(derive(IN_PROGRESS, TODAY.plusDays(2), null, null)).isEqualTo(NOT_APPLIED); // 신청 예정
        // 취득 예정일(미래)은 아직 취득이 아니다
        assertThat(derive(COMPLETED, TODAY.minusDays(5), TODAY.plusDays(5), null)).isEqualTo(IN_PROGRESS);
    }

    @Test
    void chosen_status_only_where_dates_cannot_decide() {
        assertThat(derive(IN_PROGRESS, null, null, null)).isEqualTo(IN_PROGRESS);
        assertThat(derive(NOT_APPLIED, null, null, TODAY.minusDays(3))).isEqualTo(NOT_APPLIED); // 취득 전엔 만료 없음
        // 취득일 없이 취득완료로 둔 기존 자료: 만료일 규칙은 그대로 적용
        assertThat(derive(COMPLETED, null, null, null)).isEqualTo(COMPLETED);
        assertThat(derive(COMPLETED, null, null, TODAY.plusDays(10))).isEqualTo(NEAR_EXPIRY);
        assertThat(derive(EXPIRED, null, null, TODAY.plusDays(90))).isEqualTo(COMPLETED);
        // 신청일만 있고 취득일이 없으면 저장돼 있던 만료임박은 진행중으로 (2026-11-17 review H3)
        assertThat(derive(NEAR_EXPIRY, TODAY.minusDays(28), null, TODAY.plusDays(19))).isEqualTo(IN_PROGRESS);
        assertThat(derive(COMPLETED, TODAY.minusDays(28), null, TODAY.plusDays(19))).isEqualTo(NEAR_EXPIRY);
    }
}
