package com.envpermit.obligation;

import com.envpermit.core.permit.PermitItem;
import com.envpermit.core.permit.PermitItemRepository;
import com.envpermit.notification.Deadlines;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 회차별 업무 (operations-compliance design §2): one {@code obligation_task} per obligation due date.
 * Every path that sets, moves or ends an obligation's due date calls {@link #sync}, so an ACTIVE obligation
 * with a due date always has exactly one OPEN task and one D-day deadline. Never reads {@code ai_suggestion}.
 */
@Service
public class ObligationTasks {

    static final String DEADLINE_KIND = "OBLIGATION";
    /** D-day event of a 조치 업무; target = the task id. It exists only while the task is OPEN. */
    public static final String CORRECTIVE_DEADLINE = "CORRECTIVE_TASK";
    static final String NOT_CORRECTIVE = "의무사항 업무는 취소할 수 없습니다. 의무사항을 종료하세요.";
    static final String NEEDS_EVIDENCE = "증빙 문서나 측정값이 1건 이상 있어야 완료할 수 있습니다.";
    static final String NEEDS_NEXT_DUE = "다음 기한일을 입력하세요.";
    static final String NOT_OPEN = "이미 완료되었거나 취소된 업무입니다.";
    static final String OTHER_PERMIT = "같은 인허가 항목의 문서만 증빙으로 연결할 수 있습니다.";
    static final String NOT_MEMBER = "담당자는 이 프로젝트의 멤버여야 합니다.";

    private static final String SELECT = """
            SELECT t.id, t.project_id, t.kind, t.obligation_id,
                   COALESCE(o.permit_item_id, ep.permit_item_id) AS permit_item_id,
                   CASE WHEN t.kind = 'OBLIGATION' THEN o.summary ELSE t.title END AS title,
                   t.due_date, t.assignee_id, a.name AS assignee_name, t.status, t.note, d.name AS done_by_name,
                   t.done_at, t.cancelled_reason, t.version, t.measurement_id, cm.emission_point_id,
                   (SELECT count(*) FROM task_evidence e WHERE e.task_id = t.id) AS evidence_count,
                   (SELECT count(*) FROM measurement m WHERE m.task_id = t.id AND m.deleted_at IS NULL) AS measurement_count
            FROM obligation_task t
            LEFT JOIN permit_obligation o ON o.id = t.obligation_id
            LEFT JOIN measurement cm ON cm.id = t.measurement_id
            LEFT JOIN emission_point ep ON ep.id = cm.emission_point_id
            LEFT JOIN users a ON a.id = t.assignee_id
            LEFT JOIN users d ON d.id = t.done_by
            """;

    private final JdbcTemplate jdbc;
    private final PermitObligationRepository obligations;
    private final PermitItemRepository permits;
    private final Deadlines deadlines;
    private final Clock clock;
    private final ObjectMapper json;
    private final int correctiveDays;

    ObligationTasks(JdbcTemplate jdbc, PermitObligationRepository obligations, PermitItemRepository permits,
                    Deadlines deadlines, Clock clock, ObjectMapper json,
                    @org.springframework.beans.factory.annotation.Value("${app.corrective.default-days:7}") int correctiveDays) {
        this.correctiveDays = correctiveDays;
        this.jdbc = jdbc;
        this.obligations = obligations;
        this.permits = permits;
        this.deadlines = deadlines;
        this.clock = clock;
        this.json = json;
    }

    /**
     * late: finished after its due date ("지연 완료"). Overdue is not stored: OPEN and dueDate before today.
     * measurementId / emissionPointId: the exceeding value that opened a CORRECTIVE task, and its emission point.
     */
    public record TaskView(long id, long projectId, String kind, Long obligationId, Long permitItemId, String title,
                           LocalDate dueDate, Long assigneeId, String assigneeName, String status, String note,
                           String doneByName, Instant doneAt, boolean late, String cancelledReason, long version,
                           int evidenceCount, int measurementCount, Long measurementId, Long emissionPointId) {
    }

    /** A live measured value entered for the task (operations-compliance/04); it counts like an evidence document. */
    public record MeasurementView(long id, String pointName, String pollutantName, LocalDate measuredOn, BigDecimal value,
                                  String unit, boolean belowDetection, String level) {
    }

    public record EvidenceView(long documentId, long permitItemId, String fileName, String addedByName, Instant addedAt) {
    }

    public record EventView(String type, JsonNode data, Instant at, String byName) {
    }

    public record TaskDetail(TaskView task, List<EvidenceView> evidence, List<MeasurementView> measurements,
                             List<EventView> events) {
    }

    // --- lifecycle --------------------------------------------------------------------------------------------

    /**
     * Brings the obligation's open task and D-day deadline in line with its status and next due date:
     * creates the cycle, moves it, or cancels it when the obligation ended. Runs in the caller's transaction.
     *
     * @param source why a task is created: ACCEPT / COMPLETE_NEXT / DUE_SET
     */
    void sync(PermitObligation o, PermitItem permit, String source, Long actorId) {
        TaskView open = openTask(o.getId());
        LocalDate due = o.getStatus() == PermitObligation.Status.ACTIVE ? o.getNextDueDate() : null;
        Long assignee = open == null ? null : open.assigneeId();
        if (due == null) {
            if (open != null) {
                String reason = o.getClosedReason() == PermitObligation.ClosedReason.REVISION_REMOVED
                        ? "REVISION_REMOVED" : "OBLIGATION_CLOSED";
                jdbc.update("UPDATE obligation_task SET status = 'CANCELLED', cancelled_reason = ?, version = version + 1 "
                        + "WHERE id = ?", reason, open.id());
                event(open.id(), actorId, "CANCELLED", Map.of("reason", reason));
            }
        } else if (open == null) {
            assignee = defaultAssignee(o.getDefaultAssigneeId(), permit.getProjectId(), null);
            Long id = jdbc.queryForObject("INSERT INTO obligation_task (project_id, kind, obligation_id, title, due_date, "
                            + "assignee_id, status) VALUES (?, 'OBLIGATION', ?, ?, ?, ?::bigint, 'OPEN') RETURNING id", Long.class,
                    permit.getProjectId(), o.getId(), o.getSummary(), due, assignee);
            event(id, actorId, "CREATED", Map.of("source", source));
        } else if (!due.equals(open.dueDate())) {
            jdbc.update("UPDATE obligation_task SET due_date = ?, version = version + 1 WHERE id = ?", due, open.id());
            event(open.id(), actorId, "DUE_CHANGED", Map.of("from", open.dueDate().toString(), "to", due.toString()));
        }
        String title = permit.getName() + " · " + o.getSummary();
        deadlines.register(DEADLINE_KIND, o.getId(), permit.getProjectId(), title.substring(0, Math.min(300, title.length())),
                due, assignee);
    }

    /**
     * The obligation's default assignee → project EM → PM → lastResort, skipping anyone who is not a project
     * member; else null.
     */
    private Long defaultAssignee(Long preferred, long projectId, Long lastResort) {
        return jdbc.queryForList("""
                SELECT c.id FROM project p
                CROSS JOIN LATERAL (VALUES (1, ?::bigint), (2, p.em_user_id), (3, p.pm_user_id), (4, ?::bigint)) c(ord, id)
                JOIN project_member m ON m.project_id = p.id AND m.user_id = c.id
                WHERE p.id = ? ORDER BY c.ord LIMIT 1""", Long.class, preferred, lastResort, projectId)
                .stream().findFirst().orElse(null);
    }

    /**
     * 초과 조치 (design §3.5): the exceeding measurement opens one CORRECTIVE task, due in
     * {@code app.corrective.default-days}, assigned to the limit's obligation default → EM → PM → the person who
     * entered the value. While a task for the same emission point and pollutant is OPEN, the value is only
     * appended to it as an event. The caller holds the emission point's row lock, so two saves cannot both insert.
     *
     * @param obligationId the limit's basis obligation, or null
     * @return the task the measurement now belongs to
     */
    @Transactional
    public long corrective(long projectId, long measurementId, Long obligationId, String title, long actorId) {
        Long open = jdbc.queryForList("""
                SELECT t.id FROM obligation_task t
                JOIN measurement c ON c.id = t.measurement_id
                JOIN measurement m ON m.emission_point_id = c.emission_point_id AND m.pollutant = c.pollutant
                WHERE t.kind = 'CORRECTIVE' AND t.status = 'OPEN' AND m.id = ? ORDER BY t.id LIMIT 1""",
                Long.class, measurementId).stream().findFirst().orElse(null);
        if (open != null) {
            event(open, actorId, "MEASUREMENT_ADDED", Map.of("measurement_id", measurementId, "title", title));
            return open;
        }
        LocalDate due = LocalDate.now(clock).plusDays(correctiveDays);
        Long assignee = defaultAssignee(obligationId == null ? null
                : obligations.findById(obligationId).map(PermitObligation::getDefaultAssigneeId).orElse(null), projectId, actorId);
        Long id = jdbc.queryForObject("INSERT INTO obligation_task (project_id, kind, obligation_id, measurement_id, title, "
                        + "due_date, assignee_id, status) VALUES (?, 'CORRECTIVE', ?, ?, ?, ?, ?::bigint, 'OPEN') RETURNING id",
                Long.class, projectId, obligationId, measurementId, title, due, assignee);
        event(id, actorId, "CREATED", Map.of("source", "EXCEEDANCE", "measurement_id", measurementId));
        deadlines.register(CORRECTIVE_DEADLINE, id, projectId, title, due, assignee);
        return id;
    }

    /** 근거 의무사항 담당자: who has the obligation's open cycle, else its default assignee; may be null. */
    public Long assigneeOf(long obligationId) {
        TaskView open = openTask(obligationId);
        return open != null && open.assigneeId() != null ? open.assigneeId()
                : obligations.findById(obligationId).map(PermitObligation::getDefaultAssigneeId).orElse(null);
    }

    /** 조치 업무 수동 취소 with a reason (deleting the exceeding value does not cancel it). Obligation tasks end with their obligation. */
    @Transactional
    public void cancel(long taskId, long actorId, String reason, Long version) {
        TaskView t = lockOpen(taskId, version);
        if (!t.kind().equals("CORRECTIVE")) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, NOT_CORRECTIVE);
        }
        jdbc.update("UPDATE obligation_task SET status = 'CANCELLED', cancelled_reason = 'MANUAL', version = version + 1 "
                + "WHERE id = ?", taskId);
        event(taskId, actorId, "CANCELLED", Map.of("reason", "MANUAL", "note", reason.trim()));
        deadlines.remove(CORRECTIVE_DEADLINE, taskId);
    }

    /** 422 unless the user is a member of the project; null (nobody) is always allowed. */
    void requireMember(long projectId, Long userId) {
        if (userId != null && jdbc.queryForObject("SELECT count(*) FROM project_member WHERE project_id = ? AND user_id = ?",
                Integer.class, projectId, userId) == 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, NOT_MEMBER);
        }
    }

    /**
     * 담당·기한 변경 of an open task. An obligation task's due date is the obligation's next due date, so both
     * (and the D-day deadline with its recipient) move together.
     *
     * @param setAssignee false leaves the assignee as is; true with a null assigneeId unassigns
     */
    @Transactional
    public void update(long taskId, long actorId, boolean setAssignee, Long assigneeId, LocalDate dueDate, Long version) {
        TaskView t = lockOpen(taskId, version);
        if (setAssignee && !java.util.Objects.equals(assigneeId, t.assigneeId())) {
            requireMember(t.projectId(), assigneeId);
            jdbc.update("UPDATE obligation_task SET assignee_id = ?::bigint, version = version + 1 WHERE id = ?", assigneeId, taskId);
            Map<String, Object> change = new java.util.HashMap<>(); // Map.of rejects null
            change.put("from", t.assigneeId());
            change.put("to", assigneeId);
            event(taskId, actorId, "ASSIGNED", change);
        }
        boolean moved = dueDate != null && !dueDate.equals(t.dueDate());
        if (t.kind().equals("OBLIGATION")) {
            PermitObligation o = obligations.findById(t.obligationId()).orElseThrow();
            if (moved) {
                o.moveTo(dueDate);
            }
            sync(o, permits.findById(o.getPermitItemId()).orElseThrow(), "DUE_SET", actorId); // task, deadline, recipient
        } else {
            if (moved) {
                jdbc.update("UPDATE obligation_task SET due_date = ?, version = version + 1 WHERE id = ?", dueDate, taskId);
                event(taskId, actorId, "DUE_CHANGED", Map.of("from", t.dueDate().toString(), "to", dueDate.toString()));
            }
            deadlines.register(CORRECTIVE_DEADLINE, taskId, t.projectId(), t.title(), moved ? dueDate : t.dueDate(),
                    setAssignee ? assigneeId : t.assigneeId());
        }
    }

    /**
     * [완료]: needs at least one evidence document or one measured value entered for the task (O2). The obligation moves to its next due date by frequency
     * (month-end clamped; OTHER needs a typed date; ONCE closes it) and the next cycle's task opens.
     */
    @Transactional
    public void complete(long taskId, long actorId, String note, LocalDate typedNext, Long version) {
        TaskView t = lockOpen(taskId, version);
        if (t.evidenceCount() == 0 && t.measurementCount() == 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, NEEDS_EVIDENCE);
        }
        // a corrective task has no next cycle, and must not move the obligation its limit is based on
        PermitObligation o = t.kind().equals("OBLIGATION") ? obligations.findById(t.obligationId()).orElseThrow() : null;
        LocalDate next = null;
        if (o != null && o.getFrequency() != Frequency.ONCE) {
            next = typedNext != null ? typedNext : o.getFrequency().next(t.dueDate());
            if (next == null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, NEEDS_NEXT_DUE);
            }
        }
        Instant now = clock.instant();
        jdbc.update("UPDATE obligation_task SET status = 'DONE', note = ?, done_by = ?, done_at = ?, version = version + 1 "
                + "WHERE id = ?", note == null || note.isBlank() ? null : note.trim(), actorId, Timestamp.from(now), taskId);
        event(taskId, actorId, "COMPLETED", Map.of("late", LocalDate.now(clock).isAfter(t.dueDate())));
        if (o != null) {
            if (next == null) {
                o.close(now);
            } else {
                o.moveTo(next);
            }
            sync(o, permits.findById(o.getPermitItemId()).orElseThrow(), "COMPLETE_NEXT", actorId);
        } else {
            deadlines.remove(CORRECTIVE_DEADLINE, taskId);
        }
    }

    // --- evidence ---------------------------------------------------------------------------------------------

    /** Only documents of the task's own permit item; linking the same document twice is a no-op. */
    @Transactional
    public void addEvidence(long taskId, long actorId, long documentId) {
        TaskView t = lockOpen(taskId, null);
        Integer same = jdbc.queryForObject("SELECT count(*) FROM document WHERE id = ? AND permit_item_id = ?",
                Integer.class, documentId, t.permitItemId());
        if (same == null || same == 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, OTHER_PERMIT);
        }
        if (jdbc.update("INSERT INTO task_evidence (task_id, document_id, added_by, added_at) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT DO NOTHING", taskId, documentId, actorId, Timestamp.from(clock.instant())) > 0) {
            event(taskId, actorId, "EVIDENCE_ADDED", Map.of("document_id", documentId));
        }
    }

    @Transactional
    public void removeEvidence(long taskId, long actorId, long documentId) {
        lockOpen(taskId, null);
        if (jdbc.update("DELETE FROM task_evidence WHERE task_id = ? AND document_id = ?", taskId, documentId) > 0) {
            event(taskId, actorId, "EVIDENCE_REMOVED", Map.of("document_id", documentId));
        }
    }

    // --- reads ------------------------------------------------------------------------------------------------

    public TaskView get(long taskId) {
        return jdbc.query(SELECT + "WHERE t.id = ?", this::view, taskId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** Every task of the project, oldest first; obligationId narrows it to one obligation's cycles (no corrective tasks). */
    public List<TaskView> list(long projectId, Long obligationId) {
        return jdbc.query(SELECT + "WHERE t.project_id = ? AND (?::bigint IS NULL OR (t.obligation_id = ? AND t.kind = 'OBLIGATION')) "
                        + "ORDER BY t.id",
                this::view, projectId, obligationId, obligationId);
    }

    /** 내 할 일: my open tasks in projects I am still a member of, soonest due first. */
    public List<TaskView> mine(long userId) {
        return jdbc.query(SELECT + """
                WHERE t.assignee_id = ? AND t.status = 'OPEN' AND EXISTS (SELECT 1 FROM project_member m
                      WHERE m.project_id = t.project_id AND m.user_id = t.assignee_id)
                ORDER BY t.due_date, t.id""", this::view, userId);
    }

    public TaskDetail detail(long taskId) {
        List<EvidenceView> evidence = jdbc.query("""
                SELECT e.document_id, d.permit_item_id, d.file_name, u.name, e.added_at
                FROM task_evidence e JOIN document d ON d.id = e.document_id LEFT JOIN users u ON u.id = e.added_by
                WHERE e.task_id = ? ORDER BY e.added_at, e.document_id""",
                (rs, i) -> new EvidenceView(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant()), taskId);
        List<EventView> events = jdbc.query("""
                SELECT e.type, e.data::text, e.at, u.name FROM task_event e LEFT JOIN users u ON u.id = e.actor_id
                WHERE e.task_id = ? ORDER BY e.id""",
                (rs, i) -> new EventView(rs.getString(1), tree(rs.getString(2)), rs.getTimestamp(3).toInstant(),
                        rs.getString(4)), taskId);
        List<MeasurementView> measurements = jdbc.query("""
                SELECT m.id, p.name, pl.name, m.measured_on, m.value, m.unit, m.below_detection, m.level
                FROM measurement m JOIN emission_point p ON p.id = m.emission_point_id JOIN pollutant pl ON pl.code = m.pollutant
                WHERE m.task_id = ? AND m.deleted_at IS NULL ORDER BY m.measured_on, m.id""",
                (rs, i) -> new MeasurementView(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, LocalDate.class), rs.getBigDecimal(5), rs.getString(6), rs.getBoolean(7),
                        rs.getString(8)), taskId);
        return new TaskDetail(get(taskId), evidence, measurements, events);
    }

    TaskView openTask(long obligationId) {
        return jdbc.query(SELECT + "WHERE t.obligation_id = ? AND t.kind = 'OBLIGATION' AND t.status = 'OPEN'",
                this::view, obligationId).stream().findFirst().orElse(null);
    }

    // --- internals --------------------------------------------------------------------------------------------

    /** Row lock, so two completions (or a completion racing an evidence change) cannot both pass the checks. */
    private TaskView lockOpen(long taskId, Long version) {
        if (jdbc.queryForList("SELECT id FROM obligation_task WHERE id = ? FOR UPDATE", taskId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        TaskView t = get(taskId);
        if (!t.status().equals("OPEN")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, NOT_OPEN);
        }
        if (version != null && version != t.version()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "다른 사용자가 먼저 수정했습니다. 새로고침 후 다시 시도하세요.");
        }
        return t;
    }

    private void event(long taskId, Long actorId, String type, Map<String, ?> data) {
        try {
            jdbc.update("INSERT INTO task_event (task_id, actor_id, at, type, data) VALUES (?, ?, ?, ?, ?::jsonb)",
                    taskId, actorId, Timestamp.from(clock.instant()), type, json.writeValueAsString(data));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode tree(String text) {
        try {
            return text == null ? null : json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private TaskView view(ResultSet rs, int i) throws SQLException {
        LocalDate due = rs.getObject("due_date", LocalDate.class);
        Timestamp done = rs.getTimestamp("done_at");
        return new TaskView(rs.getLong("id"), rs.getLong("project_id"), rs.getString("kind"),
                rs.getObject("obligation_id", Long.class), rs.getObject("permit_item_id", Long.class),
                rs.getString("title"), due, rs.getObject("assignee_id", Long.class), rs.getString("assignee_name"),
                rs.getString("status"), rs.getString("note"), rs.getString("done_by_name"),
                done == null ? null : done.toInstant(),
                done != null && done.toInstant().atZone(clock.getZone()).toLocalDate().isAfter(due),
                rs.getString("cancelled_reason"), rs.getLong("version"), rs.getInt("evidence_count"),
                rs.getInt("measurement_count"), rs.getObject("measurement_id", Long.class),
                rs.getObject("emission_point_id", Long.class));
    }
}
