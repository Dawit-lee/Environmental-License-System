package com.envpermit.law;

import com.envpermit.law.ImpactRule.Amended;
import com.envpermit.law.ImpactRule.Level;
import com.envpermit.law.ImpactRule.Link;
import com.envpermit.notification.Deadlines;
import com.envpermit.notification.Mailer;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * Amendment → permit_law_impact rows (+ 시행일 deadline), and the daily impact mail.
 * Recipients are the project's 책임자 (admin members): permit items have no assignee yet.
 */
@Component
public class LawImpacts {

    public static final String DEADLINE_KIND = "LAW_ENFORCEMENT";
    public static final String SOURCE = "출처: 법제처 국가법령정보센터";
    static final int MAIL_TOP = 10;
    private static final Logger log = LoggerFactory.getLogger(LawImpacts.class);

    private final JdbcTemplate jdbc;
    private final Deadlines deadlines;
    private final Mailer mailer;
    private final Clock clock;
    private final String appUrl;

    LawImpacts(JdbcTemplate jdbc, Deadlines deadlines, Mailer mailer, Clock clock,
               @Value("${app.base-url:http://localhost:5173}") String appUrl) {
        this.jdbc = jdbc;
        this.deadlines = deadlines;
        this.mailer = mailer;
        this.clock = clock;
        this.appUrl = appUrl;
    }

    private record LinkRow(long permitItemId, long projectId, String permit, String project, Link link) {
    }

    /** Runs inside the batch's day transaction, right after the amendment and its articles are stored. */
    void record(long amendmentId) {
        Map<String, Object> a = jdbc.queryForMap("""
                SELECT a.law_catalog_id, c.parent_id, c.name, a.revision_type, a.promulgated_on, a.enforced_on
                FROM law_amendment a JOIN law_catalog c ON c.id = a.law_catalog_id WHERE a.id = ?""", amendmentId);
        long lawId = (Long) a.get("law_catalog_id");
        Long parentId = (Long) a.get("parent_id");
        LocalDate enforcedOn = a.get("enforced_on") == null ? null : ((Date) a.get("enforced_on")).toLocalDate();
        Amended amended = new Amended(lawId, parentId, (String) a.get("revision_type"), new HashSet<>(jdbc.queryForList(
                "SELECT jo_code FROM law_amendment_article WHERE amendment_id = ?", String.class, amendmentId)));

        // a link made after the 공포 was made knowing the amendment (the link screen shows it): no alert
        List<LinkRow> rows = jdbc.query("""
                SELECT l.permit_item_id, p.project_id, p.name AS permit, pr.name AS project, l.law_catalog_id, l.article_no
                FROM permit_law_link l JOIN permit_item p ON p.id = l.permit_item_id JOIN project pr ON pr.id = p.project_id
                WHERE p.status NOT IN ('COMPLETED', 'EXPIRED') AND l.law_catalog_id IN (?, ?)
                  AND (l.created_at AT TIME ZONE 'Asia/Seoul')::date <= coalesce(?::date, 'infinity')
                ORDER BY l.permit_item_id, l.id""",
                (rs, i) -> new LinkRow(rs.getLong("permit_item_id"), rs.getLong("project_id"), rs.getString("permit"),
                        rs.getString("project"), new Link(rs.getLong("law_catalog_id"), rs.getString("article_no"))),
                lawId, parentId == null ? -1 : parentId, a.get("promulgated_on"));

        Map<Long, List<LinkRow>> byPermit = rows.stream()
                .collect(Collectors.groupingBy(LinkRow::permitItemId, LinkedHashMap::new, Collectors.toList()));
        LocalDate today = LocalDate.now(clock);
        byPermit.forEach((permitItemId, links) -> {
            Level level = ImpactRule.classify(amended, links.stream().map(LinkRow::link).toList());
            if (level == null) {
                return;
            }
            List<Long> created = jdbc.queryForList("""
                    INSERT INTO permit_law_impact (amendment_id, permit_item_id, level, created_at) VALUES (?, ?, ?, ?)
                    ON CONFLICT DO NOTHING RETURNING id""", Long.class,
                    amendmentId, permitItemId, level.name(), java.sql.Timestamp.from(clock.instant()));
            LinkRow p = links.get(0);
            if (!created.isEmpty() && enforcedOn != null && !enforcedOn.isBefore(today)) {
                deadlines.register(DEADLINE_KIND, created.get(0), p.projectId(),
                        "[법령 시행] %s — %s".formatted(a.get("name"), p.permit()), enforcedOn, null);
            }
        });
    }

    private record MailRow(long impactId, long userId, String email, String project, String line) {
    }

    /** One mail per 책임자 with impacts not mailed yet: by project, DIRECT first, top 10 + "외 N건". */
    void mailDigest() {
        List<MailRow> rows = jdbc.query("""
                SELECT i.id, u.id AS user_id, u.email, pr.name AS project, i.level, p.name AS permit, c.name AS law,
                       a.enforced_on, a.revision_type,
                       (SELECT string_agg(r.article_no, ', ' ORDER BY r.jo_code) FROM law_amendment_article r
                        WHERE r.amendment_id = a.id) AS articles
                FROM permit_law_impact i
                JOIN law_amendment a ON a.id = i.amendment_id JOIN law_catalog c ON c.id = a.law_catalog_id
                JOIN permit_item p ON p.id = i.permit_item_id JOIN project pr ON pr.id = p.project_id
                JOIN project_member m ON m.project_id = p.project_id AND m.is_admin JOIN users u ON u.id = m.user_id
                WHERE i.emailed_at IS NULL AND i.status = 'NEW'
                ORDER BY u.id, i.level, a.enforced_on NULLS LAST, i.id""",
                (rs, n) -> new MailRow(rs.getLong("id"), rs.getLong("user_id"), rs.getString("email"),
                        rs.getString("project"), "- [%s] %s · %s · %s · %s%s%n  앱에서 보기: %s/laws?id=%d".formatted(
                                "DIRECT".equals(rs.getString("level")) ? "직접" : "참고", rs.getString("permit"),
                                rs.getString("law"), rs.getString("articles") == null ? "조문 정보 없음" : rs.getString("articles"),
                                rs.getDate("enforced_on") == null ? "시행일 미정" : "시행 " + rs.getDate("enforced_on"),
                                ImpactRule.repealed(rs.getString("revision_type")) ? " · 대체 법령 확인 필요" : "",
                                appUrl, rs.getLong("id"))));
        Set<Long> failed = new HashSet<>();
        rows.stream().collect(Collectors.groupingBy(MailRow::userId, LinkedHashMap::new, Collectors.toList()))
                .forEach((userId, list) -> {
                    try {
                        mailer.send(list.get(0).email(), "[인허가 관리] 법령 개정 영향 %d건 (%s)"
                                .formatted(list.size(), LocalDate.now(clock)), body(list));
                    } catch (RuntimeException e) {
                        log.warn("law impact mail to user {} failed", userId, e);
                        list.forEach(r -> failed.add(r.impactId())); // retried with tomorrow's mail
                    }
                });
        List<Object[]> done = rows.stream().map(MailRow::impactId).distinct().filter(id -> !failed.contains(id))
                .map(id -> new Object[] {java.sql.Timestamp.from(clock.instant()), id}).toList();
        jdbc.batchUpdate("UPDATE permit_law_impact SET emailed_at = ? WHERE id = ?", done);
    }

    private String body(List<MailRow> list) {
        StringBuilder b = new StringBuilder("새로 확인할 법령 개정 영향이 %d건 있습니다.%n".formatted(list.size()));
        Map<String, List<MailRow>> byProject = list.stream().limit(MAIL_TOP)
                .collect(Collectors.groupingBy(MailRow::project, LinkedHashMap::new, Collectors.toList()));
        byProject.forEach((project, group) -> {
            b.append("\n[").append(project).append("]\n");
            group.forEach(r -> b.append(r.line()).append('\n'));
        });
        if (list.size() > MAIL_TOP) {
            b.append("\n외 %d건 – 앱에서 보기: %s/laws%n".formatted(list.size() - MAIL_TOP, appUrl));
        }
        return b.append('\n').append(SOURCE).append('\n').toString();
    }

    /** Admins hear about a stuck batch once, when the second day in a row fails. */
    // shortcut: every ADMIN (company master) gets it; add an ops address when there is one
    void alertAdmins(LocalDate stuckOn, int failures) {
        for (String email : jdbc.queryForList("SELECT email FROM users WHERE role = 'ADMIN'", String.class)) {
            try {
                mailer.send(email, "[인허가 관리] 법령 개정 배치 %d회 연속 실패".formatted(failures),
                        "법제처 개정 목록을 %d회 연속 받지 못했습니다. %s부터 멈춰 있고, 복구되면 밀린 날짜를 자동으로 처리합니다. 원인은 서버 로그를 확인하세요."
                                .formatted(failures, stuckOn));
            } catch (RuntimeException e) {
                log.warn("batch failure alert to {} failed", email, e);
            }
        }
    }
}
