package com.envpermit.operation;

import java.math.BigDecimal;

/**
 * F2 §3.3 판정: one measured value against its permit limit ("이하" 기준). The server result is the one that is
 * stored; the 운영 tab repeats the same rule only to show it while typing (frontend {@code judge}).
 */
final class Judgement {

    enum Level { NORMAL, NEAR, EXCEEDED, NO_LIMIT }

    private Judgement() {
    }

    /**
     * @param limit     null when no limit is in force on the measured day
     * @param warnRatio 0 &lt; r &lt; 1; ignored without a limit
     */
    static Level judge(BigDecimal value, BigDecimal limit, BigDecimal warnRatio, boolean belowDetection) {
        if (limit == null) {
            return Level.NO_LIMIT;
        }
        if (belowDetection) {
            return Level.NORMAL;
        }
        if (value.compareTo(limit) > 0) {
            return Level.EXCEEDED;
        }
        return value.compareTo(limit.multiply(warnRatio)) >= 0 ? Level.NEAR : Level.NORMAL; // value = limit is NEAR
    }
}
