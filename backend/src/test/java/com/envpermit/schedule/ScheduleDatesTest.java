package com.envpermit.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.envpermit.schedule.ScheduleDates.Range;
import com.envpermit.schedule.ScheduleDates.Weighted;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ScheduleDatesTest {

    @ParameterizedTest(name = "{0} {1}일 {2}개월 기간 {3} ms={4} → {5}~{6}")
    @CsvSource(nullValues = "none", value = {
            "2027-07-12, -150, none, none, true,  2027-02-12, 2027-02-12", // 입찰일 역산
            "2027-07-12,  -90, none, none, true,  2027-04-13, 2027-04-13", // 설계 §2.3 예시
            "2027-07-26,  -90, none, none, true,  2027-04-27, 2027-04-27", // 입찰일 +14 → 함께 +14
            "2027-07-12,    0, none, none, true,  2027-07-12, 2027-07-12",
            "2027-07-12,  -60, none,   85, false, 2027-02-18, 2027-05-13", // task ends at the offset, 85 days long
            "2027-07-12,  -60, none,    1, false, 2027-05-13, 2027-05-13", // one-day task
            "2027-01-25,   10, none, none, true,  2027-02-04, 2027-02-04", // across a month end
            "2027-12-25,   10, none, none, true,  2028-01-04, 2028-01-04", // across a year end
            "2028-02-20,   10, none, none, true,  2028-03-01, 2028-03-01", // leap February
            "2027-01-31, none,    4, none, true,  2027-05-31, 2027-05-31", // +4개월 1/31 → 5/31
            "2027-01-31, none,    1, none, true,  2027-02-28, 2027-02-28", // 말일 보정
            "2028-01-31, none,    1, none, true,  2028-02-29, 2028-02-29", // leap 말일
            "2027-03-31, none,   -1, none, true,  2027-02-28, 2027-02-28", // negative months
            "2027-10-31, none,    4, none, true,  2028-02-29, 2028-02-29",
            "2027-01-15, none,    4,   10, false, 2027-05-06, 2027-05-15",
    })
    void resolve(LocalDate base, Integer days, Integer months, Integer duration, boolean milestone,
                 LocalDate start, LocalDate end) {
        assertThat(ScheduleDates.resolve(base, days, months, duration, milestone)).isEqualTo(new Range(start, end));
    }

    @Test
    void resolve_without_a_base_date_has_no_dates() {
        assertThat(ScheduleDates.resolve(null, -90, null, null, true)).isNull();
    }

    @ParameterizedTest(name = "base {0}, {1}~{2} → {4}일, 기간 {5}")
    @CsvSource(nullValues = "none", value = {
            "2027-07-12, 2027-04-13, 2027-04-13, true,  -90, none",
            "2027-07-12, 2027-02-18, 2027-05-13, false, -60, 85",
            "2027-01-31, 2027-05-31, 2027-05-31, true,  120, none",
            "2027-01-01, 2026-12-30, 2027-01-02, false,   1, 4",
    })
    void reverse_gives_back_the_same_dates(LocalDate base, LocalDate start, LocalDate end, boolean milestone,
                                           int offset, Integer duration) {
        ScheduleDates.Offset o = ScheduleDates.reverse(base, start, end, milestone);
        assertThat(o).isEqualTo(new ScheduleDates.Offset(offset, duration));
        assertThat(ScheduleDates.resolve(base, o.offsetDays(), null, o.durationDays(), milestone)).isEqualTo(new Range(start, end));
    }

    /** Moving a base date moves a relative item by the same days (pinned items: ScheduleBaselineApiTest). */
    @ParameterizedTest(name = "base {0} → {1}: relative {2} → {3}")
    @CsvSource({
            "2027-07-12, 2027-07-26, 2027-04-13, 2027-04-27", // later
            "2027-07-12, 2027-06-28, 2027-04-13, 2027-03-30", // earlier, across a month end
            "2027-07-12, 2027-07-12, 2027-04-13, 2027-04-13", // unchanged
    })
    void moving_the_base_date(LocalDate from, LocalDate to, LocalDate before, LocalDate after) {
        ScheduleDates.Offset o = ScheduleDates.reverse(from, before, before, true);
        assertThat(ScheduleDates.resolve(to, o.offsetDays(), null, null, true).end()).isEqualTo(after);
    }

    @ParameterizedTest(name = "end {0} baseline {1} → {2}")
    @CsvSource(nullValues = "none", value = {
            "2027-04-27, 2027-04-13, 14",
            "2027-04-10, 2027-04-13, -3",
            "2027-04-13, 2027-04-13, 0",
            "2027-04-13, none,       none", // no baseline yet
            "none,       2027-04-13, none", // no dates yet
    })
    void slip_days(LocalDate end, LocalDate baselineEnd, Integer slip) {
        assertThat(ScheduleDates.slipDays(end, baselineEnd)).isEqualTo(slip);
    }

    @ParameterizedTest(name = "baseline {0}, {1} → {2}: {3}")
    @CsvSource(nullValues = "none", value = {
            "none,       2027-04-13, 2027-04-30, false", // no baseline: no reason ever
            "2027-04-13, 2027-04-13, 2027-04-27, true",  // past the baseline
            "2027-04-13, 2027-04-27, 2027-04-20, false", // earlier again, though still past
            "2027-04-13, 2027-04-27, 2027-05-04, true",  // further out
            "2027-04-13, 2027-04-01, 2027-04-13, false", // up to the baseline is fine
            "2027-04-13, none,       2027-04-20, true",  // a dateless item getting late dates
            "2027-04-13, 2027-04-20, none,       false",
    })
    void needs_reason(LocalDate baselineEnd, LocalDate oldEnd, LocalDate newEnd, boolean needs) {
        assertThat(ScheduleDates.needsReason(baselineEnd, oldEnd, newEnd)).isEqualTo(needs);
    }

    @ParameterizedTest(name = "end {0} baseline {1} actual {2} today {3} → {4}")
    @CsvSource(nullValues = "none", value = {
            "2027-04-27, 2027-04-13, none,       2027-04-20, 14", // moved +14, not due yet
            "2027-04-27, 2027-04-13, none,       2027-05-01, 18", // and now overdue 4 more
            "2027-04-27, 2027-04-13, 2027-04-25, 2027-05-01, 12", // done: frozen at the actual end
            "2027-04-13, 2027-04-13, 2027-04-10, 2027-05-01, 0",  // done early
            "2027-04-10, 2027-04-13, none,       2027-04-11, 0",  // ahead of the baseline
            "2026-01-02, none,       none,       2026-01-05, 3",  // no baseline: against the current end
            "2026-01-05, none,       none,       2026-01-05, 0",  // due today
            "none,       none,       none,       2026-01-05, 0",  // no dates
    })
    void delay_days(LocalDate end, LocalDate baselineEnd, LocalDate actualEnd, LocalDate today, int delay) {
        assertThat(ScheduleDates.delayDays(end, baselineEnd, actualEnd, today)).isEqualTo(delay);
    }

    /** Rows: weight|start|end|progress separated by ';'. */
    @ParameterizedTest(name = "{0} @ {1} → 예정 {2} 진행 {3} 합 {4}")
    @CsvSource(nullValues = "none", delimiter = '|', value = {
            "60,2027-04-01,2027-04-10,50; 40,2027-04-11,2027-04-20,0 | 2027-04-05 | 30.0  | 30.0 | 100",
            "60,2027-04-01,2027-04-10,100; 40,2027-04-11,2027-04-20,25 | 2027-04-20 | 100.0 | 70.0 | 100",
            "60,2027-04-01,2027-04-10,0; 40,2027-04-11,2027-04-20,0 | 2027-03-31 | 0.0 | 0.0 | 100", // before everything
            "30,2027-04-01,2027-04-10,100; 30,2027-04-11,2027-04-20,0 | 2027-04-10 | 50.0 | 50.0 | 60", // sum ≠ 100: divided by 60
            "50,2027-04-01,2027-04-01,0; 50,none,none,0 | 2027-04-01 | 50.0 | 0.0 | 100", // a dateless item counts as not started
            ",2027-04-01,2027-04-10,50 | 2027-04-05 | none | none | none", // no weights → no 공정률
    })
    void progress(String rows, LocalDate today, BigDecimal planned, BigDecimal actual, BigDecimal total) {
        List<Weighted> items = Arrays.stream(rows.split(";")).map(String::trim).map(r -> r.split(",", -1)).map(c -> new Weighted(
                c[0].isBlank() ? null : new BigDecimal(c[0]), date(c[1]), date(c[2]), Integer.parseInt(c[3]))).toList();
        ScheduleDates.Progress p = ScheduleDates.progress(items, today);
        if (total == null) {
            assertThat(p).isNull();
        } else {
            assertThat(p).isEqualTo(new ScheduleDates.Progress(planned, actual, total));
        }
    }

    private static LocalDate date(String s) {
        return "none".equals(s) ? null : LocalDate.parse(s);
    }

    // --- paste parser ---------------------------------------------------------------------------------------

    static final Map<String, Long> DISCIPLINES = Map.of("공정·기계", 1L, "토목", 2L, "공통", 3L);
    static final Map<String, Long> MEMBERS = Map.of("이가온", 10L);

    @Test
    void paste_parses_tasks_and_milestones_and_skips_the_header() {
        List<SchedulePaste.Row> rows = SchedulePaste.parse("""
                분야\t작업\t시작\t종료\t담당\t보할
                공정·기계\tP&ID 1차\t2027-03-02\t2027.4.13\t이가온\t15
                \t중간보고\t\t2027/05/20\t\t

                토목\t토목 기본도\t2027-04-01\t2027-04-01\t\t5%
                """, DISCIPLINES, MEMBERS);
        assertThat(rows).extracting(SchedulePaste.Row::line).containsExactly(2, 3, 5);
        assertThat(rows.get(0)).isEqualTo(new SchedulePaste.Row(2, "공정·기계", 1L, "TASK", "P&ID 1차",
                LocalDate.of(2027, 3, 2), LocalDate.of(2027, 4, 13), "이가온", 10L, new BigDecimal("15"), null));
        assertThat(rows.get(1).kind()).isEqualTo("MILESTONE");
        assertThat(rows.get(1).disciplineId()).isNull(); // 공통
        assertThat(rows.get(1).start()).isEqualTo(LocalDate.of(2027, 5, 20));
        assertThat(rows.get(2).kind()).isEqualTo("MILESTONE"); // start = end
        assertThat(rows.get(2).weight()).isEqualByComparingTo("5");
        assertThat(rows).allMatch(r -> r.error() == null);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
            "건축\\tx\\t\\t2027-04-01               | 없는 분야: 건축",
            "토목\\t\\t\\t2027-04-01                | 작업명이 없습니다",
            "토목\\tx\\t\\t                         | 종료일이 없습니다",
            "토목\\tx\\t\\t2027-13-01               | 종료일 형식 오류",
            "토목\\tx\\t4월초\\t2027-04-01          | 시작일 형식 오류",
            "토목\\tx\\t2027-04-02\\t2027-04-01     | 시작일이 종료일보다 늦습니다",
            "토목\\tx\\t\\t2027-04-01\\t박다온      | 프로젝트 멤버가 아닌 담당: 박다온",
            "토목\\tx\\t\\t2027-04-01\\t\\t120      | 보할은 0~100",
            "토목\\tx\\t\\t2027-04-01\\t\\t많이     | 보할은 0~100",
    })
    void paste_reports_the_error_of_each_bad_row(String line, String error) {
        List<SchedulePaste.Row> rows = SchedulePaste.parse("토목\tok\t\t2027-04-01\n" + line.replace("\\t", "\t"),
                DISCIPLINES, MEMBERS);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).error()).isNull();
        assertThat(rows.get(1).line()).isEqualTo(2);
        assertThat(rows.get(1).error()).contains(error);
    }
}
