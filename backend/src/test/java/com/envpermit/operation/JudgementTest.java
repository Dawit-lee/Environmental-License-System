package com.envpermit.operation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JudgementTest {

    // design §3.3: value, lower, limit, warn ratio, 불검출 → level. Empty limit = no limit in force; empty lower = upper-only.
    @ParameterizedTest
    @CsvSource({
            "30,     , 30, 0.80, false, NEAR",     // value = limit: "이하" 기준, not exceeded
            "30.0,   , 30, 0.80, false, NEAR",     // scale does not matter
            "30.01,  , 30, 0.80, false, EXCEEDED",
            "45,     , 30, 0.80, false, EXCEEDED",
            "24,     , 30, 0.80, false, NEAR",     // value = limit × 0.8
            "23.99,  , 30, 0.80, false, NORMAL",   // just below
            "0,      , 30, 0.80, false, NORMAL",
            "0.08,  , 0.1, 0.80, false, NEAR",     // exact decimals: 0.1 × 0.8 is 0.08, not 0.08000000000000002
            "45,     , 30, 0.80, true,  NORMAL",   // 불검출 wins over the number
            "45,     ,   , ,     false, NO_LIMIT",
            "45,     ,   , ,     true,  NO_LIMIT", // no limit wins over 불검출
            "24,     , 30, 0.90, false, NORMAL",   // warn ratio changed: 27 is the line now
            "27,     , 30, 0.90, false, NEAR",
            "15,     , 30, 0.50, false, NEAR",
            "14.999, , 30, 0.50, false, NORMAL",
            // range limit 4 ~ 9 (made-up): width 5, 근접 band 5 × 0.2 = 1 at each end
            "6.5,   4, 9, 0.80, false, NORMAL",
            "5.01,  4, 9, 0.80, false, NORMAL",
            "5,     4, 9, 0.80, false, NEAR",      // lower bound + band
            "4,     4, 9, 0.80, false, NEAR",      // value = lower bound: "이상" 기준, not exceeded
            "3.99,  4, 9, 0.80, false, EXCEEDED",  // below the lower bound
            "0,     4, 9, 0.80, false, EXCEEDED",
            "7.99,  4, 9, 0.80, false, NORMAL",
            "8,     4, 9, 0.80, false, NEAR",      // upper bound − band
            "9,     4, 9, 0.80, false, NEAR",
            "9.01,  4, 9, 0.80, false, EXCEEDED",
            "5.5,   4, 9, 0.90, false, NORMAL",    // warn ratio changed: band 0.5
            "4.5,   4, 9, 0.90, false, NEAR",
            "0,     0, 9, 0.80, false, NEAR",      // lower bound 0 is still a bound
            "3,     4, 9, 0.80, true,  NORMAL"})   // 불검출 wins, as for an upper-only limit
    void level_by_limit_and_warn_ratio(BigDecimal value, BigDecimal lower, BigDecimal limit, BigDecimal ratio,
                                       boolean belowDetection, Judgement.Level expected) {
        assertThat(Judgement.judge(value, lower, limit, ratio, belowDetection)).isEqualTo(expected);
    }
}
