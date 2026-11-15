package com.envpermit.support;

import com.envpermit.schedule.google.CalendarPublisher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fake Google Calendar: keeps calendars and events in memory and records every call in order. */
public class FakeCalendarPublisher implements CalendarPublisher {

    public final List<String> calls = new ArrayList<>();
    /** eventId → event, across all calendars. */
    public final Map<String, GoogleEvent> events = new LinkedHashMap<>();
    public boolean failing;
    private int seq;

    public void reset() {
        calls.clear();
        events.clear();
        failing = false;
        seq = 0;
    }

    @Override
    public String exchangeCode(String code) {
        calls.add("exchange:" + code);
        return "refresh-" + code;
    }

    @Override
    public String createCalendar(String refreshToken, String name) {
        calls.add("createCalendar:" + name);
        return "cal-" + (++seq);
    }

    @Override
    public Ref insert(String refreshToken, String calendarId, GoogleEvent event) {
        fail();
        String id = "ev-" + (++seq);
        calls.add("insert:" + event.summary());
        events.put(id, event);
        return new Ref(id, "etag-" + seq);
    }

    @Override
    public Ref patch(String refreshToken, String calendarId, String eventId, GoogleEvent event) {
        fail();
        calls.add("patch:" + event.summary());
        events.put(eventId, event);
        return new Ref(eventId, "etag-" + (++seq));
    }

    @Override
    public void delete(String refreshToken, String calendarId, String eventId) {
        fail();
        calls.add("delete:" + events.remove(eventId).summary());
    }

    @Override
    public void deleteCalendar(String refreshToken, String calendarId) {
        calls.add("deleteCalendar:" + calendarId);
    }

    @Override
    public void revoke(String refreshToken) {
        calls.add("revoke:" + refreshToken);
    }

    private void fail() {
        if (failing) throw new IllegalStateException("Google 503");
    }
}
