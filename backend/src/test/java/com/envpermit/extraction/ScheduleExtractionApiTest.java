package com.envpermit.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Ticket design-schedule/07: a project document (no permit item) → schedule suggestions → human review → items and
 * events. LLM answered by claude/schedule-bid-notice.json (fictional 가온시 bid notice; 7 usable rows + 1 without
 * evidence). kim = company ADMIN and project creator (may approve), lee = plain member, park = other company.
 */
class ScheduleExtractionApiTest extends IntegrationTest {

    static final byte[] NOTICE_PDF = ExtractionApiTest.pdf(
            "Gaon City bid notice. Site briefing: 2027-03-08 14:00 at Gaon City Hall room 301. Questions until 2027-03-12.",
            "Bid submission: 2027-04-20 10:00. Design period: 2027-05-01 to 2027-10-31. Kickoff report within 15 days of"
                    + " contract. Monthly progress report. Piping plan due 10/31.");
    static final String FIXTURE = "schedule-bid-notice.json";

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;

    String kim;
    String lee;
    String park;
    long projectId;
    long docId;

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        long a = companies.save(new Company("가온엔지니어링")).getId();
        long b = companies.save(new Company("다온설계")).getId();
        users.save(new User("kim@a.com", encoder.encode("pw"), "김가온", User.Role.ADMIN, a));
        long leeId = users.save(new User("lee@a.com", encoder.encode("pw"), "이가온", User.Role.USER, a)).getId();
        users.save(new User("park@b.com", encoder.encode("pw"), "박다온", User.Role.USER, b));
        kim = login("kim@a.com");
        lee = login("lee@a.com");
        park = login("park@b.com");
        projectId = id(call(kim, json(post("/api/projects"), """
                {"name":"가온시 하수처리장 턴키","facilityType":"SEWAGE","location":"가온시"}""")));
        call(kim, json(post("/api/projects/" + projectId + "/members"), "{\"userId\":" + leeId + "}"))
                .andExpect(status().isCreated());
        docId = upload("가온시 공고문.pdf", NOTICE_PDF, "BID_NOTICE");
    }

    // --- documents without a permit item -----------------------------------------------------------------------

    @Test
    void a_project_document_has_no_permit_item_and_lists_with_its_type() throws Exception {
        long permitId = id(call(kim, json(post("/api/projects/" + projectId + "/permits"), """
                {"name":"환경영향평가 협의","category":"ENVIRONMENT","status":"COMPLETED"}""")));
        call(kim, multipart("/api/projects/" + projectId + "/permits/" + permitId + "/documents")
                .file(new MockMultipartFile("file", "협의의견서.pdf", "application/pdf", NOTICE_PDF)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.permitItemId").value(permitId))
                .andExpect(jsonPath("$.docType").doesNotExist());

        call(lee, get("/api/projects/" + projectId + "/documents")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].fileName").value("가온시 공고문.pdf"))
                .andExpect(jsonPath("$[1].permitItemId").doesNotExist())
                .andExpect(jsonPath("$[1].docType").value("BID_NOTICE"));
        call(lee, get("/api/projects/" + projectId + "/documents/" + docId)).andExpect(status().isOk());
        call(park, get("/api/projects/" + projectId + "/documents/" + docId)).andExpect(status().isForbidden());
        call(kim, multipart("/api/projects/" + projectId + "/documents")
                .file(new MockMultipartFile("file", "x.pdf", "application/pdf", NOTICE_PDF)).param("docType", "NOPE"))
                .andExpect(status().isBadRequest());
    }

    // --- extraction --------------------------------------------------------------------------------------------

    @Test
    void ai_off_is_403_and_only_extractable_types_start() throws Exception {
        extract(docId).andExpect(status().isForbidden());
        assertThat(llm.calls).isEmpty();

        enable();
        long weekly = upload("주간공정보고.pdf", NOTICE_PDF, "WEEKLY_REPORT");
        extract(weekly).andExpect(status().isUnprocessableEntity());
        long untyped = upload("기타.pdf", NOTICE_PDF, null);
        extract(untyped).andExpect(status().isUnprocessableEntity());
        call(park, post(path(docId))).andExpect(status().isForbidden());
        assertThat(llm.calls).isEmpty();
    }

    @Test
    void extraction_makes_one_suggestion_per_row_drops_rows_without_evidence_and_adds_nothing_live() throws Exception {
        enable();
        long runId = run();

        call(kim, get("/api/ai-runs/" + runId)).andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.promptVersion").value("schedule-bid_notice-v1"))
                .andExpect(jsonPath("$.inputRef.doc_type").value("BID_NOTICE"));
        assertThat(llm.calls.get(0).system()).isEqualTo(SchedulePrompt.SYSTEM);
        List<Map<String, Object>> rows = suggestions();
        assertThat(rows).hasSize(7);
        assertThat(rows).extracting(r -> r.get("kind")).containsOnly("SCHEDULE_ITEM", "PROJECT_EVENT");
        assertThat(rows.stream().filter(r -> r.get("kind").equals("PROJECT_EVENT"))).hasSize(4);
        assertThat(rows).extracting(r -> ((Number) r.get("targetParentId")).longValue()).containsOnly(projectId);
        assertThat(rows).extracting(r -> title(r)).doesNotContain("근거 없는 기한");

        JsonNode kickoff = payload("착수보고회");
        assertThat(kickoff.path("anchor").asText()).isEqualTo("CONTRACT");
        assertThat(kickoff.path("offsetDays").asInt()).isEqualTo(15);
        assertThat(kickoff.has("date")).isFalse(); // relative stays a rule, not a date
        assertThat(kickoff.path("checks").toString()).contains("RELATIVE_RULE", "BASE_DATE_MISSING");
        assertThat(kickoff.path("evidence").path("page").asInt()).isEqualTo(2);
        assertThat(kickoff.path("quote_check").asText()).isEqualTo("VERIFIED");
        assertThat(payload("배관평면도 제출").path("checks").toString()).contains("YEAR_INFERRED");
        assertThat(payload("현장설명회").path("documentName").asText()).isEqualTo("가온시 공고문.pdf");

        assertThat(count("schedule_item")).isZero();
        assertThat(count("project_event")).isZero();
    }

    @Test
    void approval_creates_items_and_events_with_trace_and_history_and_reject_changes_nothing() throws Exception {
        enable();
        run();

        long task = id("설계기간");
        long briefing = id("현장설명회");
        accept(task).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        accept(briefing).andExpect(status().isOk());
        reject(id("질의 마감"));

        Map<String, Object> item = jdbc.queryForMap("SELECT * FROM schedule_item");
        assertThat(item.get("title")).isEqualTo("설계기간");
        assertThat(item.get("start_date").toString()).isEqualTo("2027-05-01");
        assertThat(item.get("end_date").toString()).isEqualTo("2027-10-31");
        assertThat(((Number) item.get("source_suggestion_id")).longValue()).isEqualTo(task);
        assertThat(jdbc.queryForList("SELECT type FROM schedule_item_event ORDER BY id", String.class))
                .containsExactly("CREATED", "APPLIED_FROM_SUGGESTION");
        Map<String, Object> event = jdbc.queryForMap("SELECT * FROM project_event");
        assertThat(event.get("kind")).isEqualTo("SITE_BRIEFING");
        assertThat(event.get("starts_at").toString()).startsWith("2027-03-08 14:00");
        assertThat(event.get("location")).isEqualTo("가온시청 301호");
        assertThat(((Number) event.get("source_suggestion_id")).longValue()).isEqualTo(briefing);
        assertThat(count("project_event")).isEqualTo(1); // the rejected 질의 마감 added nothing
        assertThat(count("schedule_item")).isEqualTo(1); // no automatic milestone for an applied event
    }

    @Test
    void relative_rule_is_kept_and_gets_dates_once_the_contract_date_is_set() throws Exception {
        enable();
        run();
        accept(id("착수보고회")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT end_date FROM schedule_item", Object.class)).isNull();

        call(kim, json(put("/api/projects/" + projectId + "/schedule/anchors/CONTRACT?preview=false"),
                "{\"date\":\"2027-05-03\"}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT end_date FROM schedule_item", Object.class).toString())
                .isEqualTo("2027-05-18");
    }

    @Test
    void a_similar_existing_item_is_updated_by_default_with_the_document_as_delay_reason() throws Exception {
        long existing = id(call(kim, json(post("/api/projects/" + projectId + "/schedule/items"), """
                {"kind":"MILESTONE","title":"배관 평면도 제출","endDate":"2027-10-29"}""")).andExpect(status().isCreated()));
        call(kim, post("/api/projects/" + projectId + "/schedule/baseline")).andExpect(status().isOk());
        enable();
        run();

        assertThat(payload("배관평면도 제출").path("matchId").asLong()).isEqualTo(existing);
        accept(id("배관평면도 제출")).andExpect(status().isOk());

        Map<String, Object> item = jdbc.queryForMap("SELECT * FROM schedule_item");
        assertThat(count("schedule_item")).isEqualTo(1);
        assertThat(item.get("end_date").toString()).isEqualTo("2027-10-31");
        assertThat(item.get("baseline_end").toString()).isEqualTo("2027-10-29");
        assertThat(item.get("delay_reason")).isEqualTo("문서 반영: 가온시 공고문.pdf");
        assertThat(jdbc.queryForObject("SELECT data ->> 'reason' FROM schedule_item_event WHERE type = 'DATES_CHANGED'",
                String.class)).isEqualTo("문서 반영: 가온시 공고문.pdf");
        assertThat(jdbc.queryForObject("SELECT data ->> 'action' FROM schedule_item_event WHERE type = 'APPLIED_FROM_SUGGESTION'",
                String.class)).isEqualTo("UPDATED");
    }

    @Test
    void the_reviewer_may_add_a_new_item_instead_of_updating() throws Exception {
        call(kim, json(post("/api/projects/" + projectId + "/schedule/items"), """
                {"kind":"MILESTONE","title":"배관평면도 제출","endDate":"2027-10-30"}""")).andExpect(status().isCreated());
        enable();
        run();
        long id = id("배관평면도 제출");
        ObjectNode edited = (ObjectNode) payload("배관평면도 제출");
        edited.remove("matchId");
        edited.put("createNew", true);
        call(kim, json(patch("/api/suggestions/" + id), "{\"editedPayload\":" + edited + ",\"version\":0}"))
                .andExpect(status().isOk());
        call(kim, json(post("/api/suggestions/" + id + "/accept"), "{\"version\":1}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EDITED_ACCEPTED"));
        assertThat(count("schedule_item")).isEqualTo(2);
    }

    @Test
    void the_same_document_extracted_twice_then_approved_twice_adds_nothing_twice() throws Exception {
        enable();
        long first = run();
        long second = run();
        for (long runId : List.of(first, second)) {
            for (Map<String, Object> s : suggestions(runId)) {
                accept(((Number) s.get("id")).longValue()).andExpect(status().isOk());
            }
        }
        assertThat(count("schedule_item")).isEqualTo(3);
        assertThat(count("project_event")).isEqualTo(3 + 5); // 3 events + 5 monthly reports (RECURRING never matches)
    }

    @Test
    void recurring_report_expands_into_one_event_per_month_inside_the_task_period() throws Exception {
        enable();
        run();
        accept(id("월간 보고")).andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT title || ' ' || starts_at::date FROM project_event ORDER BY starts_at",
                String.class)).containsExactly("월간 보고 (1회) 2027-06-01", "월간 보고 (2회) 2027-07-01",
                "월간 보고 (3회) 2027-08-01", "월간 보고 (4회) 2027-09-01", "월간 보고 (5회) 2027-10-01");
    }

    @Test
    void a_plain_member_cannot_approve_and_bad_edits_are_422() throws Exception {
        enable();
        run();
        long id = id("설계기간");
        call(lee, json(post("/api/suggestions/" + id + "/accept"), "{\"version\":0}")).andExpect(status().isForbidden());
        ObjectNode edited = (ObjectNode) payload("설계기간");
        edited.remove("date");
        call(kim, json(patch("/api/suggestions/" + id), "{\"editedPayload\":" + edited + ",\"version\":0}"))
                .andExpect(status().isUnprocessableEntity());
        edited = (ObjectNode) payload("설계기간");
        edited.remove("evidence");
        call(kim, json(patch("/api/suggestions/" + id), "{\"editedPayload\":" + edited + ",\"version\":0}"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(count("schedule_item")).isZero();
    }

    @Test
    void schedule_and_event_reads_never_touch_ai_suggestion() throws Exception {
        enable();
        run();
        accept(id("설계기간")).andExpect(status().isOk());
        accept(id("현장설명회")).andExpect(status().isOk());
        jdbc.execute("ALTER TABLE ai_suggestion RENAME TO ai_suggestion_hidden");
        try {
            call(lee, get("/api/projects/" + projectId + "/schedule")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1));
            call(lee, get("/api/projects/" + projectId + "/events")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1));
            call(lee, get("/api/calendar?from=2027-03-01&to=2027-12-31")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.events.length()").value(1));
        } finally {
            jdbc.execute("ALTER TABLE ai_suggestion_hidden RENAME TO ai_suggestion");
        }
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    private void enable() throws Exception {
        call(kim, json(put("/api/company/ai-extraction"), "{\"enabled\":true}")).andExpect(status().isOk());
    }

    private String path(long documentId) {
        return "/api/projects/" + projectId + "/documents/" + documentId + "/schedule-extractions";
    }

    private ResultActions extract(long documentId) throws Exception {
        return call(kim, post(path(documentId)));
    }

    private long run() throws Exception {
        llm.answer(FIXTURE);
        String body = extract(docId).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.runId")).longValue();
    }

    private List<Map<String, Object>> suggestions() throws Exception {
        return JsonPath.read(call(kim, get("/api/projects/" + projectId + "/suggestions")).andReturn().getResponse()
                .getContentAsString(), "$");
    }

    private List<Map<String, Object>> suggestions(long runId) throws Exception {
        return JsonPath.read(call(kim, get("/api/projects/" + projectId + "/suggestions?runId=" + runId))
                .andReturn().getResponse().getContentAsString(), "$");
    }

    @SuppressWarnings("unchecked")
    private static String title(Map<String, Object> s) {
        return (String) ((Map<String, Object>) s.get("payload")).get("title");
    }

    /** The newest PENDING suggestion with this title. */
    private long id(String title) throws Exception {
        return suggestions().stream().filter(s -> title.equals(title(s)) && "PENDING".equals(s.get("status")))
                .mapToLong(s -> ((Number) s.get("id")).longValue()).max().orElseThrow();
    }

    private JsonNode payload(String title) throws Exception {
        return mapper.readTree(call(kim, get("/api/projects/" + projectId + "/suggestions")).andReturn().getResponse()
                .getContentAsString()).findParents("title").stream()
                .filter(p -> title.equals(p.path("title").asText()) && p.has("evidence")).findFirst().orElseThrow()
                .deepCopy();
    }

    private ResultActions accept(long id) throws Exception {
        return call(kim, json(post("/api/suggestions/" + id + "/accept"), "{\"version\":0}"));
    }

    private void reject(long id) throws Exception {
        call(kim, json(post("/api/suggestions/" + id + "/reject"), "{\"reason\":\"중복\",\"version\":0}"))
                .andExpect(status().isOk());
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private long upload(String name, byte[] bytes, String docType) throws Exception {
        var req = multipart("/api/projects/" + projectId + "/documents")
                .file(new MockMultipartFile("file", name, "application/octet-stream", bytes));
        if (docType != null) req.param("docType", docType);
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

    private String login(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"pw\"}".formatted(email)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
