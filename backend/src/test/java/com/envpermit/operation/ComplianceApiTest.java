package com.envpermit.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.envpermit.core.company.Company;
import com.envpermit.core.company.CompanyRepository;
import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import com.envpermit.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * operations-compliance/07: 규제 대응 현황. Today is 2026-06-15 (Asia/Seoul), so the default period is
 * 2026-01-01 ~ 2026-06-15. Company A: kim (member of 광명), lee (member of 수원), boss (company ADMIN, member of
 * neither). Company B: jung with one project. Past cycles are inserted as rows; the open ones come from the API.
 */
class ComplianceApiTest extends IntegrationTest {

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;

    String kim;
    String lee;
    String boss;
    String jung;
    long gm; // 광명 (kim)
    long sw; // 수원 (lee)
    long other; // company B
    long gmPermit;

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        clock.set(Instant.parse("2026-06-15T03:00:00Z"));
        long a = companies.save(new Company("A환경")).getId();
        long b = companies.save(new Company("B환경")).getId();
        users.save(new User("kim@a.com", encoder.encode("pw"), "김에이", User.Role.USER, a));
        users.save(new User("lee@a.com", encoder.encode("pw"), "이에이", User.Role.USER, a));
        users.save(new User("boss@a.com", encoder.encode("pw"), "박대표", User.Role.ADMIN, a));
        users.save(new User("jung@b.com", encoder.encode("pw"), "정비", User.Role.ADMIN, b));
        kim = login("kim@a.com");
        lee = login("lee@a.com");
        boss = login("boss@a.com");
        jung = login("jung@b.com");
        gm = project(kim, "광명 자원회수시설");
        sw = project(lee, "수원 자원회수시설");
        other = project(jung, "타사 소각시설");
        gmPermit = id(call(kim, json(post("/api/projects/" + gm + "/permits"), """
                {"name":"대기배출시설 설치허가","category":"FACILITY","status":"COMPLETED"}""")));
    }

    @Test
    void rate_counts_tasks_that_fell_due_and_were_finished_in_time() throws Exception {
        long obligation = obligation("2026-06-30"); // open cycle, due in 15 days: not yet in the rate
        task(gm, "OBLIGATION", obligation, "DONE", "2026-03-31", "2026-03-30T03:00:00Z");
        task(gm, "OBLIGATION", obligation, "DONE", "2026-04-30", "2026-04-30T14:59:59Z"); // 23:59:59 in Seoul: in time
        task(gm, "OBLIGATION", obligation, "DONE", "2026-05-31", "2026-05-31T15:00:00Z"); // 00:00 June 1 in Seoul: late
        task(gm, "OBLIGATION", obligation, "CANCELLED", "2026-02-28", null);
        task(gm, "OBLIGATION", obligation, "DONE", "2025-12-31", "2025-12-30T03:00:00Z"); // before the period
        task(gm, "CORRECTIVE", null, "OPEN", "2026-06-10", null); // 지연 중, 미조치 초과

        call(kim, get("/api/compliance/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-01-01"))
                .andExpect(jsonPath("$.to").value("2026-06-15"))
                .andExpect(jsonPath("$.total.tasks.due").value(4))
                .andExpect(jsonPath("$.total.tasks.onTime").value(2))
                .andExpect(jsonPath("$.total.tasks.late").value(1))
                .andExpect(jsonPath("$.total.tasks.percent").value(50.0))
                .andExpect(jsonPath("$.total.tasks.overdue").value(1))
                .andExpect(jsonPath("$.total.tasks.openCorrective").value(1))
                .andExpect(jsonPath("$.total.tasks.dueSoon").value(1))
                .andExpect(jsonPath("$.projects.length()").value(1))
                .andExpect(jsonPath("$.projects[0].name").value("광명 자원회수시설"))
                .andExpect(jsonPath("$.projects[0].metrics.tasks.percent").value(50.0));

        // a wider period takes in the December cycle; a period in the future has nothing due yet
        call(kim, get("/api/compliance/summary?from=2025-12-01&to=2026-12-31"))
                .andExpect(jsonPath("$.total.tasks.due").value(5))
                .andExpect(jsonPath("$.total.tasks.percent").value(60.0));
        call(kim, get("/api/compliance/summary?from=2026-06-16&to=2026-12-31"))
                .andExpect(jsonPath("$.total.tasks.due").value(0))
                .andExpect(jsonPath("$.total.tasks.percent").value(nullValue()))
                .andExpect(jsonPath("$.total.tasks.overdue").value(1)); // today's numbers do not follow the period
        call(kim, get("/api/compliance/summary?from=2026-06-01&to=2026-01-01")).andExpect(status().isBadRequest());

        // the project page: same numbers, plus the rate per permit item (the corrective row has no permit item)
        call(kim, get("/api/projects/" + gm + "/compliance/summary"))
                .andExpect(jsonPath("$.total.tasks.percent").value(50.0))
                .andExpect(jsonPath("$.permits.length()").value(2))
                .andExpect(jsonPath("$.permits[0].permitItemId").value(gmPermit))
                .andExpect(jsonPath("$.permits[0].name").value("대기배출시설 설치허가"))
                .andExpect(jsonPath("$.permits[0].due").value(3))
                .andExpect(jsonPath("$.permits[0].percent").value(66.7))
                .andExpect(jsonPath("$.permits[1].permitItemId").value(nullValue()))
                .andExpect(jsonPath("$.permits[1].percent").value(0.0));

        // monthly: the same formula per month, up to today
        call(kim, get("/api/compliance/trend"))
                .andExpect(jsonPath("$[*].month", contains("2026-01", "2026-02", "2026-03", "2026-04", "2026-05", "2026-06")))
                .andExpect(jsonPath("$[*].due", contains(0, 0, 1, 1, 1, 1)))
                .andExpect(jsonPath("$[*].percent", contains(null, null, 100.0, 100.0, 0.0, 0.0)));
    }

    @Test
    void nothing_due_is_not_applicable_and_an_unapproved_suggestion_counts_nowhere() throws Exception {
        call(kim, json(post("/api/projects/" + gm + "/suggestions"), SUGGESTION.formatted(gmPermit))).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM obligation_task", Integer.class)).isZero();

        for (String url : List.of("/api/compliance/summary", "/api/projects/" + gm + "/compliance/summary")) {
            call(kim, get(url))
                    .andExpect(jsonPath("$.total.tasks.due").value(0))
                    .andExpect(jsonPath("$.total.tasks.percent").value(nullValue())) // 해당 없음, not 100
                    .andExpect(jsonPath("$.total.tasks.overdue").value(0))
                    .andExpect(jsonPath("$.total.tasks.dueSoon").value(0))
                    .andExpect(jsonPath("$.total.exceeded").value(0))
                    .andExpect(jsonPath("$.total.siteOpen").value(0));
        }
        call(kim, get("/api/compliance/summary")).andExpect(jsonPath("$.projects[0].metrics.tasks.percent").value(nullValue()));
        call(kim, get("/api/projects/" + gm + "/compliance/summary")).andExpect(jsonPath("$.permits.length()").value(0));
        call(kim, get("/api/compliance/trend")).andExpect(jsonPath("$[*].percent", contains(null, null, null, null, null, null)));
    }

    @Test
    void a_member_sees_own_projects_the_admin_the_whole_company_and_counts_are_added() throws Exception {
        task(gm, "CORRECTIVE", null, "DONE", "2026-03-31", "2026-03-31T03:00:00Z"); // 광명 1/1 = 100%
        task(sw, "CORRECTIVE", null, "DONE", "2026-03-31", "2026-03-31T03:00:00Z"); // 수원 1/4 = 25%
        task(sw, "CORRECTIVE", null, "DONE", "2026-04-30", "2026-05-02T03:00:00Z");
        task(sw, "CORRECTIVE", null, "OPEN", "2026-05-31", null);
        task(sw, "CORRECTIVE", null, "OPEN", "2026-06-01", null);
        task(other, "CORRECTIVE", null, "OPEN", "2026-06-01", null); // another company: never counted for A
        long dept = jdbc.queryForObject("INSERT INTO department (company_id, name) SELECT company_id, '환경사업부' FROM project "
                + "WHERE id = ? RETURNING id", Long.class, sw);
        long team = jdbc.queryForObject("INSERT INTO team (department_id, name) VALUES (?, '1팀') RETURNING id", Long.class, dept);
        jdbc.update("UPDATE project SET team_id = ? WHERE id = ?", team, sw);

        call(kim, get("/api/compliance/summary"))
                .andExpect(jsonPath("$.scope").value("MINE"))
                .andExpect(jsonPath("$.projectCount").value(1))
                .andExpect(jsonPath("$.projects[*].projectId", contains((int) gm)))
                .andExpect(jsonPath("$.total.tasks.percent").value(100.0))
                .andExpect(jsonPath("$.total.tasks.overdue").value(0));
        call(kim, get("/api/compliance/trend")).andExpect(jsonPath("$[2].due").value(1)).andExpect(jsonPath("$[4].due").value(0));

        call(boss, get("/api/compliance/summary")) // not a member of either project
                .andExpect(jsonPath("$.scope").value("COMPANY"))
                .andExpect(jsonPath("$.projectCount").value(2))
                .andExpect(jsonPath("$.projects[*].projectId", contains((int) gm, (int) sw)))
                .andExpect(jsonPath("$.projects[*].metrics.tasks.percent", contains(100.0, 25.0)))
                .andExpect(jsonPath("$.projects[1].teamName").value("환경사업부 / 1팀"))
                .andExpect(jsonPath("$.total.tasks.due").value(5))
                .andExpect(jsonPath("$.total.tasks.onTime").value(2))
                .andExpect(jsonPath("$.total.tasks.percent").value(40.0)) // 2/5, not the mean 62.5
                .andExpect(jsonPath("$.total.tasks.late").value(1))
                .andExpect(jsonPath("$.total.tasks.overdue").value(2))
                .andExpect(jsonPath("$.teams.length()").value(1));
        call(boss, get("/api/compliance/trend")).andExpect(jsonPath("$[*].due", contains(0, 0, 2, 1, 1, 1)));

        // team filter: rows, total and trend follow it; the scope line and the team list do not
        call(boss, get("/api/compliance/summary?teamId=" + team))
                .andExpect(jsonPath("$.projectCount").value(2))
                .andExpect(jsonPath("$.teams[0].id").value(team))
                .andExpect(jsonPath("$.projects[*].projectId", contains((int) sw)))
                .andExpect(jsonPath("$.total.tasks.percent").value(25.0));
        call(boss, get("/api/compliance/trend?teamId=" + team)).andExpect(jsonPath("$[*].due", contains(0, 0, 1, 1, 1, 1)));

        // project page: members and the company admin only
        call(boss, get("/api/projects/" + sw + "/compliance/summary")).andExpect(jsonPath("$.total.tasks.percent").value(25.0));
        call(kim, get("/api/projects/" + sw + "/compliance/summary")).andExpect(status().isForbidden());
        call(boss, get("/api/projects/" + other + "/compliance/summary")).andExpect(status().isForbidden());
        call(jung, get("/api/projects/" + gm + "/compliance/summary")).andExpect(status().isForbidden());
        call(jung, get("/api/compliance/summary"))
                .andExpect(jsonPath("$.projects[*].projectId", contains((int) other)))
                .andExpect(jsonPath("$.total.tasks.due").value(1));
    }

    @Test
    void measurements_expiring_permits_and_site_follow_ups_are_counted_beside_the_rate() throws Exception {
        long stack = id(call(kim, json(post("/api/projects/" + gm + "/emission-points"),
                "{\"medium\":\"AIR\",\"name\":\"소각로 1호기 굴뚝\",\"permitItemId\":" + gmPermit + "}")).andExpect(status().isCreated()));
        call(kim, json(post("/api/emission-points/" + stack + "/limits"),
                "{\"pollutant\":\"CO\",\"limitValue\":30,\"effectiveFrom\":\"2025-01-01\"}")).andExpect(status().isCreated());
        measure(stack, "2026-06-10", "31"); // 초과 → corrective task
        measure(stack, "2026-06-11", "25"); // 근접
        measure(stack, "2026-06-12", "12"); // 정상
        measure(stack, "2025-12-20", "40"); // 초과, but measured before the period
        long wrong = measure(stack, "2026-06-13", "45");
        call(kim, json(delete("/api/measurements/" + wrong), "{\"reason\":\"단위 오입력\"}")).andExpect(status().is2xxSuccessful());

        jdbc.update("UPDATE permit_item SET status = 'NEAR_EXPIRY' WHERE id = ?", gmPermit);
        long obligation = obligation("2026-06-30");
        jdbc.update("INSERT INTO revision_implementation (suggestion_id, obligation_id) "
                + "SELECT id, ? FROM ai_suggestion ORDER BY id LIMIT 1", obligation);

        for (String url : List.of("/api/compliance/summary", "/api/projects/" + gm + "/compliance/summary")) {
            call(kim, get(url))
                    .andExpect(jsonPath("$.total.exceeded").value(1)) // the deleted and the December value are out
                    .andExpect(jsonPath("$.total.near").value(1))
                    .andExpect(jsonPath("$.total.nearExpiry").value(1))
                    .andExpect(jsonPath("$.total.siteOpen").value(1))
                    .andExpect(jsonPath("$.total.tasks.openCorrective").value(1))
                    .andExpect(jsonPath("$.total.tasks.percent").value(nullValue())); // the corrective task is due in 7 days
        }
        call(kim, get("/api/compliance/summary")).andExpect(jsonPath("$.projects[0].metrics.exceeded").value(1));
        call(kim, get("/api/compliance/summary?from=2025-12-01&to=2026-06-15")).andExpect(jsonPath("$.total.exceeded").value(2));
        call(lee, get("/api/compliance/summary")).andExpect(jsonPath("$.total.exceeded").value(0)); // 수원 has none
    }

    static final String SUGGESTION = """
            {"permitItemId":%d,"payload":{"citation_raw":"허가조건 제4호","summary":"굴뚝 배출가스 월 1회 측정",
             "requirement_text":"소각로 굴뚝 배출가스를 월 1회 이상 측정할 것",
             "type":"MEASURE_SURVEY","category":"AIR_QUALITY","frequency":"MONTHLY"}}""";

    /** An approved monthly obligation of 광명's permit; its first cycle is the open task. */
    private long obligation(String firstDue) throws Exception {
        long suggestion = id(call(kim, json(post("/api/projects/" + gm + "/suggestions"), SUGGESTION.formatted(gmPermit)))
                .andExpect(status().isCreated()));
        return id(call(kim, json(post("/api/suggestions/" + suggestion + "/accept"),
                "{\"firstDueDate\":\"" + firstDue + "\",\"version\":0}")).andExpect(status().isOk()), "$.appliedEntityId");
    }

    /** A past cycle as a row: the API cannot finish a task in the past. */
    private void task(long project, String kind, Long obligation, String status, String due, String doneAt) {
        jdbc.update("INSERT INTO obligation_task (project_id, kind, obligation_id, title, due_date, status, done_at) "
                + "VALUES (?, ?, ?, '지난 회차', ?::date, ?, ?::timestamptz)", project, kind, obligation, due, status, doneAt);
    }

    private long measure(long point, String on, String value) throws Exception {
        return id(call(kim, json(post("/api/emission-points/" + point + "/measurements"), """
                {"rows":[{"measuredOn":"%s","pollutant":"CO","value":%s,"unit":"ppm"}]}""".formatted(on, value)))
                .andExpect(status().isCreated()), "$[0].id");
    }

    private long project(String token, String name) throws Exception {
        return id(call(token, json(post("/api/projects"),
                "{\"name\":\"" + name + "\",\"facilityType\":\"INCINERATION\",\"location\":\"경기\"}")));
    }

    private ResultActions call(String token, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + token));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, String body) {
        return req.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static long id(ResultActions r) throws Exception {
        return id(r, "$.id");
    }

    private static long id(ResultActions r, String path) throws Exception {
        Object v = JsonPath.read(r.andReturn().getResponse().getContentAsString(), path);
        return ((Number) (v instanceof List<?> l ? l.get(0) : v)).longValue();
    }

    private String login(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"pw\"}".formatted(email)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
