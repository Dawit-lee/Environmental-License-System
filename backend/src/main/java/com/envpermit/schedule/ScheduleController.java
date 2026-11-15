package com.envpermit.schedule;

import static com.envpermit.core.project.ProjectAccess.userId;

import com.envpermit.core.project.MemberRemoved;
import com.envpermit.core.project.ProjectAccess;
import com.envpermit.core.project.ProjectDatesChanged;
import com.envpermit.core.project.ProjectMember;
import com.envpermit.notification.Deadlines;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 프로젝트 › 일정: per-discipline tasks and milestones (설계 §2.2~2.5, §5). Members only. An item is either fixed
 * (anchor null) or base date ± offset; moving a base date moves its open relative items. The first [기준 확정] keeps
 * the plan as baseline; afterwards every change that ends later than the baseline needs a reason. Every date change
 * goes through this class. Status, delay and 공정률 are computed on read ({@link ScheduleStatus}, {@link ScheduleDates}),
 * never stored. Every change leaves an append-only {@code schedule_item_event}; delete is soft so that history stays.
 * Reads never touch {@code ai_suggestion}.
 */
@RestController
class ScheduleController {

    static final String KIND = "SCHEDULE_ITEM";
    static final List<String> ANCHORS = List.of("CONTRACT", "KICKOFF", "SUBMISSION", "COMPLETION", "REVIEW");
    private static final Map<String, String> ANCHOR_LABEL = Map.of("CONTRACT", "계약일", "KICKOFF", "착수일",
            "SUBMISSION", "입찰(제출)일", "COMPLETION", "완료일", "REVIEW", "심의일");

    private static final String ITEMS = """
            SELECT i.id, i.discipline_id, d.name AS discipline_name, i.parent_id, i.kind, i.title, i.start_date,
                   i.end_date, i.baseline_start, i.baseline_end, i.actual_end, i.anchor, i.offset_days, i.offset_months,
                   i.duration_days, i.owner_user_id, u.name AS owner_name, i.partner_name, i.weight, i.progress,
                   i.delay_reason, i.version
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

    /**
     * Dates are null while a relative item's base date is unknown. delayDays: past the current plan (ticket 02);
     * slipDays: 기준 대비 연기 (end − baseline end), null before the first baseline.
     */
    record ItemView(long id, Long disciplineId, String disciplineName, Long parentId, String kind, String title,
                    LocalDate startDate, LocalDate endDate, LocalDate baselineStart, LocalDate baselineEnd,
                    LocalDate actualEnd, String anchor, Integer offsetDays, Integer offsetMonths, Integer durationDays,
                    Long ownerUserId, String ownerName, String partnerName, BigDecimal weight, int progress,
                    String delayReason, long version, ScheduleStatus.Status status, int delayDays, Integer slipDays) {
    }

    record Dependency(long predecessorId, long successorId) {
    }

    /** All five base dates in a fixed order; date null = not set. */
    record AnchorView(String anchor, LocalDate date, String note) {
    }

    /** progress: null when no item has a 보할. delayCount: rows of 지연 현황. */
    record ScheduleView(LocalDate today, String base, int baselineNo, List<AnchorView> anchors, List<ItemView> items,
                        List<Dependency> dependencies, ScheduleDates.Progress progress, int delayCount) {
    }

    /**
     * Create, or whole replacement of the editable fields (PATCH sends the full item, e.g. after a Gantt drag).
     * Dates: anchor null = fixed (startDate/endDate). anchor + offsetDays|offsetMonths (+ durationDays for a task)
     * = relative, dates computed. anchor without offsets = [기준일에 연결]: offsets computed back from the dates.
     * A milestone is one day. predecessorIds: FS predecessors (replaced). version: optimistic lock on PATCH
     * (stale → 409; omitted = no check). reason: required when the end moves later past the baseline end.
     */
    record ItemRequest(Long disciplineId, Long parentId, @NotNull @Pattern(regexp = "TASK|MILESTONE") String kind,
                       @NotBlank @Size(max = 200) String title, LocalDate startDate, LocalDate endDate,
                       @Pattern(regexp = "CONTRACT|KICKOFF|SUBMISSION|COMPLETION|REVIEW") String anchor,
                       Integer offsetDays, Integer offsetMonths, @Min(1) Integer durationDays,
                       Long ownerUserId, @Size(max = 100) String partnerName,
                       @DecimalMin("0") @DecimalMax("100") BigDecimal weight, @Min(0) @Max(100) Integer progress,
                       List<Long> predecessorIds, Long version, @Size(max = 500) String reason) {

        boolean milestone() {
            return "MILESTONE".equals(kind);
        }

        LocalDate start() {
            return milestone() ? endDate : startDate;
        }

        /** Same item with other dates (document → schedule update, design-schedule/07); the rest is kept. */
        ItemRequest withDates(LocalDate startDate, LocalDate endDate, String anchor, Integer offsetDays,
                              Integer offsetMonths, Integer durationDays, String reason) {
            return new ItemRequest(disciplineId, parentId, kind, title, startDate, endDate, anchor, offsetDays,
                    offsetMonths, durationDays, ownerUserId, partnerName, weight, progress, predecessorIds, null, reason);
        }

        /** The milestone a 제출·보고회 event adds to the schedule (design-schedule/04). */
        static ItemRequest milestone(Long disciplineId, String title, LocalDate date) {
            return new ItemRequest(disciplineId, null, "MILESTONE", title, date, date, null, null, null, null, null, null,
                    null, null, null, null, null);
        }
    }

    /** Where an item's dates come from; start/end null while the base date is unknown. */
    private record Plan(LocalDate start, LocalDate end, String anchor, Integer offsetDays, Integer offsetMonths,
                        Integer durationDays) {
    }

    record CompleteRequest(@NotNull LocalDate actualEnd) {
    }

    @GetMapping("/api/projects/{pid}/schedule")
    ScheduleView schedule(@AuthenticationPrincipal Jwt jwt, @PathVariable long pid) {
        access.member(pid, jwt);
        return view(pid);
    }

    @PostMapping("/api/projects/{pid}/schedule/items")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    ItemView create(@AuthenticationPrincipal Jwt jwt, @PathVariable long pid, @Valid @RequestBody ItemRequest req) {
        access.member(pid, jwt);
        return find(pid, create(pid, userId(jwt), req));
    }

    /** The route's rules for a caller that already checked membership (events, the document → schedule applier). */
    long create(long pid, Long actor, ItemRequest req) {
        validate(pid, null, req);
        Plan plan = plan(pid, req);
        long id = insert(pid, req.disciplineId(), req.parentId(), req.kind(), req.title().trim(), plan,
                req.ownerUserId(), blankToNull(req.partnerName()), Objects.requireNonNullElse(req.progress(), 0),
                req.weight());
        setPredecessors(pid, id, req.predecessorIds());
        event(id, actor, "CREATED", created(req.title().trim(), plan));
        remind(pid, find(pid, id));
        return id;
    }

    @PatchMapping("/api/projects/{pid}/schedule/items/{id}")
    @Transactional
    ItemView update(@AuthenticationPrincipal Jwt jwt, @PathVariable long pid, @PathVariable long id,
                    @Valid @RequestBody ItemRequest req) {
        access.member(pid, jwt);
        update(pid, id, userId(jwt), req);
        return find(pid, id);
    }

    /** The PATCH rules (lock, reason on a slip past the baseline, history) for a caller that checked membership. */
    void update(long pid, long id, Long actor, ItemRequest req) {
        ItemView before = lock(pid, id, req.version());
        validate(pid, id, req);
        Plan plan = plan(pid, req);
        String reason = blankToNull(req.reason());
        boolean slips = ScheduleDates.needsReason(before.baselineEnd(), before.endDate(), plan.end());
        if (slips && reason == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "기준 계획보다 늦어지는 변경은 연기 사유가 필요합니다.");
        }
        jdbc.update("""
                UPDATE schedule_item SET discipline_id = ?, parent_id = ?, kind = ?, title = ?, start_date = ?,
                       end_date = ?, anchor = ?, offset_days = ?, offset_months = ?, duration_days = ?,
                       owner_user_id = ?, partner_name = ?, weight = ?, progress = ?,
                       delay_reason = CASE WHEN ? THEN ? ELSE delay_reason END, version = version + 1
                WHERE id = ?""",
                req.disciplineId(), req.parentId(), req.kind(), req.title().trim(), sqlDate(plan.start()),
                sqlDate(plan.end()), plan.anchor(), plan.offsetDays(), plan.offsetMonths(), plan.durationDays(),
                req.ownerUserId(), blankToNull(req.partnerName()), req.weight(),
                Objects.requireNonNullElse(req.progress(), before.progress()), slips, reason, id);
        setPredecessors(pid, id, req.predecessorIds());
        if (!Objects.equals(plan.start(), before.startDate()) || !Objects.equals(plan.end(), before.endDate())) {
            datesChanged(id, actor, before.startDate(), before.endDate(), plan.start(), plan.end(), reason);
        }
        if (before.anchor() != null && plan.anchor() == null) {
            event(id, actor, "PINNED", Map.of("anchor", before.anchor())); // [날짜 고정]
        } else if (before.anchor() == null && plan.anchor() != null) {
            event(id, actor, "ANCHORED", nullable("anchor", plan.anchor(), "offsetDays", plan.offsetDays(),
                    "offsetMonths", plan.offsetMonths())); // [기준일에 연결]
        }
        if (!Objects.equals(before.ownerUserId(), req.ownerUserId())) {
            event(id, actor, "OWNER_CHANGED", nullable("from", before.ownerUserId(), "to", req.ownerUserId()));
        }
        remind(pid, find(pid, id));
    }

    /** A live item's editable fields as a full request (PATCH replaces them all); 404 when gone or elsewhere. */
    ItemRequest requestOf(long pid, long id) {
        ItemView v = jdbc.query(ITEMS + " AND i.project_id = ? AND i.id = ?", (rs, n) -> item(rs, LocalDate.now(clock)),
                pid, id).stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<Long> preds = jdbc.queryForList(
                "SELECT predecessor_id FROM schedule_item_dependency WHERE successor_id = ? ORDER BY predecessor_id",
                Long.class, id);
        return new ItemRequest(v.disciplineId(), v.parentId(), v.kind(), v.title(), v.startDate(), v.endDate(),
                v.anchor(), v.offsetDays(), v.offsetMonths(), v.durationDays(), v.ownerUserId(), v.partnerName(),
                v.weight(), v.progress(), preds, null, null);
    }

    /** Approved document row (design-schedule/07): trace the suggestion (never read by queries) and log it. */
    void applied(long id, Long actor, long suggestionId, boolean created, String documentName) {
        jdbc.update("UPDATE schedule_item SET source_suggestion_id = ? WHERE id = ?", suggestionId, id);
        event(id, actor, "APPLIED_FROM_SUGGESTION", nullable("suggestionId", suggestionId,
                "action", created ? "CREATED" : "UPDATED", "document", documentName));
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
            event(id, e.actorId(), "OWNER_CHANGED", nullable("from", e.userId(), "to", null));
            remind(e.projectId(), find(e.projectId(), id));
        });
    }

    // --- base dates (설계 §2.3) ----------------------------------------------------------------------------

    record BaseRequest(@NotNull @Pattern(regexp = "SUBMISSION|KICKOFF") String base) {
    }

    record AnchorRequest(@NotNull LocalDate date, @Size(max = 200) String note) {
    }

    record Move(long id, String title, LocalDate fromStart, LocalDate fromEnd, LocalDate toStart, LocalDate toEnd) {
    }

    /** moves: the open relative items whose dates change (all of them on apply). */
    record AnchorChange(String anchor, LocalDate from, LocalDate to, int count, List<Move> moves) {
    }

    /** 주 기준일: D-day on the Gantt axis and the default anchor of new items. */
    @PutMapping("/api/projects/{pid}/schedule/base")
    @Transactional
    ScheduleView base(@AuthenticationPrincipal Jwt jwt, @PathVariable long pid, @Valid @RequestBody BaseRequest req) {
        access.member(pid, jwt);
        jdbc.update("UPDATE project SET schedule_base = ? WHERE id = ?", req.base(), pid);
        return view(pid);
    }

    /**
     * Sets a base date. preview (default): what would move. preview=false: stores the date and moves the current
     * plan of open relative items; completed, fixed and baseline dates stay. 착수일·완료일 are the project's own dates.
     */
    @PutMapping("/api/projects/{pid}/schedule/anchors/{anchor}")
    @Transactional
    AnchorChange anchor(@AuthenticationPrincipal Jwt jwt, @PathVariable long pid, @PathVariable String anchor,
                        @RequestParam(defaultValue = "true") boolean preview, @Valid @RequestBody AnchorRequest req) {
        access.member(pid, jwt);
        if (!ANCHORS.contains(anchor)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        jdbc.queryForObject("SELECT id FROM project WHERE id = ? FOR UPDATE", Long.class, pid); // one move at a time
        LocalDate from = anchorDates(pid).get(anchor);
        AnchorChange change = shift(pid, anchor, from, req.date(), !preview, userId(jwt));
        if (!preview) {
            switch (anchor) {
                case "KICKOFF" -> jdbc.update("UPDATE project SET start_date = ? WHERE id = ?", Date.valueOf(req.date()), pid);
                case "COMPLETION" -> jdbc.update("UPDATE project SET expected_end_date = ? WHERE id = ?",
                        Date.valueOf(req.date()), pid);
                default -> jdbc.update("""
                        INSERT INTO project_schedule_anchor (project_id, anchor, date, note) VALUES (?, ?, ?, ?)
                        ON CONFLICT (project_id, anchor) DO UPDATE SET date = excluded.date, note = excluded.note""",
                        pid, anchor, Date.valueOf(req.date()), blankToNull(req.note()));
            }
        }
        return change;
    }

    /** The project form changed 착수일·완료일: same move as {@link #anchor} (the form shows its preview first). */
    @EventListener
    void onProjectDatesChanged(ProjectDatesChanged e) {
        if (!Objects.equals(e.oldStart(), e.newStart())) shift(e.projectId(), "KICKOFF", e.oldStart(), e.newStart(), true, e.actorId());
        if (!Objects.equals(e.oldEnd(), e.newEnd())) shift(e.projectId(), "COMPLETION", e.oldEnd(), e.newEnd(), true, e.actorId());
    }

    /** A cleared base date moves nothing: the items keep their last dates. */
    private AnchorChange shift(long pid, String anchor, LocalDate from, LocalDate to, boolean apply, Long actor) {
        List<Move> moves = new ArrayList<>();
        if (to != null) {
            jdbc.query("""
                    SELECT id, title, kind, start_date, end_date, offset_days, offset_months, duration_days
                    FROM schedule_item WHERE project_id = ? AND anchor = ? AND actual_end IS NULL AND deleted_at IS NULL
                    ORDER BY end_date NULLS LAST, id""", rs -> {
                ScheduleDates.Range r = ScheduleDates.resolve(to, (Integer) rs.getObject("offset_days"),
                        (Integer) rs.getObject("offset_months"), (Integer) rs.getObject("duration_days"),
                        "MILESTONE".equals(rs.getString("kind")));
                LocalDate start = rs.getObject("start_date", LocalDate.class);
                LocalDate end = rs.getObject("end_date", LocalDate.class);
                if (!r.start().equals(start) || !r.end().equals(end)) {
                    moves.add(new Move(rs.getLong("id"), rs.getString("title"), start, end, r.start(), r.end()));
                }
            }, pid, anchor);
        }
        if (apply && !moves.isEmpty()) {
            String reason = "기준일 변경: %s %s → %s".formatted(ANCHOR_LABEL.get(anchor), shortDate(from), shortDate(to));
            for (Move m : moves) {
                // the move is the reason itself, so a slip past the baseline needs none from the user
                jdbc.update("""
                        UPDATE schedule_item SET start_date = ?, end_date = ?, version = version + 1,
                               delay_reason = CASE WHEN baseline_end < ? AND (end_date IS NULL OR end_date < ?)
                                                   THEN ? ELSE delay_reason END
                        WHERE id = ?""", Date.valueOf(m.toStart()), Date.valueOf(m.toEnd()), Date.valueOf(m.toEnd()),
                        Date.valueOf(m.toEnd()), reason, m.id());
                datesChanged(m.id(), actor, m.fromStart(), m.fromEnd(), m.toStart(), m.toEnd(), reason);
                remind(pid, find(pid, m.id()));
            }
        }
        return new AnchorChange(anchor, from, to, moves.size(), moves);
    }

    // --- baseline (설계 §2.4) ------------------------------------------------------------------------------

    record BaselineRequest(@Size(max = 500) String reason) {
    }

    /** [기준 확정]: every item's current plan becomes its baseline. Re-baselining: PM or project admin, with a reason. */
    @PostMapping("/api/projects/{pid}/schedule/baseline")
    @Transactional
    ScheduleView baseline(@AuthenticationPrincipal Jwt jwt, @PathVariable long pid,
                          @Valid @RequestBody(required = false) BaselineRequest req) {
        ProjectMember me = access.member(pid, jwt);
        Map<String, Object> p = jdbc.queryForMap(
                "SELECT schedule_baseline_no, pm_user_id FROM project WHERE id = ? FOR UPDATE", pid);
        int no = (Integer) p.get("schedule_baseline_no");
        String reason = req == null ? null : blankToNull(req.reason());
        if (no > 0) {
            if (!me.isAdmin() && !Objects.equals(p.get("pm_user_id"), userId(jwt))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "기준 재확정은 PM·프로젝트 어드민만 할 수 있습니다.");
            }
            if (reason == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "기준 재확정은 사유가 필요합니다.");
            }
        }
        jdbc.query("""
                SELECT id, start_date, end_date, baseline_start, baseline_end FROM schedule_item
                WHERE project_id = ? AND deleted_at IS NULL ORDER BY id""", rs -> {
            event(rs.getLong("id"), userId(jwt), "BASELINED", nullable("no", no + 1, "reason", reason,
                    "previous", dates(rs.getObject("baseline_start", LocalDate.class), rs.getObject("baseline_end", LocalDate.class)),
                    "baseline", dates(rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class))));
        }, pid);
        jdbc.update("""
                UPDATE schedule_item SET baseline_start = start_date, baseline_end = end_date, version = version + 1
                WHERE project_id = ? AND deleted_at IS NULL""", pid);
        jdbc.update("UPDATE project SET schedule_baseline_no = ? WHERE id = ?", no + 1, pid);
        return view(pid);
    }

    // --- 지연 현황 (설계 §6.1) ------------------------------------------------------------------------------

    /** delayDays: {@link ScheduleDates#delayDays}; successors: how many items wait on this one (FS). */
    record DelayRow(long id, String disciplineName, String title, String ownerName, LocalDate baselineEnd,
                    LocalDate endDate, LocalDate actualEnd, int delayDays, Integer slipDays, String reason,
                    long successors) {
    }

    /** Items behind their original end, most days first. */
    @GetMapping("/api/projects/{pid}/schedule/delays")
    List<DelayRow> delays(@AuthenticationPrincipal Jwt jwt, @PathVariable long pid) {
        access.member(pid, jwt);
        return delays(items(pid), dependencies(pid));
    }

    private List<DelayRow> delays(List<ItemView> items, List<Dependency> deps) {
        LocalDate today = LocalDate.now(clock);
        return items.stream()
                .map(i -> new DelayRow(i.id(), i.disciplineName(), i.title(), i.ownerName(), i.baselineEnd(),
                        i.endDate(), i.actualEnd(), ScheduleDates.delayDays(i.endDate(), i.baselineEnd(), i.actualEnd(), today),
                        i.slipDays(), i.delayReason(), deps.stream().filter(d -> d.predecessorId() == i.id()).count()))
                .filter(r -> r.delayDays() > 0)
                .sorted(Comparator.comparingInt(DelayRow::delayDays).reversed().thenComparingLong(DelayRow::id))
                .toList();
    }

    // --- templates and paste (설계 §5) --------------------------------------------------------------------

    record ApplyTemplateRequest(@NotNull Long templateId) {
    }

    /** Adds the template's items as relative items; they get dates once their base date is set. */
    @PostMapping("/api/projects/{pid}/schedule/apply-template")
    @Transactional
    ScheduleView applyTemplate(@AuthenticationPrincipal Jwt jwt, @PathVariable long pid,
                               @Valid @RequestBody ApplyTemplateRequest req) {
        access.member(pid, jwt);
        List<String> name = jdbc.queryForList("""
                SELECT t.name FROM schedule_template t JOIN project p ON p.company_id = t.company_id
                WHERE t.id = ? AND p.id = ?""", String.class, req.templateId(), pid);
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Map<String, LocalDate> anchors = anchorDates(pid);
        jdbc.query("""
                SELECT d.id AS discipline_id, ti.kind, ti.title, ti.anchor, ti.offset_days, ti.offset_months, ti.duration_days
                FROM schedule_template_item ti
                LEFT JOIN discipline d ON d.code = ti.discipline_code
                     AND d.company_id = (SELECT company_id FROM project WHERE id = ?)
                WHERE ti.template_id = ? ORDER BY ti.sort_order, ti.id""", rs -> {
            String kind = rs.getString("kind");
            Integer od = (Integer) rs.getObject("offset_days");
            Integer om = (Integer) rs.getObject("offset_months");
            Integer dur = (Integer) rs.getObject("duration_days");
            ScheduleDates.Range r = ScheduleDates.resolve(anchors.get(rs.getString("anchor")), od, om, dur,
                    "MILESTONE".equals(kind));
            Plan plan = new Plan(r == null ? null : r.start(), r == null ? null : r.end(), rs.getString("anchor"), od, om, dur);
            long id = insert(pid, (Long) rs.getObject("discipline_id"), null, kind, rs.getString("title"), plan, null, null, 0, null);
            Map<String, Object> data = created(rs.getString("title"), plan);
            data.put("template", name.get(0));
            event(id, userId(jwt), "CREATED", data);
            remind(pid, find(pid, id));
        }, pid, req.templateId());
        return view(pid);
    }

    record PasteRequest(@NotBlank @Size(max = 100_000) String text, boolean confirm) {
    }

    record PasteResult(List<SchedulePaste.Row> rows, long errors, int created) {
    }

    /** Excel block → preview with per-row errors; confirm=true adds them all as fixed items, or nothing if any row is bad. */
    @PostMapping("/api/projects/{pid}/schedule/paste")
    @Transactional
    PasteResult paste(@AuthenticationPrincipal Jwt jwt, @PathVariable long pid, @Valid @RequestBody PasteRequest req) {
        access.member(pid, jwt);
        Map<String, Long> disciplines = new HashMap<>();
        jdbc.query("""
                SELECT d.name, d.id FROM discipline d JOIN project p ON p.company_id = d.company_id WHERE p.id = ?""",
                rs -> { disciplines.put(rs.getString(1), rs.getLong(2)); }, pid);
        Map<String, Long> members = new HashMap<>();
        jdbc.query("""
                SELECT u.name, u.id FROM project_member m JOIN users u ON u.id = m.user_id
                WHERE m.project_id = ? ORDER BY u.id""", rs -> { members.putIfAbsent(rs.getString(1), rs.getLong(2)); }, pid);
        List<SchedulePaste.Row> rows = SchedulePaste.parse(req.text(), disciplines, members);
        long errors = rows.stream().filter(r -> r.error() != null).count();
        if (!req.confirm()) {
            return new PasteResult(rows, errors, 0);
        }
        if (rows.isEmpty() || errors > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    rows.isEmpty() ? "붙여 넣은 행이 없습니다." : "오류가 있는 행 %d개를 고친 뒤 확정하세요.".formatted(errors));
        }
        for (SchedulePaste.Row r : rows) {
            Plan plan = new Plan(r.start(), r.end(), null, null, null, null);
            long id = insert(pid, r.disciplineId(), null, r.kind(), r.title(), plan, r.ownerUserId(), null, 0, r.weight());
            Map<String, Object> data = created(r.title(), plan);
            data.put("source", "paste");
            event(id, userId(jwt), "CREATED", data);
            remind(pid, find(pid, id));
        }
        return new PasteResult(rows, 0, rows.size());
    }

    // --- rules ---------------------------------------------------------------------------------------------

    private void validate(long pid, Long id, ItemRequest req) {
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

    /** The request's dates: fixed, relative (computed), or linked back from its dates (see {@link ItemRequest}). */
    private Plan plan(long pid, ItemRequest req) {
        boolean ms = req.milestone();
        boolean relative = req.offsetDays() != null || req.offsetMonths() != null;
        if (req.anchor() != null && relative) {
            if (req.offsetDays() != null && req.offsetMonths() != null) {
                throw badRequest("오프셋은 일 또는 개월 중 하나만 지정합니다.");
            }
            if (!ms && req.durationDays() == null) {
                throw badRequest("기준일에 연결된 작업은 기간(일)이 필요합니다.");
            }
            ScheduleDates.Range r = ScheduleDates.resolve(anchorDates(pid).get(req.anchor()), req.offsetDays(),
                    req.offsetMonths(), req.durationDays(), ms);
            return new Plan(r == null ? null : r.start(), r == null ? null : r.end(), req.anchor(), req.offsetDays(),
                    req.offsetMonths(), ms ? null : req.durationDays());
        }
        if (req.endDate() == null || req.start() == null) {
            throw badRequest(req.endDate() == null ? "종료일이 필요합니다." : "작업은 시작일이 필요합니다.");
        }
        if (req.start().isAfter(req.endDate())) {
            throw badRequest("시작일이 종료일보다 늦습니다.");
        }
        if (req.anchor() == null) {
            return new Plan(req.start(), req.endDate(), null, null, null, null);
        }
        LocalDate base = anchorDates(pid).get(req.anchor());
        if (base == null) {
            throw badRequest(ANCHOR_LABEL.get(req.anchor()) + "이 정해지지 않아 연결할 수 없습니다.");
        }
        ScheduleDates.Offset o = ScheduleDates.reverse(base, req.start(), req.endDate(), ms);
        return new Plan(req.start(), req.endDate(), req.anchor(), o.offsetDays(), null, o.durationDays());
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

    /** D-7·D-1 and 기한 경과 to the owner (or every member) while open and dated; nothing once done. */
    private void remind(long pid, ItemView v) {
        deadlines.register(KIND, v.id(), pid, v.title(), v.actualEnd() == null ? v.endDate() : null, v.ownerUserId());
    }

    // --- reads ---------------------------------------------------------------------------------------------

    private ScheduleView view(long pid) {
        LocalDate today = LocalDate.now(clock);
        Map<String, Object> p = jdbc.queryForMap("SELECT schedule_base, schedule_baseline_no FROM project WHERE id = ?", pid);
        Map<String, String> notes = new HashMap<>();
        jdbc.query("SELECT anchor, note FROM project_schedule_anchor WHERE project_id = ?",
                rs -> { notes.put(rs.getString(1), rs.getString(2)); }, pid);
        Map<String, LocalDate> dates = anchorDates(pid);
        List<AnchorView> anchors = ANCHORS.stream().map(a -> new AnchorView(a, dates.get(a), notes.get(a))).toList();
        List<ItemView> items = items(pid);
        List<Dependency> deps = dependencies(pid);
        ScheduleDates.Progress progress = ScheduleDates.progress(items.stream().map(i -> new ScheduleDates.Weighted(
                i.weight(), i.baselineEnd() != null ? i.baselineStart() : i.startDate(),
                i.baselineEnd() != null ? i.baselineEnd() : i.endDate(), i.progress())).toList(), today);
        return new ScheduleView(today, (String) p.get("schedule_base"), (Integer) p.get("schedule_baseline_no"), anchors,
                items, deps, progress, delays(items, deps).size());
    }

    /** All five base dates; missing ones are absent. */
    Map<String, LocalDate> anchorDates(long pid) {
        Map<String, LocalDate> m = new HashMap<>();
        jdbc.query("SELECT start_date, expected_end_date FROM project WHERE id = ?", rs -> {
            m.put("KICKOFF", rs.getObject(1, LocalDate.class));
            m.put("COMPLETION", rs.getObject(2, LocalDate.class));
        }, pid);
        jdbc.query("SELECT anchor, date FROM project_schedule_anchor WHERE project_id = ?",
                rs -> { m.put(rs.getString(1), rs.getObject(2, LocalDate.class)); }, pid);
        return m;
    }

    private List<ItemView> items(long pid) {
        LocalDate today = LocalDate.now(clock);
        return jdbc.query(ITEMS + " AND i.project_id = ? ORDER BY i.start_date NULLS LAST, i.id", (rs, n) -> item(rs, today), pid);
    }

    private List<Dependency> dependencies(long pid) {
        return jdbc.query("""
                SELECT dep.predecessor_id, dep.successor_id FROM schedule_item_dependency dep
                JOIN schedule_item s ON s.id = dep.successor_id WHERE s.project_id = ?
                ORDER BY dep.predecessor_id, dep.successor_id""",
                (rs, n) -> new Dependency(rs.getLong(1), rs.getLong(2)), pid);
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
        LocalDate baselineEnd = rs.getObject("baseline_end", LocalDate.class);
        LocalDate actualEnd = rs.getObject("actual_end", LocalDate.class);
        ScheduleStatus.Computed c = ScheduleStatus.of(start, end, actualEnd, today);
        return new ItemView(rs.getLong("id"), (Long) rs.getObject("discipline_id"), rs.getString("discipline_name"),
                (Long) rs.getObject("parent_id"), rs.getString("kind"), rs.getString("title"), start, end,
                rs.getObject("baseline_start", LocalDate.class), baselineEnd, actualEnd, rs.getString("anchor"),
                (Integer) rs.getObject("offset_days"), (Integer) rs.getObject("offset_months"),
                (Integer) rs.getObject("duration_days"), (Long) rs.getObject("owner_user_id"), rs.getString("owner_name"),
                rs.getString("partner_name"), rs.getBigDecimal("weight"), rs.getInt("progress"),
                rs.getString("delay_reason"), rs.getLong("version"), c.status(), c.delayDays(),
                ScheduleDates.slipDays(end, baselineEnd));
    }

    // --- writes --------------------------------------------------------------------------------------------

    private long insert(long pid, Long disciplineId, Long parentId, String kind, String title, Plan plan, Long owner,
                        String partner, int progress, BigDecimal weight) {
        return jdbc.queryForObject("""
                INSERT INTO schedule_item (project_id, discipline_id, parent_id, kind, title, start_date, end_date, anchor,
                                           offset_days, offset_months, duration_days, owner_user_id, partner_name,
                                           progress, weight)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                pid, disciplineId, parentId, kind, title, sqlDate(plan.start()), sqlDate(plan.end()), plan.anchor(),
                plan.offsetDays(), plan.offsetMonths(), plan.durationDays(), owner, partner, progress, weight);
    }

    private void datesChanged(long id, Long actor, LocalDate fromStart, LocalDate fromEnd, LocalDate toStart,
                              LocalDate toEnd, String reason) {
        event(id, actor, "DATES_CHANGED", nullable("from", dates(fromStart, fromEnd), "to", dates(toStart, toEnd),
                "reason", reason));
    }

    private void event(long itemId, Long actorId, String type, Map<String, ?> data) {
        try {
            jdbc.update("INSERT INTO schedule_item_event (item_id, actor_id, at, type, data) VALUES (?, ?, ?, ?, ?::jsonb)",
                    itemId, actorId, Timestamp.from(clock.instant()), type, json.writeValueAsString(data));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> created(String title, Plan plan) {
        return nullable("title", title, "start", str(plan.start()), "end", str(plan.end()), "anchor", plan.anchor(),
                "offsetDays", plan.offsetDays(), "offsetMonths", plan.offsetMonths());
    }

    private static Map<String, Object> dates(LocalDate start, LocalDate end) {
        return nullable("start", str(start), "end", str(end));
    }

    /** Map.of rejects null values. */
    private static Map<String, Object> nullable(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static String str(LocalDate d) {
        return d == null ? null : d.toString();
    }

    private static String shortDate(LocalDate d) {
        return d == null ? "미정" : d.getMonthValue() + "/" + d.getDayOfMonth();
    }

    private static Date sqlDate(LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException unprocessable(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
