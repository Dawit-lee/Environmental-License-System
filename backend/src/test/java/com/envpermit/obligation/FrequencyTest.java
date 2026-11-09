package com.envpermit.obligation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FrequencyTest {

    // design §3.7 month-end table: clamp to the target month's last day, next cycle counts from the clamped date
    @ParameterizedTest
    @CsvSource({
            "2027-01-31, MONTHLY,    2027-02-28",
            "2028-01-31, MONTHLY,    2028-02-29",
            "2027-02-28, MONTHLY,    2027-03-28",
            "2027-08-31, QUARTERLY,  2027-11-30",
            "2027-08-31, SEMIANNUAL, 2028-02-29",
            "2028-02-29, ANNUAL,     2029-02-28",
            "2027-03-15, MONTHLY,    2027-04-15"})
    void next_due_date_moves_by_frequency_clamped_to_month_end(LocalDate current, Frequency f, LocalDate expected) {
        assertThat(f.next(current)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"ONCE", "OTHER"})
    void once_and_other_have_no_automatic_next(Frequency f) {
        assertThat(f.next(LocalDate.of(2027, 1, 31))).isNull();
    }
}
