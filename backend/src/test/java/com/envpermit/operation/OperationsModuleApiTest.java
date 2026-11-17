package com.envpermit.operation;

import static org.assertj.core.api.Assertions.assertThat;
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
 * design-schedule/01: the 운영 module is opt-in per project. kim created the project (admin), lee is a plain member,
 * choi is the PM without being a member (no PM powers until added), park belongs to another company. Today is 2026-01-05.
 */
class OperationsModuleApiTest extends IntegrationTest {

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;
    @Autowired InMemoryMailer mailer;
    @Autowired NotificationJob job;
    @Autowired ExceedanceAlerts alerts;

    String kim;
    String lee;
    String choi;
    String park;
    long kimId;
    long leeId;
    long projectId;
    long permitId;

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        mailer.clear();
        long a = companies.save(new Company("가온엔지니어링")).getId();
        long b = companies.save(new Company("다온설계")).getId();
        kimId = users.save(new User("kim@a.com", encoder.encode("pw"), "김가온", User.Role.USER, a)).getId();
        leeId = users.save(new User("lee@a.com", encoder.encode("pw"), "이가온", User.Role.USER, a)).getId();
        long choiId = users.save(new User("choi@a.com", encoder.encode("pw"), "최가온", User.Role.USER, a)).getId();
        users.save(new User("park@b.com", encoder.encode("pw"), "박다온", User.Role.USER, b));
        kim = login("kim@a.com");
        lee = login("lee@a.com");
        choi = login("choi@a.com");
        park = login("park@b.com");
        projectId = id(call(kim, json(post("/api/projects"), """
                {"name":"가온시 자원회수시설","facilityType":"INCINERATION","location":"가온시","pmUserId":%d}""".formatted(choiId))));
        call(kim, json(post("/api/projects/" + projectId + "/members"), "{\"userId\":" + leeId + "}")).andExpect(status().isCreated());
        permitId = id(call(kim, json(post("/api/projects/" + projectId + "/permits"), """
                {"name":"대기배출시설 설치허가","category":"FACILITY","status":"COMPLETED"}""")));
    }

    @Test
    void off_by_default_and_every_operations_route_answers_409() throws Exception {
        call(kim, get("/api/projects/" + projectId)).andExpect(jsonPath("$.operationsEnabled").value(false));
        call(kim, get("/api/projects")).andExpect(jsonPath("$[0].operationsEnabled").value(false));

        disabled(call(kim, get("/api/projects/" + projectId + "/emission-points")));
        disabled(call(kim, json(post("/api/projects/" + projectId + "/emission-points"), "{\"medium\":\"AIR\",\"name\":\"1호기 굴뚝\"}")));
        disabled(call(kim, get("/api/projects/" + projectId + "/permit-items/" + permitId
                + "/compliance-reports/preview?from=2026-01-01&to=2026-01-31")));
        // a non-member still gets 403, not a hint about the module
        call(park, get("/api/projects/" + projectId + "/emission-points")).andExpect(status().isForbidden());
    }

    @Test
    void only_project_admins_pm_and_em_switch_it_and_each_change_is_logged() throws Exception {
        modules(lee, true).andExpect(status().isForbidden());
        modules(park, true).andExpect(status().isForbidden());
        call(kim, json(patch("/api/projects/" + projectId + "/modules"), "{}")).andExpect(status().isBadRequest());

        modules(choi, true).andExpect(status().isForbidden()); // PM, but not a member (M1)
        jdbc.update("INSERT INTO project_member (project_id, user_id) SELECT ?, id FROM users WHERE email = 'choi@a.com'", projectId);
        modules(choi, true).andExpect(status().isOk()).andExpect(jsonPath("$.operationsEnabled").value(true)); // member PM
        modules(kim, true).andExpect(status().isOk()); // no change, no log row
        modules(kim, false).andExpect(status().isOk()).andExpect(jsonPath("$.operationsEnabled").value(false));

        assertThat(jdbc.queryForList("SELECT module || ' ' || enabled || ' ' || actor_id FROM project_module_event "
                + "WHERE project_id = ? ORDER BY id", String.class, projectId))
                .containsExactly("OPERATIONS true " + jdbc.queryForObject("SELECT id FROM users WHERE email = 'choi@a.com'", Long.class),
                        "OPERATIONS false " + kimId);
    }

    @Test
    void switching_off_keeps_the_data_and_switching_on_brings_it_back() throws Exception {
        modules(kim, true).andExpect(status().isOk());
        long stack = stack();
        measure(stack, "31").andExpect(status().isCreated()).andExpect(jsonPath("$[0].level").value("EXCEEDED"));

        modules(kim, false).andExpect(status().isOk());
        disabled(measure(stack, "10"));
        disabled(call(kim, get("/api/emission-points/" + stack + "/measurements")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM measurement", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM permit_limit", Integer.class)).isEqualTo(1);

        modules(kim, true).andExpect(status().isOk());
        call(kim, get("/api/emission-points/" + stack + "/measurements"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].value").value(31));
    }

    @Test
    void an_exceedance_on_an_enabled_project_alerts_but_on_a_disabled_one_nothing_happens() throws Exception {
        modules(kim, true).andExpect(status().isOk());
        long stack = stack();
        measure(stack, "31").andExpect(status().isCreated());
        assertThat(correctiveTasks()).isEqualTo(1); // enabled: a corrective task and notices
        assertThat(notices()).isEqualTo(2); // kim and lee

        modules(kim, false).andExpect(status().isOk());
        // a value over the limit that reaches the alerts anyway (e.g. written before the switch)
        long m = jdbc.queryForObject("""
                INSERT INTO measurement (emission_point_id, pollutant, measured_on, value, unit, below_detection, source,
                                         limit_id, limit_value, warn_ratio, level, entered_by, entered_at)
                SELECT ?, 'CO', '2026-01-05', 40, 'ppm', FALSE, 'MANUAL', id, limit_value, warn_ratio, 'EXCEEDED', ?, now()
                FROM permit_limit RETURNING id""", Long.class, stack, kimId);
        alerts.raise(projectId, stack, List.of(m), kimId);
        disabled(measure(stack, "50"));
        assertThat(correctiveTasks()).isEqualTo(1); // nothing new: no task, value appended or not
        assertThat(notices()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_event WHERE type = 'MEASUREMENT_ADDED'", Integer.class)).isZero();
    }

    @Test
    void corrective_task_reminders_stop_while_the_module_is_off() throws Exception {
        modules(kim, true).andExpect(status().isOk());
        measure(stack(), "31").andExpect(status().isCreated()); // corrective task due 2026-01-12 = D-7 today
        modules(kim, false).andExpect(status().isOk());
        job.createDueNotifications();
        assertThat(reminders()).isZero();

        modules(kim, true).andExpect(status().isOk());
        job.createDueNotifications();
        assertThat(reminders()).isEqualTo(1);
    }

    @Test
    void the_obligation_rate_counts_while_off_but_measurement_counts_only_for_enabled_projects() throws Exception {
        obligation(); // first due 2026-01-31
        modules(kim, true).andExpect(status().isOk());
        measure(stack(), "31").andExpect(status().isCreated());
        modules(kim, false).andExpect(status().isOk());
        clock.set(Instant.parse("2026-02-02T00:00:00Z"));
        kim = login("kim@a.com"); // the old token expired with the clock jump

        call(kim, get("/api/compliance/summary"))
                .andExpect(jsonPath("$.operations").value(false))
                .andExpect(jsonPath("$.projects[0].operations").value(false))
                .andExpect(jsonPath("$.total.exceeded").value(0))
                .andExpect(jsonPath("$.total.tasks.due").value(1)) // the obligation's cycle; the corrective task is out
                .andExpect(jsonPath("$.total.tasks.overdue").value(1));
        call(kim, get("/api/projects/" + projectId + "/compliance/summary"))
                .andExpect(jsonPath("$.operations").value(false))
                .andExpect(jsonPath("$.total.exceeded").value(0))
                .andExpect(jsonPath("$.total.tasks.due").value(1));

        modules(kim, true).andExpect(status().isOk());
        call(kim, get("/api/compliance/summary"))
                .andExpect(jsonPath("$.operations").value(true))
                .andExpect(jsonPath("$.total.exceeded").value(1))
                .andExpect(jsonPath("$.total.tasks.due").value(2)); // the corrective task counts again
    }

    @Test
    void open_corrective_tasks_are_hidden_while_off_and_come_back_when_on() throws Exception {
        modules(kim, true).andExpect(status().isOk());
        measure(stack(), "31").andExpect(status().isCreated()); // corrective task due 2026-01-12
        jdbc.update("UPDATE obligation_task SET assignee_id = ? WHERE kind = 'CORRECTIVE'", kimId);
        jdbc.update("UPDATE deadline_event SET assignee_id = ? WHERE kind = 'CORRECTIVE_TASK'", kimId);
        String deadlines = "/api/deadlines?from=2026-01-01&to=2026-01-31";
        String corrective = "[?(@.kind == 'CORRECTIVE')]";

        modules(kim, false).andExpect(status().isOk());
        call(kim, get("/api/projects/" + projectId + "/tasks")).andExpect(jsonPath("$" + corrective).isEmpty()); // 의무사항
        call(kim, get("/api/tasks/mine")).andExpect(jsonPath("$" + corrective).isEmpty()); // 내 할 일
        call(kim, get(deadlines)).andExpect(jsonPath("$[?(@.kind == 'CORRECTIVE_TASK')]").isEmpty());

        modules(kim, true).andExpect(status().isOk());
        call(kim, get("/api/projects/" + projectId + "/tasks")).andExpect(jsonPath("$" + corrective + ".status").value("OPEN"));
        call(kim, get("/api/tasks/mine")).andExpect(jsonPath("$" + corrective + ".status").value("OPEN"));
        call(kim, get(deadlines)).andExpect(jsonPath("$[?(@.kind == 'CORRECTIVE_TASK')]").isNotEmpty());
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private ResultActions modules(String token, boolean on) throws Exception {
        return call(token, json(patch("/api/projects/" + projectId + "/modules"), "{\"operations\":" + on + "}"));
    }

    private static void disabled(ResultActions r) throws Exception {
        r.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
    }

    /** A stack with a CO limit of 30 ppm. */
    private long stack() throws Exception {
        long stack = id(call(kim, json(post("/api/projects/" + projectId + "/emission-points"),
                "{\"medium\":\"AIR\",\"name\":\"소각로 1호기 굴뚝\",\"permitItemId\":" + permitId + "}")).andExpect(status().isCreated()));
        call(kim, json(post("/api/emission-points/" + stack + "/limits"),
                "{\"pollutant\":\"CO\",\"limitValue\":30,\"effectiveFrom\":\"2026-01-01\"}")).andExpect(status().isCreated());
        return stack;
    }

    private ResultActions measure(long stack, String value) throws Exception {
        return call(kim, json(post("/api/emission-points/" + stack + "/measurements"), """
                {"rows":[{"measuredOn":"2026-01-05","pollutant":"CO","value":%s,"unit":"ppm"}]}""".formatted(value)));
    }

    /** An approved monthly obligation of the permit, first due 2026-01-31. */
    private void obligation() throws Exception {
        long suggestion = id(call(kim, json(post("/api/projects/" + projectId + "/suggestions"), """
                {"permitItemId":%d,"payload":{"citation_raw":"허가조건 제4호","summary":"굴뚝 배출가스 월 1회 측정",
                 "requirement_text":"소각로 굴뚝 배출가스를 월 1회 이상 측정할 것",
                 "type":"MEASURE_SURVEY","category":"AIR_QUALITY","frequency":"MONTHLY"}}""".formatted(permitId)))
                .andExpect(status().isCreated()));
        call(kim, json(post("/api/suggestions/" + suggestion + "/accept"), "{\"firstDueDate\":\"2026-01-31\",\"version\":0}"))
                .andExpect(status().isOk());
    }

    private int correctiveTasks() {
        return jdbc.queryForObject("SELECT count(*) FROM obligation_task WHERE kind = 'CORRECTIVE'", Integer.class);
    }

    private int notices() {
        return jdbc.queryForObject("SELECT count(*) FROM notification WHERE deadline_event_id IS NULL", Integer.class);
    }

    private int reminders() {
        return jdbc.queryForObject("SELECT count(*) FROM notification n JOIN deadline_event e ON e.id = n.deadline_event_id "
                + "WHERE e.kind = 'CORRECTIVE_TASK'", Integer.class);
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
