package com.envpermit.schedule;

import static com.envpermit.core.project.ProjectAccess.userId;

import com.envpermit.core.project.ProjectAccess;
import com.envpermit.notification.Deadlines;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.format.annotation.DateTimeFormat;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 프로젝트 이벤트·출장 (설계 §3) and the calendars that show them (§6.1 달력, §6.2 회사 달력).
 * Project routes are members only. One {@code deadline_event} per event (kind PROJECT_EVENT, due = start day);
 * {@code NotificationJob} picks the recipients (attendees, + PM) and offsets (D-3·D-1, 회의 D-1).
 * Reads never touch {@code ai_suggestion}.
 */
@RestController
class EventController {

    static final String KINDS = "SITE_BRIEFING|QNA_DEADLINE|SUBMISSION|CLIENT_REPORT|REVIEW|MEETING|TRIP|OTHER";
    /** Kinds that are a deadline in themselves: "공정표에도 마일스톤 추가" is on by default (설계 §3 연결). */
    private static final Set<String> MILESTONE_BY_DEFAULT = Set.of("SUBMISSION", "CLIENT_REPORT");
    /** 설계 §3: 출장·회의·협의 need 상대 기관. */
    private static final Set<String> NEEDS_COUNTERPART = Set.of("TRIP", "MEETING");

    private static final String EVENTS = """
            SELECT e.id, e.project_id, p.name AS project_name, e.kind, e.subkind, e.title, e.starts_at, e.ends_at,
                   e.all_day, e.location, e.counterpart_org, e.purpose, e.discipline_id, d.name AS discipline_name,
                   e.checklist, e.version
            FROM project_event e
            JOIN project p ON p.id = e.project_id
            LEFT JOIN discipline d ON d.id = e.discipline_id
            """;

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final Deadlines deadlines;
    private final ScheduleController schedule;

    EventController(JdbcTemplate jdbc, ProjectAccess access, Deadlines deadlines, ScheduleController schedule) {
        this.jdbc = jdbc;
        this.access = access;
        this.deadlines = deadlines;
        this.schedule = schedule;
    }

    record Attendee(Long userId, String name, String externalName, String role) {
    }

    record EventView(long id, long projectId, String projectName, String kind, String subkind, String title,
                     LocalDateTime startsAt, LocalDateTime endsAt, boolean allDay, String location,
                     String counterpartOrg, String purpose, Long disciplineId, String disciplineName, String checklist,
                     long version, List<Attendee> attendees) {
    }

    /** An app user (userId) or an outside person (externalName), never both. */
    record AttendeeRequest(Long userId, @Size(max = 100) String externalName, @Size(max = 50) String role) {
    }

    /**
     * Create, or whole replacement on PATCH (attendees replaced too). All-day: times are dropped and endsAt is the
     * last day. addMilestone (create only; default on for 제출·보고회): a MILESTONE on the start day in the schedule.
     * version: optimistic lock on PATCH (stale → 409); omitted = no check.
     */
    record EventRequest(@NotNull @Pattern(regexp = KINDS) String kind, @Size(max = 50) String subkind,
                        @NotBlank @Size(max = 200) String title, @NotNull LocalDateTime startsAt,
                        @NotNull LocalDateTime endsAt, boolean allDay, @Size(max = 200) String location,
                        @Size(max = 200) String counterpartOrg, @Size(max = 500) String purpose, Long disciplineId,
                        @Size(max = 4000) String checklist, List<@Valid AttendeeRequest> attendees,
                        Boolean addMilestone, Long version) {

        LocalDateTime start() {
            return allDay ? startsAt.truncatedTo(ChronoUnit.DAYS) : startsAt;
        }

        LocalDateTime end() {
            return allDay ? endsAt.truncatedTo(ChronoUnit.DAYS) : endsAt;
        }
    }

    record MilestoneView(long id, long projectId, String projectName, String title, LocalDate date,
                         String disciplineName, boolean done) {
    }

    record DeadlineView(long id, String kind, long projectId, String projectName, String title, LocalDate dueDate) {
    }

    record CalendarView(List<EventView> events, List<MilestoneView> milestones, List<DeadlineView> deadlines) {
    }

    // --- project events -------------------------------------------------------------------------------------

    @GetMapping("/api/projects/{pid}/events")
    List<EventView> list(@AuthenticationPrincipal Jwt jwt, @PathVariable long pid) {
        access.member(pid, jwt);
        return withAttendees(jdbc.query(EVENTS + " WHERE e.project_id = ? ORDER BY e.starts_at, e.id",
                EventController::event, pid));
    }

    @PostMapping("/api/projects/{pid}/events")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    EventView create(@AuthenticationPrincipal Jwt jwt, @PathVariable long pid, @Valid @RequestBody EventRequest req) {
        access.member(pid, jwt);
        return find(pid, create(pid, userId(jwt), req));
    }

    /** The route's rules for a caller that already checked membership (the document → schedule applier). */
    long create(long pid, Long actor, EventRequest req) {
        validate(pid, req);
        long id = jdbc.queryForObject("""
                INSERT INTO project_event (project_id, kind, subkind, title, starts_at, ends_at, all_day, location,
                                           counterpart_org, purpose, discipline_id, checklist)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                pid, req.kind(), blankToNull(req.subkind()), req.title().trim(), Timestamp.valueOf(req.start()),
                Timestamp.valueOf(req.end()), req.allDay(), blankToNull(req.location()),
                blankToNull(req.counterpartOrg()), blankToNull(req.purpose()), req.disciplineId(),
                blankToNull(req.checklist()));
        setAttendees(id, req.attendees());
        if (req.addMilestone() != null ? req.addMilestone() : MILESTONE_BY_DEFAULT.contains(req.kind())) {
            schedule.create(pid, actor, ScheduleController.ItemRequest.milestone(
                    req.disciplineId(), req.title().trim(), req.start().toLocalDate(),
                    MILESTONE_BY_DEFAULT.contains(req.kind())));
        }
        remind(find(pid, id));
        return id;
    }

    @PatchMapping("/api/projects/{pid}/events/{id}")
    @Transactional
    EventView update(@AuthenticationPrincipal Jwt jwt, @PathVariable long pid, @PathVariable long id,
                     @Valid @RequestBody EventRequest req) {
        access.member(pid, jwt);
        update(pid, id, req);
        return find(pid, id);
    }

    void update(long pid, long id, EventRequest req) {
        lock(pid, id, req.version());
        validate(pid, req);
        jdbc.update("""
                UPDATE project_event SET kind = ?, subkind = ?, title = ?, starts_at = ?, ends_at = ?, all_day = ?,
                       location = ?, counterpart_org = ?, purpose = ?, discipline_id = ?, checklist = ?,
                       version = version + 1
                WHERE id = ?""",
                req.kind(), blankToNull(req.subkind()), req.title().trim(), Timestamp.valueOf(req.start()),
                Timestamp.valueOf(req.end()), req.allDay(), blankToNull(req.location()),
                blankToNull(req.counterpartOrg()), blankToNull(req.purpose()), req.disciplineId(),
                blankToNull(req.checklist()), id);
        setAttendees(id, req.attendees());
        remind(find(pid, id)); // a moved date drops the old date's unsent reminders (Deadlines.register)
    }

    /** A live event as a full request (PATCH replaces everything, attendees included); 404 when gone or elsewhere. */
    EventRequest requestOf(long pid, long id) {
        EventView e = withAttendees(jdbc.query(EVENTS + " WHERE e.project_id = ? AND e.id = ?", EventController::event,
                pid, id)).stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return new EventRequest(e.kind(), e.subkind(), e.title(), e.startsAt(), e.endsAt(), e.allDay(), e.location(),
                e.counterpartOrg(), e.purpose(), e.disciplineId(), e.checklist(), e.attendees().stream()
                .map(a -> new AttendeeRequest(a.userId(), a.externalName(), a.role())).toList(), false, null);
    }

    /** Approved document row (design-schedule/07): trace only, never read by queries. */
    void applied(long id, long suggestionId) {
        jdbc.update("UPDATE project_event SET source_suggestion_id = ? WHERE id = ?", suggestionId, id);
    }

    /** Hard delete; its reminder (and that reminder's notifications) go with it via the V330 trigger. */
    @DeleteMapping("/api/projects/{pid}/events/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long pid, @PathVariable long id) {
        access.member(pid, jwt);
        lock(pid, id, null);
        jdbc.update("DELETE FROM project_event WHERE id = ?", id);
    }

    // --- calendars ------------------------------------------------------------------------------------------

    /**
     * 회사 달력 and the project 달력 (project=). Scope: a company ADMIN sees every project of the company, anyone else
     * the projects they are a member of. Events overlapping [from, to]; milestones and 인허가 등 기한 due in it.
     * kind (e.g. TRIP for "출장만") narrows to events of that kind, so milestones and deadlines are left out.
     */
    @GetMapping("/api/calendar")
    CalendarView calendar(@AuthenticationPrincipal Jwt jwt,
                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                          @RequestParam(required = false) String kind,
                          @RequestParam(required = false) Long dept, @RequestParam(required = false) Long team,
                          @RequestParam(required = false) Long project) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 366) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "기간은 1년 이내로 지정해 주세요.");
        }
        if (kind != null && !kind.matches(KINDS)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "알 수 없는 이벤트 종류입니다.");
        }
        List<Long> ids = visibleProjects(userId(jwt), dept, team, project);
        if (ids.isEmpty()) {
            return new CalendarView(List.of(), List.of(), List.of());
        }
        String in = ids.stream().map(String::valueOf).collect(Collectors.joining(",")); // longs only, no injection
        List<Object> args = new ArrayList<>(List.of(Timestamp.valueOf(to.plusDays(1).atStartOfDay()),
                Timestamp.valueOf(from.atStartOfDay())));
        if (kind != null) args.add(kind);
        List<EventView> events = withAttendees(jdbc.query(EVENTS + " WHERE e.project_id IN (" + in + ")"
                        + " AND e.starts_at < ? AND e.ends_at >= ?" + (kind != null ? " AND e.kind = ?" : "")
                        + " ORDER BY e.starts_at, e.id",
                EventController::event, args.toArray()));
        if (kind != null) {
            return new CalendarView(events, List.of(), List.of());
        }
        List<MilestoneView> milestones = jdbc.query("""
                SELECT i.id, i.project_id, p.name, i.title, i.end_date, d.name, i.actual_end IS NOT NULL
                FROM schedule_item i JOIN project p ON p.id = i.project_id
                LEFT JOIN discipline d ON d.id = i.discipline_id
                WHERE i.project_id IN (%s) AND i.kind = 'MILESTONE' AND i.deleted_at IS NULL
                  AND i.end_date BETWEEN ? AND ?
                ORDER BY i.end_date, i.id""".formatted(in),
                (rs, n) -> new MilestoneView(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5, LocalDate.class), rs.getString(6), rs.getBoolean(7)), from, to);
        // schedule items and events have their own layers; the rest (인허가 만료·의무사항·법령 시행 …) is 기한
        List<DeadlineView> due = jdbc.query("""
                SELECT e.id, e.kind, e.project_id, p.name, e.title, e.due_date
                FROM deadline_event e JOIN project p ON p.id = e.project_id
                WHERE e.project_id IN (%s) AND e.kind NOT IN ('PROJECT_EVENT', 'SCHEDULE_ITEM')
                  AND e.due_date BETWEEN ? AND ?
                ORDER BY e.due_date, e.id""".formatted(in),
                (rs, n) -> new DeadlineView(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                        rs.getString(5), rs.getObject(6, LocalDate.class)), from, to);
        return new CalendarView(events, milestones, due);
    }

    private List<Long> visibleProjects(long userId, Long dept, Long team, Long project) {
        StringBuilder sql = new StringBuilder("""
                SELECT p.id FROM project p JOIN users u ON u.id = ? AND u.company_id = p.company_id
                WHERE (u.role = 'ADMIN' OR EXISTS (SELECT 1 FROM project_member m
                                                   WHERE m.project_id = p.id AND m.user_id = u.id))""");
        List<Object> args = new ArrayList<>(List.of(userId));
        if (project != null) {
            sql.append(" AND p.id = ?");
            args.add(project);
        }
        if (team != null) {
            sql.append(" AND p.team_id = ?");
            args.add(team);
        }
        if (dept != null) {
            sql.append(" AND p.team_id IN (SELECT id FROM team WHERE department_id = ?)");
            args.add(dept);
        }
        return jdbc.queryForList(sql + " ORDER BY p.id", Long.class, args.toArray());
    }

    // --- rules ----------------------------------------------------------------------------------------------

    private void validate(long pid, EventRequest req) {
        if (req.start().isAfter(req.end())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "시작이 종료보다 늦습니다.");
        }
        if (NEEDS_COUNTERPART.contains(req.kind()) && blankToNull(req.counterpartOrg()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "출장·회의는 상대 기관을 입력해야 합니다.");
        }
        if (req.disciplineId() != null && jdbc.queryForObject("""
                SELECT count(*) FROM discipline d JOIN project p ON p.company_id = d.company_id
                WHERE d.id = ? AND p.id = ?""", Integer.class, req.disciplineId(), pid) == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "회사의 분야만 지정할 수 있습니다.");
        }
        Set<Long> seen = new HashSet<>();
        for (AttendeeRequest a : req.attendees() == null ? List.<AttendeeRequest>of() : req.attendees()) {
            if ((a.userId() == null) == (blankToNull(a.externalName()) == null)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "참석자는 사용자 또는 외부 이름 중 하나만 지정합니다.");
            }
            if (a.userId() != null && !seen.add(a.userId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "같은 참석자가 두 번 있습니다.");
            }
            if (a.userId() != null && jdbc.queryForObject(
                    "SELECT count(*) FROM project_member WHERE project_id = ? AND user_id = ?",
                    Integer.class, pid, a.userId()) == 0) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "참석자는 프로젝트 멤버여야 합니다.");
            }
        }
    }

    private void setAttendees(long eventId, List<AttendeeRequest> attendees) {
        jdbc.update("DELETE FROM project_event_attendee WHERE event_id = ?", eventId);
        if (attendees == null) return;
        for (AttendeeRequest a : attendees) {
            jdbc.update("INSERT INTO project_event_attendee (event_id, user_id, external_name, role) VALUES (?, ?, ?, ?)",
                    eventId, a.userId(), a.userId() == null ? a.externalName().trim() : null, blankToNull(a.role()));
        }
    }

    private void remind(EventView v) {
        deadlines.register(Deadlines.PROJECT_EVENT, v.id(), v.projectId(), v.title(), v.startsAt().toLocalDate(), null);
    }

    private void lock(long pid, long id, Long version) {
        List<Long> current = jdbc.queryForList(
                "SELECT version FROM project_event WHERE id = ? AND project_id = ? FOR UPDATE", Long.class, id, pid);
        if (current.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (version != null && !version.equals(current.get(0))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "다른 사용자가 먼저 수정했습니다. 새로고침 후 다시 시도하세요.");
        }
    }

    private EventView find(long pid, long id) {
        return withAttendees(jdbc.query(EVENTS + " WHERE e.project_id = ? AND e.id = ?",
                EventController::event, pid, id)).get(0);
    }

    private List<EventView> withAttendees(List<EventView> events) {
        if (events.isEmpty()) return events;
        String in = events.stream().map(e -> String.valueOf(e.id())).collect(Collectors.joining(","));
        Map<Long, List<Attendee>> byEvent = jdbc.query("""
                SELECT a.event_id, a.user_id, u.name, a.external_name, a.role FROM project_event_attendee a
                LEFT JOIN users u ON u.id = a.user_id WHERE a.event_id IN (%s) ORDER BY a.id""".formatted(in),
                (rs, n) -> Map.entry(rs.getLong(1), new Attendee((Long) rs.getObject(2), rs.getString(3),
                        rs.getString(4), rs.getString(5))))
                .stream().collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        return events.stream().map(e -> new EventView(e.id(), e.projectId(), e.projectName(), e.kind(), e.subkind(),
                e.title(), e.startsAt(), e.endsAt(), e.allDay(), e.location(), e.counterpartOrg(), e.purpose(),
                e.disciplineId(), e.disciplineName(), e.checklist(), e.version(),
                byEvent.getOrDefault(e.id(), List.of()))).toList();
    }

    private static EventView event(ResultSet rs, int n) throws SQLException {
        return new EventView(rs.getLong("id"), rs.getLong("project_id"), rs.getString("project_name"),
                rs.getString("kind"), rs.getString("subkind"), rs.getString("title"),
                rs.getObject("starts_at", LocalDateTime.class), rs.getObject("ends_at", LocalDateTime.class),
                rs.getBoolean("all_day"), rs.getString("location"), rs.getString("counterpart_org"),
                rs.getString("purpose"), (Long) rs.getObject("discipline_id"), rs.getString("discipline_name"),
                rs.getString("checklist"), rs.getLong("version"), List.of());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
