package com.envpermit.notification;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import java.time.LocalDate;

/** In-app notification for one user. Deadline reminders carry the event, due date and D-offset. */
@Entity
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private Long deadlineEventId;
    private LocalDate dueDate;
    private Integer offsetDays;
    private String message;
    private String link;
    private Instant createdAt;
    private Instant readAt;
    private Instant emailedAt;

    protected Notification() {
    }

    Notification(long userId, DeadlineEvent e, int offsetDays, String message, String link, Instant now) {
        this.userId = userId;
        this.deadlineEventId = e.getId();
        this.dueDate = e.getDueDate();
        this.offsetDays = offsetDays;
        this.message = message;
        this.link = link;
        this.createdAt = now;
    }

    void markRead(Instant now) {
        if (readAt == null) readAt = now;
    }

    void markEmailed(Instant now) {
        emailedAt = now;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public LocalDate getDueDate() { return dueDate; }
    public Integer getOffsetDays() { return offsetDays; }
    public String getMessage() { return message; }
    public String getLink() { return link; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReadAt() { return readAt; }
}
