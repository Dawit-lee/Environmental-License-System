package com.envpermit.obligation;

import static com.envpermit.core.project.ProjectAccess.userId;

import com.envpermit.core.permit.PermitItem;
import com.envpermit.core.permit.PermitItemRepository;
import com.envpermit.core.project.ProjectAccess;
import com.envpermit.obligation.ObligationTasks.TaskView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class ObligationController {

    private final PermitObligationRepository obligations;
    private final PermitItemRepository permits;
    private final ProjectAccess access;
    private final ObligationTasks tasks;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    ObligationController(PermitObligationRepository obligations, PermitItemRepository permits, ProjectAccess access,
                         ObligationTasks tasks, JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.obligations = obligations;
        this.permits = permits;
        this.access = access;
        this.tasks = tasks;
    }

    /** nextDueDate overrides the computed one; required for OTHER and when no due date was set yet. */
    record CompleteRequest(LocalDate nextDueDate) {
    }

    /** openTask: the current cycle (null: no due date yet, or ended). lastTask: the latest finished or cancelled one. */
    record ObligationView(long id, long permitItemId, String citationKey, String summary, ObligationPayload.Type type,
                          Frequency frequency,
                          LocalDate firstDueDate, LocalDate nextDueDate, PermitObligation.Status status,
                          Long sourceDocumentId, TaskView openTask, TaskView lastTask) {
    }

    private List<ObligationView> views(long projectId, List<PermitObligation> rows) {
        Map<Long, TaskView> open = new HashMap<>();
        Map<Long, TaskView> last = new HashMap<>();
        for (TaskView t : tasks.list(projectId, null)) { // oldest first, so the newest wins
            if (t.obligationId() != null) {
                (t.status().equals("OPEN") ? open : last).put(t.obligationId(), t);
            }
        }
        return rows.stream().map(o -> new ObligationView(o.getId(), o.getPermitItemId(), o.getCitationKey(),
                o.getSummary(), o.getType(), o.getFrequency(), o.getFirstDueDate(), o.getNextDueDate(), o.getStatus(),
                o.getSourceDocumentId(), open.get(o.getId()), last.get(o.getId()))).toList();
    }

    record RevisionLogView(long id, String changeType, JsonNode before, Instant at, Long by, String byName) {
    }

    @GetMapping("/api/projects/{projectId}/permits/{permitId}/obligations")
    List<ObligationView> list(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId, @PathVariable long permitId) {
        access.member(projectId, jwt);
        permits.findById(permitId).filter(p -> p.getProjectId() == projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return views(projectId, obligations.findByPermitItemIdOrderById(permitId));
    }

    /** Every obligation of the project (프로젝트 › 의무사항 tab), nearest due date first. */
    @GetMapping("/api/projects/{projectId}/obligations")
    List<ObligationView> projectList(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId) {
        access.member(projectId, jwt);
        List<Long> permitIds = permits.findByProjectIdOrderById(projectId).stream().map(PermitItem::getId).toList();
        return views(projectId, obligations.findByPermitItemIdInOrderByNextDueDateAscIdAsc(permitIds));
    }

    /** ④ 변경 이력: what each approved revision overwrote, oldest first. */
    @GetMapping("/api/obligations/{id}/revision-log")
    List<RevisionLogView> revisionLog(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        PermitObligation o = obligations.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        access.member(permits.findById(o.getPermitItemId()).orElseThrow().getProjectId(), jwt);
        return jdbc.query("""
                SELECT l.id, l.change_type, l.before::text AS before, l.at, l.by, u.name
                FROM permit_obligation_revision_log l LEFT JOIN users u ON u.id = l.by
                WHERE l.obligation_id = ? ORDER BY l.id""", (rs, i) -> {
            try {
                return new RevisionLogView(rs.getLong("id"), rs.getString("change_type"), json.readTree(rs.getString("before")),
                        rs.getTimestamp("at").toInstant(), rs.getObject("by", Long.class), rs.getString("name"));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }, id);
    }

    /**
     * "이행 완료" (kept for compatibility): completes the obligation's open task, with the same evidence rule.
     * An obligation that has no due date yet has no task; a typed date gives it one.
     */
    @PostMapping("/api/obligations/{id}/complete")
    @Transactional
    ObligationView complete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                            @RequestBody(required = false) CompleteRequest req) {
        PermitObligation o = obligations.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        PermitItem permit = permits.findById(o.getPermitItemId()).orElseThrow();
        access.member(permit.getProjectId(), jwt);
        if (o.getStatus() == PermitObligation.Status.CLOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 종료된 의무사항입니다.");
        }
        LocalDate typed = req == null ? null : req.nextDueDate();
        TaskView open = tasks.openTask(id);
        if (open != null) {
            tasks.complete(open.id(), userId(jwt), null, typed, null);
        } else if (typed == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, ObligationTasks.NEEDS_NEXT_DUE);
        } else {
            o.moveTo(typed);
            tasks.sync(o, permit, "DUE_SET", userId(jwt));
        }
        obligations.flush(); // the view below reads tasks by SQL
        return views(permit.getProjectId(), List.of(o)).get(0);
    }
}
