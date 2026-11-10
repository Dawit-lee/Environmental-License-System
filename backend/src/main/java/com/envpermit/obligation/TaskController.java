package com.envpermit.obligation;

import static com.envpermit.core.project.ProjectAccess.userId;

import com.envpermit.core.project.ProjectAccess;
import com.envpermit.obligation.ObligationTasks.TaskDetail;
import com.envpermit.obligation.ObligationTasks.TaskView;
import java.time.LocalDate;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping("/api/projects/{projectId}/tasks")
    List<TaskView> list(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId,
                        @RequestParam(required = false) Long obligationId) {
        access.member(projectId, jwt);
        return tasks.list(projectId, obligationId);
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
