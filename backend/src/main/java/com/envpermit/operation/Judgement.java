package com.envpermit.operation;

import java.math.BigDecimal;

/**
 * F2 §3.3 판정: one measured value against its permit limit ("이하" 기준). The server result is the one that is
 * stored; the 운영 tab repeats the same rule only to show it while typing (frontend {@code judge}).
 *
 * <p>A range limit (pH: lower ~ upper) is exceeded outside the range. Its 근접 band is the same share of the
 * allowed width at both ends: within (upper − lower) × (1 − warnRatio) of a bound. With no lower bound the width
 * is the upper limit itself, which is the plain rule value ≥ limit × warnRatio.
 */
final class Judgement {

    enum Level { NORMAL, NEAR, EXCEEDED, NO_LIMIT }

    private Judgement() {
    }

    /**
     * @param lower     lower bound of a range limit; null for an upper-only limit
     * @param limit     upper bound; null when no limit is in force on the measured day
     * @param warnRatio 0 &lt; r &lt; 1; ignored without a limit
     */
    static Level judge(BigDecimal value, BigDecimal lower, BigDecimal limit, BigDecimal warnRatio, boolean belowDetection) {
        if (limit == null) {
            return Level.NO_LIMIT;
        }
        if (belowDetection) {
            return Level.NORMAL;
        }
        if (value.compareTo(limit) > 0) {
            return Level.EXCEEDED;
        }
        if (lower == null) {
            return value.compareTo(limit.multiply(warnRatio)) >= 0 ? Level.NEAR : Level.NORMAL; // value = limit is NEAR
        }
        if (value.compareTo(lower) < 0) {
            return Level.EXCEEDED;
        }
        BigDecimal band = limit.subtract(lower).multiply(BigDecimal.ONE.subtract(warnRatio));
        return value.compareTo(limit.subtract(band)) >= 0 || value.compareTo(lower.add(band)) <= 0 ? Level.NEAR : Level.NORMAL;
    }
}
