package com.envpermit.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.envpermit.review.AiSuggestion.Status;
import com.envpermit.review.Transition.Action;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.server.ResponseStatusException;

class TransitionTest {

    // design §2.2: state × action × can_self_approve (× edited for ACCEPT). 409 = wrong state, 403 = no permission.
    @ParameterizedTest(name = "{0} {1} self={2} edited={3} → {4}")
    @CsvSource({
            "PENDING,         EDIT,   false, false, PENDING",
            "PENDING,         EDIT,   true,  false, PENDING",
            "SUBMITTED,       EDIT,   true,  false, 409",
            "ACCEPTED,        EDIT,   true,  false, 409",
            "REJECTED,        EDIT,   true,  false, 409",
            "PENDING,         SUBMIT, false, false, SUBMITTED",
            "PENDING,         SUBMIT, true,  false, SUBMITTED",
            "SUBMITTED,       SUBMIT, false, false, 409",
            "REJECTED,        SUBMIT, false, false, 409",
            "PENDING,         ACCEPT, true,  false, ACCEPTED",
            "PENDING,         ACCEPT, true,  true,  EDITED_ACCEPTED",
            "PENDING,         ACCEPT, false, false, 403",
            "SUBMITTED,       ACCEPT, true,  false, ACCEPTED",
            "SUBMITTED,       ACCEPT, true,  true,  EDITED_ACCEPTED",
            "SUBMITTED,       ACCEPT, false, false, 403",
            "ACCEPTED,        ACCEPT, true,  false, 409",
            "EDITED_ACCEPTED, ACCEPT, true,  true,  409",
            "REJECTED,        ACCEPT, true,  false, 409",
            "PENDING,         REJECT, true,  false, REJECTED",
            "PENDING,         REJECT, false, false, 403",
            "SUBMITTED,       REJECT, true,  false, REJECTED",
            "SUBMITTED,       REJECT, false, false, 403",
            "ACCEPTED,        REJECT, true,  false, 409",
            "REJECTED,        REJECT, true,  false, 409",
            "SUBMITTED,       RETURN, true,  false, PENDING",
            "SUBMITTED,       RETURN, false, false, 403",
            "PENDING,         RETURN, true,  false, 409",
            "REJECTED,        REOPEN, true,  false, PENDING",
            "REJECTED,        REOPEN, false, false, 403",
            "PENDING,         REOPEN, true,  false, 409",
            "SUBMITTED,       REOPEN, true,  false, 409",
            "ACCEPTED,        REOPEN, true,  false, 409",
            "EDITED_ACCEPTED, REOPEN, true,  false, 409"})
    void transition_table(Status from, Action action, boolean selfApprove, boolean edited, String expected) {
        assertThat(outcome(from, action, selfApprove, edited)).isEqualTo(expected);
    }

    private static String outcome(Status from, Action action, boolean selfApprove, boolean edited) {
        try {
            return Transition.next(from, action, selfApprove, edited).name();
        } catch (ResponseStatusException e) {
            return String.valueOf(e.getStatusCode().value());
        }
    }
}
