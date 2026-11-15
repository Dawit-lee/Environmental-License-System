package com.envpermit.schedule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Pure date rules of 설계 §2.3~2.5: relative dates, delay against the baseline, and 공정률. Nothing here is stored. */
final class ScheduleDates {

    /** Inclusive dates; a milestone has start = end. */
    record Range(LocalDate start, LocalDate end) {
    }

    record Offset(int offsetDays, Integer durationDays) {
    }

    /** One weighted item for 공정률: start/end are the baseline when there is one, else the current plan. */
    record Weighted(BigDecimal weight, LocalDate start, LocalDate end, int progress) {
    }

    /** Percentages with one decimal; weightTotal ≠ 100 is only a warning (the sums are divided by it). */
    record Progress(BigDecimal planned, BigDecimal actual, BigDecimal weightTotal) {
    }

    private ScheduleDates() {
    }

    /**
     * Base date ± offset → dates; null while the base date is unknown. Month offsets clamp to the month's last day
     * (1/31 + 1개월 = 2/28). A task ends at the offset and lasts durationDays (counting both ends).
     */
    static Range resolve(LocalDate base, Integer offsetDays, Integer offsetMonths, Integer durationDays, boolean milestone) {
        if (base == null) return null;
        LocalDate end = offsetMonths != null ? base.plusMonths(offsetMonths) : base.plusDays(offsetDays);
        return new Range(milestone ? end : end.minusDays(durationDays - 1), end);
    }

    /** [기준일에 연결]: the offset (and task length) that gives back the current dates. */
    static Offset reverse(LocalDate base, LocalDate start, LocalDate end, boolean milestone) {
        return new Offset((int) ChronoUnit.DAYS.between(base, end),
                milestone ? null : (int) ChronoUnit.DAYS.between(start, end) + 1);
    }

    /** 기준 대비 연기: current end − baseline end (negative = earlier); null before the first baseline or without dates. */
    static Integer slipDays(LocalDate end, LocalDate baselineEnd) {
        return end == null || baselineEnd == null ? null : (int) ChronoUnit.DAYS.between(baselineEnd, end);
    }

    /** A change needs a reason when it pushes the end later and past the baseline end (설계 §2.4). */
    static boolean needsReason(LocalDate baselineEnd, LocalDate oldEnd, LocalDate newEnd) {
        return baselineEnd != null && newEnd != null && newEnd.isAfter(baselineEnd)
                && (oldEnd == null || newEnd.isAfter(oldEnd));
    }

    /**
     * 지연 현황 days: how far the (expected) finish is behind the original end — the baseline end, or the current end
     * before any baseline. The expected finish is the actual end once done, today while overdue, else the current end.
     */
    static int delayDays(LocalDate end, LocalDate baselineEnd, LocalDate actualEnd, LocalDate today) {
        if (end == null) return 0;
        LocalDate finish = actualEnd != null ? actualEnd : end.isBefore(today) ? today : end;
        LocalDate original = baselineEnd != null ? baselineEnd : end;
        return (int) Math.max(0, ChronoUnit.DAYS.between(original, finish));
    }

    /** 예정 = Σ 보할 × elapsed share of the plan by today; 진행 = Σ 보할 × progress. Null when no item has a weight. */
    static Progress progress(List<Weighted> items, LocalDate today) {
        BigDecimal total = BigDecimal.ZERO;
        double planned = 0;
        double actual = 0;
        for (Weighted w : items) {
            if (w.weight() == null) continue;
            total = total.add(w.weight());
            planned += w.weight().doubleValue() * elapsed(w.start(), w.end(), today);
            actual += w.weight().doubleValue() * w.progress() / 100.0;
        }
        if (total.signum() == 0) return null;
        return new Progress(pct(planned, total), pct(actual, total), new BigDecimal(total.stripTrailingZeros().toPlainString()));
    }

    private static double elapsed(LocalDate start, LocalDate end, LocalDate today) {
        if (start == null) return 0;
        double share = (ChronoUnit.DAYS.between(start, today) + 1.0) / (ChronoUnit.DAYS.between(start, end) + 1.0);
        return Math.min(1, Math.max(0, share));
    }

    private static BigDecimal pct(double sum, BigDecimal total) {
        return BigDecimal.valueOf(sum * 100 / total.doubleValue()).setScale(1, RoundingMode.HALF_UP);
    }
}
