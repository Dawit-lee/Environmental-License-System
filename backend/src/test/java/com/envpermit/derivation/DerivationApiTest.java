package com.envpermit.derivation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.envpermit.core.company.Company;
import com.envpermit.core.company.CompanyRepository;
import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import com.envpermit.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Attributes → derive → PENDING PERMIT_ITEM suggestions + stored judgement, against the seeded v1 rule set. */
class DerivationApiTest extends IntegrationTest {

    static final String GYEONGGI_300 = """
            {"facility_type":"INCINERATION","capacity_tpd":300,"sido":"경기","address":"가온시 한빛동 산1",
             "proponent":"MUNICIPAL","project_kind":"NEW"}""";

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;

    String kim;
    String park; // other company
    long projectId;

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        long a = companies.save(new Company("A환경")).getId();
        long b = companies.save(new Company("B엔지니어링")).getId();
        users.save(new User("kim@a.com", encoder.encode("pw"), "김에이", User.Role.USER, a));
        users.save(new User("park@b.com", encoder.encode("pw"), "박비", User.Role.USER, b));
        kim = login("kim@a.com");
        park = login("park@b.com");
        projectId = id(call(kim, json(post("/api/projects"), """
                {"name":"가온 자원회수시설","facilityType":"INCINERATION","location":"가온"}""")));
    }

    @Test
    void derivesGyeonggi300() throws Exception {
        putAttributes(GYEONGGI_300).andExpect(status().isOk());

        String body = derive().andExpect(status().isOk())
                .andExpect(jsonPath("$.rulesetVersion").value(1))
                .andExpect(jsonPath("$.applicable").value(8))
                .andExpect(jsonPath("$.onHold").value(3))
                .andExpect(jsonPath("$.notApplicable").value(3))
                .andExpect(jsonPath("$.errors").value(0))
                .andReturn().getResponse().getContentAsString();
        long runId = ((Number) JsonPath.read(body, "$.runId")).longValue();

        Map<String, Object> run = jdbc.queryForMap("SELECT * FROM ai_run WHERE id = ?", runId);
        assertThat(run).containsEntry("kind", "PERMIT_ITEM").containsEntry("model", "rule-engine")
                .containsEntry("prompt_version", "ruleset-v1").containsEntry("preparer", "RULE_ENGINE")
                .containsEntry("status", "SUCCEEDED").containsEntry("input_tokens", 0);
        assertThat(run.get("raw_response_key")).isNotNull();

        // PENDING suggestions only for APPLICABLE + ON_HOLD; nothing live yet
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_suggestion WHERE run_id = ? AND status = 'PENDING' "
                + "AND origin = 'AI' AND kind = 'PERMIT_ITEM'", Long.class, runId)).isEqualTo(11);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM permit_item", Long.class)).isZero();

        JsonNode eia = payload(runId, "EIA_MAIN");
        assertThat(eia.get("determination").asText()).isEqualTo("APPLICABLE");
        assertThat(eia.get("reason").asText()).isEqualTo("시설용량 300톤/일 ≥ 100톤/일");
        assertThat(eia.get("approval_stage").asText()).isEqualTo("설계");
        assertThat(eia.get("category").asText()).isEqualTo("SITE");
        assertThat(eia.get("ruleset_version").asInt()).isEqualTo(1);
        assertThat(eia.get("legal_basis").get(0).asText()).isEqualTo("환경영향평가법 §22, 시행령 §31 별표3");
        assertThat(eia.get("law_refs")).hasSize(2);
        assertThat(eia.get("law_refs").get(0).get("article_no").asText()).isEqualTo("제22조");
        assertThat(eia.get("required_by").get("gate").asText()).isEqualTo("CONSTRUCTION_START");
        assertThat(eia.get("required_by_text").asText()).isEqualTo("착공 전");

        JsonNode iep = payload(runId, "INTEGRATED_ENV_PERMIT");
        assertThat(iep.get("determination").asText()).isEqualTo("ON_HOLD");
        assertThat(iep.get("hold_info").get("missing_fields").toString())
                .isEqualTo("[\"air_emission_tpy\",\"wastewater_m3pd\"]");
        assertThat(iep.get("hold_info").get("missing_inputs").toString()).isEqualTo("[\"대기 발생량\",\"폐수량\"]");
        assertThat(iep.get("hold_info").get("criterion").asText()).contains("연 20톤");

        // the full judgement, including not-applicable rows with reasons
        call(kim, get("/api/projects/" + projectId + "/derivations/latest")).andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId))
                .andExpect(jsonPath("$.results.length()").value(14))
                .andExpect(jsonPath("$.results[?(@.rule_key == 'WASTE_INSTALL_REPORT')].reason",
                        contains("시설용량 300톤/일 ≥ 100톤/일")))
                .andExpect(jsonPath("$.results[?(@.rule_key == 'WASTE_DISPOSAL_BUSINESS')].determination",
                        contains("NOT_APPLICABLE")))
                .andExpect(jsonPath("$.results[?(@.rule_key == 'BUILDING_PERMIT')].reason",
                        contains("신설 시 공통 해당")))
                .andExpect(jsonPath("$.results[*].law_names[*]", hasItem("환경영향평가법")));
    }

    @Test
    void sameInputSameJudgement() throws Exception {
        putAttributes(GYEONGGI_300);
        String first = derive().andReturn().getResponse().getContentAsString();
        String second = derive().andReturn().getResponse().getContentAsString();
        assertThat((Object) JsonPath.read(second, "$.results")).isEqualTo(JsonPath.read(first, "$.results"));
    }

    @Test
    void missingRequiredIs400WithFields() throws Exception {
        putAttributes("""
                {"facility_type":"INCINERATION","capacity_tpd":130,"project_kind":"EXPANSION"}""");
        derive().andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.missingFields",
                        contains("sido", "address", "proponent", "existing_capacity_tpd")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_run", Long.class)).isZero();
    }

    @Test
    void attributesAreValidated() throws Exception {
        putAttributes("{\"nope\":1}").andExpect(status().isBadRequest());
        putAttributes("{\"proponent\":\"SOMEONE\"}").andExpect(status().isBadRequest());
        putAttributes("{\"capacity_tpd\":\"many\"}").andExpect(status().isBadRequest());
        putAttributes("{\"expansion_ratio\":0.5}").andExpect(status().isBadRequest()); // derived, not input
        call(park, json(put("/api/projects/" + projectId + "/attributes"), GYEONGGI_300))
                .andExpect(status().isForbidden());
        call(park, post("/api/projects/" + projectId + "/derivations")).andExpect(status().isForbidden());
    }

    @Test
    void noActiveRulesIs400() throws Exception {
        putAttributes(GYEONGGI_300);
        jdbc.update("UPDATE derivation_rule SET status = 'RETIRED' WHERE status = 'ACTIVE'");
        try {
            derive().andExpect(status().isBadRequest());
        } finally {
            jdbc.update("UPDATE derivation_rule SET status = 'ACTIVE' WHERE retired_in_ruleset IS NULL");
        }
    }

    @Test
    void brokenRuleIsRecordedAsErrorAndTheRestStillRuns() throws Exception {
        putAttributes(GYEONGGI_300);
        jdbc.update("""
                INSERT INTO derivation_rule (rule_key, version, status, permit_name, field, category, condition,
                    criterion_text, law_refs, approval_stage, activated_in_ruleset)
                VALUES ('BROKEN', 1, 'ACTIVE', '깨진 규칙', '공통', 'GENERAL',
                    '{"field":"capacity_tpd","op":"~","value":1}', '-',
                    '[{"law_catalog_id":1,"article_no":null}]', 'DESIGN', 1)""");
        try {
            derive().andExpect(status().isOk())
                    .andExpect(jsonPath("$.errors").value(1))
                    .andExpect(jsonPath("$.applicable").value(8))
                    .andExpect(jsonPath("$.results[?(@.rule_key == 'BROKEN')].determination", contains("ERROR")));
        } finally {
            jdbc.update("DELETE FROM derivation_rule WHERE rule_key = 'BROKEN'");
        }
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    private JsonNode payload(long runId, String ruleKey) throws Exception {
        List<String> rows = jdbc.queryForList("SELECT payload::text FROM ai_suggestion WHERE run_id = ? "
                + "AND payload->>'rule_key' = ?", String.class, runId, ruleKey);
        assertThat(rows).hasSize(1);
        return json.readTree(rows.get(0));
    }

    private ResultActions putAttributes(String body) throws Exception {
        return call(kim, json(put("/api/projects/" + projectId + "/attributes"), body));
    }

    private ResultActions derive() throws Exception {
        return call(kim, post("/api/projects/" + projectId + "/derivations"));
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
