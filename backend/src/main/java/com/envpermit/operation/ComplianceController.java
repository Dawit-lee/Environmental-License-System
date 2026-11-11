package com.envpermit.operation;

import static com.envpermit.core.project.ProjectAccess.userId;

import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import com.envpermit.operation.ComplianceRate.Counts;
import com.envpermit.operation.ComplianceRate.Task;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 규제 대응 현황 (design §5, F4): read-only numbers computed on every request, nothing stored.
 * Scope: a company ADMIN sees every project of the company, anyone else the projects they are a member of.
 * Reads obligation_task, measurement, permit_item and revision_implementation only (never ai_suggestion).
 */
@RestController
class ComplianceController {

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final Clock clock;

    ComplianceController(JdbcTemplate jdbc, UserRepository users, Clock clock) {
        this.jdbc = jdbc;
        this.users = users;
        this.clock = clock;
    }

    /** tasks: the rate and the other task counts; the rest are counted next to it, never mixed into the rate. */
    record Metrics(Counts tasks, int exceeded, int near, int nearExpiry, int siteOpen) {
    }

    record TeamView(long id, String name) {
    }

    record ProjectRow(long projectId, String name, Long teamId, String teamName, Metrics metrics) {
    }

    /** scope: COMPANY (admin) or MINE; projectCount and teams describe the whole scope, before the team filter. */
    record Summary(LocalDate from, LocalDate to, String scope, int projectCount, List<TeamView> teams,
                   Metrics total, List<ProjectRow> projects) {
    }

    record Month(String month, int due, int onTime, Double percent) {
    }

    record PermitRow(Long permitItemId, String name, int due, int onTime, int late, Double percent) {
    }

    record ProjectSummary(LocalDate from, LocalDate to, Metrics total, List<PermitRow> permits) {
    }

    @GetMapping("/api/compliance/summary")
    Summary summary(@AuthenticationPrincipal Jwt jwt,
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                    @RequestParam(required = false) Long teamId) {
        Period p = period(from, to);
        Scope scope = scope(jwt);
        List<Scoped> shown = scope.filter(teamId);
        Long[] ids = ids(shown);
        Map<Long, List<TaskRow>> tasks = new HashMap<>();
        List<TaskRow> all = tasks(ids);
        all.forEach(t -> tasks.computeIfAbsent(t.projectId(), k -> new ArrayList<>()).add(t));
        Map<Long, int[]> other = other(ids, p);
        List<ProjectRow> rows = shown.stream().map(s -> new ProjectRow(s.id(), s.name(), s.teamId(), s.teamName(),
                metrics(p.counts(tasks.getOrDefault(s.id(), List.of())), other.get(s.id())))).toList();
        int[] sum = new int[4];
        other.values().forEach(o -> {
            for (int i = 0; i < 4; i++) {
                sum[i] += o[i];
            }
        });
        List<TeamView> teams = scope.projects().stream().filter(s -> s.teamId() != null)
                .map(s -> new TeamView(s.teamId(), s.teamName())).distinct().toList();
        // total: every task counted once, then one ratio (not the average of the project rates)
        return new Summary(p.from(), p.to(), scope.admin() ? "COMPANY" : "MINE", scope.projects().size(), teams,
                metrics(p.counts(all), sum), rows);
    }

    /** The same formula per calendar month, cut to the period; a month in which nothing fell due has no percent. */
    @GetMapping("/api/compliance/trend")
    List<Month> trend(@AuthenticationPrincipal Jwt jwt,
                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                      @RequestParam(required = false) Long teamId) {
        Period p = period(from, to);
        List<Task> all = tasks(ids(scope(jwt).filter(teamId))).stream().map(TaskRow::task).toList();
        LocalDate end = p.to().isAfter(p.today()) ? p.today() : p.to();
        List<Month> months = new ArrayList<>();
        for (YearMonth m = YearMonth.from(p.from()); !m.atDay(1).isAfter(end); m = m.plusMonths(1)) {
            LocalDate first = m.atDay(1).isBefore(p.from()) ? p.from() : m.atDay(1);
            Counts c = ComplianceRate.of(all, first, m.atEndOfMonth().isAfter(end) ? end : m.atEndOfMonth(), p.today());
            months.add(new Month(m.toString(), c.due(), c.onTime(), c.percent()));
        }
        return months;
    }

    @GetMapping("/api/projects/{projectId}/compliance/summary")
    ProjectSummary project(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (scope(jwt).projects().stream().noneMatch(s -> s.id() == projectId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        Period p = period(from, to);
        Long[] ids = {projectId};
        List<TaskRow> all = tasks(ids);
        // every permit item that has a task, in id order; tasks of a point without a permit item come last
        Map<Long, String> names = new HashMap<>();
        jdbc.query("SELECT id, name FROM permit_item WHERE project_id = ?",
                rs -> { names.put(rs.getLong(1), rs.getString(2)); }, projectId);
        List<PermitRow> permits = all.stream().map(TaskRow::permitItemId).distinct()
                .sorted(java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                .map(permit -> {
                    Counts c = p.counts(all.stream().filter(t -> Objects.equals(t.permitItemId(), permit)).toList());
                    return new PermitRow(permit, permit == null ? null : names.get(permit), c.due(), c.onTime(), c.late(), c.percent());
                }).filter(r -> r.due() > 0).toList();
        return new ProjectSummary(p.from(), p.to(), metrics(p.counts(all), other(ids, p).get(projectId)), permits);
    }

    // --- scope and period ---

    private record Scoped(long id, String name, Long teamId, String teamName) {
    }

    private record Scope(boolean admin, List<Scoped> projects) {
        List<Scoped> filter(Long teamId) {
            return teamId == null ? projects : projects.stream().filter(s -> teamId.equals(s.teamId())).toList();
        }
    }

    private Scope scope(Jwt jwt) {
        User user = users.findById(userId(jwt)).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        boolean admin = user.getRole() == User.Role.ADMIN && user.getCompanyId() != null;
        return new Scope(admin, jdbc.query("""
                SELECT p.id, p.name, p.team_id, d.name || ' / ' || t.name AS team_name
                FROM project p LEFT JOIN team t ON t.id = p.team_id LEFT JOIN department d ON d.id = t.department_id
                WHERE %s ORDER BY p.id""".formatted(admin ? "p.company_id = ?"
                        : "p.id IN (SELECT project_id FROM project_member WHERE user_id = ?)"),
                (rs, i) -> new Scoped(rs.getLong(1), rs.getString(2), (Long) rs.getObject(3), rs.getString(4)),
                admin ? user.getCompanyId() : user.getId()));
    }

    private static Long[] ids(List<Scoped> projects) {
        return projects.stream().map(Scoped::id).toArray(Long[]::new);
    }

    private record Period(LocalDate from, LocalDate to, LocalDate today) {
        Counts counts(List<TaskRow> rows) {
            return ComplianceRate.of(rows.stream().map(TaskRow::task).toList(), from, to, today);
        }
    }

    /** Default: January 1 of this year to today. */
    private Period period(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        Period p = new Period(from == null ? today.withDayOfYear(1) : from, to == null ? today : to, today);
        if (p.from().isAfter(p.to())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "기간의 시작일이 종료일보다 늦습니다.");
        }
        return p;
    }

    // --- queries ---

    private record TaskRow(long projectId, Long permitItemId, Task task) {
    }

    /** permit item of a task: its obligation's, else the emission point of the value that caused it (as TaskView). */
    private List<TaskRow> tasks(Long[] projectIds) {
        return jdbc.query("""
                SELECT t.project_id, COALESCE(o.permit_item_id, e.permit_item_id), t.kind, t.status, t.due_date, t.done_at
                FROM obligation_task t LEFT JOIN permit_obligation o ON o.id = t.obligation_id
                LEFT JOIN measurement m ON m.id = t.measurement_id
                LEFT JOIN emission_point e ON e.id = m.emission_point_id
                WHERE t.project_id = ANY(?)""", (rs, i) -> {
            Timestamp done = rs.getTimestamp(6);
            return new TaskRow(rs.getLong(1), (Long) rs.getObject(2), new Task(rs.getString(3), rs.getString(4),
                    rs.getDate(5).toLocalDate(), done == null ? null : done.toInstant().atZone(clock.getZone()).toLocalDate()));
        }, (Object) projectIds);
    }

    /** Per project: [측정 초과, 측정 근접 (measured in the period, not deleted), 허가 만료 임박, 현장 반영 대기]. */
    private Map<Long, int[]> other(Long[] projectIds, Period p) {
        Map<Long, int[]> out = new HashMap<>();
        jdbc.query("""
                SELECT e.project_id, CASE m.level WHEN 'EXCEEDED' THEN 0 ELSE 1 END, count(*)
                FROM measurement m JOIN emission_point e ON e.id = m.emission_point_id
                WHERE e.project_id = ANY(?) AND m.deleted_at IS NULL AND m.level IN ('EXCEEDED', 'NEAR')
                  AND m.measured_on BETWEEN ? AND ? GROUP BY 1, 2
                UNION ALL
                SELECT project_id, 2, count(*) FROM permit_item
                WHERE project_id = ANY(?) AND status = 'NEAR_EXPIRY' GROUP BY 1
                UNION ALL
                SELECT pi.project_id, 3, count(*) FROM revision_implementation r
                JOIN permit_obligation o ON o.id = r.obligation_id JOIN permit_item pi ON pi.id = o.permit_item_id
                WHERE pi.project_id = ANY(?) AND r.done_at IS NULL GROUP BY 1""",
                rs -> { out.computeIfAbsent(rs.getLong(1), k -> new int[4])[rs.getInt(2)] = rs.getInt(3); },
                projectIds, p.from(), p.to(), projectIds, projectIds);
        return out;
    }

    private static Metrics metrics(Counts tasks, int[] other) {
        int[] o = other == null ? new int[4] : other;
        return new Metrics(tasks, o[0], o[1], o[2], o[3]);
    }
}
