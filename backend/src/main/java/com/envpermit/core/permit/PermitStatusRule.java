package com.envpermit.core.permit;

import com.envpermit.core.permit.PermitItem.Status;
import java.time.LocalDate;

/**
 * 인허가 상태 규칙. 날짜가 정할 수 있으면 날짜로 정하고, 날짜가 없을 때만 사람이 고른 상태를 쓴다.
 * 취득일이 오늘 이전이면 취득, 취득 뒤 만료일이 지났으면 만료, 30일 이내면 만료임박.
 * 취득 전이면 신청일이 오늘 이전일 때 진행중, 미래면 미신청.
 */
public final class PermitStatusRule {

    /** 기한 임박(만료임박) 기준: 오늘부터 이 일수 안. 프론트 terms.ts DUE_SOON_DAYS와 같은 값. */
    public static final int DUE_SOON_DAYS = 30;

    private PermitStatusRule() {
    }

    public static Status derive(Status chosen, LocalDate applied, LocalDate acquired, LocalDate expiry,
                                LocalDate today) {
        // Without 취득일: an explicit 취득완료 pick counts; a stored 만료임박·만료 (computed, never picked) counts only
        // when no 신청일 says the permit is still in progress.
        boolean hasAcquired = acquired != null
                ? !acquired.isAfter(today)
                : chosen == Status.COMPLETED
                        || (applied == null && (chosen == Status.NEAR_EXPIRY || chosen == Status.EXPIRED));
        if (hasAcquired) {
            if (expiry == null) return Status.COMPLETED;
            if (expiry.isBefore(today)) return Status.EXPIRED;
            return expiry.isAfter(today.plusDays(DUE_SOON_DAYS)) ? Status.COMPLETED : Status.NEAR_EXPIRY;
        }
        if (applied != null) return applied.isAfter(today) ? Status.NOT_APPLIED : Status.IN_PROGRESS;
        return chosen == Status.IN_PROGRESS ? Status.IN_PROGRESS : Status.NOT_APPLIED;
    }

    static Status derive(PermitItem p, Status chosen, LocalDate today) {
        return derive(chosen, p.getApplicationDate(), p.getAcquisitionDate(), p.getExpiryDate(), today);
    }
}
