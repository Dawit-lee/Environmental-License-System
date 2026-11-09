package com.envpermit.law;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ArticleNoTest {

    @ParameterizedTest
    @CsvSource({
            "10, 제10조",
            "제10조, 제10조",
            "제 10 조, 제10조",
            "10조, 제10조",
            "001000, 제10조", // 6-digit JO code (history / JO param)
            "001002, 제10조의2",
            "제10조의2, 제10조의2",
            "제10조의 2, 제10조의2",
            "10의2, 제10조의2",
            "제029조, 제29조",
            "별표3, 별표3",
            "별표 3, 별표3",
            "별표3의2, 별표3의2",
    })
    void normalizes_to_one_spelling(String raw, String expected) {
        assertThat(ArticleNo.normalize(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "  ")
    void blank_means_whole_law(String raw) {
        assertThat(ArticleNo.normalize(raw)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"부칙", "제조", "0", "제10항", "abc"})
    void rejects_other_text(String raw) {
        assertThatThrownBy(() -> ArticleNo.normalize(raw)).isInstanceOf(IllegalArgumentException.class);
    }
}
