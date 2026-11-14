package com.envpermit.schedule;

import static com.envpermit.core.project.ProjectAccess.userId;

import com.envpermit.core.project.MemberRemoved;
import com.envpermit.core.project.ProjectAccess;
import com.envpermit.notification.Deadlines;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 프로젝트 › 일정: per-discipline tasks and milestones with fixed dates (설계 §2.2; anchors and baselines are ticket 03).
 * Members only. Status and delay are computed on read ({@link ScheduleStatus}), never stored. Every change leaves an
 * append-only {@code schedule_item_event}; delete is soft so that history stays. Reads never touch {@code ai_suggestion}.
 */
@RestController
class ScheduleController {

    static final String KIND = "SCHEDULE_ITEM";

    private static final String ITEMS = """
            SELECT i.id, i.discipline_id, d.name AS discipline_name, i.parent_id, i.kind, i.title, i.start_date,
                   i.end_date, i.actual_end, i.owner_user_id, u.name AS owner_name, i.partner_name, i.progress, i.version
            FROM schedule_item i
            LEFT JOIN discipline d ON d.id = i.discipline_id
            LEFT JOIN users u ON u.id = i.owner_user_id
            WHERE i.deleted_at IS NULL
            """;

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final Deadlines deadlines;
    private final Clock clock;
    private final ObjectMapper json;

    ScheduleController(JdbcTemplate jdbc, ProjectAccess access, Deadlines deadlines, Clock clock, ObjectMapper json) {
        this.jdbc = jdbc;
        this.access = access;
        this.deadlines = deadlines;
        this.clock = clock;
        this.json = json;
    }

    record ItemView(long id, Long disciplineId, String disciplineName, Long parentId, String kind, String title,
                    LocalDate startDate, LocalDate endDate, LocalDate actualEnd, Long ownerUserId, String ownerName,
                    String partnerName, int progress, long version, ScheduleStatus.Status status, int delayDays) {
    }

    record Dependency(long predecessorId, long successorId) {
    }

    record ScheduleView(LocalDate today, List<ItemView> items, List<Dependency> dependencies) {
    }

    /**
     * Create, or whole replacement of the editable fields (PATCH sends the full item, e.g. after a Gantt drag).
     * A milestone is one day: its startDate is ignored and set to endDate. predecessorIds: FS predecessors (replaced).
     * version: optimistic lock on PATCH (stale → 409); omitted = no check.
     */
    record ItemRequest(Long disciplineId, Long parentId, @NotNull @Pattern(regexp = "TASK|MILESTONE") String kind,
                       @NotBlank @Size(max = 200) String title, LocalDate startDate, @NotNull LocalDate endDate,
                       Long ownerUserId, @Size(max = 100) String partnerName, @Min(0) @Max(100) Integer progress,
                       List<Long> predecessorIds, Long version) {

        LocalDate start() {
            return "MILESTONE".equals(kind) ? endDate : startDate;
        }
    }

    record CompleteRequest(@NotNull LocalDate actualEnd) {
    }

    @GetMapping("/api/projects/{pid}/schedule")
    ScheduleView schedule(@AuthenticationPrincipal Jwt jwt, @PathVariable long pid) {
        access.member(pid, jwt);
        LocalDate today = LocalDate.now(clock);
        List<ItemView> items = jdbc.query(ITEMS + " AND i.project_id = ? ORDER BY i.start_date, i.id",
                (rs, n) -> item(rs, today), pid);
        List<Dependency> deps = jdbc.query("""
                SELECT dep.predecessor_id, dep.successor_id FROM schedule_item_dependency dep
                JOIN schedule_item s ON s.id = dep.successor_id WHERE s.project_id = ?
                ORDER BY dep.predecessor_id, dep.successor_id""",
                (rs, n) -> new Dependency(rs.getLong(1), rs.getLong(2)), pid);
        return new ScheduleView(today, items, deps);
    }

    @PostMapping("/api/projects/{pid}/schedule/items")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    ItemView create(@AuthenticationPrincipal Jwt jwt, @PathVariable long pid, @Valid @RequestBody ItemRequest req) {
        access.member(pid, jwt);
        validate(pid, null, req);
        long id = jdbc.queryForObject("""
                INSERT INTO schedule_item (project_id, discipline_id, parent_id, kind, title, start_date, end_date,
                                           owner_user_id, partner_name, progress)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                pid, req.disciplineId(), req.parentId(), req.kind(), req.title().trim(), Date.valueOf(req.start()),
                Date.valueOf(req.endDate()), req.ownerUserId(), blankToNull(req.partnerName()),
                Objects.requireNonNullElse(req.progress(), 0));
        setPredecessors(pid, id, req.predecessorIds());
        event(id, userId(jwt), "CREATED", Map.of("title", req.title().trim(), "start", req.start().toString(),
                "end", req.endDate().toString()));
        ItemView v = find(pid, id);
        remind(pid, v);
        return v;
    }

    @PatchMapping("/api/projects/{pid}/schedule/items/{id}")
    @Transactional
    ItemView update(@AuthenticationPrincipal Jwt jwt, @PathVariable long pid, @PathVariable long id,
                    @Valid @RequestBody ItemRequest req) {
        access.member(pid, jwt);
        ItemView before = lock(pid, id, req.version());
        validate(pid, id, req);
        jdbc.update("""
                UPDATE schedule_item SET discipline_id = ?, parent_id = ?, kind = ?, title = ?, start_date = ?,
                       end_date = ?, owner_user_id = ?, partner_name = ?, progress = ?, version = version + 1
                WHERE id = ?""",
                req.disciplineId(), req.parentId(), req.kind(), req.title().trim(), Date.valueOf(req.start()),
                Date.valueOf(req.endDate()), req.ownerUserId(), blankToNull(req.partnerName()),
                Objects.requireNonNullElse(req.progress(), before.progress()), id);
        setPredecessors(pid, id, req.predecessorIds());
        if (!req.start().equals(before.startDate()) || !req.endDate().equals(before.endDate())) {
            event(id, userId(jwt), "DATES_CHANGED", Map.of(
                    "from", Map.of("start", before.startDate().toString(), "end", before.endDate().toString()),
                    "to", Map.of("start", req.start().toString(), "end", req.endDate().toString())));
        }
        if (!Objects.equals(before.ownerUserId(), req.ownerUserId())) {
            Map<String, Object> change = new HashMap<>(); // Map.of rejects null
            change.put("from", before.ownerUserId());
            change.put("to", req.ownerUserId());
            event(id, userId(jwt), "OWNER_CHANGED", change);
        }
        ItemView v = find(pid, id);
        remind(pid, v);
        return v;
    }

    /** 실적 종료일 → 완료. Its reminders stop. */
    @PostMapping("/api/projects/{pid}/schedule/items/{id}/complete")
    @Transactional
    ItemView complete(@AuthenticationPrincipal Jwt jwt, @PathVariable long pid, @PathVariable long id,
                      @Valid @RequestBody CompleteRequest req) {
        access.member(pid, jwt);
        ItemView before = lock(pid, id, null);
        if (before.actualEnd() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 완료된 항목입니다.");
        }
        if (req.actualEnd().isAfter(LocalDate.now(clock))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "실적 종료일은 오늘 이후일 수 없습니다.");
        }
        jdbc.update("UPDATE schedule_item SET actual_end = ?, progress = 100, version = version + 1 WHERE id = ?",
                Date.valueOf(req.actualEnd()), id);
        event(id, userId(jwt), "COMPLETED", Map.of("actualEnd", req.actualEnd().toString()));
        ItemView v = find(pid, id);
        remind(pid, v);
        return v;
    }

    /** Soft delete (history stays); its sub-items go with it, and its dependencies are dropped. */
    @DeleteMapping("/api/projects/{pid}/schedule/items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long pid, @PathVariable long id) {
        access.member(pid, jwt);
        lock(pid, id, null);
        List<Long> ids = new ArrayList<>(List.of(id));
        ids.addAll(jdbc.queryForList("SELECT id FROM schedule_item WHERE parent_id = ? AND deleted_at IS NULL",
                Long.class, id));
        for (long i : ids) {
            jdbc.update("UPDATE schedule_item SET deleted_at = ?, version = version + 1 WHERE id = ?",
                    Timestamp.from(clock.instant()), i);
            jdbc.update("DELETE FROM schedule_item_dependency WHERE predecessor_id = ? OR successor_id = ?", i, i);
            event(i, userId(jwt), "DELETED", Map.of());
            deadlines.remove(KIND, i);
        }
    }

    /** A removed member is no longer reminded: their open items become 미지정 (every member is reminded). */
    @EventListener
    void onMemberRemoved(MemberRemoved e) {
        jdbc.queryForList("""
                SELECT id FROM schedule_item WHERE project_id = ? AND owner_user_id = ? AND deleted_at IS NULL
                ORDER BY id""", Long.class, e.projectId(), e.userId()).forEach(id -> {
            jdbc.update("UPDATE schedule_item SET owner_user_id = NULL, version = version + 1 WHERE id = ?", id);
            Map<String, Object> change = new HashMap<>();
            change.put("from", e.userId());
            change.put("to", null);
            event(id, e.actorId(), "OWNER_CHANGED", change);
            remind(e.projectId(), find(e.projectId(), id));
        });
    }

    // --- rules ---------------------------------------------------------------------------------------------

    private void validate(long pid, Long id, ItemRequest req) {
        if (req.start() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "작업은 시작일이 필요합니다.");
        }
        if (req.start().isAfter(req.endDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "시작일이 종료일보다 늦습니다.");
        }
        if (req.disciplineId() != null && jdbc.queryForObject("""
                SELECT count(*) FROM discipline d JOIN project p ON p.company_id = d.company_id
                WHERE d.id = ? AND p.id = ?""", Integer.class, req.disciplineId(), pid) == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "회사의 분야만 지정할 수 있습니다.");
        }
        if (req.parentId() != null) {
            if (req.parentId().equals(id)) {
                throw unprocessable("자기 자신을 묶음으로 지정할 수 없습니다.");
            }
            List<Long> grand = jdbc.query("""
                    SELECT parent_id FROM schedule_item WHERE id = ? AND project_id = ? AND deleted_at IS NULL""",
                    (rs, n) -> (Long) rs.getObject(1), req.parentId(), pid);
            if (grand.isEmpty()) {
                throw unprocessable("같은 프로젝트의 항목만 묶음으로 지정할 수 있습니다.");
            }
            boolean hasChildren = id != null && jdbc.queryForObject(
                    "SELECT count(*) FROM schedule_item WHERE parent_id = ? AND deleted_at IS NULL", Integer.class, id) > 0;
            if (grand.get(0) != null || hasChildren) {
                throw unprocessable("묶음은 한 단계까지만 만들 수 있습니다.");
            }
        }
        if (req.ownerUserId() != null && jdbc.queryForObject(
                "SELECT count(*) FROM project_member WHERE project_id = ? AND user_id = ?",
                Integer.class, pid, req.ownerUserId()) == 0) {
            throw unprocessable("담당자는 프로젝트 멤버여야 합니다.");
        }
    }

    /** Replaces the item's FS predecessors: same project only, and no cycle (422). */
    private void setPredecessors(long pid, long id, List<Long> predecessorIds) {
        jdbc.update("DELETE FROM schedule_item_dependency WHERE successor_id = ?", id);
        if (predecessorIds == null || predecessorIds.isEmpty()) return;
        List<long[]> edges = jdbc.query("""
                SELECT dep.predecessor_id, dep.successor_id FROM schedule_item_dependency dep
                JOIN schedule_item s ON s.id = dep.successor_id WHERE s.project_id = ?""",
                (rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}, pid);
        for (long pred : new LinkedHashSet<>(predecessorIds)) {
            if (jdbc.queryForObject("SELECT count(*) FROM schedule_item WHERE id = ? AND project_id = ? AND deleted_at IS NULL",
                    Integer.class, pred, pid) == 0) {
                throw unprocessable("같은 프로젝트의 항목만 선행으로 지정할 수 있습니다.");
            }
            if (ScheduleStatus.createsCycle(edges, pred, id)) {
                throw unprocessable("선후행 관계가 순환합니다.");
            }
            jdbc.update("INSERT INTO schedule_item_dependency (predecessor_id, successor_id) VALUES (?, ?)", pred, id);
            edges.add(new long[] {pred, id});
        }
    }

    /** D-7·D-1 and 기한 경과 to the owner (or every member) while open; nothing once done. */
    private void remind(long pid, ItemView v) {
        deadlines.register(KIND, v.id(), pid, v.title(), v.actualEnd() == null ? v.endDate() : null, v.ownerUserId());
    }

    private ItemView lock(long pid, long id, Long version) {
        List<Long> current = jdbc.queryForList(
                "SELECT version FROM schedule_item WHERE id = ? AND project_id = ? AND deleted_at IS NULL FOR UPDATE",
                Long.class, id, pid);
        if (current.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (version != null && !version.equals(current.get(0))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "다른 사용자가 먼저 수정했습니다. 새로고침 후 다시 시도하세요.");
        }
        return find(pid, id);
    }

    private ItemView find(long pid, long id) {
        LocalDate today = LocalDate.now(clock);
        return jdbc.queryForObject(ITEMS + " AND i.project_id = ? AND i.id = ?", (rs, n) -> item(rs, today), pid, id);
    }

    private static ItemView item(ResultSet rs, LocalDate today) throws SQLException {
        LocalDate start = rs.getObject("start_date", LocalDate.class);
        LocalDate end = rs.getObject("end_date", LocalDate.class);
        LocalDate actualEnd = rs.getObject("actual_end", LocalDate.class);
        ScheduleStatus.Computed c = ScheduleStatus.of(start, end, actualEnd, today);
        return new ItemView(rs.getLong("id"), (Long) rs.getObject("discipline_id"), rs.getString("discipline_name"),
                (Long) rs.getObject("parent_id"), rs.getString("kind"), rs.getString("title"), start, end, actualEnd,
                (Long) rs.getObject("owner_user_id"), rs.getString("owner_name"), rs.getString("partner_name"),
                rs.getInt("progress"), rs.getLong("version"), c.status(), c.delayDays());
    }

    private void event(long itemId, Long actorId, String type, Map<String, ?> data) {
        try {
            jdbc.update("INSERT INTO schedule_item_event (item_id, actor_id, at, type, data) VALUES (?, ?, ?, ?, ?::jsonb)",
                    itemId, actorId, Timestamp.from(clock.instant()), type, json.writeValueAsString(data));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static ResponseStatusException unprocessable(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
