package com.envpermit.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * operations-compliance/05: pasted measurements (preview, save without the error rows) and the period query the
 * trend chart reads. kim = project member, park = same company, not a member. The stack has one limit:
 * CO 30 ppm, warn ratio 0.80, from 2026-01-01.
 */
class MeasurementPasteApiTest extends IntegrationTest {

    /** Lines 2-5 are fine (NORMAL, NEAR, EXCEEDED, NEAR by display name); 6-10 each carry one kind of error. */
    static final String MIXED = String.join("\n",
            "측정일\t항목\t값",
            "2026-01-02\tCO\t12",
            "2026-01-03\tCO\t24",
            "2026-01-04\tCO\t30.5",
            "2026.1.5\t일산화탄소\t30",
            "2026-01-06\tNOX\t40",      // known item, no limit on this point
            "2025-12-31\tCO\t10",       // before the limit took effect
            "2026-01-07\tBOD\t3",       // water item on an air point
            "어제\tCO\t10",
            "2026-01-08\tCO\t",
            "2026-01-02\tCO\t13");      // same day and item as line 2

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;

    String kim;
    String park;
    long stack;
    String measurements;

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        long a = companies.save(new Company("A환경")).getId();
        users.save(new User("kim@a.com", encoder.encode("pw"), "김에이", User.Role.USER, a));
        users.save(new User("park@a.com", encoder.encode("pw"), "박에이", User.Role.USER, a));
        kim = login("kim@a.com");
        park = login("park@a.com");
        long projectId = id(call(kim, json(post("/api/projects"),
                "{\"name\":\"광명 자원회수시설\",\"facilityType\":\"INCINERATION\",\"location\":\"광명\"}")));
        stack = id(call(kim, json(post("/api/projects/" + projectId + "/emission-points"),
                "{\"medium\":\"AIR\",\"name\":\"소각로 1호기 굴뚝\"}")).andExpect(status().isCreated()));
        call(kim, json(post("/api/emission-points/" + stack + "/limits"), """
                {"pollutant":"CO","limitValue":30,"effectiveFrom":"2026-01-01"}""")).andExpect(status().isCreated());
        measurements = "/api/emission-points/" + stack + "/measurements";
    }

    @Test
    void preview_judges_each_row_reports_row_errors_and_saves_nothing() throws Exception {
        paste("/preview", MIXED).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10))
                .andExpect(jsonPath("$[*].line", contains(2, 3, 4, 5, 6, 7, 8, 9, 10, 11)))
                .andExpect(jsonPath("$[0:4].level", contains("NORMAL", "NEAR", "EXCEEDED", "NEAR")))
                .andExpect(jsonPath("$[0:4].error", contains(null, null, null, null)))
                .andExpect(jsonPath("$[3].measuredOn").value("2026-01-05"))
                .andExpect(jsonPath("$[3].pollutant").value("CO"))
                .andExpect(jsonPath("$[3].pollutantName").value("일산화탄소"))
                .andExpect(jsonPath("$[3].unit").value("ppm"))
                .andExpect(jsonPath("$[3].limitValue").value(30))
                .andExpect(jsonPath("$[3].warnRatio").value(0.8))
                .andExpect(jsonPath("$[4].error").value("측정일에 유효한 한도가 없는 항목"))
                .andExpect(jsonPath("$[4].pollutantName").value("질소산화물"))
                .andExpect(jsonPath("$[4].level").doesNotExist())
                .andExpect(jsonPath("$[5].error").value("측정일에 유효한 한도가 없는 항목"))
                .andExpect(jsonPath("$[6].error").value("모르는 항목"))
                .andExpect(jsonPath("$[7].error").value("날짜 형식 오류"))
                .andExpect(jsonPath("$[7].raw").value("어제\tCO\t10"))
                .andExpect(jsonPath("$[8].error").value("빈 값"))
                .andExpect(jsonPath("$[9].error").value("2행과 측정일·항목 중복"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM measurement", Integer.class)).isZero();
    }

    @Test
    void saving_a_paste_with_errors_stores_only_the_good_rows_as_paste() throws Exception {
        paste("/paste", MIXED).andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[*].level", contains("NORMAL", "NEAR", "EXCEEDED", "NEAR")))
                .andExpect(jsonPath("$[*].source", contains("PASTE", "PASTE", "PASTE", "PASTE")))
                .andExpect(jsonPath("$[2].limitValue").value(30))
                .andExpect(jsonPath("$[2].unit").value("ppm"))
                .andExpect(jsonPath("$[2].enteredByName").value("김에이"));

        call(kim, get(measurements)).andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[*].measuredOn", contains("2026-01-05", "2026-01-04", "2026-01-03", "2026-01-02")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM measurement WHERE source = 'PASTE'", Integer.class)).isEqualTo(4);
        // values typed by hand keep their own source
        call(kim, json(post(measurements), """
                {"rows":[{"measuredOn":"2026-01-09","pollutant":"CO","value":1,"unit":"ppm"}]}"""))
                .andExpect(jsonPath("$[0].source").value("MANUAL"));
    }

    @Test
    void a_paste_with_no_good_row_or_too_many_rows_is_422_and_saves_nothing() throws Exception {
        paste("/paste", "측정일\t항목\t값\n어제\tCO\t1").andExpect(status().isUnprocessableEntity());
        paste("/paste", "").andExpect(status().isUnprocessableEntity());
        String tooMany = "2026-01-02\tCO\t1\n".repeat(501);
        paste("/preview", tooMany).andExpect(status().isUnprocessableEntity())
                .andExpect(status().reason(containsString("500")));
        paste("/paste", tooMany).andExpect(status().isUnprocessableEntity());
        call(kim, json(post(measurements + "/preview"), "{}")).andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM measurement", Integer.class)).isZero();
    }

    @Test
    void the_period_query_leaves_out_deleted_values_and_other_items_and_days() throws Exception {
        call(kim, json(post("/api/emission-points/" + stack + "/limits"), """
                {"pollutant":"NOX","limitValue":70,"effectiveFrom":"2026-01-01"}""")).andExpect(status().isCreated());
        ResultActions saved = paste("/paste", String.join("\n",
                "2026-01-02,CO,12", "2026-01-10,CO,300", "2026-01-10,NOX,40", "2026-01-20,CO,26", "2026-02-01,CO,31"))
                .andExpect(jsonPath("$.length()").value(5));
        long wrong = ((Number) JsonPath.read(saved.andReturn().getResponse().getContentAsString(), "$[1].id")).longValue();

        String january = measurements + "?pollutant=CO&from=2026-01-02&to=2026-01-31"; // both ends included
        call(kim, get(january)).andExpect(jsonPath("$[*].value", contains(26, 300, 12)));
        call(kim, json(delete("/api/measurements/" + wrong), "{\"reason\":\"자릿수 오입력\"}")).andExpect(status().isNoContent());
        call(kim, get(january)).andExpect(jsonPath("$[*].value", contains(26, 12)))
                .andExpect(jsonPath("$[*].level", contains("NEAR", "NORMAL")));
        call(kim, get(measurements + "?pollutant=CO&from=2026-01-03")).andExpect(jsonPath("$[*].value", contains(31, 26)));
        call(kim, get(measurements + "?pollutant=NOX")).andExpect(jsonPath("$[*].value", contains(40)));
    }

    @Test
    void non_members_get_403_and_unknown_points_404() throws Exception {
        for (String path : new String[] {"/preview", "/paste"}) {
            call(park, json(post(measurements + path), "{\"text\":\"2026-01-02,CO,12\"}")).andExpect(status().isForbidden());
            call(kim, json(post("/api/emission-points/999999/measurements" + path), "{\"text\":\"2026-01-02,CO,12\"}"))
                    .andExpect(status().isNotFound());
            mvc.perform(json(post(measurements + path), "{\"text\":\"2026-01-02,CO,12\"}")).andExpect(status().isUnauthorized());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM measurement", Integer.class)).isZero();
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private ResultActions paste(String path, String text) throws Exception {
        return call(kim, json(post(measurements + path), new ObjectMapper().writeValueAsString(Map.of("text", text))));
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
