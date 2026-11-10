package com.envpermit.obligation;

import com.envpermit.core.permit.PermitItem;
import com.envpermit.core.permit.PermitItemRepository;
import com.envpermit.core.project.ProjectAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
    private final ObligationApplier applier;
    private final Clock clock;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    ObligationController(PermitObligationRepository obligations, PermitItemRepository permits, ProjectAccess access,
                         ObligationApplier applier, Clock clock, JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.obligations = obligations;
        this.permits = permits;
        this.access = access;
        this.applier = applier;
        this.clock = clock;
    }

    /** nextDueDate overrides the computed one; required for OTHER and when no due date was set yet. */
    record CompleteRequest(LocalDate nextDueDate) {
    }

    record ObligationView(long id, long permitItemId, String citationKey, String summary, Frequency frequency,
                          LocalDate firstDueDate, LocalDate nextDueDate, PermitObligation.Status status,
                          Long sourceDocumentId) {
        static ObligationView of(PermitObligation o) {
            return new ObligationView(o.getId(), o.getPermitItemId(), o.getCitationKey(), o.getSummary(), o.getFrequency(),
                    o.getFirstDueDate(), o.getNextDueDate(), o.getStatus(), o.getSourceDocumentId());
        }
    }

    record RevisionLogView(long id, String changeType, JsonNode before, Instant at, Long by, String byName) {
    }

    @GetMapping("/api/projects/{projectId}/permits/{permitId}/obligations")
    List<ObligationView> list(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId, @PathVariable long permitId) {
        access.member(projectId, jwt);
        permits.findById(permitId).filter(p -> p.getProjectId() == projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return obligations.findByPermitItemIdOrderById(permitId).stream().map(ObligationView::of).toList();
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

    /** "이행 완료": move to the next due date by frequency (month-end clamped); ONCE closes. */
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
        if (o.getFrequency() == Frequency.ONCE) {
            o.close(clock.instant());
        } else {
            LocalDate next = typed != null ? typed
                    : o.getNextDueDate() == null ? null : o.getFrequency().next(o.getNextDueDate());
            if (next == null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "다음 기한일을 입력하세요.");
            }
            o.moveTo(next);
        }
        applier.registerDeadline(o, permit);
        return ObligationView.of(o);
    }
}
