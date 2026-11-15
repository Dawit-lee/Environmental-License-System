package com.envpermit.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Ticket design-schedule/05: RFC 5545 serialization. */
class IcsTest {

    private static final Instant STAMP = Instant.parse("2026-01-05T00:00:00Z");

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "plain|plain",
            "a,b|a\\,b",
            "a;b|a\\;b",
            "back\\slash|back\\\\slash",
            "\"줄1\n줄2\"|줄1\\n줄2",
            "\"crlf\r\nend\"|crlf\\nend",
    })
    void escapes_text(String in, String out) {
        assertThat(Ics.escape(in)).isEqualTo(out);
    }

    @Test
    void folds_at_75_octets_without_splitting_korean() {
        String line = "SUMMARY:" + "가".repeat(40); // 8 + 120 octets
        String folded = Ics.fold(line);
        assertThat(folded).endsWith("\r\n");
        for (String physical : folded.split("\r\n")) {
            assertThat(physical.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
        }
        assertThat(folded.replace("\r\n ", "").replace("\r\n", "")).isEqualTo(line); // unfolds back, no broken char
        assertThat(Ics.fold("A".repeat(75))).isEqualTo("A".repeat(75) + "\r\n");
        assertThat(Ics.fold("A".repeat(76))).isEqualTo("A".repeat(75) + "\r\n A\r\n");
    }

    @Test
    void all_day_end_is_exclusive_and_timed_is_utc() {
        String ics = Ics.calendar("가온 일정", STAMP, List.of(
                new Ics.Entry("item-7@envpermit", "기본설계 보고, 2차", LocalDateTime.parse("2026-01-20T00:00"),
                        LocalDateTime.parse("2026-01-22T00:00"), true, null, "분야: 토목"),
                new Ics.Entry("event-3@envpermit", "착수 회의", LocalDateTime.parse("2026-01-20T14:00"),
                        LocalDateTime.parse("2026-01-20T16:30"), false, "본사; 3층", null)));
        assertThat(ics).startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n").endsWith("END:VCALENDAR\r\n")
                .contains("UID:item-7@envpermit\r\n", "DTSTART;VALUE=DATE:20260120\r\n", "DTEND;VALUE=DATE:20260123\r\n",
                        "SUMMARY:기본설계 보고\\, 2차\r\n", "DESCRIPTION:분야: 토목\r\n",
                        "DTSTART:20260120T050000Z\r\n", "DTEND:20260120T073000Z\r\n", "LOCATION:본사\\; 3층\r\n",
                        "DTSTAMP:20260105T000000Z\r\n", "X-WR-CALNAME:가온 일정\r\n")
                .doesNotContain("\n\n");
        assertThat(ics.split("BEGIN:VEVENT")).hasSize(3);
    }
}
