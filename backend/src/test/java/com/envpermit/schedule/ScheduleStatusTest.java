package com.envpermit.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.envpermit.schedule.ScheduleStatus.Status;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ScheduleStatusTest {

    static final LocalDate TODAY = LocalDate.of(2027, 4, 13);

    @ParameterizedTest(name = "{0}~{1} actual {2} → {3} +{4}")
    @CsvSource(nullValues = "none", value = {
            "2027-04-14, 2027-04-20, none,       PLANNED,     0",
            "2027-04-13, 2027-04-20, none,       IN_PROGRESS, 0", // starts today
            "2027-04-01, 2027-04-13, none,       IN_PROGRESS, 0", // ends today: not late yet
            "2027-04-01, 2027-04-12, none,       DELAYED,     1",
            "2027-03-01, 2027-03-14, none,       DELAYED,     30",
            "2027-04-01, 2027-04-12, 2027-04-10, DONE,        0", // finished early
            "2027-04-01, 2027-04-12, 2027-04-15, DONE,        3", // finished late: delay frozen at the actual end
            "2027-05-01, 2027-05-01, 2027-04-30, DONE,        0", // done before even starting
    })
    void status_and_delay(LocalDate start, LocalDate end, LocalDate actualEnd, Status status, int delay) {
        assertThat(ScheduleStatus.of(start, end, actualEnd, TODAY)).isEqualTo(new ScheduleStatus.Computed(status, delay));
    }

    @ParameterizedTest(name = "{0} + {1}→{2} cycle={3}")
    @CsvSource(delimiter = '|', value = {
            "              | 1 | 2 | false",
            "1>2           | 2 | 1 | true",
            "1>2,2>3       | 3 | 1 | true",
            "1>2,2>3       | 1 | 3 | false", // redundant, not a cycle
            "1>2,3>4       | 4 | 1 | false",
            "1>2,2>3,3>4   | 4 | 2 | true",
            "1>2,1>3,3>4   | 4 | 1 | true",
    })
    void cycle_check(String edges, long pred, long succ, boolean cycle) {
        List<long[]> list = edges == null ? List.of() : java.util.Arrays.stream(edges.split(","))
                .map(e -> new long[] {Long.parseLong(e.split(">")[0]), Long.parseLong(e.split(">")[1])}).toList();
        assertThat(ScheduleStatus.createsCycle(list, pred, succ)).isEqualTo(cycle);
    }
}
