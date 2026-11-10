package com.envpermit.obligation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.envpermit.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 회차별 업무 + 증빙 (operations-compliance/01) through the REST API. kim = project creator (approves),
 * lee = member, park = another company. START is 2026-01-05 in Asia/Seoul.
 */
class TaskApiTest extends IntegrationTest {

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;

    String kim;
    String lee;
    String park;
    long kimId;
    long leeId;
    long parkId;
    @Autowired NotificationJob job;
    @Autowired com.envpermit.support.InMemoryMailer mailer;
    long projectId;
    long permitId;
    long otherPermitId;

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        mailer.clear();
        long a = companies.save(new Company("A환경")).getId();
        long b = companies.save(new Company("B엔지니어링")).getId();
        kimId = users.save(new User("kim@a.com", encoder.encode("pw"), "김에이", User.Role.USER, a)).getId();
        leeId = users.save(new User("lee@a.com", encoder.encode("pw"), "이에이", User.Role.USER, a)).getId();
        parkId = users.save(new User("park@b.com", encoder.encode("pw"), "박비", User.Role.USER, b)).getId();
        kim = login("kim@a.com");
        lee = login("lee@a.com");
        park = login("park@b.com");
        projectId = id(call(kim, json(post("/api/projects"), """
                {"name":"광명 자원회수시설","facilityType":"INCINERATION","location":"광명"}""")));
        call(kim, json(post("/api/projects/" + projectId + "/members"), "{\"userId\":" + leeId + "}"))
                .andExpect(status().isCreated());
        permitId = id(call(kim, json(post("/api/projects/" + projectId + "/permits"), """
                {"name":"환경영향평가 협의","category":"ENVIRONMENT","status":"COMPLETED"}""")));
        otherPermitId = id(call(kim, json(post("/api/projects/" + projectId + "/permits"), """
                {"name":"대기배출시설 설치허가","category":"ENVIRONMENT","status":"COMPLETED"}""")));
    }

    @Test
    void approval_with_a_first_due_date_opens_one_task_and_the_obligation_list_carries_it() throws Exception {
        long obligation = accepted("MONTHLY", "2026-01-31");

        call(lee, get("/api/projects/" + projectId + "/tasks"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].kind").value("OBLIGATION"))
                .andExpect(jsonPath("$[0].obligationId").value(obligation))
                .andExpect(jsonPath("$[0].title").value("소음·진동 분기 1회 측정"))
                .andExpect(jsonPath("$[0].dueDate").value("2026-01-31"))
                .andExpect(jsonPath("$[0].status").value("OPEN"));
        long task = openTask(obligation);
        call(lee, get("/api/projects/" + projectId + "/obligations"))
                .andExpect(jsonPath("$[0].openTask.id").value(task))
                .andExpect(jsonPath("$[0].openTask.dueDate").value("2026-01-31"))
                .andExpect(jsonPath("$[0].openTask.assigneeId").doesNotExist())
                .andExpect(jsonPath("$[0].openTask.evidenceCount").value(0));
        call(lee, get("/api/tasks/" + task))
                .andExpect(jsonPath("$.events[*].type", contains("CREATED")))
                .andExpect(jsonPath("$.events[0].data.source").value("ACCEPT"))
                .andExpect(jsonPath("$.events[0].byName").value("김에이"));
    }

    @Test
    void no_task_without_a_due_date_or_before_approval() throws Exception {
        addSuggestion(lee, "MONTHLY"); // pending AI/manual suggestion: not live
        long undated = id(call(kim, json(post("/api/suggestions/" + addSuggestion(lee, "MONTHLY") + "/accept"), "{\"version\":0}")),
                "$.appliedEntityId");

        call(kim, get("/api/projects/" + projectId + "/tasks")).andExpect(jsonPath("$.length()").value(0));
        call(kim, get("/api/projects/" + projectId + "/obligations"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].openTask").doesNotExist());

        // typing the first due date (old "이행 완료" path) opens the first cycle
        call(kim, json(post("/api/obligations/" + undated + "/complete"), "{\"nextDueDate\":\"2026-03-31\"}"))
                .andExpect(jsonPath("$.openTask.dueDate").value("2026-03-31"));
        call(kim, get("/api/tasks/" + openTask(undated))).andExpect(jsonPath("$.events[0].data.source").value("DUE_SET"));
    }

    @Test
    void completion_needs_evidence_then_opens_the_next_cycle_with_exactly_one_deadline() throws Exception {
        long obligation = accepted("MONTHLY", "2026-01-31");
        long task = openTask(obligation);

        call(lee, json(post("/api/tasks/" + task + "/complete"), "{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(status().reason(ObligationTasks.NEEDS_EVIDENCE));

        long doc = upload(permitId, "측정성적서-1월.pdf");
        call(lee, json(post("/api/tasks/" + task + "/evidence"), "{\"documentId\":" + doc + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.evidenceCount").value(1))
                .andExpect(jsonPath("$.evidence[0].fileName").value("측정성적서-1월.pdf"));
        call(lee, json(post("/api/tasks/" + task + "/evidence"), "{\"documentId\":" + doc + "}")) // twice: no-op
                .andExpect(jsonPath("$.task.evidenceCount").value(1));

        call(lee, json(post("/api/tasks/" + task + "/complete"), "{\"note\":\"1월분 측정 완료\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("DONE"))
                .andExpect(jsonPath("$.task.late").value(false))
                .andExpect(jsonPath("$.task.note").value("1월분 측정 완료"))
                .andExpect(jsonPath("$.task.doneByName").value("이에이"))
                .andExpect(jsonPath("$.events[*].type", contains("CREATED", "EVIDENCE_ADDED", "COMPLETED")))
                .andExpect(jsonPath("$.events[2].data.late").value(false));
        call(lee, json(post("/api/tasks/" + task + "/complete"), "{}")).andExpect(status().isConflict());

        // next cycle: month-end clamped, one open task, one D-day deadline
        call(lee, get("/api/projects/" + projectId + "/tasks?obligationId=" + obligation))
                .andExpect(jsonPath("$[*].status", contains("DONE", "OPEN")))
                .andExpect(jsonPath("$[1].dueDate").value("2026-02-28"))
                .andExpect(jsonPath("$[1].evidenceCount").value(0));
        call(lee, get("/api/tasks/" + openTask(obligation))).andExpect(jsonPath("$.events[0].data.source").value("COMPLETE_NEXT"));
        call(lee, get("/api/deadlines?from=2026-01-01&to=2026-12-31"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].kind").value("OBLIGATION"))
                .andExpect(jsonPath("$[0].dueDate").value("2026-02-28"));
        call(lee, get("/api/projects/" + projectId + "/obligations"))
                .andExpect(jsonPath("$[0].nextDueDate").value("2026-02-28"))
                .andExpect(jsonPath("$[0].lastTask.id").value(task));

        // evidence of a finished cycle stays: the link is frozen and the document cannot be deleted
        call(lee, delete("/api/tasks/" + task + "/evidence/" + doc)).andExpect(status().isConflict());
        call(lee, delete("/api/projects/" + projectId + "/permits/" + permitId + "/documents/" + doc))
                .andExpect(status().isConflict());
    }

    @Test
    void completing_after_the_due_date_is_marked_late() throws Exception {
        long obligation = accepted("QUARTERLY", "2026-01-31");
        long task = openTask(obligation);
        attach(task);
        clock.advance(Duration.ofDays(40)); // 2026-02-14
        kim = login("kim@a.com"); // the old token expired meanwhile

        call(kim, json(post("/api/tasks/" + task + "/complete"), "{}"))
                .andExpect(jsonPath("$.task.late").value(true))
                .andExpect(jsonPath("$.events[2].data.late").value(true));
        call(kim, get("/api/projects/" + projectId + "/obligations"))
                .andExpect(jsonPath("$[0].openTask.dueDate").value("2026-04-30")); // counted from the due date, not today
    }

    @Test
    void once_closes_the_obligation_and_other_needs_a_typed_next_due_date() throws Exception {
        long once = accepted("ONCE", "2026-02-10");
        long onceTask = openTask(once);
        attach(onceTask);
        call(lee, json(post("/api/tasks/" + onceTask + "/complete"), "{}")).andExpect(jsonPath("$.task.status").value("DONE"));
        call(lee, get("/api/projects/" + projectId + "/tasks?obligationId=" + once)).andExpect(jsonPath("$.length()").value(1));
        assertThat(jdbc.queryForObject("SELECT status FROM permit_obligation WHERE id = ?", String.class, once)).isEqualTo("CLOSED");

        long other = accepted("OTHER", "2026-02-10");
        long otherTask = openTask(other);
        attach(otherTask);
        call(lee, json(post("/api/tasks/" + otherTask + "/complete"), "{}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(status().reason(ObligationTasks.NEEDS_NEXT_DUE));
        call(lee, get("/api/tasks/" + otherTask)).andExpect(jsonPath("$.task.status").value("OPEN"));
        call(lee, json(post("/api/tasks/" + otherTask + "/complete"), "{\"nextDueDate\":\"2026-07-01\"}"))
                .andExpect(status().isOk());
        call(lee, get("/api/tasks/" + openTask(other))).andExpect(jsonPath("$.task.dueDate").value("2026-07-01"));
        call(kim, get("/api/deadlines?from=2026-01-01&to=2026-12-31"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].dueDate").value("2026-07-01"));
    }

    @Test
    void old_complete_endpoint_delegates_to_the_open_task_with_the_same_rules() throws Exception {
        long obligation = accepted("MONTHLY", "2026-01-31");
        long task = openTask(obligation);

        call(kim, post("/api/obligations/" + obligation + "/complete"))
                .andExpect(status().isUnprocessableEntity()).andExpect(status().reason(ObligationTasks.NEEDS_EVIDENCE));
        attach(task);
        call(kim, post("/api/obligations/" + obligation + "/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextDueDate").value("2026-02-28"))
                .andExpect(jsonPath("$.lastTask.id").value(task))
                .andExpect(jsonPath("$.lastTask.status").value("DONE"))
                .andExpect(jsonPath("$.openTask.dueDate").value("2026-02-28"));
    }

    @Test
    void evidence_must_be_a_document_of_the_same_permit_item_and_can_be_unlinked_while_open() throws Exception {
        long task = openTask(accepted("MONTHLY", "2026-01-31"));
        long foreign = upload(otherPermitId, "다른항목.pdf");
        long own = upload(permitId, "성적서.pdf");

        call(lee, json(post("/api/tasks/" + task + "/evidence"), "{\"documentId\":" + foreign + "}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(status().reason(ObligationTasks.OTHER_PERMIT));
        call(lee, json(post("/api/tasks/" + task + "/evidence"), "{\"documentId\":999999}"))
                .andExpect(status().isUnprocessableEntity());

        call(lee, json(post("/api/tasks/" + task + "/evidence"), "{\"documentId\":" + own + "}")).andExpect(status().isOk());
        call(lee, delete("/api/tasks/" + task + "/evidence/" + own))
                .andExpect(jsonPath("$.task.evidenceCount").value(0))
                .andExpect(jsonPath("$.events[*].type", contains("CREATED", "EVIDENCE_ADDED", "EVIDENCE_REMOVED")));
        call(lee, json(post("/api/tasks/" + task + "/complete"), "{}")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void non_members_are_refused_everywhere() throws Exception {
        long task = openTask(accepted("MONTHLY", "2026-01-31"));
        long doc = upload(permitId, "성적서.pdf");

        call(park, get("/api/projects/" + projectId + "/tasks")).andExpect(status().isForbidden());
        call(park, get("/api/tasks/" + task)).andExpect(status().isForbidden());
        call(park, json(post("/api/tasks/" + task + "/evidence"), "{\"documentId\":" + doc + "}")).andExpect(status().isForbidden());
        call(park, delete("/api/tasks/" + task + "/evidence/" + doc)).andExpect(status().isForbidden());
        call(park, json(post("/api/tasks/" + task + "/complete"), "{}")).andExpect(status().isForbidden());
        call(kim, get("/api/tasks/999999")).andExpect(status().isNotFound());
    }

    @Test
    void database_allows_one_open_task_per_obligation_and_keeps_events_append_only() throws Exception {
        long obligation = accepted("MONTHLY", "2026-01-31");

        assertThatThrownBy(() -> jdbc.update("INSERT INTO obligation_task (project_id, kind, obligation_id, title, due_date, status) "
                + "VALUES (?, 'OBLIGATION', ?, 'x', DATE '2026-02-01', 'OPEN')", projectId, obligation))
                .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE task_event SET type = 'X'")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM task_event")).hasMessageContaining("append-only");

        // removing the permit item takes its tasks, events and evidence links with it
        attach(openTask(obligation));
        call(kim, delete("/api/projects/" + projectId + "/permits/" + permitId)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT (SELECT count(*) FROM obligation_task) + (SELECT count(*) FROM task_event) "
                + "+ (SELECT count(*) FROM task_evidence)", Long.class)).isZero();
    }

    // --- operations-compliance/02: assignee, due date, inbox, overdue ------------------------------------------

    @Test
    void reassigning_moves_the_d_day_reminder_and_the_inbox_entry_to_the_new_assignee() throws Exception {
        long obligation = accepted("MONTHLY", "2026-01-31");
        long task = openTask(obligation);

        call(kim, json(patch("/api/tasks/" + task), "{\"assigneeId\":" + leeId + ",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.assigneeName").value("이에이"))
                .andExpect(jsonPath("$.events[*].type", contains("CREATED", "ASSIGNED")))
                .andExpect(jsonPath("$.events[1].data.from").doesNotExist())
                .andExpect(jsonPath("$.events[1].data.to").value(leeId));
        job.createDueNotifications(); // D-26 → the D-30 reminder

        assertThat(notified(leeId)).containsExactly(30);
        assertThat(notified(kimId)).isEmpty();
        call(lee, get("/api/tasks/mine"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(task))
                .andExpect(jsonPath("$[0].projectId").value(projectId));
        call(kim, get("/api/tasks/mine")).andExpect(jsonPath("$.length()").value(0));

        // unassigned again → every member, as before
        call(kim, json(patch("/api/tasks/" + task), "{\"assigneeId\":null}"))
                .andExpect(jsonPath("$.task.assigneeId").doesNotExist());
        job.createDueNotifications();
        assertThat(notified(kimId)).containsExactly(30);
        call(lee, get("/api/tasks/mine")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void changing_the_due_date_moves_the_obligation_and_its_single_deadline_in_step() throws Exception {
        long obligation = accepted("MONTHLY", "2026-01-31");
        long task = openTask(obligation);

        call(lee, json(patch("/api/tasks/" + task), "{\"dueDate\":\"2026-02-10\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.id").value(task))
                .andExpect(jsonPath("$.task.dueDate").value("2026-02-10"))
                .andExpect(jsonPath("$.events[*].type", contains("CREATED", "DUE_CHANGED")))
                .andExpect(jsonPath("$.events[1].data.from").value("2026-01-31"))
                .andExpect(jsonPath("$.events[1].data.to").value("2026-02-10"));

        call(lee, get("/api/projects/" + projectId + "/obligations"))
                .andExpect(jsonPath("$[0].nextDueDate").value("2026-02-10"))
                .andExpect(jsonPath("$[0].openTask.id").value(task));
        call(lee, get("/api/deadlines?from=2026-01-01&to=2026-12-31"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].dueDate").value("2026-02-10"));
        call(lee, json(patch("/api/tasks/" + task), "{\"dueDate\":\"02/10\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void a_new_cycle_goes_to_the_default_assignee_then_the_project_em_then_the_pm() throws Exception {
        call(kim, get("/api/tasks/" + openTask(accepted("MONTHLY", "2026-01-31"))))
                .andExpect(jsonPath("$.task.assigneeId").doesNotExist()); // no default, no EM, no PM

        project("\"pmUserId\":" + kimId);
        call(kim, get("/api/tasks/" + openTask(accepted("MONTHLY", "2026-01-31"))))
                .andExpect(jsonPath("$.task.assigneeId").value(kimId));

        project("\"pmUserId\":" + kimId + ",\"emUserId\":" + leeId);
        long obligation = accepted("MONTHLY", "2026-01-31");
        long task = openTask(obligation);
        call(kim, get("/api/tasks/" + task)).andExpect(jsonPath("$.task.assigneeId").value(leeId));

        // the default assignee wins from the next cycle on; the open one keeps its assignee
        call(lee, json(patch("/api/obligations/" + obligation), "{\"defaultAssigneeId\":" + kimId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultAssigneeId").value(kimId))
                .andExpect(jsonPath("$.openTask.assigneeId").value(leeId));
        attach(task);
        call(lee, json(post("/api/tasks/" + task + "/complete"), "{}")).andExpect(status().isOk());
        call(kim, get("/api/tasks/" + openTask(obligation))).andExpect(jsonPath("$.task.assigneeId").value(kimId));
        job.createDueNotifications(); // next cycle 2026-02-28: its reminder goes to the task's assignee
        assertThat(jdbc.queryForList("SELECT DISTINCT n.user_id FROM notification n JOIN deadline_event e "
                + "ON e.id = n.deadline_event_id WHERE e.target_id = ?", Long.class, obligation)).containsExactly(kimId);

        // an EM who is not a member of the project is skipped
        project("\"pmUserId\":" + kimId + ",\"emUserId\":null");
        jdbc.update("UPDATE project SET em_user_id = ? WHERE id = ?", parkId, projectId);
        call(kim, get("/api/tasks/" + openTask(accepted("MONTHLY", "2026-01-31"))))
                .andExpect(jsonPath("$.task.assigneeId").value(kimId));
    }

    @Test
    void the_day_after_the_due_date_the_assignee_or_every_member_is_told_once() throws Exception {
        long assigned = openTask(accepted("MONTHLY", "2026-01-31"));
        accepted("MONTHLY", "2026-01-31"); // nobody assigned
        accepted("MONTHLY", "2026-02-28"); // not due yet
        call(kim, json(patch("/api/tasks/" + assigned), "{\"assigneeId\":" + leeId + "}")).andExpect(status().isOk());

        clock.advance(Duration.ofDays(26)); // 2026-01-31: due today, not overdue
        job.createDueNotifications();
        assertThat(notified(leeId)).doesNotContain(NotificationJob.OVERDUE);

        clock.advance(Duration.ofDays(1)); // 2026-02-01
        job.createDueNotifications();
        job.createDueNotifications(); // rerun: nothing new
        assertThat(overdue(leeId)).isEqualTo(2); // own task + the unassigned one
        assertThat(overdue(kimId)).isEqualTo(1); // only the unassigned one
        assertThat(overdue(parkId)).isZero();
        assertThat(jdbc.queryForObject("SELECT message || ' ' || link FROM notification WHERE user_id = ? AND offset_days = -1",
                String.class, kimId)).isEqualTo("기한 경과 · 광명 자원회수시설 · 환경영향평가 협의 · 소음·진동 분기 1회 측정 (2026-01-31) "
                + "/projects/" + projectId + "/obligations");

        clock.advance(Duration.ofDays(1)); // 2026-02-02: not repeated
        job.createDueNotifications();
        assertThat(overdue(leeId)).isEqualTo(2);
        assertThat(overdue(kimId)).isEqualTo(1);

        job.sendDigests();
        assertThat(mailer.sent()).anySatisfy(m -> assertThat(m.body()).contains("[기한 경과]"));
    }

    @Test
    void assignees_must_be_project_members_and_a_stale_version_is_refused() throws Exception {
        long obligation = accepted("MONTHLY", "2026-01-31");
        long task = openTask(obligation);

        call(kim, json(patch("/api/tasks/" + task), "{\"assigneeId\":" + parkId + ",\"version\":0}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(status().reason(ObligationTasks.NOT_MEMBER));
        call(kim, json(patch("/api/obligations/" + obligation), "{\"defaultAssigneeId\":" + parkId + "}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(status().reason(ObligationTasks.NOT_MEMBER));
        call(park, json(patch("/api/tasks/" + task), "{\"assigneeId\":" + parkId + "}")).andExpect(status().isForbidden());
        call(park, json(patch("/api/obligations/" + obligation), "{\"defaultAssigneeId\":null}")).andExpect(status().isForbidden());

        call(kim, json(patch("/api/tasks/" + task), "{\"assigneeId\":" + leeId + ",\"version\":0}")).andExpect(status().isOk());
        call(lee, json(patch("/api/tasks/" + task), "{\"dueDate\":\"2026-02-10\",\"version\":0}")) // kim got there first
                .andExpect(status().isConflict());
        call(lee, get("/api/tasks/" + task)).andExpect(jsonPath("$.task.dueDate").value("2026-01-31"));

        attach(task);
        call(kim, json(post("/api/tasks/" + task + "/complete"), "{}")).andExpect(status().isOk());
        call(kim, json(patch("/api/tasks/" + task), "{\"assigneeId\":null}")).andExpect(status().isConflict()); // not open
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private void project(String roles) throws Exception {
        call(kim, json(put("/api/projects/" + projectId), """
                {"name":"광명 자원회수시설","facilityType":"INCINERATION","location":"광명",%s}""".formatted(roles)))
                .andExpect(status().isOk());
    }

    private java.util.List<Integer> notified(long userId) {
        return jdbc.queryForList("SELECT offset_days FROM notification WHERE user_id = ? ORDER BY id", Integer.class, userId);
    }

    private long overdue(long userId) {
        return notified(userId).stream().filter(o -> o == NotificationJob.OVERDUE).count();
    }

    private long accepted(String frequency, String firstDue) throws Exception {
        return id(call(kim, json(post("/api/suggestions/" + addSuggestion(lee, frequency) + "/accept"),
                "{\"firstDueDate\":\"" + firstDue + "\",\"version\":0}")).andExpect(status().isOk()), "$.appliedEntityId");
    }

    private long addSuggestion(String token, String frequency) throws Exception {
        return id(call(token, json(post("/api/projects/" + projectId + "/suggestions"), """
                {"permitItemId":%d,"payload":{"citation_raw":"협의의견 3-2항","summary":"소음·진동 분기 1회 측정",
                 "requirement_text":"부지경계 4개 지점에서 소음·진동을 분기 1회 이상 측정할 것",
                 "type":"MEASURE_SURVEY","category":"NOISE_VIBRATION","frequency":"%s"}}""".formatted(permitId, frequency)))
                .andExpect(status().isCreated()));
    }

    private long openTask(long obligation) throws Exception {
        return id(call(kim, get("/api/projects/" + projectId + "/tasks?obligationId=" + obligation)), "$[?(@.status=='OPEN')].id");
    }

    private void attach(long task) throws Exception {
        call(kim, json(post("/api/tasks/" + task + "/evidence"), "{\"documentId\":" + upload(permitId, "증빙.pdf") + "}"))
                .andExpect(status().isOk());
    }

    private long upload(long permit, String name) throws Exception {
        return id(call(kim, multipart("/api/projects/" + projectId + "/permits/" + permit + "/documents")
                .file(new MockMultipartFile("file", name, "application/pdf", "%PDF-1.4 test".getBytes())))
                .andExpect(status().isCreated()));
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
        return ((Number) (v instanceof java.util.List<?> l ? l.get(0) : v)).longValue();
    }

    private String login(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"pw\"}".formatted(email)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
