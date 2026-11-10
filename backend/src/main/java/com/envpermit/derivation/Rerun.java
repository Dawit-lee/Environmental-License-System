package com.envpermit.derivation;

import com.envpermit.derivation.Evaluator.Determination;
import com.envpermit.review.AiSuggestion.Status;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.Objects;

/** Pure re-run rules: the policy table (design §4.3) and required_by_date (§4.5). */
final class Rerun {

    enum Action {
        NONE, CREATE, CREATE_WITH_WARNING, SUPERSEDE, CLOSE, WARN_NOT_APPLICABLE
    }

    /** The latest suggestion of the same rule_key in this project, as the engine wrote it. */
    record Prev(Status status, boolean bySystem, Determination determination, String reason) {
    }

    private Rerun() {
    }

    static Action decide(Prev prev, Determination now, String reason) {
        boolean suggestable = now == Determination.APPLICABLE || now == Determination.ON_HOLD;
        // a SYSTEM-closed suggestion counts as "nothing raised": only people's decisions are respected
        if (prev == null || prev.status() == Status.REJECTED && prev.bySystem()) {
            return suggestable ? Action.CREATE : Action.NONE;
        }
        boolean same = prev.determination() == now && Objects.equals(prev.reason(), reason);
        return switch (prev.status()) {
            case PENDING -> same ? Action.NONE : suggestable ? Action.SUPERSEDE : Action.CLOSE;
            case SUBMITTED -> same || !suggestable ? Action.NONE : Action.CREATE_WITH_WARNING;
            case ACCEPTED, EDITED_ACCEPTED -> now == Determination.NOT_APPLICABLE ? Action.WARN_NOT_APPLICABLE : Action.NONE;
            case REJECTED -> prev.determination() == now || !suggestable ? Action.NONE : Action.CREATE;
        };
    }

    /** construction_start + offset_days for the CONSTRUCTION_START gate; other gates show text only in v1. */
    static LocalDate requiredByDate(JsonNode requiredBy, LocalDate constructionStart) {
        if (requiredBy == null || constructionStart == null
                || !"CONSTRUCTION_START".equals(requiredBy.path("gate").asText())) {
            return null;
        }
        return constructionStart.plusDays(requiredBy.path("offset_days").asInt());
    }
}
