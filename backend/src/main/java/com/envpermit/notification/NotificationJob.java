package com.envpermit.notification;

import com.envpermit.core.project.Project;
import com.envpermit.core.project.ProjectMember;
import com.envpermit.core.project.ProjectMemberRepository;
import com.envpermit.core.project.ProjectRepository;
import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily: deadline events → in-app reminders at the offsets of their kind (D-90/60/30/7 unless listed in
 * {@link #OFFSETS_BY_KIND}), plus one "기한 경과" the day after a task's due date, then one digest mail per user.
 */
@Component
public class NotificationJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationJob.class);
    private static final int[] OFFSETS = {7, 30, 60, 90}; // ascending
    public static final String SCHEDULE_ITEM = "SCHEDULE_ITEM";
    /** Kinds with their own reminder offsets (ascending, at most the last of {@link #OFFSETS}). */
    private static final Map<String, int[]> OFFSETS_BY_KIND = Map.of(SCHEDULE_ITEM, new int[] {1, 7},
            Deadlines.PROJECT_EVENT, new int[] {1, 3});
    /** offset_days of the overdue notice. */
    public static final int OVERDUE = -1;
    // a task's deadline exists exactly while the task is open, so "due yesterday" means an open task went overdue
    private static final Set<String> TASK_KINDS = Set.of("OBLIGATION", "CORRECTIVE_TASK", SCHEDULE_ITEM);

    private final DeadlineEventRepository events;
    private final NotificationRepository notifications;
    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final UserRepository users;
    private final Mailer mailer;
    private final Clock clock;
    private final JdbcTemplate jdbc;

    NotificationJob(DeadlineEventRepository events, NotificationRepository notifications, ProjectRepository projects,
                    ProjectMemberRepository members, UserRepository users, Mailer mailer, Clock clock, JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.events = events;
        this.notifications = notifications;
        this.projects = projects;
        this.members = members;
        this.users = users;
        this.mailer = mailer;
        this.clock = clock;
    }

    /**
     * The tightest reminder already reached, or null. Only that one is sent, so a deadline registered
     * (or a server back up) at D-20 gets the D-30 reminder, not stale D-90/D-60 ones.
     */
    static Integer crossedOffset(String kind, long daysLeft) {
        if (daysLeft < 0) return null;
        for (int o : OFFSETS_BY_KIND.getOrDefault(kind, OFFSETS)) {
            if (daysLeft <= o) return o;
        }
        return null;
    }

    @Scheduled(cron = "0 0 7 * * *", zone = "Asia/Seoul")
    void daily() {
        createDueNotifications();
        sendDigests();
    }

    public void createDueNotifications() {
        LocalDate today = LocalDate.now(clock);
        List<DeadlineEvent> due = events.findByDueDateBetween(today, today.plusDays(OFFSETS[OFFSETS.length - 1]));
        List<DeadlineEvent> overdue = events.findByDueDateAndKindIn(today.minusDays(1), TASK_KINDS);
        Map<Long, Project> byId = projects.findAllById(
                        Stream.concat(due.stream(), overdue.stream()).map(DeadlineEvent::getProjectId).toList())
                .stream().collect(Collectors.toMap(Project::getId, Function.identity()));
        Map<Long, String> projectNames = byId.values().stream().collect(Collectors.toMap(Project::getId, Project::getName));
        // 조치 업무 belong to the 운영 module: no reminders while a project has it off (design-schedule §8.1)
        Predicate<DeadlineEvent> off = e -> e.getKind().equals("CORRECTIVE_TASK")
                && byId.containsKey(e.getProjectId()) && !byId.get(e.getProjectId()).isOperationsEnabled();
        due.removeIf(off);
        overdue.removeIf(off);
        for (DeadlineEvent e : due) {
            Integer offset = crossedOffset(e.getKind(), ChronoUnit.DAYS.between(today, e.getDueDate()));
            if (offset == null) continue; // e.g. a schedule item at D-20
            String message = "%s · %s (%s)".formatted(projectNames.get(e.getProjectId()), e.getTitle(), e.getDueDate());
            if (Deadlines.PROJECT_EVENT.equals(e.getKind())) {
                notify(e, offset, message, link(e, null), eventRecipients(e.getTargetId(), offset));
            } else {
                remind(e, offset, message, link(e, "/projects/" + e.getProjectId()));
            }
        }
        for (DeadlineEvent e : overdue) { // once: only on the day after, and the unique key stops a rerun
            String message = "기한 경과 · %s · %s (%s)".formatted(projectNames.get(e.getProjectId()), e.getTitle(), e.getDueDate());
            remind(e, OVERDUE, message, link(e, "/projects/" + e.getProjectId() + "/obligations"));
            if (SCHEDULE_ITEM.equals(e.getKind())) { // a late predecessor holds up its successors: tell their owners
                String held = "선행 지연 · %s · %s (%s)".formatted(projectNames.get(e.getProjectId()), e.getTitle(), e.getDueDate());
                notify(e, OVERDUE, held, link(e, null), jdbc.queryForList("""
                        SELECT DISTINCT s.owner_user_id FROM schedule_item_dependency d
                        JOIN schedule_item s ON s.id = d.successor_id
                        WHERE d.predecessor_id = ? AND s.owner_user_id IS NOT NULL AND s.deleted_at IS NULL
                          AND s.actual_end IS NULL""", Long.class, e.getTargetId()));
            }
        }
    }

    private static String link(DeadlineEvent e, String fallback) {
        return SCHEDULE_ITEM.equals(e.getKind()) || Deadlines.PROJECT_EVENT.equals(e.getKind())
                ? "/projects/" + e.getProjectId() + "/schedule" : fallback;
    }

    /**
     * 설계 §3: a meeting reminds its attendees at D-1 only; every other event (출장·심의·현설·보고회·제출 …)
     * reminds its attendees and the project PM at D-3 and D-1. Outside attendees (by name only) get nothing.
     */
    private List<Long> eventRecipients(long eventId, int offset) {
        return jdbc.queryForList("""
                SELECT a.user_id FROM project_event e JOIN project_event_attendee a ON a.event_id = e.id
                WHERE e.id = ? AND a.user_id IS NOT NULL AND (e.kind <> 'MEETING' OR ? = 1)
                UNION
                SELECT p.pm_user_id FROM project_event e JOIN project p ON p.id = e.project_id
                WHERE e.id = ? AND e.kind <> 'MEETING' AND p.pm_user_id IS NOT NULL
                ORDER BY 1""", Long.class, eventId, offset, eventId);
    }

    /** To the assignee, or to every project member when nobody is assigned; never twice for the same due date and offset. */
    private void remind(DeadlineEvent e, int offset, String message, String link) {
        notify(e, offset, message, link, e.getAssigneeId() != null ? List.of(e.getAssigneeId())
                : members.findByIdProjectIdOrderByIdUserId(e.getProjectId()).stream().map(ProjectMember::getUserId).toList());
    }

    private void notify(DeadlineEvent e, int offset, String message, String link, List<Long> recipients) {
        for (long userId : recipients) {
            if (!notifications.existsByDeadlineEventIdAndDueDateAndOffsetDaysAndUserId(e.getId(), e.getDueDate(), offset, userId)) {
                notifications.save(new Notification(userId, e, offset, message, link, clock.instant()));
            }
        }
    }

    public void sendDigests() {
        Map<Long, List<Notification>> byUser = notifications
                .findByEmailedAtIsNullOrderByUserIdAscOffsetDaysAscDueDateAscIdAsc().stream()
                .collect(Collectors.groupingBy(Notification::getUserId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, User> recipients = users.findAllById(byUser.keySet()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        String today = LocalDate.now(clock).toString();
        byUser.forEach((userId, list) -> {
            try {
                mailer.send(recipients.get(userId).getEmail(),
                        "[인허가 관리] 기한 알림 %d건 (%s)".formatted(list.size(), today), digest(list));
                list.forEach(n -> n.markEmailed(clock.instant()));
                notifications.saveAll(list);
            } catch (RuntimeException ex) {
                // left unmarked, so tomorrow's digest retries it
                log.warn("digest mail to user {} failed", userId, ex);
            }
        });
    }

    private static String digest(List<Notification> list) {
        StringBuilder b = new StringBuilder("오늘 확인할 기한 알림입니다.\n");
        Map<Integer, List<Notification>> byOffset = list.stream().collect(Collectors.groupingBy(
                n -> n.getOffsetDays() == null ? Integer.MAX_VALUE : n.getOffsetDays(),
                LinkedHashMap::new, Collectors.toList()));
        byOffset.forEach((offset, group) -> {
            b.append('\n').append(offset == Integer.MAX_VALUE ? "[기타]" : offset == OVERDUE ? "[기한 경과]" : "[D-" + offset + "]").append('\n');
            group.forEach(n -> b.append("- ").append(n.getMessage()).append('\n'));
        });
        return b.toString();
    }
}
