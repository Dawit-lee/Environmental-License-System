package com.envpermit.operation;

import com.envpermit.notification.Mailer;
import com.envpermit.notification.Notifier;
import com.envpermit.obligation.ObligationTasks;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * F2 §3.5, right after measured values are saved (same transaction): 초과 → every project member is told and a
 * 조치 업무 opens (or the open one for that point and item gets the value appended); 근접 → only the person who
 * entered it and the assignee of the limit's basis obligation are told. In-app notice per value, one mail per person.
 */
@Component
class ExceedanceAlerts {

    private static final Logger log = LoggerFactory.getLogger(ExceedanceAlerts.class);

    private final JdbcTemplate jdbc;
    private final ObligationTasks tasks;
    private final Notifier notifier;
    private final Mailer mailer;
    private final String appUrl;

    ExceedanceAlerts(JdbcTemplate jdbc, ObligationTasks tasks, Notifier notifier, Mailer mailer,
                     @Value("${app.base-url}") String appUrl) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.notifier = notifier;
        this.mailer = mailer;
        this.appUrl = appUrl;
    }

    void raise(long projectId, long pointId, List<Long> measurementIds, long actorId) {
        String link = "/projects/" + projectId + "/operations?point=" + pointId;
        List<Long> members = jdbc.queryForList("SELECT user_id FROM project_member WHERE project_id = ? ORDER BY user_id",
                Long.class, projectId);
        Map<Long, List<String>> lines = new LinkedHashMap<>(); // user → what to mail
        jdbc.query("""
                SELECT m.id, m.level, m.measured_on, m.value, m.unit, m.limit_lower, m.limit_value, pl.name AS pollutant, e.name AS point,
                       pr.name AS project, l.obligation_id
                FROM measurement m
                JOIN pollutant pl ON pl.code = m.pollutant
                JOIN emission_point e ON e.id = m.emission_point_id
                JOIN project pr ON pr.id = e.project_id
                LEFT JOIN permit_limit l ON l.id = m.limit_id
                WHERE m.level IN ('EXCEEDED', 'NEAR') AND m.id IN (%s) ORDER BY m.id"""
                .formatted(measurementIds.stream().map(String::valueOf).collect(Collectors.joining(","))), rs -> {
            boolean exceeded = rs.getString("level").equals("EXCEEDED");
            Long obligationId = rs.getObject("obligation_id", Long.class);
            BigDecimal value = rs.getBigDecimal("value");
            BigDecimal lower = rs.getBigDecimal("limit_lower"); // range limit (pH): which bound was crossed, or the range
            String limit = !exceeded && lower != null ? plain(lower) + "~" + plain(rs.getBigDecimal("limit_value"))
                    : plain(lower != null && value.compareTo(lower) < 0 ? lower : rs.getBigDecimal("limit_value"));
            String what = "%s %s %s %s %s %s".formatted(rs.getString("point"), rs.getString("pollutant"), plain(value),
                    !exceeded ? "/" : lower != null && value.compareTo(lower) < 0 ? "<" : ">", limit, rs.getString("unit"));
            Set<Long> to = new LinkedHashSet<>();
            if (exceeded) {
                tasks.corrective(projectId, rs.getLong("id"), obligationId, "[초과] " + what, actorId);
                to.addAll(members);
            } else {
                to.add(actorId);
                Long assignee = obligationId == null ? null : tasks.assigneeOf(obligationId);
                if (assignee != null && members.contains(assignee)) {
                    to.add(assignee);
                }
            }
            String message = "%s · %s · %s (%s)".formatted(exceeded ? "초과" : "근접", rs.getString("project"), what,
                    rs.getString("measured_on"));
            for (long userId : to) {
                notifier.notify(userId, message, link);
                lines.computeIfAbsent(userId, u -> new ArrayList<>()).add(message);
            }
        });
        lines.forEach((userId, list) -> {
            String to = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
            try { // a mail failure must not undo the saved values; the in-app notice is already there
                mailer.send(to, "[인허가 관리] 측정값 초과·근접 %d건".formatted(list.size()),
                        "허가 한도를 넘었거나 한도에 가까운 측정값이 입력되었습니다.\n\n- " + String.join("\n- ", list)
                                + "\n\n" + appUrl + link + "\n");
            } catch (RuntimeException e) {
                log.warn("exceedance mail to user {} failed", userId, e);
            }
        });
    }

    private static String plain(BigDecimal n) {
        return n.stripTrailingZeros().toPlainString();
    }
}
