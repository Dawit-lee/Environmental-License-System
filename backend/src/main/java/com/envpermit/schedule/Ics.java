package com.envpermit.schedule;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * RFC 5545 serializer shared by 공유 링크 and 개인 구독. Pure: the stamp is passed in. All-day entries are
 * VALUE=DATE with an exclusive DTEND; timed ones are Asia/Seoul wall clock written as UTC (no VTIMEZONE needed).
 * Lines end in CRLF and fold at 75 octets without splitting a UTF-8 character.
 */
final class Ics {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    /** end: the last day (all-day, inclusive) or the end time. location / description may be null. */
    record Entry(String uid, String summary, LocalDateTime start, LocalDateTime end, boolean allDay, String location,
                 String description) {
    }

    private Ics() {
    }

    static String calendar(String name, Instant stamp, List<Entry> entries) {
        StringBuilder out = new StringBuilder();
        for (String l : List.of("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//envpermit//schedule//KO",
                "CALSCALE:GREGORIAN", "METHOD:PUBLISH", "X-WR-CALNAME:" + escape(name))) {
            out.append(fold(l));
        }
        String dtstamp = stamp.atOffset(ZoneOffset.UTC).format(UTC);
        for (Entry e : entries) {
            out.append(fold("BEGIN:VEVENT")).append(fold("UID:" + e.uid())).append(fold("DTSTAMP:" + dtstamp));
            if (e.allDay()) {
                out.append(fold("DTSTART;VALUE=DATE:" + e.start().format(DATE)));
                out.append(fold("DTEND;VALUE=DATE:" + e.end().toLocalDate().plusDays(1).format(DATE)));
            } else {
                out.append(fold("DTSTART:" + utc(e.start()))).append(fold("DTEND:" + utc(e.end())));
            }
            out.append(fold("SUMMARY:" + escape(e.summary())));
            if (e.location() != null) out.append(fold("LOCATION:" + escape(e.location())));
            if (e.description() != null) out.append(fold("DESCRIPTION:" + escape(e.description())));
            out.append(fold("END:VEVENT"));
        }
        return out.append(fold("END:VCALENDAR")).toString();
    }

    static String utc(LocalDateTime t) {
        return t.atZone(SEOUL).withZoneSameInstant(ZoneOffset.UTC).format(UTC);
    }

    /** TEXT value: backslash, semicolon, comma and line breaks. */
    static String escape(String s) {
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace("\r\n", "\\n").replace("\r", "\\n").replace("\n", "\\n");
    }

    /** One content line + CRLF, folded so no physical line exceeds 75 octets (the leading space counts). */
    static String fold(String line) {
        StringBuilder out = new StringBuilder();
        int octets = 0;
        for (int i = 0; i < line.length(); ) {
            int cp = line.codePointAt(i);
            int n = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (octets + n > 75) {
                out.append("\r\n ");
                octets = 1;
            }
            out.appendCodePoint(cp);
            octets += n;
            i += Character.charCount(cp);
        }
        return out.append("\r\n").toString();
    }
}
