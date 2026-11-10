package com.envpermit.notification;

import java.time.Clock;
import org.springframework.stereotype.Component;

/** In-app notice for events that mail the user directly (e.g. join requests); not part of the daily digest. */
@Component
public class Notifier {

    private final NotificationRepository notifications;
    private final Clock clock;

    Notifier(NotificationRepository notifications, Clock clock) {
        this.notifications = notifications;
        this.clock = clock;
    }

    public void notify(long userId, String message, String link) {
        notifications.save(new Notification(userId, message, link, clock.instant()));
    }
}
