package com.envpermit.law;

import static org.assertj.core.api.Assertions.assertThat;

import com.envpermit.law.LawApiClient.LawRow;
import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AmendmentFilterTest {

    @ParameterizedTest(name = "{0} MST {1} vs last {2}/{3} → {4}")
    @CsvSource(value = {
            "현행, 290881, 289271, null, true",
            "현행, 289271, 289271, null, false", // same version again
            "연혁, 289271, 284000, null, false", // old version re-listed (seen on regDt 20261002)
            "연혁, 290881, 289271, null, false",
            "현행, 290881, null, 2026-08-31, true", // no last_mst → compare 공포일
            "현행, 290881, null, 2026-10-01, false",
            "현행, 290881, null, null, true",
    }, nullValues = "null")
    void only_a_newer_current_version_is_an_amendment(String status, long mst, Long lastMst, LocalDate lastPromulgated,
                                                      boolean expected) {
        LawRow row = new LawRow("008567", mst, "폐기물관리법 시행규칙", "기후에너지환경부령", null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), "일부개정", status);

        assertThat(AmendmentBatch.isNewVersion(row, new AmendmentBatch.Watched(1L, lastMst, lastPromulgated)))
                .isEqualTo(expected);
    }
}
