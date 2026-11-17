package com.envpermit.core.project;

import static com.envpermit.core.project.ProjectAccess.userId;

import com.envpermit.core.permit.PermitController;
import com.envpermit.core.permit.PermitController.PermitView;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Everything the dashboard needs about my projects in one call (was 1 + 3 calls per project).
 * Same project and permit shapes as GET /api/projects and /api/projects/{id}/permits.
 */
@RestController
class DashboardController {

    private final ProjectRepository projects;
    private final ProjectController projectViews;
    private final PermitController permitViews;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    DashboardController(ProjectRepository projects, ProjectController projectViews, PermitController permitViews,
                        JdbcTemplate jdbc, Clock clock) {
        this.projects = projects;
        this.projectViews = projectViews;
        this.permitViews = permitViews;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    record DelayedCount(String discipline, long count) {
    }

    /** reviewOpen: AI obligation and revision suggestions awaiting review. delayed: open schedule items past their end. */
    record Dashboard(List<ProjectController.ProjectView> projects, List<PermitView> permits, long reviewOpen,
                     List<DelayedCount> delayedByDiscipline) {
    }

    @GetMapping("/api/dashboard")
    Dashboard dashboard(@AuthenticationPrincipal Jwt jwt) {
        List<Project> mine = projects.findByMember(userId(jwt)); // member of all of them, so no further access check
        List<Long> ids = mine.stream().map(Project::getId).toList();
        Long[] idArray = ids.toArray(Long[]::new);
        long reviewOpen = jdbc.queryForObject("""
                SELECT count(*) FROM ai_suggestion WHERE project_id = ANY(?)
                AND kind IN ('OBLIGATION', 'OBLIGATION_REVISION') AND status IN ('PENDING', 'SUBMITTED')""",
                Long.class, (Object) idArray);
        // same rule as ScheduleStatus DELAYED: planned end passed, not finished
        List<DelayedCount> delayed = jdbc.query("""
                SELECT COALESCE(d.name, '공통') AS discipline, count(*) AS n FROM schedule_item i
                LEFT JOIN discipline d ON d.id = i.discipline_id
                WHERE i.project_id = ANY(?) AND i.deleted_at IS NULL AND i.actual_end IS NULL AND i.end_date < ?
                GROUP BY 1 ORDER BY 2 DESC, 1""",
                (rs, n) -> new DelayedCount(rs.getString(1), rs.getLong(2)), idArray, LocalDate.now(clock));
        return new Dashboard(projectViews.views(mine, userId(jwt)), permitViews.viewsOf(ids), reviewOpen, delayed);
    }
}
