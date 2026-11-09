package com.envpermit.review;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The only way a suggestion reaches live tables. Runs inside the approval transaction: any exception rolls
 * back the live rows, the status change and the event together. Add a kind = add an enum value + a bean.
 */
public interface SuggestionApplier {

    Kind kind();

    /** Where the suggestion attaches (e.g. the permit item); throw 422 if it is not in the project. */
    void checkTarget(long projectId, Long targetParentId);

    /** Throw 422 with field errors when the payload is not acceptable. */
    void validate(JsonNode effectivePayload);

    /** Create the live row(s); returns the live row id. */
    long apply(AiSuggestion s, JsonNode effectivePayload, ApplyContext ctx);

    record ApplyContext(Long actorId, LocalDate firstDueDate, Instant now) {
    }
}
