package com.envpermit.extraction;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
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
 * ④ version-diff/01: upload a new version that supersedes the old one, guards, and the diff run that turns the
 * extraction into OBLIGATION_REVISION suggestions against v1's live obligations. Claude answers from fixtures.
 */
class RevisionApiTest extends IntegrationTest {

    static final String FINISH_V1 = "이전 버전 검토를 먼저 끝내세요.";
    static final String REVISION_OPEN = "진행 중인 개정을 먼저 끝내세요.";
    static final String ALREADY_SUPERSEDED = "이미 다른 문서로 대체된 문서입니다. 최신 문서를 선택하세요.";

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;

    String kim;
    long projectId;
    long permitId;
    long v1;

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        long companyId = companies.save(new Company("A환경")).getId();
        users.save(new User("master@a.com", encoder.encode("pw"), "마스터", User.Role.ADMIN, companyId));
        users.save(new User("kim@a.com", encoder.encode("pw"), "김에이", User.Role.USER, companyId));
        String master = login("master@a.com");
        kim = login("kim@a.com");
        call(master, json(put("/api/company/ai-extraction"), "{\"enabled\":true}")).andExpect(status().isOk());
        projectId = id(call(kim, json(post("/api/projects"), """
                {"name":"광명 자원회수시설","facilityType":"INCINERATION","location":"광명"}""")));
        permitId = newPermit();
        v1 = id(upload(permitId, "협의의견서-v1.pdf", null, null).andExpect(status().isCreated()));
    }

    // --- upload --------------------------------------------------------------------------------------------

    @Test
    void new_version_keeps_its_predecessor_and_effective_date() throws Exception {
        long v2 = id(upload(permitId, "협의의견서-v2.pdf", v1, "2026-03-01").andExpect(status().isCreated())
                .andExpect(jsonPath("$.supersedesDocumentId").value(v1))
                .andExpect(jsonPath("$.effectiveDate").value("2026-03-01")));

        call(kim, get("/api/projects/" + projectId + "/permits/" + permitId + "/documents"))
                .andExpect(jsonPath("$[0].id").value(v2))
                .andExpect(jsonPath("$[0].supersedesDocumentId").value(v1))
                .andExpect(jsonPath("$[1].supersedesDocumentId").doesNotExist());
    }

    @Test
    void a_document_has_one_successor_from_the_same_permit_item() throws Exception {
        upload(permitId, "v2.pdf", v1, null).andExpect(status().isCreated());

        upload(permitId, "v2-again.pdf", v1, null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(ALREADY_SUPERSEDED));
        long other = newPermit();
        upload(other, "other.pdf", v1, null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("같은 인허가 항목의 문서만 대체할 수 있습니다."));
    }

    @Test
    void pending_review_of_the_old_version_blocks_the_upload() throws Exception {
        claude.answer("obligations-ok.json");
        call(kim, post(base(v1) + "/extractions")).andExpect(status().isAccepted());

        upload(permitId, "v2.pdf", v1, null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(FINISH_V1));
    }

    // --- revision run --------------------------------------------------------------------------------------

    @Test
    void diff_against_v1_live_obligations_becomes_revision_suggestions_only() throws Exception {
        approveV1();
        // closed by hand: still compared. Closed by an earlier revision: left out.
        jdbc.update("UPDATE permit_obligation SET status = 'CLOSED', closed_reason = 'MANUAL' WHERE citation_key = '5'");
        jdbc.update("""
                INSERT INTO permit_obligation (permit_item_id, source_document_id, citation_key, requirement_text, summary,
                    type, category, frequency, status, closed_reason)
                VALUES (?, ?, '9', 'Old removed rule.', '옛 조건', 'OTHER', 'OTHER', 'ONCE', 'CLOSED', 'REVISION_REMOVED')""",
                permitId, v1);
        Map<String, Long> ids = jdbc.queryForList("SELECT citation_key, id FROM permit_obligation").stream()
                .collect(Collectors.toMap(r -> (String) r.get("citation_key"), r -> (Long) r.get("id")));
        jdbc.update("UPDATE permit_obligation SET version = 4 WHERE citation_key = '3-2'");
        long v2 = id(upload(permitId, "v2.pdf", v1, null));

        claude.answer("revision-v2.json");
        long runId = runId(call(kim, post(base(v2) + "/revisions")).andExpect(status().isAccepted()));

        call(kim, get("/api/ai-runs/" + runId)).andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.inputRef.document_id").value(v2))
                .andExpect(jsonPath("$.inputRef.prev_document_id").value(v1));
        JsonNode list = suggestions(runId);
        assertThat(list).hasSize(4).allSatisfy(s -> {
            assertThat(s.get("kind").asText()).isEqualTo("OBLIGATION_REVISION");
            assertThat(s.get("status").asText()).isEqualTo("PENDING");
            assertThat(s.get("targetParentId").asLong()).isEqualTo(permitId);
        });
        Map<String, JsonNode> byType = StreamSupport.stream(list.spliterator(), false)
                .collect(Collectors.toMap(s -> s.at("/payload/change_type").asText(), s -> s.get("payload")));
        assertThat(byType).containsOnlyKeys("CHANGED", "UNCHANGED", "REMOVED", "ADDED");

        JsonNode changed = byType.get("CHANGED");
        assertThat(changed.get("prev_obligation_id").asLong()).isEqualTo(ids.get("3-2"));
        assertThat(changed.get("prev_obligation_version").asLong()).isEqualTo(4);
        assertThat(changed.at("/match/method").asText()).isEqualTo("KEY");
        assertThat(changed.get("changed_fields").toString()).contains("frequency");
        assertThat(changed.at("/new/frequency").asText()).isEqualTo("MONTHLY");
        assertThat(changed.at("/prev/frequency").asText()).isEqualTo("QUARTERLY");

        JsonNode unchanged = byType.get("UNCHANGED");
        assertThat(unchanged.get("prev_obligation_id").asLong()).isEqualTo(ids.get("5"));
        assertThat(unchanged.get("prev_obligation_closed").asBoolean()).isTrue();
        assertThat(unchanged.at("/match/flags")).isEmpty();

        JsonNode removed = byType.get("REMOVED");
        assertThat(removed.get("prev_obligation_id").asLong()).isEqualTo(ids.get("P2-1"));
        assertThat(removed.get("new").isNull()).isTrue();

        JsonNode added = byType.get("ADDED");
        assertThat(added.get("prev_obligation_id").isNull()).isTrue();
        assertThat(added.at("/new/citation_key").asText()).isEqualTo("7");

        // nothing reached live tables
        assertThat(jdbc.queryForObject("SELECT count(*) FROM permit_obligation", Long.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_suggestion WHERE run_id = ? AND kind = 'OBLIGATION'",
                Long.class, runId)).isZero();
    }

    @Test
    void approving_v1_records_its_source_document() throws Exception {
        approveV1();

        assertThat(jdbc.queryForList("SELECT DISTINCT source_document_id FROM permit_obligation", Long.class))
                .containsExactly(v1);
    }

    @Test
    void a_revision_in_progress_blocks_another_and_the_next_version() throws Exception {
        approveV1();
        long v2 = id(upload(permitId, "v2.pdf", v1, null));
        claude.answer("revision-v2.json");
        call(kim, post(base(v2) + "/revisions")).andExpect(status().isAccepted());

        call(kim, post(base(v2) + "/revisions")).andExpect(status().isUnprocessableEntity())
                .andExpect(status().reason(REVISION_OPEN));
        upload(permitId, "v3.pdf", v2, null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(REVISION_OPEN));
    }

    @Test
    void pending_review_of_the_old_version_blocks_the_revision() throws Exception {
        long v2 = id(upload(permitId, "v2.pdf", v1, null));
        claude.answer("obligations-ok.json");
        // v1 extracted after v2 was uploaded: its suggestions are still pending
        call(kim, post(base(v1) + "/extractions")).andExpect(status().isAccepted());

        call(kim, post(base(v2) + "/revisions")).andExpect(status().isUnprocessableEntity())
                .andExpect(status().reason(FINISH_V1));
        assertThat(claude.calls).hasSize(1);
    }

    @Test
    void a_new_version_goes_through_the_revision_path_only() throws Exception {
        long v2 = id(upload(permitId, "v2.pdf", v1, null));

        call(kim, post(base(v2) + "/extractions")).andExpect(status().isUnprocessableEntity())
                .andExpect(status().reason("개정 문서는 [개정 비교]로 처리합니다."));
        call(kim, post(base(v1) + "/revisions")).andExpect(status().isUnprocessableEntity())
                .andExpect(status().reason("대체할 이전 버전이 없는 문서입니다."));
        assertThat(claude.calls).isEmpty();
    }

    // --- helpers -------------------------------------------------------------------------------------------

    /** Extract v1 with the 3-row fixture and approve every suggestion. */
    private void approveV1() throws Exception {
        claude.answer("obligations-ok.json");
        long runId = runId(call(kim, post(base(v1) + "/extractions")).andExpect(status().isAccepted()));
        for (JsonNode s : suggestions(runId)) {
            call(kim, json(post("/api/suggestions/" + s.get("id").asLong() + "/accept"),
                    "{\"version\":" + s.get("version").asLong() + "}")).andExpect(status().isOk());
        }
    }

    private JsonNode suggestions(long runId) throws Exception {
        return mapper.readTree(call(kim, get("/api/projects/" + projectId + "/suggestions?runId=" + runId))
                .andReturn().getResponse().getContentAsString());
    }

    private long newPermit() throws Exception {
        return id(call(kim, json(post("/api/projects/" + projectId + "/permits"), """
                {"name":"환경영향평가 협의","category":"ENVIRONMENT","status":"COMPLETED"}""")));
    }

    private String base(long documentId) {
        return "/api/projects/" + projectId + "/permit-items/" + permitId + "/documents/" + documentId;
    }

    private ResultActions upload(long permit, String name, Long supersedes, String effectiveDate) throws Exception {
        MockMultipartHttpServletRequestBuilder req = multipart("/api/projects/" + projectId + "/permits/" + permit + "/documents");
        req.file(new MockMultipartFile("file", name, "application/pdf", ExtractionApiTest.PERMIT_PDF));
        if (supersedes != null) {
            req.param("supersedesDocumentId", supersedes.toString());
        }
        if (effectiveDate != null) {
            req.param("effectiveDate", effectiveDate);
        }
        return call(kim, req);
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
