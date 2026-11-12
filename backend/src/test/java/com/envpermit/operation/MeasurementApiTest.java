package com.envpermit.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * operations-compliance/04: measured values judged against the permit limit. kim = project member, park = same
 * company, not a member. The stack has one limit: CO 30 ppm, warn ratio 0.80, from 2026-01-01. Today is 2026-01-05.
 */
class MeasurementApiTest extends IntegrationTest {

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;

    String kim;
    String park;
    long projectId;
    long permitId;
    long stack;
    long coLimit;
    String measurements;

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        long a = companies.save(new Company("A환경")).getId();
        users.save(new User("kim@a.com", encoder.encode("pw"), "김에이", User.Role.USER, a));
        users.save(new User("park@a.com", encoder.encode("pw"), "박에이", User.Role.USER, a));
        kim = login("kim@a.com");
        park = login("park@a.com");
        projectId = project("가온 자원회수시설");
        permitId = id(call(kim, json(post("/api/projects/" + projectId + "/permits"), """
                {"name":"대기배출시설 설치허가","category":"FACILITY","status":"COMPLETED"}""")));
        stack = point(projectId);
        coLimit = id(call(kim, json(post("/api/emission-points/" + stack + "/limits"), """
                {"pollutant":"CO","limitValue":30,"effectiveFrom":"2026-01-01"}""")).andExpect(status().isCreated()));
        measurements = "/api/emission-points/" + stack + "/measurements";
    }

    @Test
    void saving_judges_each_row_and_keeps_the_limit_of_the_measured_day() throws Exception {
        call(kim, json(post(measurements), """
                {"rows":[
                  {"measuredOn":"2026-01-05","pollutant":"CO","value":23.9,"unit":"ppm"},
                  {"measuredOn":"2026-01-05","pollutant":"CO","value":24,"unit":"ppm"},
                  {"measuredOn":"2026-01-05","pollutant":"CO","value":30,"unit":"ppm"},
                  {"measuredOn":"2026-01-05","pollutant":"CO","value":30.1,"unit":"ppm"},
                  {"measuredOn":"2026-01-05","pollutant":"CO","value":45,"unit":"ppm","belowDetection":true},
                  {"measuredOn":"2026-01-05","pollutant":"NOX","value":50,"unit":"ppm"},
                  {"measuredOn":"2025-12-31","pollutant":"CO","value":45,"unit":"ppm"}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[*].level", contains("NORMAL", "NEAR", "NEAR", "EXCEEDED", "NORMAL", "NO_LIMIT", "NO_LIMIT")))
                .andExpect(jsonPath("$[1].limitId").value(coLimit))
                .andExpect(jsonPath("$[1].limitValue").value(30))
                .andExpect(jsonPath("$[1].warnRatio").value(0.8))
                .andExpect(jsonPath("$[1].pollutantName").value("일산화탄소"))
                .andExpect(jsonPath("$[1].source").value("MANUAL"))
                .andExpect(jsonPath("$[1].enteredByName").value("김에이"))
                .andExpect(jsonPath("$[4].belowDetection").value(true))
                .andExpect(jsonPath("$[5].limitId").doesNotExist()) // no NOX limit
                .andExpect(jsonPath("$[5].limitValue").doesNotExist())
                .andExpect(jsonPath("$[6].limitValue").doesNotExist()); // measured before the limit took effect

        call(kim, get(measurements)).andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[0].pollutant").value("NOX")) // newest day first, then newest entry
                .andExpect(jsonPath("$[6].measuredOn").value("2025-12-31"));
        call(kim, get(measurements + "?pollutant=CO&from=2026-01-01&to=2026-01-31")).andExpect(jsonPath("$.length()").value(5));
    }

    @Test
    void a_limit_change_leaves_past_judgements_as_they_were() throws Exception {
        long before = id(measure("2026-01-03", "CO", "28", "ppm", null).andExpect(jsonPath("$[0].level").value("NEAR")), "$[0].id");

        call(kim, json(post("/api/emission-points/" + stack + "/limits"),
                "{\"pollutant\":\"CO\",\"limitValue\":20,\"warnRatio\":0.5,\"effectiveFrom\":\"2026-02-01\",\"replacesLimitId\":" + coLimit + "}"))
                .andExpect(status().isCreated());

        call(kim, get(measurements)).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(before))
                .andExpect(jsonPath("$[0].level").value("NEAR"))
                .andExpect(jsonPath("$[0].limitId").value(coLimit))
                .andExpect(jsonPath("$[0].limitValue").value(30))
                .andExpect(jsonPath("$[0].warnRatio").value(0.8));
        // same value under the new limit, and a late entry for a day the old limit still covered
        measure("2026-02-02", "CO", "28", "ppm", null).andExpect(jsonPath("$[0].level").value("EXCEEDED"))
                .andExpect(jsonPath("$[0].limitValue").value(20));
        measure("2026-02-02", "CO", "10", "ppm", null).andExpect(jsonPath("$[0].level").value("NEAR")); // 20 × 0.5
        measure("2026-01-31", "CO", "28", "ppm", null).andExpect(jsonPath("$[0].level").value("NEAR"))
                .andExpect(jsonPath("$[0].limitId").value(coLimit));
    }

    @Test
    void wrong_unit_or_item_is_422_and_saves_nothing() throws Exception {
        measure("2026-01-05", "CO", "10", "mg/L", null).andExpect(status().isUnprocessableEntity());   // limit unit
        measure("2026-01-05", "NOX", "10", "mg/L", null).andExpect(status().isUnprocessableEntity());  // no limit: seed unit
        measure("2026-01-05", "BOD", "10", "mg/L", null).andExpect(status().isUnprocessableEntity());  // water item on an air point
        measure("2026-01-05", "XYZ", "10", "ppm", null).andExpect(status().isUnprocessableEntity());
        call(kim, json(post(measurements), """
                {"rows":[{"measuredOn":"2026-01-05","pollutant":"CO","value":10,"unit":"ppm"},
                         {"measuredOn":"2026-01-05","pollutant":"CO","value":10,"unit":"ppb"}]}"""))
                .andExpect(status().isUnprocessableEntity()); // all rows or none
        measure("2026-01-05", "CO", "-1", "ppm", null).andExpect(status().isBadRequest());
        call(kim, json(post(measurements), "{\"rows\":[]}")).andExpect(status().isBadRequest());

        call(kim, get(measurements)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void delete_needs_a_reason_and_takes_the_value_out_of_the_list() throws Exception {
        long wrong = id(measure("2026-01-05", "CO", "300", "ppm", null), "$[0].id");
        measure("2026-01-05", "CO", "3", "ppm", null);

        call(kim, json(delete("/api/measurements/" + wrong), "{\"reason\":\" \"}")).andExpect(status().isBadRequest());
        call(kim, delete("/api/measurements/" + wrong)).andExpect(status().isBadRequest());
        call(kim, get(measurements)).andExpect(jsonPath("$.length()").value(2));

        call(kim, json(delete("/api/measurements/" + wrong), "{\"reason\":\"자릿수 오입력\"}")).andExpect(status().isNoContent());
        call(kim, get(measurements)).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].value").value(3));
        call(kim, json(delete("/api/measurements/" + wrong), "{\"reason\":\"다시\"}")).andExpect(status().isNotFound());
        // the row itself stays, with who and why
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT delete_reason FROM measurement WHERE id = ? AND deleted_by IS NOT NULL", String.class, wrong))
                .isEqualTo("자릿수 오입력");
    }

    @Test
    void a_measurement_entered_for_a_task_is_enough_to_complete_it() throws Exception {
        long task = measureTask();
        call(kim, get("/api/projects/" + projectId + "/obligations")).andExpect(jsonPath("$[0].type").value("MEASURE_SURVEY"));
        call(kim, json(post("/api/tasks/" + task + "/complete"), "{}")).andExpect(status().isUnprocessableEntity());

        long wrong = id(measure("2026-01-05", "CO", "300", "ppm", task).andExpect(jsonPath("$[0].taskId").value(task)), "$[0].id");
        call(kim, get("/api/tasks/" + task)).andExpect(jsonPath("$.task.measurementCount").value(1));
        call(kim, json(delete("/api/measurements/" + wrong), "{\"reason\":\"오입력\"}")).andExpect(status().isNoContent());
        call(kim, json(post("/api/tasks/" + task + "/complete"), "{}")).andExpect(status().isUnprocessableEntity()); // deleted values do not count

        measure("2026-01-05", "CO", "12", "ppm", task).andExpect(status().isCreated());
        call(kim, get("/api/tasks/" + task))
                .andExpect(jsonPath("$.task.evidenceCount").value(0))
                .andExpect(jsonPath("$.task.measurementCount").value(1))
                .andExpect(jsonPath("$.measurements.length()").value(1))
                .andExpect(jsonPath("$.measurements[0].pointName").value("소각로 1호기 굴뚝"))
                .andExpect(jsonPath("$.measurements[0].pollutantName").value("일산화탄소"))
                .andExpect(jsonPath("$.measurements[0].value").value(12))
                .andExpect(jsonPath("$.measurements[0].level").value("NORMAL"))
                // linking and deleting a value is part of the task's history, like evidence
                .andExpect(jsonPath("$.events[*].type", contains("CREATED", "MEASUREMENT_LINKED", "MEASUREMENT_REMOVED", "MEASUREMENT_LINKED")))
                .andExpect(jsonPath("$.events[2].data.measurement_ids[0]").value(wrong));
        call(kim, json(post("/api/tasks/" + task + "/complete"), "{}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("DONE"));

        // only an open task of this project can take values
        measure("2026-01-05", "CO", "12", "ppm", task).andExpect(status().isUnprocessableEntity());
        long otherPoint = point(project("다른 사업"));
        call(kim, json(post("/api/emission-points/" + otherPoint + "/measurements"),
                row("2026-01-05", "CO", "12", "ppm", openTask()))).andExpect(status().isUnprocessableEntity());
        call(kim, get(measurements)).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void non_members_get_403_and_unknown_ids_404() throws Exception {
        long m = id(measure("2026-01-05", "CO", "12", "ppm", null), "$[0].id");

        call(park, get(measurements)).andExpect(status().isForbidden());
        call(park, json(post(measurements), row("2026-01-05", "CO", "12", "ppm", null))).andExpect(status().isForbidden());
        call(park, json(delete("/api/measurements/" + m), "{\"reason\":\"x\"}")).andExpect(status().isForbidden());

        call(kim, get("/api/emission-points/999/measurements")).andExpect(status().isNotFound());
        call(kim, json(delete("/api/measurements/999"), "{\"reason\":\"x\"}")).andExpect(status().isNotFound());
        call(kim, get(measurements)).andExpect(jsonPath("$.length()").value(1));
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private ResultActions measure(String on, String pollutant, String value, String unit, Long taskId) throws Exception {
        return call(kim, json(post(measurements), row(on, pollutant, value, unit, taskId)));
    }

    private static String row(String on, String pollutant, String value, String unit, Long taskId) {
        return "{\"rows\":[{\"measuredOn\":\"%s\",\"pollutant\":\"%s\",\"value\":%s,\"unit\":\"%s\"}],\"taskId\":%s}"
                .formatted(on, pollutant, value, unit, taskId);
    }

    /** An approved 측정·조사 obligation due 2026-01-31; returns its open task. */
    private long measureTask() throws Exception {
        long suggestion = id(call(kim, json(post("/api/projects/" + projectId + "/suggestions"), """
                {"permitItemId":%d,"payload":{"citation_raw":"허가조건 제4호","summary":"굴뚝 배출가스 월 1회 측정",
                 "requirement_text":"소각로 굴뚝 배출가스를 월 1회 이상 측정할 것",
                 "type":"MEASURE_SURVEY","category":"AIR_QUALITY","frequency":"MONTHLY"}}""".formatted(permitId)))
                .andExpect(status().isCreated()));
        call(kim, json(post("/api/suggestions/" + suggestion + "/accept"), "{\"firstDueDate\":\"2026-01-31\",\"version\":0}"))
                .andExpect(status().isOk());
        return openTask();
    }

    private long openTask() throws Exception {
        return id(call(kim, get("/api/projects/" + projectId + "/tasks")), "$[?(@.status=='OPEN')].id");
    }

    // ticket 09: a range limit (pH). Made-up range 4 ~ 9, warn ratio 0.80 → 근접 at ≤ 5 and ≥ 8.
    @Test
    void a_range_limit_needs_both_bounds_and_judges_below_the_lower_bound_as_exceeded() throws Exception {
        long outfall = id(call(kim, json(post("/api/projects/" + projectId + "/emission-points"),
                "{\"medium\":\"WATER\",\"name\":\"방류구\"}")).andExpect(status().isCreated()));
        String limits = "/api/emission-points/" + outfall + "/limits";
        for (String bad : new String[]{
                "{\"pollutant\":\"PH\",\"limitValue\":9,\"effectiveFrom\":\"2026-01-01\"}", // upper only
                "{\"pollutant\":\"PH\",\"limitLower\":9,\"limitValue\":9,\"effectiveFrom\":\"2026-01-01\"}",
                "{\"pollutant\":\"BOD\",\"limitLower\":1,\"limitValue\":9,\"effectiveFrom\":\"2026-01-01\"}"}) { // not a range item
            call(kim, json(post(limits), bad)).andExpect(status().isUnprocessableEntity());
        }
        call(kim, json(post(limits), "{\"pollutant\":\"PH\",\"limitLower\":-1,\"limitValue\":9,\"effectiveFrom\":\"2026-01-01\"}"))
                .andExpect(status().isBadRequest());
        call(kim, json(post(limits), "{\"pollutant\":\"PH\",\"limitLower\":4,\"limitValue\":9,\"effectiveFrom\":\"2026-01-01\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.limitLower").value(4)).andExpect(jsonPath("$.limitValue").value(9))
                .andExpect(jsonPath("$.unit").value("pH"));
        call(kim, get("/api/emission-points/" + stack + "/limits")).andExpect(jsonPath("$[0].limitLower").doesNotExist());

        String values = "/api/emission-points/" + outfall + "/measurements";
        call(kim, json(post(values), """
                {"rows":[
                  {"measuredOn":"2026-01-01","pollutant":"PH","value":3.9,"unit":"pH"},
                  {"measuredOn":"2026-01-02","pollutant":"PH","value":4,"unit":"pH"},
                  {"measuredOn":"2026-01-03","pollutant":"PH","value":5.1,"unit":"pH"},
                  {"measuredOn":"2026-01-04","pollutant":"PH","value":8,"unit":"pH"},
                  {"measuredOn":"2026-01-05","pollutant":"PH","value":9.1,"unit":"pH"}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[*].level", contains("EXCEEDED", "NEAR", "NORMAL", "NEAR", "EXCEEDED")))
                .andExpect(jsonPath("$[0].limitLower").value(4)).andExpect(jsonPath("$[0].limitValue").value(9));
        // one 조치 업무 for the point and item: titled by the first value, the second is appended to it
        assertThat(jdbc.queryForList("SELECT title FROM obligation_task WHERE kind = 'CORRECTIVE'", String.class))
                .containsExactly("[초과] 방류구 수소이온농도(pH) 3.9 < 4 pH");
        assertThat(jdbc.queryForList("SELECT message FROM notification ORDER BY id", String.class)).containsExactly(
                "초과 · 가온 자원회수시설 · 방류구 수소이온농도(pH) 3.9 < 4 pH (2026-01-01)",
                "근접 · 가온 자원회수시설 · 방류구 수소이온농도(pH) 4 / 4~9 pH (2026-01-02)",
                "근접 · 가온 자원회수시설 · 방류구 수소이온농도(pH) 8 / 4~9 pH (2026-01-04)",
                "초과 · 가온 자원회수시설 · 방류구 수소이온농도(pH) 9.1 > 9 pH (2026-01-05)");

        call(kim, json(post(values + "/preview"), "{\"text\":\"2026-01-06\\tpH\\t3\\n2026-01-07\\tPH\\t7\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].level", contains("EXCEEDED", "NORMAL")))
                .andExpect(jsonPath("$[0].limitLower").value(4));
    }

    private long project(String name) throws Exception {
        return id(call(kim, json(post("/api/projects"),
                "{\"name\":\"" + name + "\",\"facilityType\":\"INCINERATION\",\"location\":\"가온\"}")));
    }

    private long point(long project) throws Exception {
        return id(call(kim, json(post("/api/projects/" + project + "/emission-points"),
                "{\"medium\":\"AIR\",\"name\":\"소각로 1호기 굴뚝\"}")).andExpect(status().isCreated()));
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

    /** A number, or the single match of a filter expression. */
    private static long id(ResultActions r, String path) throws Exception {
        Object v = JsonPath.read(r.andReturn().getResponse().getContentAsString(), path);
        return ((Number) (v instanceof java.util.List<?> l ? l.get(0) : v)).longValue();
    }

    private String login(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"pw\"}".formatted(email)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
