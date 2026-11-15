package com.envpermit.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.envpermit.core.company.Company;
import com.envpermit.core.company.CompanyRepository;
import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import com.envpermit.notification.NotificationJob;
import com.envpermit.support.InMemoryMailer;
import com.envpermit.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Ticket design-schedule/04. Today is 2026-01-05 (START, Asia/Seoul). */
class EventApiTest extends IntegrationTest {

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;
    @Autowired NotificationJob job;
    @Autowired InMemoryMailer mailer;

    long kimId; // company ADMIN, creator and PM of both projects
    long leeId; // member of project A only
    long choId; // same company, member of nothing
    long projectA;
    long projectB; // kim only
    long teamA;
    long deptB;
    String kim;
    String lee;
    String cho;
    String park; // another company's ADMIN

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        mailer.clear();
        long a = companies.save(new Company("가온엔지니어링")).getId();
        long b = companies.save(new Company("다온설계")).getId();
        kimId = users.save(new User("kim@a.com", encoder.encode("pw"), "김가온", User.Role.ADMIN, a)).getId();
        leeId = users.save(new User("lee@a.com", encoder.encode("pw"), "이가온", User.Role.USER, a)).getId();
        choId = users.save(new User("cho@a.com", encoder.encode("pw"), "조가온", User.Role.USER, a)).getId();
        users.save(new User("park@b.com", encoder.encode("pw"), "박다온", User.Role.ADMIN, b));
        kim = login("kim@a.com");
        lee = login("lee@a.com");
        cho = login("cho@a.com");
        park = login("park@b.com");
        long deptA = jdbc.queryForObject("INSERT INTO department (company_id, name) VALUES (?, '환경사업부') RETURNING id", Long.class, a);
        deptB = jdbc.queryForObject("INSERT INTO department (company_id, name) VALUES (?, '플랜트사업부') RETURNING id", Long.class, a);
        teamA = jdbc.queryForObject("INSERT INTO team (department_id, name) VALUES (?, '수처리1팀') RETURNING id", Long.class, deptA);
        long teamB = jdbc.queryForObject("INSERT INTO team (department_id, name) VALUES (?, '플랜트팀') RETURNING id", Long.class, deptB);
        projectA = id(call(kim, json(post("/api/projects"), """
                {"name":"가온시 하수처리장 턴키","facilityType":"SEWAGE","location":"가온시","teamId":%d,"pmUserId":%d}"""
                .formatted(teamA, kimId))));
        projectB = id(call(kim, json(post("/api/projects"), """
                {"name":"나래군 폐수처리시설","facilityType":"WASTEWATER","location":"나래군","teamId":%d,"pmUserId":%d}"""
                .formatted(teamB, kimId))));
        call(kim, json(post("/api/projects/" + projectA + "/members"), "{\"userId\":" + leeId + "}"))
                .andExpect(status().isCreated());
    }

    // --- CRUD and rules -------------------------------------------------------------------------------------

    @Test
    void create_update_and_delete_a_trip_with_attendees() throws Exception {
        long trip = id(call(lee, json(post(events(projectA)), """
                {"kind":"TRIP","title":"가온시 환경과 협의","startsAt":"2026-01-20T00:00","endsAt":"2026-01-21T18:00",
                 "allDay":true,"location":"가온시청 별관","counterpartOrg":"가온시 환경과",
                 "purpose":"소규모 환경영향평가 사전 협의","checklist":"설명 자료\\n도면 2부",
                 "attendees":[{"userId":%d,"role":"발표"},{"externalName":"가온구조 최대리","role":"동행"}]}"""
                .formatted(leeId))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.endsAt").value("2026-01-21T00:00:00")) // all-day: last day, no time
                .andExpect(jsonPath("$.projectName").value("가온시 하수처리장 턴키"))
                .andExpect(jsonPath("$.attendees.length()").value(2))
                .andExpect(jsonPath("$.attendees[0].name").value("이가온"))
                .andExpect(jsonPath("$.attendees[1].externalName").value("가온구조 최대리"))
                .andExpect(jsonPath("$.version").value(0)));

        call(lee, get(events(projectA))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        call(kim, json(patch(events(projectA) + "/" + trip), """
                {"kind":"TRIP","title":"가온시 환경과 협의","startsAt":"2026-01-22T00:00","endsAt":"2026-01-23T00:00",
                 "allDay":true,"counterpartOrg":"가온시 환경과","attendees":[],"version":0}"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.startsAt").value("2026-01-22T00:00:00"))
                .andExpect(jsonPath("$.attendees.length()").value(0)).andExpect(jsonPath("$.version").value(1));
        call(kim, json(patch(events(projectA) + "/" + trip), """
                {"kind":"TRIP","title":"x","startsAt":"2026-01-22T00:00","endsAt":"2026-01-23T00:00",
                 "allDay":true,"counterpartOrg":"가온시 환경과","version":0}""")).andExpect(status().isConflict());

        call(lee, delete(events(projectA) + "/" + trip)).andExpect(status().isNoContent());
        call(lee, get(events(projectA))).andExpect(jsonPath("$.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deadline_event WHERE kind = 'PROJECT_EVENT'", Integer.class)).isZero();
        call(lee, delete(events(projectA) + "/" + trip)).andExpect(status().isNotFound());
    }

    @Test
    void trip_and_meeting_need_a_counterpart_and_attendees_must_be_members() throws Exception {
        for (String kind : List.of("TRIP", "MEETING")) {
            call(lee, json(post(events(projectA)), """
                    {"kind":"%s","title":"협의","startsAt":"2026-01-20T10:00","endsAt":"2026-01-20T12:00"}"""
                    .formatted(kind))).andExpect(status().isBadRequest());
            call(lee, json(post(events(projectA)), """
                    {"kind":"%s","title":"협의","startsAt":"2026-01-20T10:00","endsAt":"2026-01-20T12:00",
                     "counterpartOrg":"  "}""".formatted(kind))).andExpect(status().isBadRequest());
        }
        call(lee, json(post(events(projectA)), """
                {"kind":"REVIEW","title":"설계 심의","startsAt":"2026-01-20T14:00","endsAt":"2026-01-20T10:00"}"""))
                .andExpect(status().isBadRequest()); // ends before it starts
        call(lee, json(post(events(projectA)), """
                {"kind":"PARTY","title":"x","startsAt":"2026-01-20T14:00","endsAt":"2026-01-20T15:00"}"""))
                .andExpect(status().isBadRequest());
        call(lee, json(post(events(projectA)), """
                {"kind":"REVIEW","title":"설계 심의","startsAt":"2026-01-20T14:00","endsAt":"2026-01-20T16:00",
                 "attendees":[{"userId":%d}]}""".formatted(choId))).andExpect(status().isUnprocessableEntity());
        call(lee, json(post(events(projectA)), """
                {"kind":"REVIEW","title":"설계 심의","startsAt":"2026-01-20T14:00","endsAt":"2026-01-20T16:00",
                 "attendees":[{"userId":%d,"externalName":"둘 다"}]}""".formatted(leeId))).andExpect(status().isBadRequest());
        // 설명회 needs no counterpart
        call(lee, json(post(events(projectA)), """
                {"kind":"SITE_BRIEFING","title":"현장설명회","startsAt":"2026-01-20T14:00","endsAt":"2026-01-20T16:00"}"""))
                .andExpect(status().isCreated());
    }

    @Test
    void only_members_reach_project_events() throws Exception {
        long ev = event(projectA, """
                {"kind":"SITE_BRIEFING","title":"현장설명회","startsAt":"2026-01-20T14:00","endsAt":"2026-01-20T16:00"}""");
        String body = """
                {"kind":"SITE_BRIEFING","title":"x","startsAt":"2026-01-20T14:00","endsAt":"2026-01-20T16:00"}""";
        for (String outsider : List.of(cho, park)) {
            call(outsider, get(events(projectA))).andExpect(status().isForbidden());
            call(outsider, json(post(events(projectA)), body)).andExpect(status().isForbidden());
            call(outsider, json(patch(events(projectA) + "/" + ev), body)).andExpect(status().isForbidden());
            call(outsider, delete(events(projectA) + "/" + ev)).andExpect(status().isForbidden());
        }
        // an event id of another project is not reachable through a project I am a member of
        long other = event(projectB, body, kim);
        call(kim, json(patch(events(projectA) + "/" + other), body)).andExpect(status().isNotFound());
    }

    @Test
    void submission_and_report_add_a_same_day_milestone_by_default() throws Exception {
        event(projectA, """
                {"kind":"SUBMISSION","title":"입찰서 제출","startsAt":"2026-02-10T10:00","endsAt":"2026-02-10T11:00"}""");
        event(projectA, """
                {"kind":"CLIENT_REPORT","subkind":"중간","title":"중간보고회","startsAt":"2026-02-20T14:00","endsAt":"2026-02-20T16:00",
                 "addMilestone":false}""");
        event(projectA, """
                {"kind":"MEETING","title":"공정회의","startsAt":"2026-02-03T10:00","endsAt":"2026-02-03T11:00",
                 "counterpartOrg":"가온시 하수과"}""");
        event(projectA, """
                {"kind":"MEETING","title":"분야 미팅","startsAt":"2026-02-04T10:00","endsAt":"2026-02-04T11:00",
                 "counterpartOrg":"가온구조","addMilestone":true}""");
        call(lee, get("/api/projects/" + projectA + "/schedule")).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].title").value("분야 미팅"))
                .andExpect(jsonPath("$.items[1].title").value("입찰서 제출"))
                .andExpect(jsonPath("$.items[1].kind").value("MILESTONE"))
                .andExpect(jsonPath("$.items[1].startDate").value("2026-02-10"))
                .andExpect(jsonPath("$.items[1].endDate").value("2026-02-10"));
    }

    // --- reminders ------------------------------------------------------------------------------------------

    @Test
    void attendees_and_pm_get_d3_and_d1_a_meeting_only_its_attendees_at_d1() throws Exception {
        event(projectA, """
                {"kind":"TRIP","title":"한국환경공단 방문","startsAt":"2026-01-15T00:00","endsAt":"2026-01-16T00:00",
                 "allDay":true,"counterpartOrg":"한국환경공단","attendees":[{"userId":%d},{"externalName":"협력사 담당"}]}"""
                .formatted(leeId));
        event(projectA, """
                {"kind":"MEETING","title":"공정회의","startsAt":"2026-01-12T10:00","endsAt":"2026-01-12T11:00",
                 "counterpartOrg":"가온시 하수과","attendees":[{"userId":%d}]}""".formatted(leeId));
        for (int day = 0; day <= 12; day++) { // 2026-01-05 … 2026-01-17
            clock.set(START.plus(Duration.ofDays(day)));
            job.createDueNotifications();
        }
        assertThat(reminders(leeId)).containsExactly("공정회의 2026-01-12 D-1", "한국환경공단 방문 2026-01-15 D-1",
                "한국환경공단 방문 2026-01-15 D-3");
        assertThat(reminders(kimId)).containsExactly("한국환경공단 방문 2026-01-15 D-1", "한국환경공단 방문 2026-01-15 D-3");
        assertThat(reminders(choId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT link FROM notification WHERE user_id = ? LIMIT 1", String.class, leeId))
                .isEqualTo("/projects/" + projectA + "/schedule");
    }

    @Test
    void moving_an_event_drops_the_old_dates_unsent_reminders() throws Exception {
        long ev = event(projectA, """
                {"kind":"REVIEW","title":"설계 심의","startsAt":"2026-01-08T14:00","endsAt":"2026-01-08T16:00",
                 "attendees":[{"userId":%d}]}""".formatted(leeId));
        job.createDueNotifications(); // D-3 of 01-08 is created, not mailed yet
        assertThat(reminders(leeId)).containsExactly("설계 심의 2026-01-08 D-3");

        call(lee, json(patch(events(projectA) + "/" + ev), """
                {"kind":"REVIEW","title":"설계 심의","startsAt":"2026-01-28T14:00","endsAt":"2026-01-28T16:00",
                 "attendees":[{"userId":%d}]}""".formatted(leeId))).andExpect(status().isOk());
        assertThat(reminders(leeId)).isEmpty();
        job.sendDigests();
        assertThat(mailer.sent()).isEmpty();

        clock.set(START.plus(Duration.ofDays(20))); // 2026-01-25 = D-3 of the new date
        job.createDueNotifications();
        job.sendDigests();
        assertThat(reminders(leeId)).containsExactly("설계 심의 2026-01-28 D-3");
        assertThat(mailer.sent()).hasSize(2); // lee and the PM
        assertThat(mailer.sent().get(0).body()).contains("설계 심의 (2026-01-28)").doesNotContain("2026-01-08");
    }

    // --- calendar -------------------------------------------------------------------------------------------

    @Test
    void company_calendar_shows_member_projects_and_everything_to_the_company_admin() throws Exception {
        event(projectA, """
                {"kind":"TRIP","title":"가온시 출장","startsAt":"2026-01-20T00:00","endsAt":"2026-01-22T00:00","allDay":true,
                 "counterpartOrg":"가온시 환경과"}""");
        event(projectB, """
                {"kind":"REVIEW","title":"나래군 심의","startsAt":"2026-01-21T14:00","endsAt":"2026-01-21T16:00"}""", kim);
        call(kim, json(post("/api/projects/" + projectB + "/schedule/items"), """
                {"kind":"MILESTONE","title":"실시설계 제출","endDate":"2026-01-25"}""")).andExpect(status().isCreated());
        call(kim, json(post("/api/projects/" + projectB + "/permits"), """
                {"name":"배출시설 설치허가","category":"CONSTRUCTION","expiryDate":"2026-01-30","status":"COMPLETED"}"""))
                .andExpect(status().isCreated());

        String month = "/api/calendar?from=2026-01-01&to=2026-01-31";
        call(lee, get(month)).andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].title").value("가온시 출장"))
                .andExpect(jsonPath("$.milestones.length()").value(0))
                .andExpect(jsonPath("$.deadlines.length()").value(0));
        call(cho, get(month)).andExpect(jsonPath("$.events.length()").value(0));
        call(park, get(month)).andExpect(jsonPath("$.events.length()").value(0));
        call(kim, get(month))
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.milestones[0].title").value("실시설계 제출"))
                .andExpect(jsonPath("$.milestones[0].projectName").value("나래군 폐수처리시설"))
                .andExpect(jsonPath("$.deadlines.length()").value(1))
                .andExpect(jsonPath("$.deadlines[0].kind").value("PERMIT_EXPIRY"));
        // a non-member asking for a project by id still gets nothing
        call(lee, get(month + "&project=" + projectB)).andExpect(jsonPath("$.events.length()").value(0));

        // filters: kind (출장만) drops milestones and deadlines; team; department
        call(kim, get(month + "&kind=TRIP")).andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].kind").value("TRIP"))
                .andExpect(jsonPath("$.milestones.length()").value(0)).andExpect(jsonPath("$.deadlines.length()").value(0));
        call(kim, get(month + "&team=" + teamA)).andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].title").value("가온시 출장"));
        call(kim, get(month + "&dept=" + deptB)).andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].title").value("나래군 심의"));
        call(kim, get(month + "&project=" + projectA)).andExpect(jsonPath("$.events.length()").value(1));
        call(kim, get(month + "&kind=PARTY")).andExpect(status().isBadRequest());
        call(kim, get("/api/calendar?from=2026-01-01&to=2027-06-01")).andExpect(status().isBadRequest());
    }

    @Test
    void calendar_range_includes_events_overlapping_its_first_and_last_day() throws Exception {
        event(projectA, """
                {"kind":"TRIP","title":"걸쳐 시작","startsAt":"2026-01-28T00:00","endsAt":"2026-02-02T00:00","allDay":true,
                 "counterpartOrg":"가온시"}""");
        event(projectA, """
                {"kind":"REVIEW","title":"마지막 날 밤","startsAt":"2026-02-28T23:30","endsAt":"2026-02-28T23:59"}""");
        event(projectA, """
                {"kind":"REVIEW","title":"다음 달 첫날","startsAt":"2026-03-01T00:00","endsAt":"2026-03-01T01:00"}""");
        event(projectA, """
                {"kind":"REVIEW","title":"전날 끝","startsAt":"2026-01-31T10:00","endsAt":"2026-01-31T23:59"}""");
        call(lee, get("/api/calendar?from=2026-02-01&to=2026-02-28"))
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.events[0].title").value("걸쳐 시작"))
                .andExpect(jsonPath("$.events[1].title").value("마지막 날 밤"));
    }

    @Test
    void project_events_are_not_listed_as_deadlines() throws Exception {
        event(projectA, """
                {"kind":"SITE_BRIEFING","title":"현장설명회","startsAt":"2026-01-20T14:00","endsAt":"2026-01-20T16:00"}""");
        call(lee, get("/api/deadlines?from=2026-01-01&to=2026-01-31")).andExpect(jsonPath("$.length()").value(0));
    }

    // --- helpers --------------------------------------------------------------------------------------------

    /** "title due D-n" of the user's event reminders, sorted. */
    private List<String> reminders(long userId) {
        return jdbc.queryForList("""
                SELECT e.title || ' ' || n.due_date || ' D-' || n.offset_days FROM notification n
                JOIN deadline_event e ON e.id = n.deadline_event_id
                WHERE n.user_id = ? AND e.kind = 'PROJECT_EVENT' ORDER BY 1""", String.class, userId);
    }

    private long event(long projectId, String body) throws Exception {
        return event(projectId, body, lee);
    }

    private long event(long projectId, String body, String token) throws Exception {
        return id(call(token, json(post(events(projectId)), body)).andExpect(status().isCreated()));
    }

    private static String events(long projectId) {
        return "/api/projects/" + projectId + "/events";
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
