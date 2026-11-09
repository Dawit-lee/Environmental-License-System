package com.envpermit.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** design §3.4 case table. Changing a row means a data migration (④ version diff depends on these keys). */
class CitationKeyTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            // design examples
            "협의의견 3-2항   | 3-2",
            "허가조건 제5호   | 5",
            "3.2.①           | 3-2-1",
            "(가)             | 가",
            // full-width digits, circled / parenthesized numbers
            "１２             | 12",
            "제３조 ②         | 3조-2",
            "⑴               | 1",
            "(1)              | 1",
            "1)               | 1",
            "가)              | 가",
            // separators and noise
            "3·2              | 3-2",
            "제3항 제2호      | 3-2",
            "' 3 - 2 . '      | 3-2",
            "(가)목           | 가",
            "협의의견 (3)-②   | 3-2",
            // per-field numbering keeps the 평가항목 name as prefix
            "대기질 1항       | 대기질-1",
            "수질 제2호       | 수질-2",
            "동·식물상 3-1    | 동·식물상-3-1",
            "소음·진동 (1)    | 소음·진동-1",
            // nothing left → caller makes a synthetic key
            "협의의견         | NULL",
            "NULL             | NULL",
            "'   '            | NULL",
    })
    void normalizes(String raw, String expected) {
        assertThat(CitationKey.normalize(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"3.2.①", "협의의견 3-2항", "대기질 1항"})
    void is_deterministic_and_idempotent(String raw) {
        String once = CitationKey.normalize(raw);
        assertThat(CitationKey.normalize(raw)).isEqualTo(once);
        assertThat(CitationKey.normalize(once)).isEqualTo(once);
    }

    @ParameterizedTest
    @CsvSource({"4, 1, P4-1", "12, 3, P12-3"})
    void synthetic_key_is_page_and_order(int page, int seq, String expected) {
        assertThat(CitationKey.synthetic(page, seq)).isEqualTo(expected);
    }
}
