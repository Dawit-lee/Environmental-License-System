package com.envpermit.law;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LegalBasisTest {

    @ParameterizedTest
    @CsvSource(nullValues = "-", value = {
            "폐기물관리법 시행규칙 제29조, 폐기물관리법시행규칙, 제29조",
            "폐기물관리법 시행규칙 제 29 조의 2, 폐기물관리법시행규칙, 제29조의2",
            "대기환경보전법 제23조 제1항, 대기환경보전법, 제23조",
            "「폐기물관리법」 제25조제3항, 폐기물관리법, 제25조",
            "폐기물관리법 시행규칙 별표 5, 폐기물관리법시행규칙, 별표5",
            "환경영향평가법, 환경영향평가법, -",
            "  하수도법  , 하수도법, -",
    })
    void splits_law_name_key_and_article(String raw, String key, String articleNo) {
        LawCatalogService.Ref ref = LawCatalogService.ref(raw);
        assertThat(ref.key()).isEqualTo(key);
        assertThat(ref.articleNo()).isEqualTo(articleNo);
    }
}
