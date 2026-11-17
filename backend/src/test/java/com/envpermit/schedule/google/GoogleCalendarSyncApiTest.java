package com.envpermit.schedule.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.envpermit.core.company.Company;
import com.envpermit.core.company.CompanyRepository;
import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import com.envpermit.support.FakeCalendarPublisher;
import com.envpermit.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/** Ticket design-schedule/06 with the fake {@link CalendarPublisher}. */
@TestPropertySource(properties = {"app.google.client-id=test-client", "app.google.client-secret=test-secret",
        "app.google.token-enc-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class GoogleCalendarSyncApiTest extends IntegrationTest {

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;
    @Autowired FakeCalendarPublisher google;
    @Autowired GoogleCalendarSync sync;

    long kimId; // company ADMIN, PM
    long leeId; // member
    long projectA;
    String kim;
    String lee;

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        google.reset();
        long a = companies.save(new Company("가온엔지니어링")).getId();
        kimId = users.save(new User("kim@a.com", encoder.encode("pw"), "김가온", User.Role.ADMIN, a)).getId();
        leeId = users.save(new User("lee@a.com", encoder.encode("pw"), "이가온", User.Role.USER, a)).getId();
        kim = login("kim@a.com");
        lee = login("lee@a.com");
        long dept = jdbc.queryForObject("INSERT INTO department (company_id, name) VALUES (?, '환경사업부') RETURNING id", Long.class, a);
        long team = jdbc.queryForObject("INSERT INTO team (department_id, name) VALUES (?, '수처리1팀') RETURNING id", Long.class, dept);
        projectA = id(call(kim, json(post("/api/projects"), """
                {"name":"가온시 하수처리장","facilityType":"SEWAGE","location":"가온시","teamId":%d,"pmUserId":%d}"""
                .formatted(team, kimId))));
        call(kim, json(post("/api/projects/" + projectA + "/members"), "{\"userId\":" + leeId + "}"))
                .andExpect(status().isCreated());
    }

    @Test
    void company_must_allow_it_and_connect_asks_only_the_app_created_scope() throws Exception {
        call(lee, get("/api/me/google-calendar")).andExpect(status().isOk())
                .andExpect(jsonPath("$.companyEnabled").value(false)).andExpect(jsonPath("$.connected").value(false));
        call(lee, get("/api/me/google-calendar/connect")).andExpect(status().isForbidden());
        call(lee, json(put("/api/company/google-sync"), "{\"enabled\":true}")).andExpect(status().isForbidden());
        allowCompany();

        String url = JsonPath.read(call(lee, get("/api/me/google-calendar/connect")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.url");
        assertThat(param(url, "scope")).isEqualTo("https://www.googleapis.com/auth/calendar.app.created");
        assertThat(param(url, "access_type")).isEqualTo("offline");
        assertThat(param(url, "prompt")).isEqualTo("consent");
        assertThat(param(url, "redirect_uri")).isEqualTo("http://localhost:5182/api/me/google-calendar/callback");
    }

    @Test
    void state_must_match_and_is_single_use_and_the_token_is_never_stored_plain() throws Exception {
        allowCompany();
        mvc.perform(get("/api/me/google-calendar/callback").param("code", "c1").param("state", "forged"))
                .andExpect(status().isBadRequest());

        String state = state(lee);
        mvc.perform(get("/api/me/google-calendar/callback").param("code", "secret-code").param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5182/calendar?google=connected"));
        mvc.perform(get("/api/me/google-calendar/callback").param("code", "secret-code").param("state", state))
                .andExpect(status().isBadRequest()); // used up
        assertThat(google.calls).containsExactly("exchange:secret-code", "createCalendar:인허가 일정");
        String stored = jdbc.queryForObject("SELECT encrypted_refresh_token FROM user_google_link WHERE user_id = ?",
                String.class, leeId);
        assertThat(stored).doesNotContain("refresh-secret-code");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_google_link WHERE user_id = ? AND calendar_id = 'cal-1'",
                Integer.class, leeId)).isEqualTo(1);

        clock.advance(Duration.ofMinutes(11));
        String old = state(kim);
        clock.advance(Duration.ofMinutes(11));
        mvc.perform(get("/api/me/google-calendar/callback").param("code", "c").param("state", old))
                .andExpect(status().isBadRequest()); // expired
    }

    @Test
    void event_create_update_delete_reach_google_once_each_in_order() throws Exception {
        connect(lee);
        long ev = id(call(lee, json(post(events()), meeting("공정회의", leeId, 0))).andExpect(status().isCreated()));
        sync.runOnce();
        sync.runOnce(); // nothing left: still one Google event
        assertThat(google.events).hasSize(1);

        call(lee, json(patch(events() + "/" + ev), meeting("공정회의(변경)", leeId, 0))).andExpect(status().isOk());
        sync.runOnce();
        call(lee, delete(events() + "/" + ev)).andExpect(status().isNoContent());
        sync.runOnce();

        assertThat(google.calls.subList(2, google.calls.size())).containsExactly(
                "insert:[가온시 하수처리장] 공정회의", "patch:[가온시 하수처리장] 공정회의(변경)",
                "delete:[가온시 하수처리장] 공정회의(변경)");
        assertThat(google.events).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_sync_outbox", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_sync_link", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT last_synced_at IS NOT NULL FROM user_google_link", Boolean.class)).isTrue();
    }

    @Test
    void only_attendees_get_it_and_leaving_the_attendees_deletes_it() throws Exception {
        connect(lee);
        connect(kim);
        long ev = id(call(lee, json(post(events()), meeting("분야 미팅", leeId, 0))).andExpect(status().isCreated()));
        sync.runOnce();
        assertThat(google.calls).filteredOn(c -> c.startsWith("insert:")).hasSize(1); // lee only, not kim

        call(kim, json(patch(events() + "/" + ev), meeting("분야 미팅", kimId, 0))).andExpect(status().isOk());
        sync.runOnce();
        assertThat(google.calls).filteredOn(c -> !c.startsWith("exchange") && !c.startsWith("createCalendar"))
                .containsExactlyInAnyOrder("insert:[가온시 하수처리장] 분야 미팅", "delete:[가온시 하수처리장] 분야 미팅",
                        "insert:[가온시 하수처리장] 분야 미팅");
        assertThat(jdbc.queryForList("SELECT user_id FROM calendar_sync_link", Long.class)).containsExactly(kimId);
    }

    @Test
    void milestones_follow_the_targets_toggle() throws Exception {
        connect(lee);
        // a 제출 event without attendees adds an unowned milestone: not mine, so not sent by default
        call(lee, json(post(events()), """
                {"kind":"SUBMISSION","title":"기본설계 제출","startsAt":"2026-02-10T00:00","endsAt":"2026-02-10T00:00",
                 "allDay":true}""")).andExpect(status().isCreated());
        sync.runOnce();
        assertThat(google.events).isEmpty();

        // an unflagged, unowned milestone (e.g. an internal review) is not a 제출·보고 milestone: never sent
        long internal = id(call(lee, json(post(events().replace("/events", "/schedule/items")), """
                {"kind":"MILESTONE","title":"내부 검토","endDate":"2026-02-03"}""")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.submission").value(false)));

        call(lee, json(put("/api/me/google-calendar"), "{\"projectMilestones\":true,\"tasks\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.projectMilestones").value(true));
        sync.runOnce();
        assertThat(google.events.values()).singleElement()
                .satisfies(e -> assertThat(e.allDay()).isTrue())
                .satisfies(e -> assertThat(e.summary()).isEqualTo("[가온시 하수처리장] 기본설계 제출"));

        // flagging it later sends it too
        call(lee, json(patch(events().replace("/events", "/schedule/items/" + internal)), """
                {"kind":"MILESTONE","title":"내부 검토","endDate":"2026-02-03","submission":true}"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.submission").value(true));
        sync.runOnce();
        assertThat(google.events).hasSize(2);

        call(lee, json(put("/api/me/google-calendar"), "{\"projectMilestones\":false,\"tasks\":false}"))
                .andExpect(status().isOk());
        sync.runOnce();
        assertThat(google.events).isEmpty();
    }

    @Test
    void failures_back_off_and_after_24h_notify_once_and_keep_last_error() throws Exception {
        connect(lee);
        call(lee, json(post(events()), meeting("공정회의", leeId, 0))).andExpect(status().isCreated());
        google.failing = true;
        sync.runOnce();
        assertThat(jdbc.queryForObject("SELECT attempts FROM calendar_sync_outbox", Integer.class)).isEqualTo(1);
        sync.runOnce(); // not due yet (1 min)
        assertThat(jdbc.queryForObject("SELECT attempts FROM calendar_sync_outbox", Integer.class)).isEqualTo(1);

        clock.advance(Duration.ofMinutes(2));
        sync.runOnce();
        assertThat(jdbc.queryForObject("SELECT attempts FROM calendar_sync_outbox", Integer.class)).isEqualTo(2);

        clock.advance(Duration.ofHours(25));
        sync.runOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_sync_outbox", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT last_error FROM user_google_link", String.class)).contains("503");
        assertThat(jdbc.queryForList("SELECT message FROM notification WHERE user_id = ?", String.class, leeId))
                .containsExactly("Google 동기화 실패: Google 503");
        lee = login("lee@a.com"); // 25 h later: the old access token has expired
        call(lee, get("/api/me/google-calendar")).andExpect(jsonPath("$.lastError").value("Google 503"));

        google.failing = false; // next change succeeds and clears the error
        call(lee, json(post(events()), meeting("다음 회의", leeId, 1))).andExpect(status().isCreated());
        sync.runOnce();
        assertThat(jdbc.queryForObject("SELECT last_error FROM user_google_link", String.class)).isNull();
    }

    @Test
    void disconnect_revokes_and_forgets_and_optionally_deletes_the_calendar() throws Exception {
        connect(lee);
        call(lee, json(post(events()), meeting("공정회의", leeId, 0))).andExpect(status().isCreated());
        sync.runOnce();
        call(lee, delete("/api/me/google-calendar").param("deleteCalendar", "true")).andExpect(status().isNoContent());
        assertThat(google.calls).endsWith("deleteCalendar:cal-1", "revoke:refresh-code-" + leeId);
        for (String t : List.of("user_google_link", "calendar_sync_link", "calendar_sync_outbox")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + t, Integer.class)).as(t).isZero();
        }
        call(lee, delete("/api/me/google-calendar")).andExpect(status().isNotFound());
    }

    // --- helpers --------------------------------------------------------------------------------------------

    private void allowCompany() throws Exception {
        call(kim, json(put("/api/company/google-sync"), "{\"enabled\":true}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.googleSyncEnabled").value(true));
    }

    private String state(String token) throws Exception {
        String url = JsonPath.read(call(token, get("/api/me/google-calendar/connect")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.url");
        return param(url, "state");
    }

    private static String param(String url, String name) {
        return URLDecoder.decode(UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst(name),
                StandardCharsets.UTF_8);
    }

    private void connect(String token) throws Exception {
        allowCompany();
        long uid = token.equals(lee) ? leeId : kimId;
        mvc.perform(get("/api/me/google-calendar/callback").param("code", "code-" + uid).param("state", state(token)))
                .andExpect(status().isFound());
    }

    private static String meeting(String title, long attendee, int day) {
        return """
                {"kind":"MEETING","title":"%s","startsAt":"2026-01-2%dT10:00","endsAt":"2026-01-2%dT12:00",
                 "counterpartOrg":"가온시 환경과","attendees":[{"userId":%d}]}""".formatted(title, day, day, attendee);
    }

    private String events() {
        return "/api/projects/" + projectA + "/events";
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
