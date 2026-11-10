package com.envpermit.obligation;

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

/** law-monitoring/04: approving an OBLIGATION links its legal_basis laws to the parent permit item (AI_EXTRACTED). */
class ObligationLawLinkApiTest extends IntegrationTest {

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;

    String kim;
    long projectId;
    long permitId;
    String links;

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        long a = companies.save(new Company("A환경")).getId();
        users.save(new User("kim@a.com", encoder.encode("pw"), "김에이", User.Role.USER, a));
        kim = login("kim@a.com");
        projectId = id(call(kim, json(post("/api/projects"), """
                {"name":"광명 자원회수시설","facilityType":"INCINERATION","location":"광명"}""")));
        permitId = id(call(kim, json(post("/api/projects/" + projectId + "/permits"), """
                {"name":"폐기물처리시설 설치승인","category":"FACILITY","status":"COMPLETED"}""")));
        links = "/api/permit-items/" + permitId + "/law-links";
    }

    @Test
    void matching_legal_basis_creates_ai_extracted_links_and_string_stays() throws Exception {
        approve("[\"폐기물관리법 시행규칙 제29조\",\"폐기물시설촉진법\"]");

        call(kim, get(links)).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("폐기물관리법 시행규칙"))
                .andExpect(jsonPath("$[0].articleNo").value("제29조"))
                .andExpect(jsonPath("$[0].source").value("AI_EXTRACTED"))
                .andExpect(jsonPath("$[1].name").value("폐기물처리시설 설치촉진 및 주변지역지원 등에 관한 법률"))
                .andExpect(jsonPath("$[1].articleNo").doesNotExist())
                .andExpect(jsonPath("$[1].source").value("AI_EXTRACTED"));
    }

    @Test
    void unknown_law_name_still_approves_without_a_link() throws Exception {
        long obligation = approve("[\"없는법 제3조\"]");

        call(kim, get(links)).andExpect(jsonPath("$.length()").value(0));
        Integer kept = jdbc.queryForObject(
                "SELECT count(*) FROM permit_obligation WHERE id = ? AND legal_basis::text LIKE '%없는법 제3조%'",
                Integer.class, obligation);
        org.assertj.core.api.Assertions.assertThat(kept).isEqualTo(1);
    }

    @Test
    void existing_link_is_not_duplicated() throws Exception {
        long rule = jdbc.queryForObject("SELECT id FROM law_catalog WHERE law_id = '008567'", Long.class);
        call(kim, json(post(links), "{\"lawCatalogId\":%d,\"articleNo\":\"29\"}".formatted(rule)))
                .andExpect(status().isCreated());

        approve("[\"폐기물관리법 시행규칙 제29조\",\"폐기물관리법 시행규칙 제 29 조\"]");

        call(kim, get(links)).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].source").value("MANUAL"));
    }

    @Test
    void editing_the_article_of_an_ai_link_flips_source_to_manual() throws Exception {
        approve("[\"폐기물관리법 시행규칙 제29조\"]");
        long linkId = ((Number) JsonPath.read(call(kim, get(links)).andReturn().getResponse().getContentAsString(),
                "$[0].id")).longValue();

        call(kim, json(patch(links + "/" + linkId), "{\"articleNo\":\"30조의2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articleNo").value("제30조의2"))
                .andExpect(jsonPath("$.source").value("MANUAL"));
        call(kim, json(patch(links + "/" + linkId), "{\"articleNo\":\"부칙\"}")).andExpect(status().isBadRequest());
        call(kim, get(links)).andExpect(jsonPath("$[0].source").value("MANUAL"))
                .andExpect(jsonPath("$[0].articleNo").value("제30조의2"));
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private long approve(String legalBasis) throws Exception {
        long id = id(call(kim, json(post("/api/projects/" + projectId + "/suggestions"),
                "{\"permitItemId\":" + permitId + ",\"payload\":" + """
                        {"citation_raw":"허가조건 2항","summary":"폐기물 처리실적 보고",
                         "requirement_text":"처리실적을 매월 보고할 것","type":"REPORT_SUBMIT",
                         "category":"RESOURCE_CIRCULATION","frequency":"MONTHLY","legal_basis":%s}""".formatted(legalBasis) + "}"))
                .andExpect(status().isCreated()));
        String body = call(kim, json(post("/api/suggestions/" + id + "/accept"), "{\"version\":0}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.appliedEntityId")).longValue();
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
