package com.envpermit.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.envpermit.notification.NotificationJob;
import com.envpermit.support.InMemoryMailer;
import com.envpermit.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * operations-compliance/06: 초과 → 조치 업무 + 알림, 근접 → 알림만. kim (enters the values), lee and choi are project
 * members, park is not. The stack belongs to the permit and has one limit: CO 30 ppm, warn ratio 0.80.
 * Today is 2026-01-05, so a corrective task is due 2026-01-12.
 */
class CorrectiveTaskApiTest extends IntegrationTest {

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;
    @Autowired InMemoryMailer mailer;
    @Autowired NotificationJob job;

    String kim;
    String lee;
    long kimId;
    long leeId;
    long choiId;
    long parkId;
    long projectId;
    long permitId;
    long stack;
    long coLimit;
    String measurements;

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        mailer.clear();
        long a = companies.save(new Company("A환경")).getId();
        kimId = users.save(new User("kim@a.com", encoder.encode("pw"), "김에이", User.Role.USER, a)).getId();
        leeId = users.save(new User("lee@a.com", encoder.encode("pw"), "이에이", User.Role.USER, a)).getId();
        choiId = users.save(new User("choi@a.com", encoder.encode("pw"), "최에이", User.Role.USER, a)).getId();
        parkId = users.save(new User("park@a.com", encoder.encode("pw"), "박에이", User.Role.USER, a)).getId();
        kim = login("kim@a.com");
        lee = login("lee@a.com");
        projectId = id(call(kim, json(post("/api/projects"), """
                {"name":"광명 자원회수시설","facilityType":"INCINERATION","location":"광명"}""")));
        for (long member : List.of(leeId, choiId)) {
            call(kim, json(post("/api/projects/" + projectId + "/members"), "{\"userId\":" + member + "}"))
                    .andExpect(status().isCreated());
        }
        permitId = id(call(kim, json(post("/api/projects/" + projectId + "/permits"), """
                {"name":"대기배출시설 설치허가","category":"FACILITY","status":"COMPLETED"}""")));
        stack = id(call(kim, json(post("/api/projects/" + projectId + "/emission-points"),
                "{\"medium\":\"AIR\",\"name\":\"소각로 1호기 굴뚝\",\"permitItemId\":" + permitId + "}")).andExpect(status().isCreated()));
        coLimit = limit("CO", "30", null);
        measurements = "/api/emission-points/" + stack + "/measurements";
    }

    @Test
    void an_exceedance_opens_one_corrective_task_and_tells_every_member() throws Exception {
        long m = id(measure("CO", "30.1").andExpect(jsonPath("$[0].level").value("EXCEEDED")), "$[0].id");

        call(lee, get("/api/projects/" + projectId + "/tasks"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].kind").value("CORRECTIVE"))
                .andExpect(jsonPath("$[0].title").value("[초과] 소각로 1호기 굴뚝 일산화탄소 30.1 > 30 ppm"))
                .andExpect(jsonPath("$[0].dueDate").value("2026-01-12")) // entered 2026-01-05 + 7 days
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].measurementId").value(m))
                .andExpect(jsonPath("$[0].emissionPointId").value(stack))
                .andExpect(jsonPath("$[0].permitItemId").value(permitId))
                .andExpect(jsonPath("$[0].assigneeId").value(kimId)); // no default, EM or PM: the person who entered it
        long task = correctiveTask();
        call(lee, get("/api/tasks/" + task))
                .andExpect(jsonPath("$.events[*].type", contains("CREATED")))
                .andExpect(jsonPath("$.events[0].data.source").value("EXCEEDANCE"));
        call(kim, get("/api/tasks/mine")).andExpect(jsonPath("$[0].id").value(task)); // 내 할 일

        // every member right away, in the app and by mail; the notice opens the 운영 tab on that point
        for (long member : List.of(kimId, leeId, choiId)) {
            assertThat(notices(member)).containsExactly("초과 · 광명 자원회수시설 · 소각로 1호기 굴뚝 일산화탄소 30.1 > 30 ppm (2026-01-05)");
        }
        assertThat(notices(parkId)).isEmpty();
        assertThat(jdbc.queryForList("SELECT DISTINCT link FROM notification", String.class))
                .containsExactly("/projects/" + projectId + "/operations?point=" + stack);
        assertThat(mailer.sent()).extracting(InMemoryMailer.Mail::to)
                .containsExactlyInAnyOrder("kim@a.com", "lee@a.com", "choi@a.com");

        // D-day reminders: one CORRECTIVE_TASK deadline, to the assignee
        call(kim, get("/api/deadlines?from=2026-01-01&to=2026-02-01"))
                .andExpect(jsonPath("$[?(@.kind=='CORRECTIVE_TASK')].targetId", contains((int) task)))
                .andExpect(jsonPath("$[?(@.kind=='CORRECTIVE_TASK')].dueDate", contains("2026-01-12")));
        job.createDueNotifications();
        assertThat(jdbc.queryForList("SELECT user_id FROM notification WHERE offset_days = 7", Long.class)).containsExactly(kimId);
    }

    @Test
    void a_second_exceedance_of_the_same_point_and_item_joins_the_open_task() throws Exception {
        measure("CO", "30.1");
        long task = correctiveTask();
        mailer.clear();

        call(kim, json(post(measurements), """
                {"source":"PASTE","rows":[{"measuredOn":"2026-01-04","pollutant":"CO","value":45,"unit":"ppm"},
                                          {"measuredOn":"2026-01-05","pollutant":"CO","value":12,"unit":"ppm"}]}"""))
                .andExpect(status().isCreated()); // pasted rows follow the same rule

        call(kim, get("/api/projects/" + projectId + "/tasks")).andExpect(jsonPath("$.length()").value(1));
        call(kim, get("/api/tasks/" + task))
                .andExpect(jsonPath("$.task.title").value("[초과] 소각로 1호기 굴뚝 일산화탄소 30.1 > 30 ppm"))
                .andExpect(jsonPath("$.events[*].type", contains("CREATED", "MEASUREMENT_ADDED")))
                .andExpect(jsonPath("$.events[1].data.title").value("[초과] 소각로 1호기 굴뚝 일산화탄소 45 > 30 ppm"));
        assertThat(notices(choiId)).hasSize(2); // told again
        assertThat(mailer.sent()).hasSize(3);

        // another item of the same point is its own task
        limit("NOX", "50", null);
        measure("NOX", "70");
        call(kim, get("/api/projects/" + projectId + "/tasks")).andExpect(jsonPath("$.length()").value(2));

        // once the task is finished, the next exceedance opens a new one
        attach(task);
        call(kim, json(post("/api/tasks/" + task + "/complete"), "{}")).andExpect(status().isOk());
        measure("CO", "31");
        call(kim, get("/api/projects/" + projectId + "/tasks"))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].title").value("[초과] 소각로 1호기 굴뚝 일산화탄소 31 > 30 ppm"));
    }

    @Test
    void within_the_limit_or_without_a_limit_nothing_happens_and_an_unconfirmed_limit_still_counts() throws Exception {
        measure("CO", "23.9").andExpect(jsonPath("$[0].level").value("NORMAL"));
        measure("NOX", "500").andExpect(jsonPath("$[0].level").value("NO_LIMIT")); // no NOX limit entered
        call(kim, json(post(measurements), """
                {"rows":[{"measuredOn":"2026-01-05","pollutant":"CO","value":300,"unit":"ppm","belowDetection":true}]}"""))
                .andExpect(jsonPath("$[0].level").value("NORMAL"));

        call(kim, get("/api/projects/" + projectId + "/tasks")).andExpect(jsonPath("$.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification", Integer.class)).isZero();
        assertThat(mailer.sent()).isEmpty();

        // "한도 확인 필요" does not change the value in force: it still judges, so an exceedance still opens a task
        jdbc.update("UPDATE permit_limit SET needs_review = TRUE WHERE id = ?", coLimit);
        measure("CO", "31").andExpect(jsonPath("$[0].level").value("EXCEEDED"));
        call(kim, get("/api/projects/" + projectId + "/tasks")).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void a_near_value_opens_no_task_and_tells_only_the_enterer_and_the_obligation_assignee() throws Exception {
        measure("CO", "24").andExpect(jsonPath("$[0].level").value("NEAR")); // limit has no basis obligation
        assertThat(notices(kimId)).containsExactly("근접 · 광명 자원회수시설 · 소각로 1호기 굴뚝 일산화탄소 24 / 30 ppm (2026-01-05)");
        assertThat(notices(leeId)).isEmpty();

        long obligation = obligation();
        call(kim, json(patch("/api/obligations/" + obligation), "{\"defaultAssigneeId\":" + leeId + "}")).andExpect(status().isOk());
        limit("NOX", "50", obligation);
        mailer.clear();
        measure("NOX", "50").andExpect(jsonPath("$[0].level").value("NEAR")); // value = limit is 근접, not 초과

        assertThat(notices(kimId)).hasSize(2);
        assertThat(notices(leeId)).hasSize(1);
        assertThat(notices(choiId)).isEmpty();
        assertThat(mailer.sent()).extracting(InMemoryMailer.Mail::to).containsExactlyInAnyOrder("kim@a.com", "lee@a.com");
        call(kim, get("/api/projects/" + projectId + "/tasks"))
                .andExpect(jsonPath("$[?(@.kind=='CORRECTIVE')]").isEmpty());
    }

    @Test
    void the_assignee_is_the_obligation_default_then_the_em_then_the_pm_then_the_enterer() throws Exception {
        long obligation = obligation();
        limit("NOX", "50", obligation);
        limit("SOX", "20", null);

        call(kim, json(put("/api/projects/" + projectId), """
                {"name":"광명 자원회수시설","facilityType":"INCINERATION","location":"광명","pmUserId":%d}""".formatted(choiId)))
                .andExpect(status().isOk());
        measure("CO", "31");
        call(kim, get("/api/tasks/" + correctiveTask())).andExpect(jsonPath("$.task.assigneeId").value(choiId)); // PM

        call(kim, json(put("/api/projects/" + projectId), """
                {"name":"광명 자원회수시설","facilityType":"INCINERATION","location":"광명","pmUserId":%d,"emUserId":%d}"""
                .formatted(choiId, leeId))).andExpect(status().isOk());
        measure("SOX", "21");
        call(kim, get("/api/projects/" + projectId + "/tasks"))
                .andExpect(jsonPath("$[?(@.title=='[초과] 소각로 1호기 굴뚝 황산화물 21 > 20 ppm')].assigneeId", contains((int) leeId))); // EM

        call(kim, json(patch("/api/obligations/" + obligation), "{\"defaultAssigneeId\":" + kimId + "}")).andExpect(status().isOk());
        measure("NOX", "51");
        call(kim, get("/api/projects/" + projectId + "/tasks")) // the limit's basis obligation decides, and the task carries it
                .andExpect(jsonPath("$[?(@.kind=='CORRECTIVE' && @.obligationId==" + obligation + ")].assigneeId", contains((int) kimId)));
    }

    @Test
    void completion_needs_evidence_and_neither_opens_a_next_cycle_nor_moves_the_basis_obligation() throws Exception {
        long obligation = obligation(); // monthly, due 2026-01-31, with its own open task
        limit("NOX", "50", obligation);
        measure("NOX", "51");
        long task = correctiveTask();

        // it is not a cycle of the obligation
        call(kim, get("/api/projects/" + projectId + "/tasks?obligationId=" + obligation))
                .andExpect(jsonPath("$[*].kind", contains("OBLIGATION")));
        call(kim, get("/api/projects/" + projectId + "/obligations")).andExpect(jsonPath("$[0].openTask.kind").value("OBLIGATION"));

        call(kim, json(post("/api/tasks/" + task + "/complete"), "{}")).andExpect(status().isUnprocessableEntity());
        attach(task);
        call(kim, json(post("/api/tasks/" + task + "/complete"), "{\"note\":\"필터 교체\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("DONE"));

        call(kim, get("/api/projects/" + projectId + "/tasks"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.status=='OPEN')].kind", contains("OBLIGATION")));
        call(kim, get("/api/projects/" + projectId + "/obligations"))
                .andExpect(jsonPath("$[0].nextDueDate").value("2026-01-31"))
                .andExpect(jsonPath("$[0].openTask.dueDate").value("2026-01-31"));
        call(kim, get("/api/deadlines?from=2026-01-01&to=2026-02-01"))
                .andExpect(jsonPath("$[?(@.kind=='CORRECTIVE_TASK')]").isEmpty());
    }

    @Test
    void deleting_the_value_keeps_the_task_which_is_cancelled_by_hand_with_a_reason() throws Exception {
        long m = id(measure("CO", "300"), "$[0].id");
        long task = correctiveTask();

        call(kim, json(delete("/api/measurements/" + m), "{\"reason\":\"자릿수 오입력\"}")).andExpect(status().isNoContent());
        call(kim, get("/api/tasks/" + task)).andExpect(jsonPath("$.task.status").value("OPEN"));

        // moving the due date or the assignee moves the reminder with it
        call(kim, json(patch("/api/tasks/" + task), "{\"dueDate\":\"2026-01-20\",\"assigneeId\":" + leeId + "}")).andExpect(status().isOk());
        call(kim, get("/api/deadlines?from=2026-01-01&to=2026-02-01"))
                .andExpect(jsonPath("$[?(@.kind=='CORRECTIVE_TASK')].dueDate", contains("2026-01-20")));
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM deadline_event WHERE kind = 'CORRECTIVE_TASK'", Long.class))
                .isEqualTo(leeId);

        call(lee, json(post("/api/tasks/" + task + "/cancel"), "{\"reason\":\" \"}")).andExpect(status().isBadRequest());
        call(lee, json(post("/api/tasks/" + task + "/cancel"), "{\"reason\":\"오입력으로 삭제된 측정값\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("CANCELLED"))
                .andExpect(jsonPath("$.task.cancelledReason").value("MANUAL"))
                .andExpect(jsonPath("$.events[-1:].data.note", contains("오입력으로 삭제된 측정값")));
        call(lee, json(post("/api/tasks/" + task + "/cancel"), "{\"reason\":\"다시\"}")).andExpect(status().isConflict());
        call(kim, get("/api/deadlines?from=2026-01-01&to=2026-02-01"))
                .andExpect(jsonPath("$[?(@.kind=='CORRECTIVE_TASK')]").isEmpty());

        // an obligation's cycle is not cancelled this way
        obligation();
        long cycle = id(call(kim, get("/api/projects/" + projectId + "/tasks")), "$[?(@.kind=='OBLIGATION')].id");
        call(kim, json(post("/api/tasks/" + cycle + "/cancel"), "{\"reason\":\"x\"}")).andExpect(status().isUnprocessableEntity());
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private ResultActions measure(String pollutant, String value) throws Exception {
        return call(kim, json(post(measurements), """
                {"rows":[{"measuredOn":"2026-01-05","pollutant":"%s","value":%s,"unit":"ppm"}]}"""
                .formatted(pollutant, value))).andExpect(status().isCreated());
    }

    private long limit(String pollutant, String value, Long obligationId) throws Exception {
        return id(call(kim, json(post("/api/emission-points/" + stack + "/limits"),
                "{\"pollutant\":\"%s\",\"limitValue\":%s,\"effectiveFrom\":\"2026-01-01\",\"obligationId\":%s}"
                        .formatted(pollutant, value, obligationId))).andExpect(status().isCreated()));
    }

    /** An approved monthly obligation of the permit, first due 2026-01-31. */
    private long obligation() throws Exception {
        long suggestion = id(call(kim, json(post("/api/projects/" + projectId + "/suggestions"), """
                {"permitItemId":%d,"payload":{"citation_raw":"허가조건 제4호","summary":"굴뚝 배출가스 월 1회 측정",
                 "requirement_text":"소각로 굴뚝 배출가스를 월 1회 이상 측정할 것",
                 "type":"MEASURE_SURVEY","category":"AIR_QUALITY","frequency":"MONTHLY"}}""".formatted(permitId)))
                .andExpect(status().isCreated()));
        return id(call(kim, json(post("/api/suggestions/" + suggestion + "/accept"), "{\"firstDueDate\":\"2026-01-31\",\"version\":0}"))
                .andExpect(status().isOk()), "$.appliedEntityId");
    }

    /** The single open corrective task. */
    private long correctiveTask() throws Exception {
        return id(call(kim, get("/api/projects/" + projectId + "/tasks")), "$[?(@.kind=='CORRECTIVE' && @.status=='OPEN')].id");
    }

    private void attach(long task) throws Exception {
        long document = id(call(kim, multipart("/api/projects/" + projectId + "/permits/" + permitId + "/documents")
                .file(new MockMultipartFile("file", "조치결과.pdf", "application/pdf", "%PDF-1.4 test".getBytes())))
                .andExpect(status().isCreated()));
        call(kim, json(post("/api/tasks/" + task + "/evidence"), "{\"documentId\":" + document + "}")).andExpect(status().isOk());
    }

    private List<String> notices(long userId) {
        return jdbc.queryForList("SELECT message FROM notification WHERE user_id = ? AND deadline_event_id IS NULL ORDER BY id",
                String.class, userId);
    }

    private ResultActions call(String token, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + token));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, String body) {
        return req.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static long id(ResultActions r) throws Exception {
        return id(r, "$.id");
    }

    /** A number, or the single match of a filter expression. */
    private static long id(ResultActions r, String path) throws Exception {
        Object v = JsonPath.read(r.andReturn().getResponse().getContentAsString(), path);
        return ((Number) (v instanceof List<?> l ? l.get(0) : v)).longValue();
    }

    private String login(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"pw\"}".formatted(email)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
