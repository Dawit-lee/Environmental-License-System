package com.envpermit.notification;

import static com.envpermit.core.project.ProjectAccess.userId;

import com.envpermit.core.project.Project;
import com.envpermit.core.project.ProjectRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class NotificationController {

    private final NotificationRepository notifications;
    private final DeadlineEventRepository events;
    private final ProjectRepository projects;
    private final Clock clock;

    NotificationController(NotificationRepository notifications, DeadlineEventRepository events,
                           ProjectRepository projects, Clock clock) {
        this.notifications = notifications;
        this.events = events;
        this.projects = projects;
        this.clock = clock;
    }

    record NotificationView(long id, String message, String link, LocalDate dueDate, Integer offsetDays,
                            Instant createdAt, Instant readAt) {
    }

    record DeadlineView(long id, String kind, long targetId, long projectId, String projectName, String title,
                        LocalDate dueDate, long dDay) {
    }

    @GetMapping("/api/notifications")
    List<NotificationView> mine(@AuthenticationPrincipal Jwt jwt) {
        return notifications.findTop50ByUserIdOrderByCreatedAtDescDueDateAscIdDesc(userId(jwt)).stream()
                .map(n -> new NotificationView(n.getId(), n.getMessage(), n.getLink(), n.getDueDate(),
                        n.getOffsetDays(), n.getCreatedAt(), n.getReadAt()))
                .toList();
    }

    @PostMapping("/api/notifications/{id}/read")
    @Transactional
    ResponseEntity<Void> read(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        notifications.findByIdAndUserId(id, userId(jwt))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND))
                .markRead(clock.instant());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/notifications/read-all")
    @Transactional
    ResponseEntity<Void> readAll(@AuthenticationPrincipal Jwt jwt) {
        notifications.markAllRead(userId(jwt), clock.instant());
        return ResponseEntity.noContent().build();
    }

    /** Deadlines in the caller's projects, for the dashboard and calendar. */
    @GetMapping("/api/deadlines")
    List<DeadlineView> deadlines(@AuthenticationPrincipal Jwt jwt,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 366) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "기간은 1년 이내로 지정해 주세요.");
        }
        List<DeadlineEvent> list = events.findVisible(userId(jwt), from, to);
        Map<Long, String> names = projects.findAllById(list.stream().map(DeadlineEvent::getProjectId).toList())
                .stream().collect(Collectors.toMap(Project::getId, Project::getName));
        LocalDate today = LocalDate.now(clock);
        return list.stream().map(e -> new DeadlineView(e.getId(), e.getKind(), e.getTargetId(), e.getProjectId(),
                names.get(e.getProjectId()), e.getTitle(), e.getDueDate(),
                ChronoUnit.DAYS.between(today, e.getDueDate()))).toList();
    }
}
