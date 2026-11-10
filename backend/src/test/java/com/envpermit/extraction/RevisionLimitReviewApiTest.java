package com.envpermit.extraction;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * operations-compliance/03 (design §2.6 last row): approving a CHANGED revision that carries numeric_changes marks the
 * limits linked to that obligation "한도 확인 필요"; the limit value itself never changes. Same v1/v2 documents as
 * {@link RevisionApplyApiTest}: 3-2 is CHANGED (quarterly → monthly), 5 is UNCHANGED.
 */
class RevisionLimitReviewApiTest extends IntegrationTest {

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;

    String kim;
    long projectId;
    long permitId;
    long point;
    Map<String, Long> limitIds = new HashMap<>(); // pollutant → limit id
    JsonNode changed;
    JsonNode unchanged;

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        long companyId = companies.save(new Company("A환경")).getId();
        users.save(new User("master@a.com", encoder.encode("pw"), "마스터", User.Role.ADMIN, companyId));
        users.save(new User("kim@a.com", encoder.encode("pw"), "김에이", User.Role.USER, companyId));
        call(login("master@a.com"), json(put("/api/company/ai-extraction"), "{\"enabled\":true}")).andExpect(status().isOk());
        kim = login("kim@a.com");
        projectId = id(call(kim, json(post("/api/projects"), """
                {"name":"광명 자원회수시설","facilityType":"INCINERATION","location":"광명"}""")));
        permitId = id(call(kim, json(post("/api/projects/" + projectId + "/permits"), """
                {"name":"환경영향평가 협의","category":"ENVIRONMENT","status":"COMPLETED"}""")));

        long v1 = upload("v1.pdf", null);
        llm.answer("obligations-ok.json");
        Map<String, Long> obligations = new HashMap<>(); // citation_key → live obligation id
        for (JsonNode s : suggestions(runId(call(kim, post(base(v1) + "/extractions")).andExpect(status().isAccepted())))) {
            obligations.put(s.at("/payload/citation_key").asText(),
                    applied(accept(s, "\"firstDueDate\":\"2026-03-31\"").andExpect(status().isOk())));
        }

        point = id(call(kim, json(post("/api/projects/" + projectId + "/emission-points"), "{\"medium\":\"AIR\",\"name\":\"굴뚝\"}")));
        limit("CO", obligations.get("3-2"), "2026-01-01", null);            // open, linked to the obligation that changes
        limit("NOX", obligations.get("3-2"), "2025-01-01", "2025-12-31");   // same obligation, closed long ago
        limit("SOX", obligations.get("5"), "2026-01-01", null);             // linked to the unchanged obligation
        limit("DUST", null, "2026-01-01", null);                            // no basis obligation

        long v2 = upload("v2.pdf", v1);
        llm.answer("revision-v2.json");
        for (JsonNode s : suggestions(runId(call(kim, post(base(v2) + "/revisions")).andExpect(status().isAccepted())))) {
            switch (s.at("/payload/change_type").asText()) {
                case "CHANGED" -> changed = s;
                case "UNCHANGED" -> unchanged = s;
                default -> { }
            }
        }
    }

    @Test
    void changed_with_a_number_change_flags_only_the_open_linked_limit_until_someone_confirms_it() throws Exception {
        // the fixture's CHANGED differs in frequency only; give it the number change the diff engine would report
        jdbc.update("UPDATE ai_suggestion SET payload = jsonb_set(payload, '{numeric_changes}', "
                + "'[{\"before\":\"30\",\"after\":\"20\"}]') WHERE id = ?", changed.get("id").asLong());

        accept(unchanged, null).andExpect(status().isOk());
        accept(changed, "\"firstDueDate\":\"2026-02-15\"").andExpect(status().isOk());

        call(kim, get("/api/emission-points/" + point + "/limits"))
                .andExpect(jsonPath("$[?(@.pollutant=='CO')].needsReview").value(true))
                .andExpect(jsonPath("$[?(@.pollutant=='CO')].limitValue").value(30)) // never changed automatically
                .andExpect(jsonPath("$[?(@.pollutant=='NOX')].needsReview").value(false))
                .andExpect(jsonPath("$[?(@.pollutant=='SOX')].needsReview").value(false))
                .andExpect(jsonPath("$[?(@.pollutant=='DUST')].needsReview").value(false));
        call(kim, get("/api/projects/" + projectId + "/emission-points")).andExpect(jsonPath("$[0].needsReviewCount").value(1));

        call(kim, post("/api/limits/" + limitIds.get("CO") + "/reviewed")).andExpect(status().isOk())
                .andExpect(jsonPath("$.needsReview").value(false)).andExpect(jsonPath("$.limitValue").value(30));
        call(kim, get("/api/projects/" + projectId + "/emission-points")).andExpect(jsonPath("$[0].needsReviewCount").value(0));
    }

    @Test
    void changed_without_a_number_change_flags_nothing() throws Exception {
        accept(changed, "\"firstDueDate\":\"2026-02-15\"").andExpect(status().isOk());

        call(kim, get("/api/projects/" + projectId + "/emission-points")).andExpect(jsonPath("$[0].needsReviewCount").value(0));
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private void limit(String pollutant, Long obligationId, String from, String to) throws Exception {
        limitIds.put(pollutant, id(call(kim, json(post("/api/emission-points/" + point + "/limits"),
                "{\"pollutant\":\"%s\",\"limitValue\":30,\"obligationId\":%s,\"effectiveFrom\":\"%s\",\"effectiveTo\":%s}"
                        .formatted(pollutant, obligationId, from, to == null ? "null" : "\"" + to + "\"")))
                .andExpect(status().isCreated())));
    }

    private ResultActions accept(JsonNode s, String extra) throws Exception {
        String body = "{" + (extra == null ? "" : extra + ",") + "\"version\":" + s.get("version").asLong() + "}";
        return call(kim, json(post("/api/suggestions/" + s.get("id").asLong() + "/accept"), body));
    }

    private JsonNode suggestions(long run) throws Exception {
        return mapper.readTree(call(kim, get("/api/projects/" + projectId + "/suggestions?runId=" + run))
                .andReturn().getResponse().getContentAsString());
    }

    private String base(long documentId) {
        return "/api/projects/" + projectId + "/permit-items/" + permitId + "/documents/" + documentId;
    }

    private long upload(String name, Long supersedes) throws Exception {
        MockMultipartHttpServletRequestBuilder req = multipart("/api/projects/" + projectId + "/permits/" + permitId + "/documents");
        req.file(new MockMultipartFile("file", name, "application/pdf", ExtractionApiTest.PERMIT_PDF));
        if (supersedes != null) {
            req.param("supersedesDocumentId", supersedes.toString());
        }
        return id(call(kim, req).andExpect(status().isCreated()));
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

    private static long applied(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.appliedEntityId")).longValue();
    }

    private static long runId(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.runId")).longValue();
    }

    private String login(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"pw\"}".formatted(email)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
