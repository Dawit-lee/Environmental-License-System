package com.envpermit.notification;

import com.envpermit.core.permit.PermitChanged;
import com.envpermit.core.permit.PermitItem;
import java.time.LocalDate;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Entry point for every module: "register a dated event for X". One event per (kind, targetId);
 * registering again moves it. The daily {@link NotificationJob} turns events into D-90/60/30/7 reminders.
 */
@Service
public class Deadlines {

    public static final String PERMIT_EXPIRY = "PERMIT_EXPIRY";

    private final DeadlineEventRepository events;

    Deadlines(DeadlineEventRepository events) {
        this.events = events;
    }

    /** @param assigneeId who is reminded; null = every project member. A null dueDate removes the event. */
    @Transactional
    public void register(String kind, long targetId, long projectId, String title, LocalDate dueDate, Long assigneeId) {
        if (dueDate == null) {
            remove(kind, targetId);
            return;
        }
        DeadlineEvent e = events.findByKindAndTargetId(kind, targetId).orElseGet(() -> new DeadlineEvent(kind, targetId));
        e.set(projectId, title, dueDate, assigneeId);
        events.save(e);
    }

    @Transactional
    public void remove(String kind, long targetId) {
        events.deleteByKindAndTargetId(kind, targetId);
    }

    @EventListener
    void onPermitChanged(PermitChanged c) {
        PermitItem p = c.permit();
        if (c.deleted()) {
            remove(PERMIT_EXPIRY, p.getId());
        } else {
            register(PERMIT_EXPIRY, p.getId(), p.getProjectId(), p.getName() + " 만료", p.getExpiryDate(), null);
        }
    }
}
