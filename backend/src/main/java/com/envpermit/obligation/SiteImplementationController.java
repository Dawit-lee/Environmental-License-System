package com.envpermit.obligation;

import static com.envpermit.core.project.ProjectAccess.userId;

import com.envpermit.core.project.ProjectAccess;
import com.envpermit.notification.Deadlines;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * ④ §4.8 현장 반영: approved CHANGED/ADDED revisions tracked to "done on site". A target date is a
 * SITE_IMPLEMENTATION deadline for the assignee (D-90/60/30/7); completing removes it.
 */
@RestController
class SiteImplementationController {

    static final String DEADLINE_KIND = "SITE_IMPLEMENTATION";

    private static final String SELECT = """
            SELECT r.suggestion_id, r.obligation_id, o.permit_item_id, p.name AS permit_name, p.project_id,
                   o.citation_key, o.summary, s.payload ->> 'change_type' AS change_type,
                   r.assignee_id, a.name AS assignee_name, r.target_date, r.note, r.done_by, r.done_at
            FROM revision_implementation r
            JOIN permit_obligation o ON o.id = r.obligation_id
            JOIN permit_item p ON p.id = o.permit_item_id
            JOIN ai_suggestion s ON s.id = r.suggestion_id
            LEFT JOIN users a ON a.id = r.assignee_id
            """;

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final Deadlines deadlines;
    private final Clock clock;

    SiteImplementationController(JdbcTemplate jdbc, ProjectAccess access, Deadlines deadlines, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.deadlines = deadlines;
        this.clock = clock;
    }

    record View(long suggestionId, long obligationId, long permitItemId, String permitName, long projectId,
                String citationKey, String summary, String changeType, Long assigneeId, String assigneeName,
                LocalDate targetDate, String note, Long doneBy, Instant doneAt) {
    }

    /** Whole replacement of the editable fields; a null assigneeId keeps the current one, a null targetDate clears it. */
    record UpdateRequest(Long assigneeId, LocalDate targetDate, @Size(max = 1000) String note) {
    }

    @GetMapping("/api/projects/{projectId}/revision-implementations")
    List<View> list(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId,
                    @RequestParam(required = false) String status) {
        access.member(projectId, jwt);
        String done = switch (status == null ? "" : status) {
            case "" -> "";
            case "open" -> " AND r.done_at IS NULL";
            case "done" -> " AND r.done_at IS NOT NULL";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status는 open 또는 done");
        };
        return query("p.project_id = ?" + done + " ORDER BY r.target_date NULLS LAST, r.suggestion_id", projectId);
    }

    /** Dashboard card "현장 반영 대기 N건": open rows in every project I am a member of. */
    @GetMapping("/api/revision-implementations/count")
    Map<String, Integer> count(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("count", jdbc.queryForObject("""
                SELECT count(*) FROM revision_implementation r JOIN permit_obligation o ON o.id = r.obligation_id
                JOIN permit_item p ON p.id = o.permit_item_id
                JOIN project_member m ON m.project_id = p.project_id AND m.user_id = ?
                WHERE r.done_at IS NULL""", Integer.class, userId(jwt)));
    }

    @PatchMapping("/api/revision-implementations/{suggestionId}")
    @Transactional
    View update(@AuthenticationPrincipal Jwt jwt, @PathVariable long suggestionId,
                @Validated @RequestBody UpdateRequest req) {
        View v = open(suggestionId, jwt);
        Long assignee = req.assigneeId() == null ? v.assigneeId() : req.assigneeId();
        if (req.assigneeId() != null && jdbc.queryForObject("SELECT count(*) FROM project_member WHERE project_id = ? AND user_id = ?",
                Integer.class, v.projectId(), req.assigneeId()) == 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "담당자는 프로젝트 멤버여야 합니다.");
        }
        jdbc.update("UPDATE revision_implementation SET assignee_id = ?, target_date = ?, note = ? WHERE suggestion_id = ?",
                assignee, req.targetDate(), req.note(), suggestionId);
        String title = "현장 반영 · " + v.permitName() + " · " + v.summary();
        deadlines.register(DEADLINE_KIND, suggestionId, v.projectId(), title.substring(0, Math.min(300, title.length())),
                req.targetDate(), assignee);
        return find(suggestionId);
    }

    @PostMapping("/api/revision-implementations/{suggestionId}/complete")
    @Transactional
    View complete(@AuthenticationPrincipal Jwt jwt, @PathVariable long suggestionId) {
        open(suggestionId, jwt);
        jdbc.update("UPDATE revision_implementation SET done_by = ?, done_at = ? WHERE suggestion_id = ?",
                userId(jwt), Timestamp.from(clock.instant()), suggestionId);
        deadlines.remove(DEADLINE_KIND, suggestionId);
        return find(suggestionId);
    }

    /** Row as a project member may change it: 404 unknown, 403 outsider, 409 already done. */
    private View open(long suggestionId, Jwt jwt) {
        List<View> rows = query("r.suggestion_id = ? FOR UPDATE OF r", suggestionId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        View v = rows.get(0);
        access.member(v.projectId(), jwt);
        if (v.doneAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 반영 완료된 항목입니다.");
        }
        return v;
    }

    private View find(long suggestionId) {
        return query("r.suggestion_id = ?", suggestionId).get(0);
    }

    private List<View> query(String where, Object... args) {
        return jdbc.query(SELECT + " WHERE " + where, (rs, i) -> new View(
                rs.getLong("suggestion_id"), rs.getLong("obligation_id"), rs.getLong("permit_item_id"),
                rs.getString("permit_name"), rs.getLong("project_id"), rs.getString("citation_key"),
                rs.getString("summary"), rs.getString("change_type"), rs.getObject("assignee_id", Long.class),
                rs.getString("assignee_name"), rs.getObject("target_date", LocalDate.class), rs.getString("note"),
                rs.getObject("done_by", Long.class),
                rs.getTimestamp("done_at") == null ? null : rs.getTimestamp("done_at").toInstant()), args);
    }
}
