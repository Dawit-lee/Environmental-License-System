package com.envpermit.obligation;

import java.time.LocalDate;

public enum Frequency {
    ONCE(0), MONTHLY(1), QUARTERLY(3), SEMIANNUAL(6), ANNUAL(12), OTHER(0);

    private final int months;

    Frequency(int months) {
        this.months = months;
    }

    /**
     * Next due date after "이행 완료"; null when there is no automatic next (ONCE closes, OTHER is typed in).
     * plusMonths clamps to the month's last day (1/31 → 2/28), and the next cycle counts from that date.
     */
    public LocalDate next(LocalDate current) {
        return months == 0 ? null : current.plusMonths(months);
    }
}
