package com.envpermit.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.envpermit.core.company.Company;
import com.envpermit.core.company.CompanyRepository;
import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import com.envpermit.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Ticket design-schedule/05: partner share links and personal .ics. Every value that must never leave contains
 * "SECRET", so a leak shows up as that word in a response. Today is 2026-01-05 (START).
 */
class ShareApiTest extends IntegrationTest {

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;
    @Autowired ShareRequestFilter filter;

    long kimId; // company ADMIN, creator (project admin) of both projects
    long leeId; // plain member of A, owner/attendee of the personal-feed rows
    long yoonId; // EM of A, plain member
    long projectA;
    long projectB;
    long civil;
    long arch;
    String kim;
    String lee;
    String yoon;
    String cho; // same company, not a member
    String park; // another company's ADMIN

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        filter.reset();
        long a = companies.save(new Company("가온엔지니어링")).getId();
        long b = companies.save(new Company("다온설계")).getId();
        kimId = users.save(new User("kim@a.com", encoder.encode("pw"), "김가온", User.Role.ADMIN, a)).getId();
        leeId = users.save(new User("lee@a.com", encoder.encode("pw"), "이SECRET직원", User.Role.USER, a)).getId();
        yoonId = users.save(new User("yoon@a.com", encoder.encode("pw"), "윤가온", User.Role.USER, a)).getId();
        users.save(new User("cho@a.com", encoder.encode("pw"), "조가온", User.Role.USER, a));
        users.save(new User("park@b.com", encoder.encode("pw"), "박다온", User.Role.ADMIN, b));
        kim = login("kim@a.com");
        lee = login("lee@a.com");
        yoon = login("yoon@a.com");
        cho = login("cho@a.com");
        park = login("park@b.com");
        long dept = jdbc.queryForObject("INSERT INTO department (company_id, name) VALUES (?, '환경사업부') RETURNING id", Long.class, a);
        long team = jdbc.queryForObject("INSERT INTO team (department_id, name) VALUES (?, '수처리1팀') RETURNING id", Long.class, dept);
        projectA = id(call(kim, json(post("/api/projects"), """
                {"name":"가온시 하수처리장 턴키","facilityType":"SEWAGE","location":"가온시","teamId":%d,"pmUserId":%d,"emUserId":%d}"""
                .formatted(team, kimId, yoonId))));
        projectB = id(call(kim, json(post("/api/projects"), """
                {"name":"나래군 SECRET 폐수처리시설","facilityType":"WASTEWATER","location":"나래군","teamId":%d,"pmUserId":%d}"""
                .formatted(team, kimId))));
        for (long u : List.of(leeId, yoonId)) {
            call(kim, json(post("/api/projects/" + projectA + "/members"), "{\"userId\":" + u + "}")).andExpect(status().isCreated());
        }
        civil = discipline(a, "토목");
        arch = discipline(a, "건축");

        // A: one item per discipline + 공통; forbidden fields filled in
        item(projectA, civil, "토목 기본설계", "가온토목");
        item(projectA, arch, "건축 SECRET 분야외", "다른협력사SECRET");
        item(projectA, null, "공통 SECRET 착수보고", null);
        jdbc.update("""
                INSERT INTO schedule_item (project_id, discipline_id, kind, title, start_date, end_date, owner_user_id)
                VALUES (?, ?, 'MILESTONE', '토목 실시설계 제출', '2026-03-31', '2026-03-31', ?)""", projectA, civil, leeId);
        long ev = jdbc.queryForObject("""
                INSERT INTO project_event (project_id, kind, title, starts_at, ends_at, location, counterpart_org, purpose,
                                           discipline_id, checklist)
                VALUES (?, 'TRIP', '토목 현장 답사', '2026-01-20 09:00', '2026-01-20 18:00', '가온시 현장',
                        'SECRET 상대기관', 'SECRET 목적', ?, 'SECRET 체크리스트') RETURNING id""", Long.class, projectA, civil);
        jdbc.update("INSERT INTO project_event_attendee (event_id, user_id, role) VALUES (?, ?, '발표')", ev, leeId);
        jdbc.update("INSERT INTO project_event_attendee (event_id, external_name) VALUES (?, 'SECRET 외부인')", ev);
        jdbc.update("""
                INSERT INTO deadline_event (kind, target_id, project_id, title, due_date)
                VALUES ('PERMIT_EXPIRY', 1, ?, 'SECRET 인허가 기한', '2026-02-01')""", projectA);
        // B: another project, same disciplines
        item(projectB, civil, "SECRET 다른 프로젝트 토목", "SECRET");
    }

    // --- management ---------------------------------------------------------------------------------------

    @Test
    void only_pm_em_or_project_admin_manage_links() throws Exception {
        for (String who : List.of(lee, cho, park)) {
            call(who, json(post(links(projectA)), "{}")).andExpect(status().isForbidden());
            call(who, get(links(projectA))).andExpect(status().isForbidden());
        }
        call(kim, json(post(links(projectA)), "{}")).andExpect(status().isCreated()); // PM + admin
        call(yoon, json(post(links(projectA)), "{}")).andExpect(status().isCreated()); // EM
        call(yoon, get(links(projectA))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        call(yoon, get(links(projectB))).andExpect(status().isForbidden());
    }

    @Test
    void only_the_hash_is_stored_and_expiry_defaults_to_90_days() throws Exception {
        MvcResult r = call(kim, json(post(links(projectA)), "{}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.link.active").value(true))
                .andExpect(jsonPath("$.link.expiresAt").value("2026-04-05T00:00:00Z")).andReturn();
        String token = token(r);
        assertThat(token).matches("[A-Za-z0-9_-]{43}"); // 32 bytes, URL-safe
        assertThat(JsonPath.<String>read(r.getResponse().getContentAsString(), "$.icsPath")).isEqualTo("/share/" + token + ".ics");
        assertThat(jdbc.queryForObject("SELECT token_hash FROM project_share_link", String.class))
                .isEqualTo(ShareController.hash(token)).isNotEqualTo(token);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project_share_link WHERE row_to_json(project_share_link)::text LIKE ?",
                Integer.class, "%" + token + "%")).isZero();
        call(kim, get(links(projectA))).andExpect(jsonPath("$[0].path").doesNotExist());
    }

    @Test
    void expiry_and_disciplines_are_checked() throws Exception {
        call(kim, json(post(links(projectA)), "{\"expiresInDays\":366}")).andExpect(status().isBadRequest());
        call(kim, json(post(links(projectA)), "{\"expiresInDays\":0}")).andExpect(status().isBadRequest());
        call(kim, json(post(links(projectA)), "{\"expiresInDays\":365}")).andExpect(status().isCreated());
        call(kim, json(post(links(projectA)), "{\"disciplineIds\":[]}")).andExpect(status().isBadRequest());
        long otherCompany = jdbc.queryForObject("""
                SELECT d.id FROM discipline d JOIN company c ON c.id = d.company_id WHERE c.name = '다온설계' LIMIT 1""", Long.class);
        call(kim, json(post(links(projectA)), "{\"disciplineIds\":[" + otherCompany + "]}")).andExpect(status().isBadRequest());
    }

    @Test
    void revoking_another_projects_link_is_404() throws Exception {
        long id = ((Number) JsonPath.read(call(kim, json(post(links(projectB)), "{}")).andReturn()
                .getResponse().getContentAsString(), "$.link.id")).longValue();
        call(kim, delete(links(projectA) + "/" + id)).andExpect(status().isNotFound());
        call(kim, delete(links(projectB) + "/" + id)).andExpect(status().isNoContent());
        call(kim, delete(links(projectB) + "/" + id)).andExpect(status().isNoContent()); // idempotent
        call(kim, get(links(projectB))).andExpect(jsonPath("$[0].active").value(false))
                .andExpect(jsonPath("$[0].revokedAt").value("2026-01-05T00:00:00Z"));
    }

    // --- public reads -------------------------------------------------------------------------------------

    @Test
    void web_and_ics_export_only_the_chosen_disciplines_and_whitelisted_fields() throws Exception {
        String token = create("{\"disciplineIds\":[" + civil + "]}");
        String web = mvc.perform(get("/share/" + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.projectName").value("가온시 하수처리장 턴키"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].title").value("토목 기본설계"))
                .andExpect(jsonPath("$.items[0].partnerName").value("가온토목"))
                .andExpect(jsonPath("$.items[0].disciplineName").value("토목"))
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].location").value("가온시 현장"))
                .andReturn().getResponse().getContentAsString();
        assertThat(web).doesNotContain("SECRET", "이가온", "delay", "owner", "checklist", "counterpart", "purpose", "\"id\"");

        String ics = mvc.perform(get("/share/" + token + ".ics")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/calendar;charset=UTF-8"))
                .andReturn().getResponse().getContentAsString();
        assertThat(ics).contains("SUMMARY:토목 기본설계", "SUMMARY:토목 현장 답사", "SUMMARY:토목 실시설계 제출",
                "협력사: 가온토목", "LOCATION:가온시 현장").doesNotContain("SECRET");
        assertThat(ics.split("BEGIN:VEVENT")).hasSize(4);
    }

    @Test
    void all_disciplines_include_common_and_events_can_be_left_out() throws Exception {
        String token = create("{\"includeEvents\":false}");
        String web = mvc.perform(get("/share/" + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(4)).andExpect(jsonPath("$.events.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(web).doesNotContain("다른 프로젝트", "체크리스트", "인허가 기한", "상대기관");
    }

    @Test
    void expired_revoked_forged_and_malformed_tokens_get_the_same_404() throws Exception {
        String expired = create("{\"expiresInDays\":1}");
        String revoked = create("{}");
        long revokedId = jdbc.queryForObject("SELECT id FROM project_share_link WHERE token_hash = ?", Long.class,
                ShareController.hash(revoked));
        call(kim, delete(links(projectA) + "/" + revokedId)).andExpect(status().isNoContent());
        clock.advance(Duration.ofDays(1));
        String forged = ShareController.newToken();
        for (String t : List.of(expired, revoked, forged, "x", expired.substring(1))) {
            for (String path : List.of("/share/" + t, "/share/" + t + ".ics")) {
                MvcResult r = mvc.perform(get(path)).andExpect(status().isNotFound()).andReturn();
                assertThat(r.getResponse().getContentAsString()).isEmpty();
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project_share_link WHERE last_accessed_at IS NOT NULL",
                Integer.class)).isZero();
    }

    @Test
    void the_list_shows_the_last_access() throws Exception {
        String token = create("{}");
        clock.advance(Duration.ofMinutes(20)); // within the 30m access token
        mvc.perform(get("/share/" + token)).andExpect(status().isOk());
        call(kim, get(links(projectA))).andExpect(jsonPath("$[0].lastAccessedAt").value("2026-01-05T00:20:00Z"));
    }

    @Test
    void no_write_route_is_reachable_with_a_token() throws Exception {
        String token = create("{}");
        mvc.perform(post("/share/" + token)).andExpect(status().isUnauthorized());
        mvc.perform(patch("/share/" + token)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/share/" + token)).andExpect(status().isUnauthorized());
        mvc.perform(get(links(projectA)).param("token", token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/projects/" + projectA + "/schedule").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void responses_are_private_noindex_and_no_referrer() throws Exception {
        String token = create("{}");
        for (String path : List.of("/share/" + token, "/share/" + token + ".ics", "/share/nope")) {
            mvc.perform(get(path))
                    .andExpect(header().string("Cache-Control", "private, no-store"))
                    .andExpect(header().string("X-Robots-Tag", "noindex"))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"));
        }
    }

    @Test
    void more_than_60_requests_a_minute_from_one_ip_get_429() throws Exception {
        String token = create("{}");
        for (int i = 0; i < ShareRequestFilter.LIMIT_PER_MINUTE; i++) {
            mvc.perform(get("/share/" + token)).andExpect(status().isOk());
        }
        mvc.perform(get("/share/" + token)).andExpect(status().isTooManyRequests());
        mvc.perform(get("/share/" + token).with(r -> {
            r.setRemoteAddr("10.0.0.9");
            return r;
        })).andExpect(status().isOk()); // per IP
        clock.advance(Duration.ofMinutes(1));
        mvc.perform(get("/share/" + token)).andExpect(status().isOk()); // next window
    }

    // --- personal subscription ----------------------------------------------------------------------------

    @Test
    void personal_feed_has_my_events_and_milestones_and_reissue_kills_the_old_address() throws Exception {
        String first = icsPath(call(lee, post("/api/me/calendar-token")).andExpect(status().isOk()));
        String ics = mvc.perform(get(first)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andReturn().getResponse().getContentAsString();
        assertThat(ics).contains("SUMMARY:[가온시 하수처리장 턴키] 토목 현장 답사", "SUMMARY:[가온시 하수처리장 턴키] 토목 실시설계 제출")
                .doesNotContain("SECRET", "기본설계");
        assertThat(ics.split("BEGIN:VEVENT")).hasSize(3);

        String second = icsPath(call(lee, post("/api/me/calendar-token")));
        mvc.perform(get(first)).andExpect(status().isNotFound());
        mvc.perform(get(second)).andExpect(status().isOk());
        mvc.perform(get("/api/me/calendar.ics")).andExpect(status().isNotFound());

        // removed from the project → its rows leave the feed
        call(kim, delete("/api/projects/" + projectA + "/members/" + leeId));
        assertThat(mvc.perform(get(second)).andReturn().getResponse().getContentAsString()).doesNotContain("BEGIN:VEVENT");

        call(lee, delete("/api/me/calendar-token")).andExpect(status().isNoContent());
        mvc.perform(get(second)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_calendar_token", Integer.class)).isZero();
    }

    // --- helpers ------------------------------------------------------------------------------------------

    private void item(long project, Long discipline, String title, String partner) {
        jdbc.update("""
                INSERT INTO schedule_item (project_id, discipline_id, kind, title, start_date, end_date, owner_user_id,
                                           partner_name, delay_reason)
                VALUES (?, ?, 'TASK', ?, '2026-01-10', '2026-02-10', ?, ?, 'SECRET 지연 사유')""",
                project, discipline, title, leeId, partner);
    }

    private long discipline(long company, String name) {
        return jdbc.queryForObject("SELECT id FROM discipline WHERE company_id = ? AND name = ?", Long.class, company, name);
    }

    private String create(String body) throws Exception {
        return token(call(kim, json(post(links(projectA)), body)).andExpect(status().isCreated()).andReturn());
    }

    private static String token(MvcResult r) throws Exception {
        return JsonPath.<String>read(r.getResponse().getContentAsString(), "$.path").substring("/share/".length());
    }

    private static String icsPath(ResultActions r) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.icsPath");
    }

    private static String links(long projectId) {
        return "/api/projects/" + projectId + "/share-links";
    }

    private ResultActions call(String token, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + token));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, String body) {
        return req.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static long id(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private String login(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"pw\"}".formatted(email)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
