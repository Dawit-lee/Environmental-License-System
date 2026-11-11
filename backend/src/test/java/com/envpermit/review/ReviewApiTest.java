package com.envpermit.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.envpermit.core.company.Company;
import com.envpermit.core.company.CompanyRepository;
import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import com.envpermit.notification.Deadlines;
import com.envpermit.notification.NotificationJob;
import com.envpermit.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Review pipeline through the REST API with manual (origin MANUAL) OBLIGATION suggestions.
 * kim = project creator (can_self_approve + can_bulk_approve), lee = plain member (request only).
 */
class ReviewApiTest extends IntegrationTest {

    static final LocalDate TODAY = LocalDate.of(2026, 1, 5); // START in Asia/Seoul

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;
    @Autowired NotificationJob job;
    @Autowired Review review;
    @MockitoSpyBean Deadlines deadlines;

    String kim;
    String lee;
    String park; // other company
    long projectId;
    long permitId;

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        long a = companies.save(new Company("A환경")).getId();
        long b = companies.save(new Company("B엔지니어링")).getId();
        users.save(new User("kim@a.com", encoder.encode("pw"), "김에이", User.Role.USER, a));
        long leeId = users.save(new User("lee@a.com", encoder.encode("pw"), "이에이", User.Role.USER, a)).getId();
        users.save(new User("park@b.com", encoder.encode("pw"), "박비", User.Role.USER, b));
        kim = login("kim@a.com");
        lee = login("lee@a.com");
        park = login("park@b.com");
        projectId = id(call(kim, json(post("/api/projects"), """
                {"name":"가온 자원회수시설","facilityType":"INCINERATION","location":"가온"}""")));
        call(kim, json(post("/api/projects/" + projectId + "/members"), "{\"userId\":" + leeId + "}"))
                .andExpect(status().isCreated());
        permitId = id(call(kim, json(post("/api/projects/" + projectId + "/permits"), """
                {"name":"환경영향평가 협의","category":"ENVIRONMENT","status":"COMPLETED"}""")));
    }

    // --- happy paths --------------------------------------------------------------------------------------

    @Test
    void self_approver_accepts_manual_suggestion_and_obligation_with_deadline_appears() throws Exception {
        long id = addSuggestion(lee, "MONTHLY");
        call(lee, get("/api/projects/" + projectId + "/suggestions"))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].origin").value("MANUAL"))
                .andExpect(jsonPath("$[0].payload.summary").value("소음·진동 분기 1회 측정"));

        call(kim, json(post("/api/suggestions/" + id + "/accept"), "{\"firstDueDate\":\"2026-03-31\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.appliedEntityId").isNumber());

        call(kim, get("/api/deadlines?from=2026-01-01&to=2026-12-31"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].kind").value("OBLIGATION"))
                .andExpect(jsonPath("$[0].dueDate").value("2026-03-31"))
                .andExpect(jsonPath("$[0].title").value("환경영향평가 협의 · 소음·진동 분기 1회 측정"));
        call(kim, get("/api/suggestions/" + id + "/events"))
                .andExpect(jsonPath("$[*].type", contains("CREATED", "ACCEPTED")))
                .andExpect(jsonPath("$[1].data.first_due_date").value("2026-03-31"))
                .andExpect(jsonPath("$[1].data.bulk").value(false));
    }

    @Test
    void member_without_self_approve_can_only_request_and_approver_decides() throws Exception {
        long id = addSuggestion(lee, "QUARTERLY");

        call(lee, json(post("/api/suggestions/" + id + "/accept"), "{\"version\":0}")).andExpect(status().isForbidden());
        call(lee, json(post("/api/suggestions/" + id + "/reject"), "{\"reason\":\"중복\",\"version\":0}"))
                .andExpect(status().isForbidden());

        // edit then request approval
        call(lee, json(patch("/api/suggestions/" + id), "{\"editedPayload\":" + payload("ANNUAL") + ",\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.editedPayload.frequency").value("ANNUAL"));
        call(lee, json(post("/api/suggestions/" + id + "/submit"), "{\"proposedDecision\":\"ACCEPT\",\"version\":1}"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.proposedDecision").value("ACCEPT"));
        // SUBMITTED cannot be edited; approver returns it
        call(lee, json(patch("/api/suggestions/" + id), "{\"editedPayload\":" + payload("MONTHLY") + ",\"version\":2}"))
                .andExpect(status().isConflict());
        call(kim, json(post("/api/suggestions/" + id + "/return"), "{\"reason\":\"\",\"version\":2}"))
                .andExpect(status().isBadRequest()); // reason required
        call(kim, json(post("/api/suggestions/" + id + "/return"), "{\"reason\":\"근거 페이지 확인\",\"version\":2}"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        call(lee, json(post("/api/suggestions/" + id + "/submit"), "{\"proposedDecision\":\"ACCEPT\",\"version\":3}"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
        call(kim, json(post("/api/suggestions/" + id + "/accept"), "{\"version\":4}"))
                .andExpect(jsonPath("$.status").value("EDITED_ACCEPTED"));

        call(kim, get("/api/suggestions/" + id + "/events")).andExpect(jsonPath("$[*].type",
                contains("CREATED", "EDITED", "SUBMITTED", "RETURNED", "SUBMITTED", "ACCEPTED")));
        call(kim, get("/api/suggestions/" + id + "/events"))
                .andExpect(jsonPath("$[1].data.frequency.from").value("QUARTERLY"))
                .andExpect(jsonPath("$[1].data.frequency.to").value("ANNUAL"));
    }

    @Test
    void rejection_request_then_reject_then_reopen() throws Exception {
        long id = addSuggestion(lee, "ONCE");
        call(lee, json(post("/api/suggestions/" + id + "/submit"), "{\"proposedDecision\":\"REJECT\",\"reason\":\"원문에 없음\",\"version\":0}"))
                .andExpect(jsonPath("$.proposedDecision").value("REJECT"));
        call(kim, json(post("/api/suggestions/" + id + "/reject"), "{\"reason\":\"원문에 없음\",\"version\":1}"))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value("원문에 없음"));
        call(lee, json(post("/api/suggestions/" + id + "/reopen"), "{\"version\":2}")).andExpect(status().isForbidden());
        call(kim, json(post("/api/suggestions/" + id + "/reopen"), "{\"version\":2}"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void invalid_payload_is_422_and_other_company_is_403() throws Exception {
        call(lee, json(post("/api/projects/" + projectId + "/suggestions"),
                "{\"permitItemId\":" + permitId + ",\"payload\":{\"summary\":\"\",\"frequency\":\"MONTHLY\"}}"))
                .andExpect(status().isUnprocessableEntity());
        call(lee, json(post("/api/projects/" + projectId + "/suggestions"),
                "{\"permitItemId\":999999,\"payload\":" + payload("MONTHLY") + "}"))
                .andExpect(status().isUnprocessableEntity());
        long id = addSuggestion(lee, "MONTHLY");
        call(park, get("/api/projects/" + projectId + "/suggestions")).andExpect(status().isForbidden());
        call(park, json(post("/api/suggestions/" + id + "/accept"), "{\"version\":0}")).andExpect(status().isForbidden());
    }

    // --- transaction & concurrency ------------------------------------------------------------------------

    @Test
    void stale_version_is_409() throws Exception {
        long id = addSuggestion(lee, "MONTHLY");
        call(lee, json(patch("/api/suggestions/" + id), "{\"editedPayload\":" + payload("ANNUAL") + ",\"version\":0}"));
        call(kim, json(post("/api/suggestions/" + id + "/accept"), "{\"version\":0}")).andExpect(status().isConflict());
    }

    @Test
    void concurrent_accepts_one_wins_the_other_gets_409() throws Exception {
        long id = addSuggestion(lee, "MONTHLY");
        Callable<Integer> accept = () -> call(kim, json(post("/api/suggestions/" + id + "/accept"),
                "{\"firstDueDate\":\"2026-03-31\",\"version\":0}")).andReturn().getResponse().getStatus();
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = pool.invokeAll(List.of(accept, accept));
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdown();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM permit_obligation", Long.class)).isEqualTo(1);
        call(kim, get("/api/suggestions/" + id + "/events"))
                .andExpect(jsonPath("$[*].type", contains("CREATED", "ACCEPTED")));
    }

    @Test
    void applier_failure_rolls_everything_back_and_suggestion_stays_pending() throws Exception {
        long id = addSuggestion(lee, "MONTHLY");
        doThrow(new IllegalStateException("boom")).when(deadlines)
                .register(eq("OBLIGATION"), anyLong(), anyLong(), any(), any(), any());

        // unhandled → 500 in the app; MockMvc rethrows it
        assertThatThrownBy(() -> call(kim, json(post("/api/suggestions/" + id + "/accept"),
                "{\"firstDueDate\":\"2026-03-31\",\"version\":0}"))).hasRootCauseMessage("boom");

        call(kim, get("/api/projects/" + projectId + "/suggestions"))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].version").value(0))
                .andExpect(jsonPath("$[0].appliedEntityId").doesNotExist());
        call(kim, get("/api/suggestions/" + id + "/events")).andExpect(jsonPath("$[*].type", contains("CREATED")));
        call(kim, get("/api/deadlines?from=2026-01-01&to=2026-12-31")).andExpect(jsonPath("$.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM permit_obligation", Long.class)).isZero();
    }

    // --- bulk -----------------------------------------------------------------------------------------------

    @Test
    void bulk_accept_needs_can_bulk_approve_and_allows_partial_success() throws Exception {
        long ok1 = addSuggestion(lee, "MONTHLY");
        long ok2 = addSuggestion(lee, "ANNUAL");
        long done = addSuggestion(lee, "ONCE");
        call(kim, json(post("/api/suggestions/" + done + "/accept"), "{\"version\":0}")).andExpect(status().isOk());

        call(lee, json(post("/api/suggestions/bulk-accept"), "{\"ids\":[" + ok1 + "]}")).andExpect(status().isForbidden());

        call(kim, json(post("/api/suggestions/bulk-accept"), "{\"ids\":[%d,%d,%d]}".formatted(ok1, done, ok2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].ok", contains(true, false, true)))
                .andExpect(jsonPath("$[1].reason").isString());
        call(kim, get("/api/suggestions/" + ok1 + "/events")).andExpect(jsonPath("$[1].data.bulk").value(true));
        // bulk approval has no first due date → "기한 미정", no deadline
        call(kim, get("/api/deadlines?from=2026-01-01&to=2026-12-31")).andExpect(jsonPath("$.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM permit_obligation", Long.class)).isEqualTo(3);
    }

    // --- invariant ------------------------------------------------------------------------------------------

    @Test
    void unapproved_suggestions_never_reach_deadlines_notifications_dashboard_or_calendar() throws Exception {
        long pending = addSuggestion(lee, "MONTHLY");
        long submitted = addSuggestion(lee, "MONTHLY");
        long rejected = addSuggestion(lee, "MONTHLY");
        call(lee, json(post("/api/suggestions/" + submitted + "/submit"), "{\"proposedDecision\":\"ACCEPT\",\"version\":0}"));
        call(kim, json(post("/api/suggestions/" + rejected + "/reject"), "{\"reason\":\"중복\",\"version\":0}"));

        for (int day : new int[] {0, 30, 60, 90, 300}) {
            clock.set(START.plus(Duration.ofDays(day)));
            job.createDueNotifications();
        }
        clock.set(START);
        for (String token : List.of(kim, lee)) {
            // dashboard and calendar both read /api/deadlines
            call(token, get("/api/deadlines?from=2026-01-01&to=2026-12-31")).andExpect(jsonPath("$.length()").value(0));
            call(token, get("/api/notifications")).andExpect(jsonPath("$.length()").value(0));
        }

        // control: once approved with a due date it does show up
        call(kim, json(post("/api/suggestions/" + pending + "/accept"), "{\"firstDueDate\":\"2026-02-01\",\"version\":0}"));
        call(kim, get("/api/deadlines?from=2026-01-01&to=2026-12-31")).andExpect(jsonPath("$.length()").value(1));
        job.createDueNotifications();
        call(kim, get("/api/notifications")).andExpect(jsonPath("$.length()").value(1));
    }

    // --- obligation "이행 완료" --------------------------------------------------------------------------------

    @Test
    void complete_moves_next_due_date_by_frequency_clamped_to_month_end() throws Exception {
        long obligation = accepted("MONTHLY", "2026-01-31");
        evidence(obligation);
        call(kim, post("/api/obligations/" + obligation + "/complete"))
                .andExpect(jsonPath("$.nextDueDate").value("2026-02-28")).andExpect(jsonPath("$.status").value("ACTIVE"));
        evidence(obligation); // each cycle needs its own
        call(kim, post("/api/obligations/" + obligation + "/complete"))
                .andExpect(jsonPath("$.nextDueDate").value("2026-03-28"));
        call(kim, get("/api/deadlines?from=2026-01-01&to=2026-12-31"))
                .andExpect(jsonPath("$[0].dueDate").value("2026-03-28"));
    }

    @Test
    void complete_once_closes_and_other_needs_a_typed_date() throws Exception {
        long once = accepted("ONCE", "2026-02-10");
        evidence(once);
        call(lee, post("/api/obligations/" + once + "/complete")).andExpect(jsonPath("$.status").value("CLOSED"));
        call(lee, post("/api/obligations/" + once + "/complete")).andExpect(status().isConflict());

        long other = accepted("OTHER", "2026-02-10");
        evidence(other);
        call(lee, post("/api/obligations/" + other + "/complete")).andExpect(status().isUnprocessableEntity());
        call(lee, json(post("/api/obligations/" + other + "/complete"), "{\"nextDueDate\":\"2026-07-01\"}"))
                .andExpect(jsonPath("$.nextDueDate").value("2026-07-01"));
        call(kim, get("/api/deadlines?from=2026-01-01&to=2026-12-31"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].dueDate").value("2026-07-01"));
        call(park, post("/api/obligations/" + other + "/complete")).andExpect(status().isForbidden());
    }

    @Test
    void deleting_the_permit_removes_its_obligation_deadlines() throws Exception {
        accepted("MONTHLY", "2026-02-10");
        call(kim, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/projects/" + projectId + "/permits/" + permitId)).andExpect(status().isNoContent());
        call(kim, get("/api/deadlines?from=2026-01-01&to=2026-12-31")).andExpect(jsonPath("$.length()").value(0));
    }

    // --- audit log & exception paths ---------------------------------------------------------------------

    @Test
    void events_are_append_only_in_the_database() throws Exception {
        long id = addSuggestion(lee, "MONTHLY");
        assertThatThrownBy(() -> jdbc.update("UPDATE ai_suggestion_event SET type = 'X'")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ai_suggestion_event")).hasMessageContaining("append-only");
        call(kim, get("/api/suggestions/" + id + "/events")).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void system_rerun_rejection_skips_permission_and_records_system_actor() throws Exception {
        long id = addSuggestion(lee, "MONTHLY");
        review.rejectBySystem(id, "재실행으로 대체");
        call(kim, get("/api/suggestions/" + id + "/events"))
                .andExpect(jsonPath("$[1].type").value("REJECTED"))
                .andExpect(jsonPath("$[1].actorId").doesNotExist())
                .andExpect(jsonPath("$[1].data.reason").value("재실행으로 대체"));
    }

    @Test
    void pair_restructure_closes_without_self_approve_and_cannot_be_reopened() throws Exception {
        long id = addSuggestion(lee, "MONTHLY");
        long leeId = users.findByEmail("lee@a.com").orElseThrow().getId();
        review.closeForRestructure(id, leeId, Review.Restructure.SPLIT);
        call(kim, get("/api/projects/" + projectId + "/suggestions"))
                .andExpect(jsonPath("$[0].status").value("REJECTED"))
                .andExpect(jsonPath("$[0].rejectReason").value("매칭 분리"));
        call(kim, json(post("/api/suggestions/" + id + "/reopen"), "{\"version\":1}")).andExpect(status().isConflict());
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private long accepted(String frequency, String firstDue) throws Exception {
        long id = addSuggestion(lee, frequency);
        String body = call(kim, json(post("/api/suggestions/" + id + "/accept"),
                "{\"firstDueDate\":\"" + firstDue + "\",\"version\":0}")).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.appliedEntityId")).longValue();
    }

    /** Completion needs an evidence document on the obligation's open task (operations-compliance/01). */
    private void evidence(long obligation) throws Exception {
        long doc = id(call(kim, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart("/api/projects/" + projectId + "/permits/" + permitId + "/documents")
                .file(new org.springframework.mock.web.MockMultipartFile("file", "증빙.pdf", "application/pdf", "%PDF-1.4 test".getBytes()))));
        List<Number> task = JsonPath.read(call(kim, get("/api/projects/" + projectId + "/obligations"))
                .andReturn().getResponse().getContentAsString(), "$[?(@.id==" + obligation + ")].openTask.id");
        call(kim, json(post("/api/tasks/" + task.get(0) + "/evidence"), "{\"documentId\":" + doc + "}")).andExpect(status().isOk());
    }

    private long addSuggestion(String token, String frequency) throws Exception {
        return id(call(token, json(post("/api/projects/" + projectId + "/suggestions"),
                "{\"permitItemId\":" + permitId + ",\"payload\":" + payload(frequency) + "}"))
                .andExpect(status().isCreated()));
    }

    private static String payload(String frequency) {
        return """
                {"citation_raw":"협의의견 3-2항","summary":"소음·진동 분기 1회 측정",
                 "requirement_text":"부지경계 4개 지점에서 소음·진동을 분기 1회 이상 측정할 것",
                 "type":"MEASURE_SURVEY","category":"NOISE_VIBRATION","frequency":"%s"}""".formatted(frequency);
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
