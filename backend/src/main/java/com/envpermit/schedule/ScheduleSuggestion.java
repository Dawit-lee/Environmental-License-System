package com.envpermit.schedule;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * One row of a document → schedule extraction (설계 §4.1): the ai_suggestion payload of kind SCHEDULE_ITEM
 * (MILESTONE, TASK) or PROJECT_EVENT (EVENT, RECURRING). A relative deadline stays anchor + offset, never a date.
 * Server-set: quote_check, checks (확인 필요), matchId (기존 항목 갱신 — the default when a similar item exists; the
 * reviewer clears it and sets createNew to add a new one instead), documentName (default delay reason).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScheduleSuggestion(@NotNull Type type, String kind, @Size(max = 50) String subkind,
                                 @NotBlank @Size(max = 200) String title, String discipline,
                                 LocalDate date, LocalDate startDate, LocalDateTime startsAt, LocalDateTime endsAt,
                                 @Size(max = 200) String location, @Size(max = 200) String counterpartOrg,
                                 @Size(max = 500) String purpose, String anchor, Integer offsetDays,
                                 Integer offsetMonths, Integer durationDays, String rule, Interval interval,
                                 boolean yearInferred, @NotNull @Valid Evidence evidence, String confidence,
                                 @JsonProperty("quote_check") String quoteCheck, List<String> checks, Long matchId,
                                 Boolean createNew, @Size(max = 500) String reason, String documentName) {

    public enum Type { EVENT, MILESTONE, TASK, RECURRING }

    public enum Interval { WEEKLY, BIWEEKLY, MONTHLY, QUARTERLY }

    /** 확인 필요 labels shown on the review screen. */
    public static final String YEAR_INFERRED = "YEAR_INFERRED";
    public static final String RELATIVE_RULE = "RELATIVE_RULE";
    public static final String BASE_DATE_MISSING = "BASE_DATE_MISSING";

    /** Where the quote is: physical PDF page (1-based) and the sentence as written. Required. */
    public record Evidence(@NotNull @Positive Integer page, @NotBlank String quote) {
    }

    ScheduleSuggestion annotated(Long matchId, List<String> checks) {
        return new ScheduleSuggestion(type, kind, subkind, title, discipline, date, startDate, startsAt, endsAt,
                location, counterpartOrg, purpose, anchor, offsetDays, offsetMonths, durationDays, rule, interval,
                yearInferred, evidence, confidence, quoteCheck, checks.isEmpty() ? null : checks, matchId, createNew,
                reason, documentName);
    }

    /** 422 with the field name when the payload is not a row (Applier validate). */
    static ScheduleSuggestion parse(ObjectMapper json, Validator validator, JsonNode payload) {
        ScheduleSuggestion r;
        try {
            r = json.treeToValue(payload, ScheduleSuggestion.class);
        } catch (JsonProcessingException e) {
            String field = e instanceof JsonMappingException m && !m.getPath().isEmpty()
                    ? m.getPath().get(0).getFieldName() : "payload";
            throw unprocessable(field + ": 형식이 잘못되었습니다.");
        }
        String errors = validator.validate(r).stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted().collect(Collectors.joining(", "));
        if (!errors.isEmpty()) {
            throw unprocessable(errors);
        }
        return r;
    }

    static ResponseStatusException unprocessable(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
