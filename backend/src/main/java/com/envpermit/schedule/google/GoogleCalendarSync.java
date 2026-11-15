package com.envpermit.schedule.google;

import com.envpermit.notification.Notifier;
import com.envpermit.schedule.google.CalendarPublisher.GoogleEvent;
import com.envpermit.schedule.google.CalendarPublisher.Ref;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every minute: drains {@code calendar_sync_outbox} (written by the V345 triggers) through {@link CalendarPublisher}.
 * Each row is reconciled to the source's current state for that user — wanted and not in Google → insert, wanted and
 * linked → patch, not wanted (deleted, no longer attendee/owner, toggle off) and linked → delete — so processing a
 * row twice never makes a second Google event. Failure: retry after 1, 2, 4 … 60 min; after 24 h drop the row,
 * keep {@code last_error} and notify the user once ("Google 동기화 실패").
 */
@Component
public class GoogleCalendarSync {

    private static final Logger log = LoggerFactory.getLogger(GoogleCalendarSync.class);
    static final Duration GIVE_UP_AFTER = Duration.ofHours(24);
    private static final String NOTE = "인허가 일정 앱에서 보낸 일정입니다. Google에서 고친 내용은 앱으로 돌아오지 않고 다음 동기화 때 덮어씁니다.";

    private final JdbcTemplate jdbc;
    private final GoogleSettings settings;
    private final CalendarPublisher google;
    private final Notifier notifier;
    private final Clock clock;

    GoogleCalendarSync(JdbcTemplate jdbc, GoogleSettings settings, CalendarPublisher google, Notifier notifier,
                       Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.google = google;
        this.notifier = notifier;
        this.clock = clock;
    }

    private record Row(long id, String xmin, long userId, String kind, long sourceId, int attempts,
                       Instant failingSince, String token, String calendarId, boolean projectMilestones,
                       boolean tasks) {
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void run() {
        if (settings.enabled()) runOnce();
    }

    /** One pass over the due rows. Public for tests (scheduling is off there). */
    public void runOnce() {
        // not connected any more, or the company switched the feature off: nothing to send
        jdbc.update("""
                DELETE FROM calendar_sync_outbox o WHERE NOT EXISTS (
                    SELECT 1 FROM user_google_link g JOIN users u ON u.id = g.user_id
                    JOIN company c ON c.id = u.company_id AND c.google_sync_enabled
                    WHERE g.user_id = o.user_id)""");
        Instant now = clock.instant();
        List<Row> due = jdbc.query("""
                SELECT o.id, o.xmin::text, o.user_id, o.source_kind, o.source_id, o.attempts, o.failing_since,
                       g.encrypted_refresh_token, g.calendar_id, g.sync_project_milestones, g.sync_tasks
                FROM calendar_sync_outbox o JOIN user_google_link g ON g.user_id = o.user_id
                WHERE o.next_attempt_at IS NULL OR o.next_attempt_at <= ?
                ORDER BY o.id LIMIT 500""",
                (rs, n) -> new Row(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getLong(5),
                        rs.getInt(6), rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant(),
                        rs.getString(8), rs.getString(9), rs.getBoolean(10), rs.getBoolean(11)),
                Timestamp.from(now));
        for (Row r : due) {
            try {
                sync(r);
                // xmin: a trigger that re-queued the source during the Google call keeps the row for the next pass
                jdbc.update("DELETE FROM calendar_sync_outbox WHERE id = ? AND xmin::text = ?", r.id(), r.xmin());
                jdbc.update("UPDATE user_google_link SET last_synced_at = ?, last_error = NULL WHERE user_id = ?",
                        Timestamp.from(now), r.userId());
            } catch (RuntimeException e) {
                failed(r, e, now);
            }
        }
    }

    private void sync(Row r) {
        GoogleEvent wanted = "EVENT".equals(r.kind()) ? event(r) : item(r);
        List<String> linked = jdbc.queryForList("""
                SELECT google_event_id FROM calendar_sync_link WHERE user_id = ? AND source_kind = ? AND source_id = ?""",
                String.class, r.userId(), r.kind(), r.sourceId());
        if (wanted == null && linked.isEmpty()) return;
        String token = settings.decrypt(r.token());
        if (wanted == null) {
            google.delete(token, r.calendarId(), linked.get(0));
            jdbc.update("DELETE FROM calendar_sync_link WHERE user_id = ? AND source_kind = ? AND source_id = ?",
                    r.userId(), r.kind(), r.sourceId());
            return;
        }
        Ref ref = linked.isEmpty() ? google.insert(token, r.calendarId(), wanted)
                : google.patch(token, r.calendarId(), linked.get(0), wanted);
        jdbc.update("""
                INSERT INTO calendar_sync_link (user_id, source_kind, source_id, google_event_id, etag)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (user_id, source_kind, source_id)
                DO UPDATE SET google_event_id = EXCLUDED.google_event_id, etag = EXCLUDED.etag""",
                r.userId(), r.kind(), r.sourceId(), ref.id(), ref.etag());
    }

    /** Events the user attends. */
    private GoogleEvent event(Row r) {
        return jdbc.query("""
                SELECT p.name, e.title, e.purpose, e.location, e.starts_at, e.ends_at, e.all_day
                FROM project_event e JOIN project p ON p.id = e.project_id
                JOIN project_event_attendee a ON a.event_id = e.id AND a.user_id = ?
                JOIN project_member m ON m.project_id = e.project_id AND m.user_id = a.user_id
                WHERE e.id = ?""",
                (rs, n) -> new GoogleEvent("[" + rs.getString(1) + "] " + rs.getString(2),
                        description(rs.getString(3)), rs.getString(4), rs.getObject(5, LocalDateTime.class),
                        rs.getObject(6, LocalDateTime.class), rs.getBoolean(7)),
                r.userId(), r.sourceId()).stream().findFirst().orElse(null);
    }

    /** Milestones I own (or all of my projects' with the toggle), TASK bars I own only with the toggle. */
    private GoogleEvent item(Row r) {
        return jdbc.query("""
                SELECT p.name, i.title, d.name, i.start_date, i.end_date
                FROM schedule_item i JOIN project p ON p.id = i.project_id
                JOIN project_member m ON m.project_id = i.project_id AND m.user_id = ?
                LEFT JOIN discipline d ON d.id = i.discipline_id
                WHERE i.id = ? AND i.deleted_at IS NULL
                  AND ((i.kind = 'MILESTONE' AND (i.owner_user_id = m.user_id OR ?))
                    OR (i.kind = 'TASK' AND i.owner_user_id = m.user_id AND ?))""",
                (rs, n) -> new GoogleEvent("[" + rs.getString(1) + "] " + rs.getString(2),
                        description(rs.getString(3) == null ? null : "분야: " + rs.getString(3)), null,
                        rs.getObject(4, LocalDate.class).atStartOfDay(), rs.getObject(5, LocalDate.class).atStartOfDay(),
                        true),
                r.userId(), r.sourceId(), r.projectMilestones(), r.tasks()).stream().findFirst().orElse(null);
    }

    private static String description(String first) {
        return first == null || first.isBlank() ? NOTE : first + "\n\n" + NOTE;
    }

    private void failed(Row r, RuntimeException e, Instant now) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        message = message.length() > 400 ? message.substring(0, 400) : message;
        log.warn("Google sync failed (user {}, {} {}): {}", r.userId(), r.kind(), r.sourceId(), message);
        Instant since = r.failingSince() == null ? now : r.failingSince();
        if (Duration.between(since, now).compareTo(GIVE_UP_AFTER) >= 0) {
            jdbc.update("DELETE FROM calendar_sync_outbox WHERE id = ?", r.id());
            // notify once per failure spell (a later success clears last_error)
            if (jdbc.update("UPDATE user_google_link SET last_error = ? WHERE user_id = ? AND last_error IS NULL",
                    message, r.userId()) == 1) {
                notifier.notify(r.userId(), "Google 동기화 실패: " + message, "/calendar");
            }
            jdbc.update("UPDATE user_google_link SET last_error = ? WHERE user_id = ?", message, r.userId());
            return;
        }
        long minutes = Math.min(1L << Math.min(r.attempts(), 6), 60); // 1, 2, 4 … 60
        jdbc.update("""
                UPDATE calendar_sync_outbox SET attempts = attempts + 1, failing_since = ?, next_attempt_at = ?
                WHERE id = ?""", Timestamp.from(since), Timestamp.from(now.plus(Duration.ofMinutes(minutes))), r.id());
    }
}
