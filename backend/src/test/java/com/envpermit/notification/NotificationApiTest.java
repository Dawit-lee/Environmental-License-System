package com.envpermit.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.envpermit.core.company.Company;
import com.envpermit.core.company.CompanyRepository;
import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import com.envpermit.support.InMemoryMailer;
import com.envpermit.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class NotificationApiTest extends IntegrationTest {

    static final LocalDate TODAY = LocalDate.of(2026, 1, 5); // START in Asia/Seoul

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;
    @Autowired NotificationJob job;
    @Autowired Deadlines deadlines;
    @Autowired InMemoryMailer mailer;

    long kimId;
    long leeId;
    long projectId;

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        mailer.clear();
        long a = companies.save(new Company("A환경")).getId();
        long b = companies.save(new Company("B엔지니어링")).getId();
        kimId = users.save(new User("kim@a.com", encoder.encode("pw"), "김에이", User.Role.USER, a)).getId();
        leeId = users.save(new User("lee@a.com", encoder.encode("pw"), "이에이", User.Role.USER, a)).getId();
        users.save(new User("park@b.com", encoder.encode("pw"), "박비", User.Role.USER, b));
        String kim = login("kim@a.com");
        projectId = id(call(kim, json(post("/api/projects"), """
                {"name":"다온 하수처리장","facilityType":"SEWAGE","location":"다온"}""")));
        call(kim, json(post("/api/projects/" + projectId + "/members"), "{\"userId\":" + leeId + "}"))
                .andExpect(status().isCreated());
    }

    @ParameterizedTest
    @CsvSource(nullValues = "none", value = {
            "-1, none", "0, 7", "7, 7", "8, 30", "30, 30", "31, 60", "60, 60", "61, 90", "90, 90", "91, none"})
    void crossed_offset_is_the_tightest_reminder_already_reached(long daysLeft, Integer expected) {
        assertThat(NotificationJob.crossedOffset(daysLeft)).isEqualTo(expected);
    }

    @Test
    void permit_expiry_reminds_every_member_at_d90_60_30_7_once_each() throws Exception {
        createPermit(login("kim@a.com"), TODAY.plusDays(100));

        int[] expectedPerUser = {0, 1, 2, 3, 4};
        int[] dayOffsets = {0, 10, 40, 70, 93}; // D-100, D-90, D-60, D-30, D-7
        for (int i = 0; i < dayOffsets.length; i++) {
            clock.set(START.plus(Duration.ofDays(dayOffsets[i])));
            job.createDueNotifications();
            job.createDueNotifications(); // a second run the same day adds nothing
            assertThat(count(kimId)).isEqualTo(expectedPerUser[i]);
            assertThat(count(leeId)).isEqualTo(expectedPerUser[i]);
        }

        call(login("lee@a.com"), get("/api/notifications")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].offsetDays").value(7))
                .andExpect(jsonPath("$[0].message").value("다온 하수처리장 · 건축허가 만료 (2026-04-15)"))
                .andExpect(jsonPath("$[0].link").value("/projects/" + projectId))
                .andExpect(jsonPath("$[0].readAt").doesNotExist());
    }

    @Test
    void late_registration_only_sends_the_current_reminder_not_the_missed_ones() throws Exception {
        deadlines.register("OBLIGATION", 42, projectId, "수질 자가측정 보고", TODAY.plusDays(20), leeId);

        job.createDueNotifications();

        assertThat(count(kimId)).isZero(); // assignee set → only the assignee
        assertThat(jdbc.queryForList("SELECT offset_days FROM notification WHERE user_id = ?", Integer.class, leeId))
                .containsExactly(30);
    }

    @Test
    void moved_due_date_is_reminded_again() throws Exception {
        String kim = login("kim@a.com");
        long permit = createPermit(kim, TODAY.plusDays(5));
        job.createDueNotifications();

        call(kim, json(put("/api/projects/" + projectId + "/permits/" + permit), permitJson(TODAY.plusDays(6))))
                .andExpect(status().isOk());
        job.createDueNotifications();

        assertThat(count(kimId)).isEqualTo(2);
    }

    @Test
    void daily_digest_is_one_mail_per_user_grouped_by_d_day_and_sent_once() throws Exception {
        deadlines.register("PERMIT_EXPIRY", 1, projectId, "건축허가 만료", TODAY.plusDays(3), kimId);
        deadlines.register("PERMIT_EXPIRY", 2, projectId, "개발행위허가 만료", TODAY.plusDays(25), kimId);
        deadlines.register("OBLIGATION", 3, projectId, "정기검사", TODAY.plusDays(6), kimId);
        job.createDueNotifications();

        job.sendDigests();
        job.sendDigests();

        assertThat(mailer.sent()).hasSize(1);
        InMemoryMailer.Mail mail = mailer.sent().get(0);
        assertThat(mail.to()).isEqualTo("kim@a.com");
        assertThat(mail.subject()).isEqualTo("[인허가 관리] 기한 알림 3건 (2026-01-05)");
        assertThat(mail.body()).containsSubsequence(
                "[D-7]", "다온 하수처리장 · 건축허가 만료 (2026-01-08)", "다온 하수처리장 · 정기검사 (2026-01-11)",
                "[D-30]", "다온 하수처리장 · 개발행위허가 만료 (2026-01-30)");
    }

    @Test
    void member_lists_and_marks_own_notifications_read() throws Exception {
        createPermit(login("kim@a.com"), TODAY.plusDays(5));
        job.createDueNotifications();
        String kim = login("kim@a.com");
        long id = ((Number) JsonPath.read(call(kim, get("/api/notifications")).andReturn().getResponse()
                .getContentAsString(), "$[0].id")).longValue();

        call(login("lee@a.com"), post("/api/notifications/" + id + "/read")).andExpect(status().isNotFound());
        call(kim, post("/api/notifications/" + id + "/read")).andExpect(status().isNoContent());
        call(kim, get("/api/notifications")).andExpect(jsonPath("$[0].readAt").value("2026-01-05T00:00:00Z"));

        String lee = login("lee@a.com");
        call(lee, post("/api/notifications/read-all")).andExpect(status().isNoContent());
        call(lee, get("/api/notifications")).andExpect(jsonPath("$[0].readAt").exists());
    }

    @Test
    void deadlines_endpoint_shows_only_my_projects_and_follows_permit_changes() throws Exception {
        String kim = login("kim@a.com");
        long permit = createPermit(kim, TODAY.plusDays(10));
        String range = "/api/deadlines?from=2026-01-01&to=2026-01-31";

        call(kim, get(range)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].kind").value("PERMIT_EXPIRY"))
                .andExpect(jsonPath("$[0].targetId").value(permit))
                .andExpect(jsonPath("$[0].projectName").value("다온 하수처리장"))
                .andExpect(jsonPath("$[0].title").value("건축허가 만료"))
                .andExpect(jsonPath("$[0].dueDate").value("2026-01-15"))
                .andExpect(jsonPath("$[0].dDay").value(10));
        call(login("park@b.com"), get(range)).andExpect(jsonPath("$.length()").value(0));

        call(kim, json(put("/api/projects/" + projectId + "/permits/" + permit), permitJson(null)))
                .andExpect(status().isOk());
        call(kim, get(range)).andExpect(jsonPath("$.length()").value(0));

        long other = createPermit(kim, TODAY.plusDays(3));
        call(kim, delete("/api/projects/" + projectId + "/permits/" + other)).andExpect(status().isNoContent());
        call(kim, get(range)).andExpect(jsonPath("$.length()").value(0));
    }

    // --- helpers ----------------------------------------------------------

    private int count(long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM notification WHERE user_id = ?", Integer.class, userId);
    }

    private long createPermit(String token, LocalDate expiry) throws Exception {
        return id(call(token, json(post("/api/projects/" + projectId + "/permits"), permitJson(expiry))));
    }

    private static String permitJson(LocalDate expiry) {
        return """
                {"name":"건축허가","category":"CONSTRUCTION","expiryDate":%s,"status":"COMPLETED"}"""
                .formatted(expiry == null ? "null" : "\"" + expiry + "\"");
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
