package com.envpermit.review;

import static com.envpermit.review.AiSuggestion.Status.ACCEPTED;
import static com.envpermit.review.AiSuggestion.Status.EDITED_ACCEPTED;
import static com.envpermit.review.AiSuggestion.Status.PENDING;
import static com.envpermit.review.AiSuggestion.Status.REJECTED;
import static com.envpermit.review.AiSuggestion.Status.SUBMITTED;

import com.envpermit.review.AiSuggestion.Status;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The review state machine (design §2.2) as a pure function. Wrong state → 409, missing can_self_approve → 403.
 * Without can_self_approve a member may only edit and submit: rejecting is as risky as approving.
 * Internal paths that bypass the permission check (SYSTEM re-run, pair restructure) live in {@link Review}.
 */
final class Transition {

    enum Action { EDIT, SUBMIT, ACCEPT, REJECT, RETURN, REOPEN }

    private Transition() {
    }

    static Status next(Status from, Action action, boolean canSelfApprove, boolean edited) {
        Set<Status> allowed = switch (action) {
            case EDIT, SUBMIT -> Set.of(PENDING);
            case ACCEPT, REJECT -> Set.of(PENDING, SUBMITTED);
            case RETURN -> Set.of(SUBMITTED);
            case REOPEN -> Set.of(REJECTED);
        };
        if (!allowed.contains(from)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "현재 상태(" + from + ")에서는 할 수 없는 동작입니다.");
        }
        boolean needsSelfApprove = action != Action.EDIT && action != Action.SUBMIT;
        if (needsSelfApprove && !canSelfApprove) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "혼자 승인 권한이 없습니다. 승인 요청을 보내세요.");
        }
        return switch (action) {
            case EDIT, RETURN, REOPEN -> PENDING;
            case SUBMIT -> SUBMITTED;
            case ACCEPT -> edited ? EDITED_ACCEPTED : ACCEPTED;
            case REJECT -> REJECTED;
        };
    }
}
