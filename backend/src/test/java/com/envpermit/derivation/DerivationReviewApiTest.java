package com.envpermit.derivation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import com.fasterxml.jackson.databind.node.ObjectNode;
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

/** permit-derivation/02: approving derived items, re-run policy, missing inputs, required_by_date. */
class DerivationReviewApiTest extends IntegrationTest {

    static final String BASE = "\"facility_type\":\"INCINERATION\",\"sido\":\"경기\",\"address\":\"가온시 한빛동 산1\","
            + "\"proponent\":\"MUNICIPAL\",\"project_kind\":\"NEW\"";

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;

    String kim;
    long projectId;

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        long a = companies.save(new Company("A환경")).getId();
        users.save(new User("kim@a.com", encoder.encode("pw"), "김에이", User.Role.USER, a));
        kim = login("kim@a.com");
        projectId = id(call(json(post("/api/projects"), """
                {"name":"가온 자원회수시설","facilityType":"INCINERATION","location":"가온"}""")));
    }

    @Test
    void approvingApplicableCreatesPermitItemAndDerivedLawLinks() throws Exception {
        attributes("\"capacity_tpd\":300");
        derive();
        long id = pending("EIA_MAIN");

        accept(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));

        long permitId = jdbc.queryForObject("SELECT applied_entity_id FROM ai_suggestion WHERE id = ?", Long.class, id);
        Map<String, Object> item = jdbc.queryForMap("SELECT * FROM permit_item WHERE id = ?", permitId);
        assertThat(item).containsEntry("project_id", projectId).containsEntry("name", "환경영향평가")
                .containsEntry("category", "LOCATION").containsEntry("status", "NOT_APPLIED")
                .containsEntry("reason", "시설용량 300톤/일 ≥ 100톤/일").containsEntry("approval_date", "설계")
                .containsEntry("regulation", "환경영향평가법 §22, 시행령 §31 별표3");
        assertThat(jdbc.queryForList("SELECT l.article_no || ' ' || l.source FROM permit_law_link l "
                + "WHERE permit_item_id = ? ORDER BY article_no", String.class, permitId))
                .containsExactly("별표3 DERIVED", "제22조 DERIVED");
        // shows up in the live permit list
        call(get("/api/projects/" + projectId + "/permits")).andExpect(jsonPath("$[*].name", contains("환경영향평가")));
    }

    @Test
    void sameLawRefTwiceMakesOneLink() throws Exception {
        attributes("\"capacity_tpd\":300");
        derive();
        long id = pending("SITE_SELECTION"); // one ref, article null
        ObjectNode edited = (ObjectNode) payload(id);
        edited.withArray("law_refs").add(edited.get("law_refs").get(0).deepCopy());
        edit(id, edited).andExpect(status().isOk());

        accept(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EDITED_ACCEPTED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM permit_law_link", Long.class)).isEqualTo(1);
    }

    @Test
    void onHoldCannotBeApprovedAsIsButCanAfterEditingToApplicable() throws Exception {
        attributes("\"capacity_tpd\":300");
        derive();
        long id = pending("INTEGRATED_ENV_PERMIT");

        accept(id).andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM permit_item", Long.class)).isZero();

        ObjectNode edited = (ObjectNode) payload(id);
        edited.put("determination", "APPLICABLE").put("reason", "대기 발생량 연 25톤 (설계서)");
        edit(id, edited).andExpect(status().isOk());
        accept(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EDITED_ACCEPTED"));
        assertThat(jdbc.queryForObject("SELECT reason FROM permit_item", String.class)).isEqualTo("대기 발생량 연 25톤 (설계서)");
    }

    @Test
    void rerunFollowsThePolicyTable() throws Exception {
        attributes("\"capacity_tpd\":300");
        long run1 = derive();
        long keep = pending("BUILDING_PERMIT");
        long reasonChanges = pending("WASTE_INSTALL_APPROVAL");
        long becomesNotApplicable = pending("DISASTER_IA_SMALL");
        long submitted = pending("DISASTER_IA");
        call(json(post("/api/suggestions/" + submitted + "/submit"),
                "{\"proposedDecision\":\"ACCEPT\",\"version\":" + version(submitted) + "}")).andExpect(status().isOk());
        long humanRejected = pending("INTEGRATED_ENV_PERMIT");
        call(json(post("/api/suggestions/" + humanRejected + "/reject"),
                "{\"reason\":\"자료 없음\",\"version\":" + version(humanRejected) + "}")).andExpect(status().isOk());
        long accepted = pending("EIA_MAIN");
        accept(accepted).andExpect(status().isOk());

        attributes("\"capacity_tpd\":200,\"site_area_m2\":60000");
        long run2 = derive();
        assertThat(run2).isNotEqualTo(run1);

        // same judgement: kept as is
        assertThat(statusOf(keep)).isEqualTo("PENDING");
        assertThat(count("BUILDING_PERMIT")).isEqualTo(1);
        // changed reason: SYSTEM rejects, a new PENDING replaces it
        assertThat(statusOf(reasonChanges)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT reject_reason FROM ai_suggestion WHERE id = ?", String.class,
                reasonChanges)).isEqualTo("재실행으로 대체: 해당 (run " + run2 + ")");
        call(get("/api/suggestions/" + reasonChanges + "/events"))
                .andExpect(jsonPath("$[-1].type").value("REJECTED"))
                .andExpect(jsonPath("$[-1].actorId").doesNotExist())
                .andExpect(jsonPath("$[-1].data.reason", startsWith("재실행으로 대체")));
        assertThat(payload(pending("WASTE_INSTALL_APPROVAL")).get("reason").asText()).contains("시설용량 200톤/일 ≥ 100톤/일");
        // now not applicable: SYSTEM closes it, nothing new
        assertThat(statusOf(becomesNotApplicable)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT reject_reason FROM ai_suggestion WHERE id = ?", String.class,
                becomesNotApplicable)).startsWith("재실행으로 대체: 해당없음 — ");
        assertThat(count("DISASTER_IA_SMALL")).isEqualTo(1);
        // submitted: untouched, the new one carries a warning
        assertThat(statusOf(submitted)).isEqualTo("SUBMITTED");
        JsonNode replacement = payload(pending("DISASTER_IA"));
        assertThat(replacement.get("determination").asText()).isEqualTo("APPLICABLE");
        assertThat(replacement.get("warning").asText()).isEqualTo("결재 중인 이전 판정 있음");
        // human rejection with the same judgement is not raised again
        assertThat(count("INTEGRATED_ENV_PERMIT")).isEqualTo(1);
        // accepted and still applicable: nothing new
        assertThat(count("EIA_MAIN")).isEqualTo(1);

        // accepted item is now not applicable: live item stays, warning only
        attributes("\"capacity_tpd\":40,\"site_area_m2\":60000");
        String body = call(post("/api/projects/" + projectId + "/derivations")).andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings[?(@.rule_key == 'EIA_MAIN')].message",
                        contains(startsWith("현재 규칙상 해당없음: "))))
                .andReturn().getResponse().getContentAsString();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM permit_item", Long.class)).isEqualTo(1);
        assertThat(count("EIA_MAIN")).isEqualTo(1);
        call(get("/api/projects/" + projectId + "/derivations/latest"))
                .andExpect(jsonPath("$.warnings.length()").value(((List<?>) JsonPath.read(body, "$.warnings")).size()));
    }

    @Test
    void missingInputsAreGroupedByFieldAndShrinkAfterSaving() throws Exception {
        attributes("\"capacity_tpd\":300");
        derive();

        call(get("/api/projects/" + projectId + "/derivations/missing-inputs")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.field == 'site_area_m2')].label", contains("부지면적")))
                .andExpect(jsonPath("$[?(@.field == 'site_area_m2')].rules[*]",
                        contains("재해영향평가", "소규모 재해영향평가")))
                .andExpect(jsonPath("$[?(@.field == 'air_emission_tpy')].rules[*]", hasItem("통합환경허가")))
                .andExpect(jsonPath("$.length()").value(17)); // one row per missing input of the 48 rules

        attributes("\"capacity_tpd\":300,\"site_area_m2\":60000");
        derive();
        call(get("/api/projects/" + projectId + "/derivations/missing-inputs"))
                .andExpect(jsonPath("$[?(@.field == 'site_area_m2')]").isEmpty())
                .andExpect(jsonPath("$[?(@.field == 'air_emission_tpy')]").isNotEmpty())
                .andExpect(jsonPath("$.length()").value(16));
    }

    @Test
    void requiredByDateFollowsConstructionStartForPendingOnly() throws Exception {
        attributes("\"capacity_tpd\":300,\"construction_start\":\"2027-03-01\"");
        derive();
        long eia = pending("EIA_MAIN");
        long other = pending("SITE_SELECTION"); // gate OTHER
        long edited = pending("BUILDING_PERMIT");
        assertThat(payload(eia).get("required_by_date").asText()).isEqualTo("2027-03-01");
        assertThat(payload(other).get("required_by_date").isNull()).isTrue();
        ObjectNode e = (ObjectNode) payload(edited);
        e.put("reason", "직접 수정");
        edit(edited, e).andExpect(status().isOk());
        long accepted = pending("WASTE_INSTALL_APPROVAL");
        accept(accepted).andExpect(status().isOk());
        long eiaVersion = version(eia);

        attributes("\"capacity_tpd\":300,\"construction_start\":\"2027-05-10\"");

        assertThat(payload(eia).get("required_by_date").asText()).isEqualTo("2027-05-10");
        assertThat(version(eia)).isGreaterThan(eiaVersion); // an open approval screen with the old date gets 409
        assertThat(payload(other).get("required_by_date").isNull()).isTrue();
        assertThat(payload(edited).get("required_by_date").asText()).isEqualTo("2027-05-10");
        assertThat(json.readTree(jdbc.queryForObject("SELECT edited_payload::text FROM ai_suggestion WHERE id = ?",
                String.class, edited)).get("required_by_date").asText()).isEqualTo("2027-03-01");
        assertThat(payload(accepted).get("required_by_date").asText()).isEqualTo("2027-03-01");
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    private void attributes(String fields) throws Exception {
        call(json(put("/api/projects/" + projectId + "/attributes"), "{" + BASE + "," + fields + "}"))
                .andExpect(status().isOk());
    }

    private long derive() throws Exception {
        String body = call(post("/api/projects/" + projectId + "/derivations")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.runId")).longValue();
    }

    private long pending(String ruleKey) {
        return jdbc.queryForObject("SELECT id FROM ai_suggestion WHERE project_id = ? AND status = 'PENDING' "
                + "AND payload->>'rule_key' = ?", Long.class, projectId, ruleKey);
    }

    private long count(String ruleKey) {
        return jdbc.queryForObject("SELECT count(*) FROM ai_suggestion WHERE project_id = ? "
                + "AND payload->>'rule_key' = ?", Long.class, projectId, ruleKey);
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM ai_suggestion WHERE id = ?", String.class, id);
    }

    private long version(long id) {
        return jdbc.queryForObject("SELECT version FROM ai_suggestion WHERE id = ?", Long.class, id);
    }

    private JsonNode payload(long id) throws Exception {
        return json.readTree(jdbc.queryForObject("SELECT payload::text FROM ai_suggestion WHERE id = ?", String.class, id));
    }

    private ResultActions accept(long id) throws Exception {
        return call(json(post("/api/suggestions/" + id + "/accept"), "{\"version\":" + version(id) + "}"));
    }

    private ResultActions edit(long id, JsonNode edited) throws Exception {
        return call(json(patch("/api/suggestions/" + id),
                "{\"editedPayload\":" + edited + ",\"version\":" + version(id) + "}"));
    }

    private ResultActions call(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + kim));
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
