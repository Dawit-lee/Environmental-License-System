package com.envpermit.obligation;

import static com.envpermit.core.project.ProjectAccess.userId;

import com.envpermit.core.project.ProjectAccess;
import com.envpermit.obligation.ObligationTasks.TaskDetail;
import com.envpermit.obligation.ObligationTasks.TaskView;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 회차별 업무 API (design §6 F1). Every route checks project membership through the task's project. */
@RestController
class TaskController {

    private final ObligationTasks tasks;
    private final ProjectAccess access;

    TaskController(ObligationTasks tasks, ProjectAccess access) {
        this.tasks = tasks;
        this.access = access;
    }

    record EvidenceRequest(long documentId) {
    }

    /** nextDueDate overrides the computed one and is required for OTHER; version is checked when given. */
    record CompleteRequest(String note, LocalDate nextDueDate, Long version) {
    }

    record CancelRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 300) String reason,
                         Long version) {
    }

    /** 조치 업무 수동 취소; the reason is kept in the task's history. */
    @PostMapping("/api/tasks/{id}/cancel")
    TaskDetail cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                      @org.springframework.validation.annotation.Validated @RequestBody CancelRequest req) {
        member(jwt, id);
        tasks.cancel(id, userId(jwt), req.reason(), req.version());
        return tasks.detail(id);
    }

    @GetMapping("/api/projects/{projectId}/tasks")
    List<TaskView> list(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId,
                        @RequestParam(required = false) Long obligationId) {
        access.member(projectId, jwt);
        return tasks.list(projectId, obligationId);
    }

    @GetMapping("/api/tasks/mine")
    List<TaskView> mine(@AuthenticationPrincipal Jwt jwt) {
        return tasks.mine(userId(jwt));
    }

    /** {assigneeId?, dueDate?, version}: a key that is absent stays as is; "assigneeId": null unassigns. */
    @PatchMapping("/api/tasks/{id}")
    TaskDetail update(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestBody JsonNode req) {
        member(jwt, id);
        LocalDate due;
        try {
            due = req.hasNonNull("dueDate") ? LocalDate.parse(req.get("dueDate").asText()) : null;
        } catch (java.time.format.DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "기한 형식이 올바르지 않습니다.");
        }
        tasks.update(id, userId(jwt), req.has("assigneeId"),
                req.hasNonNull("assigneeId") ? req.get("assigneeId").asLong() : null, due,
                req.hasNonNull("version") ? req.get("version").asLong() : null);
        return tasks.detail(id);
    }

    @GetMapping("/api/tasks/{id}")
    TaskDetail get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        member(jwt, id);
        return tasks.detail(id);
    }

    @PostMapping("/api/tasks/{id}/evidence")
    TaskDetail addEvidence(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestBody EvidenceRequest req) {
        member(jwt, id);
        tasks.addEvidence(id, userId(jwt), req.documentId());
        return tasks.detail(id);
    }

    @DeleteMapping("/api/tasks/{id}/evidence/{documentId}")
    TaskDetail removeEvidence(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @PathVariable long documentId) {
        member(jwt, id);
        tasks.removeEvidence(id, userId(jwt), documentId);
        return tasks.detail(id);
    }

    @PostMapping("/api/tasks/{id}/complete")
    TaskDetail complete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                        @RequestBody(required = false) CompleteRequest req) {
        member(jwt, id);
        tasks.complete(id, userId(jwt), req == null ? null : req.note(), req == null ? null : req.nextDueDate(),
                req == null ? null : req.version());
        return tasks.detail(id);
    }

    private void member(Jwt jwt, long taskId) {
        access.member(tasks.get(taskId).projectId(), jwt);
    }
}
