package com.envpermit.operation;

import static org.assertj.core.api.Assertions.assertThat;

import com.envpermit.operation.ComplianceRate.Counts;
import com.envpermit.operation.ComplianceRate.Task;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** design §5.1. Period 2026-01-01 ~ 2026-06-30, today 2026-06-15 unless a row says otherwise. */
class ComplianceRateTest {

    static final LocalDate FROM = d("2026-01-01");
    static final LocalDate TO = d("2026-06-30");
    static final LocalDate TODAY = d("2026-06-15");

    // expected: due, onTime, late, percent, overdue, openCorrective, dueSoon
    static Stream<Arguments> cases() {
        return Stream.of(
                row("no tasks: no rate, neither 0 nor 100", List.of(), 0, 0, 0, null, 0, 0, 0),
                row("only a future due date: still no rate", List.of(open("2026-06-20")), 0, 0, 0, null, 0, 0, 1),
                row("only cancelled: no rate", List.of(t("OBLIGATION", "CANCELLED", "2026-03-31", null)), 0, 0, 0, null, 0, 0, 0),
                row("done before the due date", List.of(done("2026-03-31", "2026-03-20")), 1, 1, 0, 100.0, 0, 0, 0),
                row("done on the due date is on time", List.of(done("2026-03-31", "2026-03-31")), 1, 1, 0, 100.0, 0, 0, 0),
                row("done a day late: in the denominator only", List.of(done("2026-03-31", "2026-04-01")), 1, 0, 1, 0.0, 0, 0, 0),
                row("overdue and still open", List.of(open("2026-06-14")), 1, 0, 0, 0.0, 1, 0, 0),
                row("due today and open: counted as due, not yet overdue", List.of(open("2026-06-15")), 1, 0, 0, 0.0, 0, 0, 0),
                row("cancelled is left out of both sides",
                        List.of(done("2026-03-31", "2026-03-31"), t("OBLIGATION", "CANCELLED", "2026-03-31", null)), 1, 1, 0, 100.0, 0, 0, 0),
                row("corrective tasks count like obligation tasks", List.of(
                        t("CORRECTIVE", "DONE", "2026-02-10", "2026-02-09"), t("CORRECTIVE", "DONE", "2026-02-10", "2026-02-12"),
                        t("CORRECTIVE", "OPEN", "2026-06-01", null), t("CORRECTIVE", "OPEN", "2026-06-22", null)), 3, 1, 1, 33.3, 1, 2, 1),
                row("period start is inclusive, the day before is out",
                        List.of(done("2026-01-01", "2026-01-01"), done("2025-12-31", "2026-01-05")), 1, 1, 0, 100.0, 0, 0, 0),
                row("a period ending in the future stops at today",
                        List.of(done("2026-06-15", "2026-06-15"), done("2026-06-16", "2026-06-10")), 1, 1, 0, 100.0, 0, 0, 0),
                row("due soon: tomorrow to today + 30, open only", List.of(
                        open("2026-06-16"), open("2026-07-15"), open("2026-07-16"), done("2026-06-20", "2026-06-10")), 0, 0, 0, null, 0, 0, 2),
                row("an old overdue task is 지연 중 even when outside the period", List.of(open("2025-11-30")), 0, 0, 0, null, 1, 0, 0),
                row("mixed: 2 on time of 5 due", List.of(
                        done("2026-01-31", "2026-01-31"), done("2026-02-28", "2026-02-27"), done("2026-03-31", "2026-04-02"),
                        open("2026-04-30"), open("2026-05-31"), open("2026-06-30"),
                        t("OBLIGATION", "CANCELLED", "2026-05-31", null)), 5, 2, 1, 40.0, 2, 0, 1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void counts(String name, List<Task> tasks, Counts expected) {
        assertThat(ComplianceRate.of(tasks, FROM, TO, TODAY)).isEqualTo(expected);
    }

    @Test
    void a_period_that_ended_is_cut_at_its_end_not_at_today() {
        List<Task> tasks = List.of(done("2026-03-31", "2026-03-31"), done("2026-04-01", "2026-04-05"));
        assertThat(ComplianceRate.of(tasks, FROM, d("2026-03-31"), TODAY).percent()).isEqualTo(100.0);
        assertThat(ComplianceRate.of(tasks, d("2026-04-01"), d("2026-04-30"), TODAY).percent()).isEqualTo(0.0);
    }

    @Test
    void the_company_rate_adds_counts_and_is_not_the_mean_of_project_rates() {
        List<Task> a = List.of(done("2026-03-31", "2026-03-31")); // 1/1 = 100%
        List<Task> b = List.of(done("2026-03-31", "2026-03-31"), open("2026-04-30"), open("2026-05-31"), open("2026-06-01")); // 1/4 = 25%
        assertThat(ComplianceRate.of(a, FROM, TO, TODAY).percent()).isEqualTo(100.0);
        assertThat(ComplianceRate.of(b, FROM, TO, TODAY).percent()).isEqualTo(25.0);
        assertThat(ComplianceRate.of(Stream.concat(a.stream(), b.stream()).toList(), FROM, TO, TODAY).percent())
                .isEqualTo(40.0); // 2/5, not (100 + 25) / 2 = 62.5
    }

    private static Arguments row(String name, List<Task> tasks, int due, int onTime, int late, Double percent,
                                 int overdue, int openCorrective, int dueSoon) {
        return Arguments.of(name, tasks, new Counts(due, onTime, late, percent, overdue, openCorrective, dueSoon));
    }

    private static Task open(String due) {
        return t("OBLIGATION", "OPEN", due, null);
    }

    private static Task done(String due, String doneOn) {
        return t("OBLIGATION", "DONE", due, doneOn);
    }

    private static Task t(String kind, String status, String due, String doneOn) {
        return new Task(kind, status, d(due), doneOn == null ? null : d(doneOn));
    }

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }
}
