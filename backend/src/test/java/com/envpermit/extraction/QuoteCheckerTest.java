package com.envpermit.extraction;

import static com.envpermit.obligation.ObligationPayload.QuoteCheck.MISMATCH;
import static com.envpermit.obligation.ObligationPayload.QuoteCheck.UNVERIFIABLE;
import static com.envpermit.obligation.ObligationPayload.QuoteCheck.VERIFIED;
import static org.assertj.core.api.Assertions.assertThat;

import com.envpermit.obligation.ObligationPayload.QuoteCheck;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** design §3.6: whitespace-insensitive, NFC, page_no ±1; all three pages blank → UNVERIFIABLE. */
class QuoteCheckerTest {

    static final String DECOMPOSED_HAN = "한"; // 한 as jamo (NFD)

    static Stream<Arguments> cases() {
        Map<Integer, String> doc = Map.of(
                1, "협의의견\n3-2. 공사 중 소음·진동을 분기 1회 측정하고",
                2, "결과를 관할 지자체에 제출할 것.\n3-3. 비산먼지 저감",
                3, "",
                5, "대기질 1. 굴뚝 TMS를 상시 운영" + DECOMPOSED_HAN + "다.");
        return Stream.of(
                Arguments.of("exact", "공사 중 소음·진동을 분기 1회 측정하고", 1, doc, VERIFIED),
                Arguments.of("broken spacing in text layer", "공사중 소음 · 진동을분기1회 측정하고", 1, doc, VERIFIED),
                Arguments.of("crosses into next page", "분기 1회 측정하고 결과를 관할 지자체에 제출할 것.", 1, doc, VERIFIED),
                Arguments.of("model gave the next page number", "공사 중 소음·진동", 2, doc, VERIFIED),
                Arguments.of("two pages away is not checked", "공사 중 소음·진동", 3, doc, MISMATCH),
                Arguments.of("NFD vs NFC", "상시 운영한다.", 5, doc, VERIFIED),
                Arguments.of("hallucinated wording", "공사 중 소음을 매월 측정하고", 1, doc, MISMATCH),
                Arguments.of("blank quote", "  ", 1, doc, MISMATCH),
                Arguments.of("no page number", "공사 중 소음·진동", null, doc, MISMATCH),
                Arguments.of("scan: no text on page±1", "무엇이든", 8, doc, UNVERIFIABLE),
                Arguments.of("scan: blank pages count as no text", "무엇이든", 4, Map.of(3, " ", 4, "\n"), UNVERIFIABLE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void checks(String name, String quote, Integer pageNo, Map<Integer, String> pages, QuoteCheck expected) {
        assertThat(QuoteChecker.check(quote, pageNo, pages)).isEqualTo(expected);
    }
}
