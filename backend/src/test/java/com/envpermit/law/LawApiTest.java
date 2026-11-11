package com.envpermit.law;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class LawApiTest extends IntegrationTest {

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;

    String kim; // company A, project member
    String park; // company B
    long permit;

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        long a = companies.save(new Company("A환경")).getId();
        long b = companies.save(new Company("B엔지니어링")).getId();
        users.save(new User("kim@a.com", encoder.encode("pw"), "김에이", User.Role.USER, a));
        users.save(new User("park@b.com", encoder.encode("pw"), "박비", User.Role.USER, b));
        kim = login("kim@a.com");
        park = login("park@b.com");
        long project = id(call(kim, json(post("/api/projects"), """
                {"name":"가온 소각시설","facilityType":"INCINERATION","location":"가온시"}"""))
                .andExpect(status().isCreated()));
        permit = id(call(kim, json(post("/api/projects/" + project + "/permits"), """
                {"name":"폐기물처리시설 설치승인","category":"FACILITY","status":"IN_PROGRESS"}"""))
                .andExpect(status().isCreated()));
    }

    @Test
    void seed_migration_has_the_starter_laws_grouped_under_their_act() throws Exception {
        call(kim, get("/api/laws").param("query", "폐기물 관리법")) // spaces ignored
                .andExpect(jsonPath("$[*].lawId").value(containsInAnyOrder("001771", "005353", "008567")));
        long act = jdbc.queryForObject("SELECT id FROM law_catalog WHERE law_id = '001771'", Long.class);
        call(kim, get("/api/laws").param("query", "폐기물관리법 시행규칙"))
                .andExpect(jsonPath("$[0].lawId").value("008567"))
                .andExpect(jsonPath("$[0].kind").value("RULE"))
                .andExpect(jsonPath("$[0].parentId").value(act))
                .andExpect(jsonPath("$[0].lastPromulgatedOn").value("2026-10-01"))
                .andExpect(jsonPath("$[0].lastEnforcedOn").value("2026-10-02"))
                .andExpect(jsonPath("$[0].source").value("SEED"));
        call(kim, get("/api/laws").param("query", "환경오염시설법")) // 법제처 약칭
                .andExpect(jsonPath("$.length()").value(3));
        Integer orphans = jdbc.queryForObject(
                "SELECT count(*) FROM law_catalog WHERE kind <> 'LAW' AND parent_id IS NULL", Integer.class);
        org.assertj.core.api.Assertions.assertThat(orphans).isZero();
    }

    @Test
    void search_keeps_only_the_exact_name_and_its_decree_and_rule() throws Exception {
        call(kim, post("/api/laws/search").param("query", "폐기물관리법")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(containsInAnyOrder(
                        "폐기물관리법", "폐기물관리법 시행령", "폐기물관리법 시행규칙"))) // no 방사성폐기물·해양폐기물
                .andExpect(jsonPath("$[0].catalogId").isNumber());
        call(kim, post("/api/laws/search").param("query", "환경영향평가법 시행규칙")) // single-object response
                .andExpect(jsonPath("$[*].lawId").value(containsInAnyOrder("009169")));
        call(kim, post("/api/laws/search").param("query", "없는법")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void adding_from_search_groups_decree_and_rule_under_the_act_whatever_the_order() throws Exception {
        call(kim, post("/api/laws/search").param("query", "하수도법"))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.catalogId != null)]").isEmpty()); // none in the catalog yet

        long decree = id(call(kim, json(post("/api/laws"), "{\"lawId\":\"005369\",\"query\":\"하수도법\"}"))
                .andExpect(jsonPath("$.source").value("USER_SEARCH"))
                .andExpect(jsonPath("$.kind").value("DECREE"))
                .andExpect(jsonPath("$.parentId").value(nullValue())));
        long act = id(call(kim, json(post("/api/laws"), "{\"lawId\":\"001815\",\"query\":\"하수도법\"}"))
                .andExpect(jsonPath("$.kind").value("LAW")));
        call(kim, json(post("/api/laws"), "{\"lawId\":\"008578\",\"query\":\"하수도법\"}"))
                .andExpect(jsonPath("$.parentId").value(act));
        call(kim, get("/api/laws").param("query", "하수도법 시행령"))
                .andExpect(jsonPath("$[0].id").value(decree))
                .andExpect(jsonPath("$[0].parentId").value(act));
        // adding again returns the same row
        call(kim, json(post("/api/laws"), "{\"lawId\":\"001815\",\"query\":\"하수도법\"}"))
                .andExpect(jsonPath("$.id").value(act));
        // a 법제처 id that is not an exact-name hit is refused (방사성폐기물 관리법 via 폐기물관리법)
        call(kim, json(post("/api/laws"), "{\"lawId\":\"010722\",\"query\":\"폐기물관리법\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void link_and_unlink_a_law_with_a_normalized_article() throws Exception {
        long rule = jdbc.queryForObject("SELECT id FROM law_catalog WHERE law_id = '008567'", Long.class);
        String links = "/api/permit-items/" + permit + "/law-links";

        long linkId = id(call(kim, json(post(links), "{\"lawCatalogId\":%d,\"articleNo\":\"29조의 2\"}".formatted(rule)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.articleNo").value("제29조의2"))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.name").value("폐기물관리법 시행규칙"))
                .andExpect(jsonPath("$.lastEnforcedOn").value("2026-10-02")));
        // same link again (other spelling) → no duplicate
        call(kim, json(post(links), "{\"lawCatalogId\":%d,\"articleNo\":\"제29조의2\"}".formatted(rule)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(linkId));
        // whole law (no article), twice → one row
        call(kim, json(post(links), "{\"lawCatalogId\":%d}".formatted(rule))).andExpect(status().isCreated());
        call(kim, json(post(links), "{\"lawCatalogId\":%d,\"articleNo\":\" \"}".formatted(rule)))
                .andExpect(status().isOk());
        call(kim, json(post(links), "{\"lawCatalogId\":%d,\"articleNo\":\"부칙\"}".formatted(rule)))
                .andExpect(status().isBadRequest());

        call(kim, get(links)).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].articleNo").value(hasItem("제29조의2")));

        call(kim, delete(links + "/" + linkId)).andExpect(status().isNoContent());
        call(kim, get(links)).andExpect(jsonPath("$.length()").value(1));
        call(kim, delete(links + "/" + linkId)).andExpect(status().isNotFound());
    }

    @Test
    void non_members_cannot_see_or_change_links() throws Exception {
        long rule = jdbc.queryForObject("SELECT id FROM law_catalog WHERE law_id = '008567'", Long.class);
        String links = "/api/permit-items/" + permit + "/law-links";
        long linkId = id(call(kim, json(post(links), "{\"lawCatalogId\":%d}".formatted(rule))));

        call(park, get(links)).andExpect(status().isForbidden());
        call(park, json(post(links), "{\"lawCatalogId\":%d}".formatted(rule))).andExpect(status().isForbidden());
        call(park, delete(links + "/" + linkId)).andExpect(status().isForbidden());
        call(park, get("/api/permit-items/999999/law-links")).andExpect(status().isForbidden());
    }

    private ResultActions call(String token, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + token));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, String body) {
        return req.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String login(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"pw\"}".formatted(email)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private static long id(ResultActions res) throws Exception {
        return ((Number) JsonPath.read(res.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }
}
