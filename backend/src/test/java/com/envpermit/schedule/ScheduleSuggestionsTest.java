package com.envpermit.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.envpermit.schedule.ScheduleSuggestion.Interval;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Pure rules of design-schedule/07: title similarity and RECURRING steps. */
class ScheduleSuggestionsTest {

    @Test
    void titles_match_ignoring_spaces_and_punctuation_or_by_containment() {
        assertThat(ScheduleSuggestions.similarTitle("배관평면도 제출", "배관 평면도 제출")).isTrue();
        assertThat(ScheduleSuggestions.similarTitle("중간보고회", "중간 보고회(2차)")).isTrue();
        assertThat(ScheduleSuggestions.similarTitle("착수보고회", "최종보고회")).isFalse();
        assertThat(ScheduleSuggestions.similarTitle("가", "가나다")).isFalse(); // one letter is not a title
    }

    @Test
    void recurring_steps_count_from_the_start_so_month_ends_stay() {
        LocalDate jan31 = LocalDate.of(2027, 1, 31);
        assertThat(ProjectEventApplier.step(jan31, Interval.MONTHLY, 1)).isEqualTo("2027-02-28");
        assertThat(ProjectEventApplier.step(jan31, Interval.MONTHLY, 2)).isEqualTo("2027-03-31");
        assertThat(ProjectEventApplier.step(jan31, Interval.QUARTERLY, 1)).isEqualTo("2027-04-30");
        assertThat(ProjectEventApplier.step(jan31, Interval.BIWEEKLY, 1)).isEqualTo("2027-02-14");
        assertThat(ProjectEventApplier.step(jan31, Interval.WEEKLY, 2)).isEqualTo("2027-02-14");
    }
}
