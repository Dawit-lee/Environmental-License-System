package com.envpermit.law;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.envpermit.core.company.Company;
import com.envpermit.core.company.CompanyRepository;
import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import com.envpermit.support.FixtureLawApi;
import com.envpermit.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Daily batch against the recorded 법제처 days: 2026-10-01 (폐기물관리법 시행규칙 MST 290881 공포, 현행)
 * and 2026-10-02 (612 rows over 7 pages; the same law's older MST 289271 comes back as 연혁).
 */
class AmendmentBatchTest extends IntegrationTest {

    static final String RULE = "008567"; // 폐기물관리법 시행규칙

    @Autowired AmendmentBatch batch;
    @Autowired LawApiClient api;
    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;

    FixtureLawApi fake;
    long permit;

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        fake = (FixtureLawApi) api;
        fake.reset();
        // catalog as it was before MST 290881 (the version in force from 2026-09-18)
        catalogAt(289271L, "2026-08-31", "2026-09-18");
        today("2026-10-03");

        long a = companies.save(new Company("A환경")).getId();
        users.save(new User("kim@a.com", encoder.encode("pw"), "김에이", User.Role.USER, a));
        String kim = login("kim@a.com");
        long project = id(call(kim, "/api/projects",
                "{\"name\":\"광명 소각시설\",\"facilityType\":\"INCINERATION\",\"location\":\"광명시\"}"));
        permit = id(call(kim, "/api/projects/" + project + "/permits",
                "{\"name\":\"폐기물처리시설 설치승인\",\"category\":\"FACILITY\",\"status\":\"IN_PROGRESS\"}"));
        // only the 법률 is linked; its 시행규칙 is watched through parent_id
        long act = jdbc.queryForObject("SELECT id FROM law_catalog WHERE law_id = '001771'", Long.class);
        call(kim, "/api/permit-items/" + permit + "/law-links", "{\"lawCatalogId\":%d}".formatted(act));
    }

    @AfterEach
    void restoreSeed() {
        catalogAt(290881L, "2026-10-01", "2026-10-02"); // V31 values; other tests read them
    }

    @Test
    void stores_a_watched_amendment_with_its_changed_articles_and_old_new_text() {
        cursor("2026-09-30");
        today("2026-10-02");

        batch.run();

        Map<String, Object> am = jdbc.queryForMap("SELECT a.*, a.old_new_raw::text AS raw, c.law_id FROM law_amendment a "
                + "JOIN law_catalog c ON c.id = a.law_catalog_id");
        assertThat(am).containsEntry("law_id", RULE).containsEntry("mst", 290881L)
                .containsEntry("promulgated_on", Date.valueOf("2026-10-01"))
                .containsEntry("enforced_on", Date.valueOf("2026-10-02"))
                .containsEntry("revision_type", "일부개정")
                .containsEntry("detected_on", Date.valueOf("2026-10-01"));
        assertThat((String) am.get("reason")).contains("공소청압수물사무규칙");
        assertThat((String) am.get("raw")).contains("OldAndNewService", "신조문목록", "공소청압수물사무규칙")
                .doesNotContain("test-oc");
        assertThat(jdbc.queryForList("SELECT article_no, jo_code, change_reason, article_enforced_on FROM law_amendment_article"))
                .containsExactly(Map.of("article_no", "제10조", "jo_code", "001000", "change_reason", "개정",
                        "article_enforced_on", Date.valueOf("2026-10-02")));
        assertThat(jdbc.queryForMap("SELECT last_mst, last_promulgated_on, last_enforced_on FROM law_catalog WHERE law_id = ?", RULE))
                .containsEntry("last_mst", 290881L)
                .containsEntry("last_promulgated_on", Date.valueOf("2026-10-01"))
                .containsEntry("last_enforced_on", Date.valueOf("2026-10-02"));
        assertThat(cursorDate()).isEqualTo("2026-10-01");
        assertThat(fake.calls).contains("lsHstInf:20261001:1", "lsHstInf:20261001:2"); // 197 rows → 2 pages
    }

    @Test
    void running_the_same_date_twice_gives_the_same_result() {
        cursor("2026-09-30");
        today("2026-10-02");
        batch.run();
        String first = snapshot();

        cursor("2026-09-30");
        batch.run();

        assertThat(snapshot()).isEqualTo(first);
        assertThat(count("law_amendment")).isEqualTo(1);
    }

    @Test
    void a_cursor_three_days_behind_processes_all_three_days() {
        cursor("2026-09-29"); // today 2026-10-03 → 09-30, 10-01, 10-02

        batch.run();

        assertThat(cursorDate()).isEqualTo("2026-10-02");
        assertThat(fake.calls).contains("lsHstInf:20260930:1", "lsHstInf:20261001:2", "lsHstInf:20261002:7");
        assertThat(jdbc.queryForList("SELECT mst FROM law_amendment", Long.class)).containsExactly(290881L);
    }

    @Test
    void old_history_rows_never_create_amendments() {
        catalogAt(284000L, "2026-03-01", "2026-03-01"); // older than the 연혁 row 289271 on 10-02
        cursor("2026-10-01");

        batch.run();

        assertThat(fake.calls).contains("lsHstInf:20261002:7");
        assertThat(count("law_amendment")).isZero();
        assertThat(jdbc.queryForObject("SELECT last_mst FROM law_catalog WHERE law_id = ?", Long.class, RULE)).isEqualTo(284000L);
    }

    @Test
    void a_failing_day_rolls_back_and_the_cursor_stops_before_it() {
        cursor("2026-09-29");
        fake.failing.add("law:290881");

        batch.run();

        assertThat(cursorDate()).isEqualTo("2026-09-30");
        assertThat(failures()).isEqualTo(1);
        assertThat(count("law_amendment")).isZero();
        assertThat(jdbc.queryForObject("SELECT last_mst FROM law_catalog WHERE law_id = ?", Long.class, RULE)).isEqualTo(289271L);
        assertThat(fake.calls).noneMatch(c -> c.startsWith("lsHstInf:20261002"));

        fake.failing.clear();
        batch.run();

        assertThat(cursorDate()).isEqualTo("2026-10-02");
        assertThat(failures()).isZero();
        assertThat(count("law_amendment")).isEqualTo(1);
    }

    @Test
    void one_run_processes_at_most_31_days() {
        cursor("2026-08-01");

        batch.run();

        assertThat(cursorDate()).isEqualTo("2026-09-01");
    }

    @Test
    void first_run_starts_from_yesterday_without_back_filling() {
        batch.run();

        assertThat(cursorDate()).isEqualTo("2026-10-02");
        assertThat(fake.calls).isEmpty();
    }

    @Test
    void failed_old_new_is_stored_as_null_and_fetched_on_the_next_run() {
        cursor("2026-09-30");
        today("2026-10-02");
        fake.failing.add("oldAndNew:290881");

        batch.run();

        assertThat(jdbc.queryForObject("SELECT old_new_raw::text FROM law_amendment", String.class)).isNull();
        assertThat(count("law_amendment_article")).isEqualTo(1);

        fake.failing.clear();
        batch.run();

        assertThat(jdbc.queryForObject("SELECT old_new_raw::text FROM law_amendment", String.class)).contains("OldAndNewService");
    }

    @Test
    void laws_linked_only_to_completed_permits_are_not_watched() {
        jdbc.update("UPDATE permit_item SET status = 'COMPLETED' WHERE id = ?", permit);
        cursor("2026-09-30");
        today("2026-10-02");

        batch.run();

        assertThat(count("law_amendment")).isZero();
        assertThat(cursorDate()).isEqualTo("2026-10-01");
    }

    // --- helpers

    private void today(String ymd) {
        clock.set(LocalDate.parse(ymd).atTime(6, 0).atZone(clock.getZone()).toInstant());
    }

    private void cursor(String ymd) {
        jdbc.update("INSERT INTO batch_cursor (job_name, last_success_date, consecutive_failures) VALUES (?, ?, 0) "
                + "ON CONFLICT (job_name) DO UPDATE SET last_success_date = EXCLUDED.last_success_date",
                AmendmentBatch.JOB, LocalDate.parse(ymd));
    }

    private String cursorDate() {
        return jdbc.queryForObject("SELECT last_success_date::text FROM batch_cursor WHERE job_name = ?", String.class, AmendmentBatch.JOB);
    }

    private int failures() {
        return jdbc.queryForObject("SELECT consecutive_failures FROM batch_cursor WHERE job_name = ?", Integer.class, AmendmentBatch.JOB);
    }

    private void catalogAt(long mst, String promulgated, String enforced) {
        jdbc.update("UPDATE law_catalog SET last_mst = ?, last_promulgated_on = ?, last_enforced_on = ? WHERE law_id = ?",
                mst, LocalDate.parse(promulgated), LocalDate.parse(enforced), RULE);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private String snapshot() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT a.law_catalog_id, a.mst, a.promulgated_on, a.enforced_on, "
                + "a.revision_type, a.reason, a.old_new_raw::text, a.detected_on, r.article_no, r.jo_code, r.change_reason, "
                + "r.article_enforced_on FROM law_amendment a JOIN law_amendment_article r ON r.amendment_id = a.id ORDER BY r.jo_code");
        return rows + "|" + jdbc.queryForList("SELECT last_mst FROM law_catalog ORDER BY id", Long.class);
    }

    private String login(String email) throws Exception {
        return JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"pw\"}".formatted(email)))
                .andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    private long id(String body) {
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private String call(String token, String url, String body) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }
}
