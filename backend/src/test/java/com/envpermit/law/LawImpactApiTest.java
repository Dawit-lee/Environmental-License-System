package com.envpermit.law;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.envpermit.core.company.Company;
import com.envpermit.core.company.CompanyRepository;
import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import com.envpermit.support.FixtureLawApi;
import com.envpermit.support.InMemoryMailer;
import com.envpermit.support.InMemoryMailer.Mail;
import com.jayway.jsonpath.JsonPath;
import com.envpermit.support.IntegrationTest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Batch → impacts → API/mail, on the recorded 2026-10-01 day: 폐기물관리법 시행규칙 (MST 290881, 공포 10-01,
 * 시행 10-02, only 제10조 changed). Links are made on 09-20, before the 공포.
 */
class LawImpactApiTest extends IntegrationTest {

    static final String ACT = "001771", RULE = "008567"; // 폐기물관리법 / 시행규칙

    @Autowired AmendmentBatch batch;
    @Autowired LawApiClient api;
    @Autowired InMemoryMailer mailer;
    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;

    FixtureLawApi fake;
    String kim, lee, other;
    long project;

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        fake = (FixtureLawApi) api;
        fake.reset();
        mailer.clear();
        catalogAt(289271L, "2026-08-31", "2026-09-18");
        today("2026-09-20");

        long a = companies.save(new Company("A환경")).getId();
        long b = companies.save(new Company("B건설")).getId();
        users.save(new User("kim@a.com", encoder.encode("pw"), "김책임", User.Role.USER, a));
        users.save(new User("lee@a.com", encoder.encode("pw"), "이팀원", User.Role.USER, a));
        users.save(new User("boss@a.com", encoder.encode("pw"), "관리자", User.Role.ADMIN, a));
        users.save(new User("x@b.com", encoder.encode("pw"), "타회사", User.Role.USER, b));
        kim = login("kim@a.com");
        lee = login("lee@a.com");
        kim = login("kim@a.com");
        other = login("x@b.com");
        project = id(send(kim, "/api/projects",
                "{\"name\":\"가온 소각시설\",\"facilityType\":\"INCINERATION\",\"location\":\"가온시\"}"));
        long leeId = users.findByEmail("lee@a.com").orElseThrow().getId();
        send(kim, "/api/projects/" + project + "/members", "{\"userId\":%d}".formatted(leeId));
        cursor("2026-09-30");
    }

    @AfterEach
    void restoreSeed() {
        catalogAt(290881L, "2026-10-01", "2026-10-02"); // V31 values; other tests read them
    }

    @Test
    void each_in_play_permit_gets_one_impact_listed_direct_first() throws Exception {
        long viaAct = permit("설치승인", ACT, null);          // 시행규칙 of the linked 법률 → DIRECT
        long ref = permit("변경신고", RULE, "제11조");       // another article → REFERENCE
        permit("운영허가", RULE, "제10조");        // my article → DIRECT
        long both = permit("사용개시", RULE, "제11조");
        link(both, ACT, null);                               // REFERENCE + DIRECT → DIRECT only
        long done = permit("완료된 허가", RULE, null);
        jdbc.update("UPDATE permit_item SET status = 'COMPLETED' WHERE id = ?", done);
        long late = permit("늦게 연결", "", null);
        today("2026-10-02");
        link(late, RULE, null);                              // linked after the 공포 → no impact

        batch.run();

        ok(kim, "/api/law-impacts")
                .andExpect(jsonPath("$[*].permitName", contains("설치승인", "운영허가", "사용개시", "변경신고")))
                .andExpect(jsonPath("$[*].level", contains("DIRECT", "DIRECT", "DIRECT", "REFERENCE")))
                .andExpect(jsonPath("$[0].lawName").value("폐기물관리법 시행규칙"))
                .andExpect(jsonPath("$[0].status").value("NEW"))
                .andExpect(jsonPath("$[0].projectName").value("가온 소각시설"))
                .andExpect(jsonPath("$[0].enforcedOn").value("2026-10-02"))
                .andExpect(jsonPath("$[0].createdAt").value("2026-10-01T21:00:00Z"));
        ok(kim, "/api/law-impacts?level=REFERENCE").andExpect(jsonPath("$[*].permitItemId", contains((int) ref)));
        ok(kim, "/api/law-impacts?projectId=" + project + "&status=REVIEWED").andExpect(jsonPath("$", hasSize(0)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM permit_law_impact WHERE permit_item_id IN (?, ?)",
                Integer.class, done, late)).isZero();

        batch.run(); // nothing new; the same day re-run adds nothing
        cursor("2026-09-30");
        batch.run();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM permit_law_impact", Integer.class)).isEqualTo(4);
    }

    @Test
    void detail_highlights_my_article_and_shows_the_old_new_table() throws Exception {
        permit("운영허가", RULE, "제10조");
        today("2026-10-02");
        batch.run();
        long id = firstImpact();

        ok(kim, "/api/law-impacts/" + id)
                .andExpect(jsonPath("$.impact.level").value("DIRECT"))
                .andExpect(jsonPath("$.impact.revisionType").value("일부개정"))
                .andExpect(jsonPath("$.impact.promulgatedOn").value("2026-10-01"))
                .andExpect(jsonPath("$.reason", containsString("공소청압수물사무규칙")))
                .andExpect(jsonPath("$.articles[0].articleNo").value("제10조"))
                .andExpect(jsonPath("$.articles[0].mine").value(true))
                .andExpect(jsonPath("$.linkedArticles", contains("제10조")))
                .andExpect(jsonPath("$.oldNew", hasSize(5)))
                .andExpect(jsonPath("$.oldNew[2].old", containsString("검찰압수물사무규칙")))
                .andExpect(jsonPath("$.oldNew[2].next", containsString("공소청압수물사무규칙")))
                .andExpect(jsonPath("$.sourceUrl", containsString("290881")))
                .andExpect(jsonPath("$.deadline").value("2026-10-02"));
    }

    @Test
    void old_new_not_fetched_yet_is_null() throws Exception {
        permit("운영허가", RULE, "제10조");
        today("2026-10-02");
        fake.failing.add("oldAndNew:290881");
        batch.run();

        ok(kim, "/api/law-impacts/" + firstImpact()).andExpect(jsonPath("$.oldNew").doesNotExist());
    }

    @Test
    void review_needs_a_memo_and_keeps_the_enforcement_deadline() throws Exception {
        permit("운영허가", RULE, "제10조");
        today("2026-10-02");
        batch.run();
        long id = firstImpact();
        assertThat(deadlines()).containsExactly("[법령 시행] 폐기물관리법 시행규칙 — 가온 소각시설 / 운영허가 2026-10-02");

        mvc.perform(post("/api/law-impacts/" + id + "/review").header("Authorization", "Bearer " + kim)
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"  \"}")).andExpect(status().isBadRequest());
        clock.advance(Duration.ofHours(3));
        lee = login("lee@a.com");
        kim = login("kim@a.com");
        mvc.perform(post("/api/law-impacts/" + id + "/review").header("Authorization", "Bearer " + lee)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"영향 없음\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.impact.status").value("REVIEWED"))
                .andExpect(jsonPath("$.impact.createdAt").value("2026-10-01T21:00:00Z"))
                .andExpect(jsonPath("$.impact.reviewedAt").value("2026-10-02T00:00:00Z"))
                .andExpect(jsonPath("$.note").value("영향 없음"))
                .andExpect(jsonPath("$.reviewedByName").value("이팀원"));
        mvc.perform(post("/api/law-impacts/" + id + "/review").header("Authorization", "Bearer " + kim)
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"다시\"}")).andExpect(status().isConflict());

        assertThat(deadlines()).hasSize(1);
        ok(kim, "/api/law-impacts?status=NEW").andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void a_past_enforcement_date_registers_no_deadline() throws Exception {
        permit("운영허가", RULE, "제10조");
        today("2026-10-03"); // 시행 10-02 already passed

        batch.run();

        assertThat(firstImpact()).isPositive();
        assertThat(deadlines()).isEmpty();
    }

    @Test
    void other_projects_impacts_are_hidden() throws Exception {
        permit("운영허가", RULE, "제10조");
        today("2026-10-02");
        batch.run();
        long id = firstImpact();

        ok(other, "/api/law-impacts").andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/law-impacts?projectId=" + project).header("Authorization", "Bearer " + other))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/law-impacts/" + id).header("Authorization", "Bearer " + other))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/law-impacts/" + id + "/review").header("Authorization", "Bearer " + other)
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\"}")).andExpect(status().isForbidden());
    }

    @Test
    void the_bell_counts_new_impacts_of_the_projects_i_am_responsible_for() throws Exception {
        permit("운영허가", RULE, "제10조");
        permit("변경신고", RULE, "제11조");
        today("2026-10-02");
        batch.run();

        ok(kim, "/api/law-impacts/count").andExpect(jsonPath("$.count").value(2));
        ok(lee, "/api/law-impacts/count").andExpect(jsonPath("$.count").value(0)); // member, not 책임자
    }

    @Test
    void one_mail_per_responsible_person_with_new_impacts_only() throws Exception {
        permit("운영허가", RULE, "제10조");
        permit("변경신고", RULE, "제11조");
        today("2026-10-02");

        batch.run();

        assertThat(mailer.sent()).hasSize(1);
        Mail m = mailer.sent().get(0);
        assertThat(m.to()).isEqualTo("kim@a.com");
        assertThat(m.subject()).contains("법령 개정", "2건");
        assertThat(m.body()).contains("[가온 소각시설]", "출처: 법제처 국가법령정보센터")
                .doesNotContain("외 ");
        assertThat(m.body().indexOf("운영허가")).isLessThan(m.body().indexOf("변경신고")); // DIRECT first
        assertThat(m.body()).contains("폐기물관리법 시행규칙 · 제10조 · 시행 2026-10-02");

        mailer.clear();
        today("2026-10-03");
        batch.run();
        assertThat(mailer.sent()).isEmpty(); // nothing new → no mail
    }

    @Test
    void a_busy_day_sends_the_top_10_and_counts_the_rest() throws Exception {
        for (int i = 1; i <= 12; i++) {
            permit("허가 %02d".formatted(i), RULE, i == 12 ? "제10조" : "제11조");
        }
        today("2026-10-02");

        batch.run();

        assertThat(mailer.sent()).hasSize(1);
        String body = mailer.sent().get(0).body();
        assertThat(body).contains("허가 12", "외 2건").doesNotContain("허가 10", "허가 11"); // DIRECT 12 first, then 01..09
        assertThat(body.split("\n- ")).hasSize(11);
    }

    @Test
    void two_failed_days_in_a_row_alert_the_admin_once() throws Exception {
        fake.failing.add("lsHstInf:20261001:1");
        today("2026-10-02");
        batch.run();
        assertThat(mailer.sent()).isEmpty();

        today("2026-10-03");
        batch.run();
        assertThat(mailer.sent()).extracting(Mail::to).containsExactly("boss@a.com");
        assertThat(mailer.sent().get(0).body()).contains("2026-10-01", "2");

        today("2026-10-04");
        batch.run();
        assertThat(mailer.sent()).hasSize(1);
    }

    // --- helpers

    private long permit(String name, String lawId, String articleNo) throws Exception {
        long id = id(send(kim, "/api/projects/" + project + "/permits",
                "{\"name\":\"%s\",\"category\":\"FACILITY\",\"status\":\"IN_PROGRESS\"}".formatted(name)));
        if (!lawId.isEmpty()) {
            link(id, lawId, articleNo);
        }
        return id;
    }

    private void link(long permit, String lawId, String articleNo) throws Exception {
        long law = jdbc.queryForObject("SELECT id FROM law_catalog WHERE law_id = ?", Long.class, lawId);
        send(kim, "/api/permit-items/" + permit + "/law-links", articleNo == null
                ? "{\"lawCatalogId\":%d}".formatted(law)
                : "{\"lawCatalogId\":%d,\"articleNo\":\"%s\"}".formatted(law, articleNo));
    }

    private long firstImpact() {
        return jdbc.queryForObject("SELECT min(id) FROM permit_law_impact", Long.class);
    }

    private List<String> deadlines() {
        return jdbc.queryForList("SELECT title || ' ' || due_date FROM deadline_event WHERE kind = 'LAW_ENFORCEMENT'",
                String.class);
    }

    /** Moves the clock; tokens are re-issued since they expire after 30 minutes. */
    private void today(String ymd) throws Exception {
        clock.set(LocalDate.parse(ymd).atTime(6, 0).atZone(clock.getZone()).toInstant());
        if (kim != null) {
            kim = login("kim@a.com");
            lee = login("lee@a.com");
            other = login("x@b.com");
        }
    }

    private void cursor(String ymd) {
        jdbc.update("INSERT INTO batch_cursor (job_name, last_success_date, consecutive_failures) VALUES (?, ?, 0) "
                + "ON CONFLICT (job_name) DO UPDATE SET last_success_date = EXCLUDED.last_success_date",
                AmendmentBatch.JOB, LocalDate.parse(ymd));
    }

    private void catalogAt(long mst, String promulgated, String enforced) {
        jdbc.update("UPDATE law_catalog SET last_mst = ?, last_promulgated_on = ?, last_enforced_on = ? WHERE law_id = ?",
                mst, LocalDate.parse(promulgated), LocalDate.parse(enforced), RULE);
    }

    private ResultActions ok(String token, String url) throws Exception {
        return mvc.perform(get(url)
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    private String login(String email) throws Exception {
        return JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"pw\"}".formatted(email)))
                .andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    private long id(String body) {
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private String send(String token, String url, String body) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }
}
