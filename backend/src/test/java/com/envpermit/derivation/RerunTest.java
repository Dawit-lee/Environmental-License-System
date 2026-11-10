package com.envpermit.derivation;

import static org.assertj.core.api.Assertions.assertThat;

import com.envpermit.derivation.Evaluator.Determination;
import com.envpermit.derivation.Rerun.Action;
import com.envpermit.derivation.Rerun.Prev;
import com.envpermit.review.AiSuggestion.Status;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Re-run policy table (design §4.3) and required_by_date (§4.5), row by row. */
class RerunTest {

    /** prev: status/bySystem/determination/reason ("-" = no previous suggestion). */
    @ParameterizedTest
    @CsvSource(textBlock = """
            -,                 false, -,              -,  APPLICABLE,     a, CREATE
            -,                 false, -,              -,  ON_HOLD,        a, CREATE
            -,                 false, -,              -,  NOT_APPLICABLE, a, NONE
            PENDING,           false, APPLICABLE,     a,  APPLICABLE,     a, NONE
            PENDING,           false, APPLICABLE,     a,  APPLICABLE,     b, SUPERSEDE
            PENDING,           false, ON_HOLD,        a,  APPLICABLE,     a, SUPERSEDE
            PENDING,           false, APPLICABLE,     a,  NOT_APPLICABLE, a, CLOSE
            SUBMITTED,         false, ON_HOLD,        a,  ON_HOLD,        a, NONE
            SUBMITTED,         false, ON_HOLD,        a,  APPLICABLE,     a, CREATE_WITH_WARNING
            SUBMITTED,         false, ON_HOLD,        a,  NOT_APPLICABLE, a, NONE
            ACCEPTED,          false, APPLICABLE,     a,  APPLICABLE,     b, NONE
            EDITED_ACCEPTED,   false, ON_HOLD,        a,  APPLICABLE,     a, NONE
            ACCEPTED,          false, APPLICABLE,     a,  NOT_APPLICABLE, a, WARN_NOT_APPLICABLE
            EDITED_ACCEPTED,   false, ON_HOLD,        a,  NOT_APPLICABLE, a, WARN_NOT_APPLICABLE
            ACCEPTED,          false, APPLICABLE,     a,  ON_HOLD,        a, NONE
            REJECTED,          false, APPLICABLE,     a,  APPLICABLE,     b, NONE
            REJECTED,          false, ON_HOLD,        a,  APPLICABLE,     a, CREATE
            REJECTED,          false, ON_HOLD,        a,  NOT_APPLICABLE, a, NONE
            REJECTED,          true,  APPLICABLE,     a,  APPLICABLE,     a, CREATE
            REJECTED,          true,  APPLICABLE,     a,  NOT_APPLICABLE, a, NONE
            """)
    void policy(String status, boolean bySystem, String prevDet, String prevReason, Determination now,
                String nowReason, Action expected) {
        Prev prev = status.equals("-") ? null
                : new Prev(Status.valueOf(status), bySystem, Determination.valueOf(prevDet), prevReason);
        assertThat(Rerun.decide(prev, now, nowReason)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            CONSTRUCTION_START,   0, 2027-03-01, 2027-03-01
            CONSTRUCTION_START, -30, 2027-03-01, 2027-01-30
            CONSTRUCTION_START,  10, 2027-03-01, 2027-03-11
            CONSTRUCTION_START, -30,           ,
            OPERATION_START,    -30, 2027-03-01,
            OTHER,                0, 2027-03-01,
            """)
    void requiredByDate(String gate, int offset, LocalDate start, LocalDate expected) {
        var requiredBy = new ObjectMapper().createObjectNode().put("gate", gate).put("offset_days", offset)
                .put("text", "-");
        assertThat(Rerun.requiredByDate(requiredBy, start)).isEqualTo(expected);
        assertThat(Rerun.requiredByDate(null, start)).isNull();
    }

}
