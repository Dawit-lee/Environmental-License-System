package com.envpermit.schedule.google;

import java.time.LocalDateTime;

/**
 * Port to Google Calendar (설계 §6.4). Real adapter: {@link GoogleCalendarClient}; tests use a fake.
 * Every call takes the user's refresh token; the adapter gets its own access token.
 */
public interface CalendarPublisher {

    /** All-day: start/end are dates (end = last day, inclusive). Otherwise local Asia/Seoul wall-clock times. */
    record GoogleEvent(String summary, String description, String location, LocalDateTime start, LocalDateTime end,
                       boolean allDay) {
    }

    record Ref(String id, String etag) {
    }

    /** Authorization code → refresh token. */
    String exchangeCode(String code);

    /** Creates the app's secondary calendar; returns its id. */
    String createCalendar(String refreshToken, String name);

    Ref insert(String refreshToken, String calendarId, GoogleEvent event);

    Ref patch(String refreshToken, String calendarId, String eventId, GoogleEvent event);

    /** Already gone in Google counts as done. */
    void delete(String refreshToken, String calendarId, String eventId);

    void deleteCalendar(String refreshToken, String calendarId);

    void revoke(String refreshToken);
}
