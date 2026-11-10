package com.envpermit.operation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

/** operations-compliance/03: emission points and permit limits. kim = project member, park = same company, not a member. */
class EmissionLimitApiTest extends IntegrationTest {

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;

    String kim;
    String park;
    long projectId;
    long permitId;
    long stack;
    String limits;

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        long a = companies.save(new Company("A환경")).getId();
        users.save(new User("kim@a.com", encoder.encode("pw"), "김에이", User.Role.USER, a));
        users.save(new User("park@a.com", encoder.encode("pw"), "박에이", User.Role.USER, a));
        kim = login("kim@a.com");
        park = login("park@a.com");
        projectId = project(kim, "광명 자원회수시설");
        permitId = id(call(kim, json(post("/api/projects/" + projectId + "/permits"), """
                {"name":"대기배출시설 설치허가","category":"FACILITY","status":"COMPLETED"}""")));
        stack = id(call(kim, json(post("/api/projects/" + projectId + "/emission-points"),
                "{\"permitItemId\":" + permitId + ",\"medium\":\"AIR\",\"name\":\"소각로 1호기 굴뚝\"}"))
                .andExpect(status().isCreated()));
        limits = "/api/emission-points/" + stack + "/limits";
    }

    @Test
    void points_are_registered_edited_and_stopped_instead_of_deleted() throws Exception {
        call(kim, json(post("/api/projects/" + projectId + "/emission-points"), "{\"medium\":\"WATER\",\"name\":\"방류구\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.permitItemId").doesNotExist());

        call(kim, json(patch("/api/emission-points/" + stack), "{\"name\":\"소각로 1호기 굴뚝(폐쇄)\",\"active\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));

        call(kim, get("/api/projects/" + projectId + "/emission-points"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("방류구")) // active first
                .andExpect(jsonPath("$[1].name").value("소각로 1호기 굴뚝(폐쇄)"))
                .andExpect(jsonPath("$[1].permitItemId").doesNotExist());
    }

    @Test
    void permit_item_of_another_project_and_bad_medium_are_refused() throws Exception {
        long other = project(kim, "다른 사업");
        call(kim, json(post("/api/projects/" + other + "/emission-points"),
                "{\"permitItemId\":" + permitId + ",\"medium\":\"AIR\",\"name\":\"굴뚝\"}"))
                .andExpect(status().isUnprocessableEntity());
        call(kim, json(post("/api/projects/" + projectId + "/emission-points"), "{\"medium\":\"SOIL\",\"name\":\"굴뚝\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void limit_takes_its_unit_from_the_seed_and_defaults_the_warn_ratio() throws Exception {
        call(kim, json(post(limits), """
                {"pollutant":"CO","limitValue":30,"basisText":"협의의견 대기질 4","effectiveFrom":"2026-01-01"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.unit").value("ppm"))
                .andExpect(jsonPath("$.pollutantName").value("일산화탄소"))
                .andExpect(jsonPath("$.warnRatio").value(0.8))
                .andExpect(jsonPath("$.needsReview").value(false));
    }

    @Test
    void invalid_limits_are_422() throws Exception {
        String[] bad = {
                "{\"pollutant\":\"XYZ\",\"limitValue\":30,\"effectiveFrom\":\"2026-01-01\"}", // not in the seed
                "{\"pollutant\":\"BOD\",\"limitValue\":30,\"effectiveFrom\":\"2026-01-01\"}", // water item on an air point
                "{\"pollutant\":\"CO\",\"limitValue\":30,\"warnRatio\":1,\"effectiveFrom\":\"2026-01-01\"}",
                "{\"pollutant\":\"CO\",\"limitValue\":30,\"warnRatio\":0,\"effectiveFrom\":\"2026-01-01\"}",
                "{\"pollutant\":\"CO\",\"limitValue\":30,\"effectiveFrom\":\"2026-02-01\",\"effectiveTo\":\"2026-01-01\"}",
                "{\"pollutant\":\"CO\",\"limitValue\":30,\"obligationId\":999,\"effectiveFrom\":\"2026-01-01\"}",
        };
        for (String body : bad) {
            call(kim, json(post(limits), body)).andExpect(status().isUnprocessableEntity());
        }
        call(kim, get(limits)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void overlapping_periods_of_the_same_item_are_422_but_other_items_and_gaps_are_fine() throws Exception {
        limit("CO", 30, "2026-01-01", "2026-06-30").andExpect(status().isCreated());

        limit("CO", 25, "2026-06-30", null).andExpect(status().isUnprocessableEntity()); // end date is inclusive
        limit("CO", 25, "2025-01-01", null).andExpect(status().isUnprocessableEntity());
        limit("CO", 25, "2026-07-01", null).andExpect(status().isCreated());
        limit("NOX", 70, "2026-01-01", null).andExpect(status().isCreated());
    }

    @Test
    void changing_a_limit_closes_the_old_row_and_each_date_answers_with_its_own_limit() throws Exception {
        long old = id(limit("CO", 30, "2026-01-01", null));

        call(kim, json(post(limits), "{\"pollutant\":\"CO\",\"limitValue\":20,\"effectiveFrom\":\"2026-01-01\",\"replacesLimitId\":" + old + "}"))
                .andExpect(status().isUnprocessableEntity()); // must start after the old one
        call(kim, json(post(limits), "{\"pollutant\":\"CO\",\"limitValue\":20,\"effectiveFrom\":\"2026-07-01\",\"replacesLimitId\":" + old + "}"))
                .andExpect(status().isCreated());

        call(kim, get(limits + "?on=2026-06-30")).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].limitValue").value(30)).andExpect(jsonPath("$[0].effectiveTo").value("2026-06-30"));
        call(kim, get(limits + "?on=2026-07-01")).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].limitValue").value(20)).andExpect(jsonPath("$[0].effectiveTo").doesNotExist());
        call(kim, get(limits + "?on=2025-12-31")).andExpect(jsonPath("$.length()").value(0));
        call(kim, get(limits)).andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].limitValue").value(20));
    }

    @Test
    void closing_by_hand_then_adding_works_and_a_closed_limit_cannot_be_closed_again() throws Exception {
        long old = id(limit("CO", 30, "2026-01-01", null));
        String close = "/api/limits/" + old + "/close";

        call(kim, json(post(close), "{\"effectiveTo\":\"2025-12-31\"}")).andExpect(status().isUnprocessableEntity());
        call(kim, json(post(close), "{\"effectiveTo\":\"2026-03-31\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveTo").value("2026-03-31"));
        call(kim, json(post(close), "{\"effectiveTo\":\"2026-04-30\"}")).andExpect(status().isConflict());
        limit("CO", 20, "2026-04-01", null).andExpect(status().isCreated());
    }

    @Test
    void non_members_get_403_everywhere_and_unknown_ids_404() throws Exception {
        long limitId = id(limit("CO", 30, "2026-01-01", null));

        call(park, get("/api/projects/" + projectId + "/emission-points")).andExpect(status().isForbidden());
        call(park, json(post("/api/projects/" + projectId + "/emission-points"), "{\"medium\":\"AIR\",\"name\":\"굴뚝\"}"))
                .andExpect(status().isForbidden());
        call(park, json(patch("/api/emission-points/" + stack), "{\"name\":\"x\",\"active\":false}")).andExpect(status().isForbidden());
        call(park, get(limits)).andExpect(status().isForbidden());
        call(park, json(post(limits), "{\"pollutant\":\"NOX\",\"limitValue\":70,\"effectiveFrom\":\"2026-01-01\"}"))
                .andExpect(status().isForbidden());
        call(park, json(post("/api/limits/" + limitId + "/close"), "{\"effectiveTo\":\"2026-03-31\"}")).andExpect(status().isForbidden());
        call(park, post("/api/limits/" + limitId + "/reviewed")).andExpect(status().isForbidden());

        call(kim, get("/api/emission-points/999/limits")).andExpect(status().isNotFound());
        call(kim, post("/api/limits/999/reviewed")).andExpect(status().isNotFound());
        call(kim, get(limits)).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].effectiveTo").doesNotExist());
    }

    @Test
    void pollutant_list_is_the_seed_in_order() throws Exception {
        call(kim, get("/api/pollutants")).andExpect(jsonPath("$.length()").value(12))
                .andExpect(jsonPath("$[0].code").value("DUST")).andExpect(jsonPath("$[0].unit").value("mg/S㎥"))
                .andExpect(jsonPath("$[6].code").value("BOD")).andExpect(jsonPath("$[6].medium").value("WATER"));
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private ResultActions limit(String pollutant, int value, String from, String to) throws Exception {
        return call(kim, json(post(limits), "{\"pollutant\":\"%s\",\"limitValue\":%d,\"effectiveFrom\":\"%s\",\"effectiveTo\":%s}"
                .formatted(pollutant, value, from, to == null ? "null" : "\"" + to + "\"")));
    }

    private long project(String token, String name) throws Exception {
        return id(call(token, json(post("/api/projects"),
                "{\"name\":\"" + name + "\",\"facilityType\":\"INCINERATION\",\"location\":\"광명\"}")));
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
