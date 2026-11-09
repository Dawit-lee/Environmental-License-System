package com.envpermit.notification;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDate;

/** A dated thing to be reminded about. Register through {@link Deadlines}. */
@Entity
public class DeadlineEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String kind;
    private Long targetId;
    private Long projectId;
    private String title;
    private LocalDate dueDate;
    private Long assigneeId;

    protected DeadlineEvent() {
    }

    DeadlineEvent(String kind, long targetId) {
        this.kind = kind;
        this.targetId = targetId;
    }

    void set(long projectId, String title, LocalDate dueDate, Long assigneeId) {
        this.projectId = projectId;
        this.title = title;
        this.dueDate = dueDate;
        this.assigneeId = assigneeId;
    }

    public Long getId() { return id; }
    public String getKind() { return kind; }
    public Long getTargetId() { return targetId; }
    public Long getProjectId() { return projectId; }
    public String getTitle() { return title; }
    public LocalDate getDueDate() { return dueDate; }
    public Long getAssigneeId() { return assigneeId; }
}
