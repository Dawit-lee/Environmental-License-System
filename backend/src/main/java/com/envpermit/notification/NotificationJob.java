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
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily: deadline events → D-90/60/30/7 in-app notifications, then one digest mail per user. */
@Component
public class NotificationJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationJob.class);
    private static final int[] OFFSETS = {7, 30, 60, 90}; // ascending

    private final DeadlineEventRepository events;
    private final NotificationRepository notifications;
    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final UserRepository users;
    private final Mailer mailer;
    private final Clock clock;

    NotificationJob(DeadlineEventRepository events, NotificationRepository notifications, ProjectRepository projects,
                    ProjectMemberRepository members, UserRepository users, Mailer mailer, Clock clock) {
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
    static Integer crossedOffset(long daysLeft) {
        if (daysLeft < 0) return null;
        for (int o : OFFSETS) {
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
        Map<Long, String> projectNames = projects.findAllById(due.stream().map(DeadlineEvent::getProjectId).toList())
                .stream().collect(Collectors.toMap(Project::getId, Project::getName));
        for (DeadlineEvent e : due) {
            int offset = crossedOffset(ChronoUnit.DAYS.between(today, e.getDueDate()));
            List<Long> recipients = e.getAssigneeId() != null ? List.of(e.getAssigneeId())
                    : members.findByIdProjectIdOrderByIdUserId(e.getProjectId()).stream()
                            .map(ProjectMember::getUserId).toList();
            String message = "%s · %s (%s)".formatted(projectNames.get(e.getProjectId()), e.getTitle(), e.getDueDate());
            for (long userId : recipients) {
                if (!notifications.existsByDeadlineEventIdAndDueDateAndOffsetDaysAndUserId(
                        e.getId(), e.getDueDate(), offset, userId)) {
                    notifications.save(new Notification(userId, e, offset, message,
                            "/projects/" + e.getProjectId(), clock.instant()));
                }
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
            b.append('\n').append(offset == Integer.MAX_VALUE ? "[기타]" : "[D-" + offset + "]").append('\n');
            group.forEach(n -> b.append("- ").append(n.getMessage()).append('\n'));
        });
        return b.toString();
    }
}
