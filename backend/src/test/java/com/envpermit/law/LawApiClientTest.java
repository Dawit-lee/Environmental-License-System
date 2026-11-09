package com.envpermit.law;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.envpermit.support.FixtureLawApi;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Parsing against bodies recorded from the real 법제처 API. */
class LawApiClientTest {

    final ObjectMapper json = new ObjectMapper();

    @Test
    void several_results_come_as_an_array() throws Exception {
        List<LawApiClient.LawRow> rows = LawApiClient.parse(json, FixtureLawApi.read("/lawapi/search-waste.json"));

        assertThat(rows).hasSize(9);
        assertThat(rows).filteredOn(r -> r.lawId().equals("008567")).singleElement().satisfies(r -> {
            assertThat(r.name()).isEqualTo("폐기물관리법 시행규칙");
            assertThat(r.mst()).isEqualTo(290881);
            assertThat(r.kindName()).isEqualTo("기후에너지환경부령");
            assertThat(r.promulgatedOn()).isEqualTo(LocalDate.of(2026, 10, 1));
            assertThat(r.enforcedOn()).isEqualTo(LocalDate.of(2026, 10, 2));
            assertThat(r.status()).isEqualTo("현행");
            assertThat(r.shortName()).isNull();
        });
    }

    @Test
    void a_single_result_comes_as_an_object() throws Exception {
        List<LawApiClient.LawRow> rows = LawApiClient.parse(json, FixtureLawApi.read("/lawapi/search-eia-rule.json"));

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.lawId()).isEqualTo("009169");
            assertThat(r.name()).isEqualTo("환경영향평가법 시행규칙");
        });
    }

    @Test
    void no_result_has_no_law_key() throws Exception {
        assertThat(LawApiClient.parse(json, FixtureLawApi.read("/lawapi/search-empty.json"))).isEmpty();
    }

    @Test
    void unexpected_shape_fails() {
        assertThatThrownBy(() -> LawApiClient.parse(json, "{\"error\":\"bad key\"}")).isInstanceOf(Exception.class);
    }

    @Test
    void masks_oc_in_links_and_messages() {
        assertThat(LawApiClient.maskOc("/DRF/lawService.do?OC=secret123&target=law&MST=1"))
                .isEqualTo("/DRF/lawService.do?OC=***&target=law&MST=1");
        assertThat(LawApiClient.maskOc("I/O error on GET request for \"https://x/?OC=k9\": timeout"))
                .isEqualTo("I/O error on GET request for \"https://x/?OC=***\": timeout");
        assertThat(LawApiClient.maskOc("{\"link\":\"?OC=abc\"}")).isEqualTo("{\"link\":\"?OC=***\"}");
    }

    @Test
    void recorded_fixtures_hold_no_key() throws Exception {
        try (var files = java.nio.file.Files.list(java.nio.file.Path.of(FixtureLawApi.class.getResource("/lawapi").toURI()))) {
            assertThat(files).hasSizeGreaterThan(10).allSatisfy(f ->
                    assertThat(java.nio.file.Files.readString(f)).doesNotContainPattern("OC=(?!\\*\\*\\*)"));
        }
        assertThat(FixtureLawApi.read("/lawapi/hst-20261001-p1.json")).contains("OC=***"); // links were scrubbed, not dropped
    }

    @Test
    void law_body_gives_the_changed_articles_and_the_reason() throws Exception {
        LawApiClient.LawBody body = LawApiClient.parseBody(json, FixtureLawApi.read("/lawapi/law-290881.json"));

        // 157 articles, only 제10조 has 조문변경여부=Y (matches lsJoHstInf 001000)
        assertThat(body.changed()).containsExactly(
                new LawApiClient.ChangedArticle("제10조", "001000", "개정", LocalDate.of(2026, 10, 2)));
        assertThat(body.reason()).startsWith("[일부개정]").contains("공소청압수물사무규칙");
    }

    @Test
    void change_list_rows_mix_current_and_history() throws Exception {
        List<LawApiClient.LawRow> rows = LawApiClient.parse(json, FixtureLawApi.read("/lawapi/hst-20261002-p6.json"));

        assertThat(rows).hasSize(100).extracting(LawApiClient.LawRow::status).contains("현행", "연혁");
        assertThat(rows).filteredOn(r -> r.lawId().equals("008567")).singleElement()
                .satisfies(r -> assertThat(r.mst()).isEqualTo(289271));
    }
}
