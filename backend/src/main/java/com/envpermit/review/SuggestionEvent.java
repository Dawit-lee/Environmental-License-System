package com.envpermit.review;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only audit row (the DB rejects UPDATE/DELETE). actorId null = SYSTEM. */
@Entity
@Immutable
@Table(name = "ai_suggestion_event")
public class SuggestionEvent {

    public enum Type { CREATED, EDITED, PAGE_VIEWED, SUBMITTED, RETURNED, ACCEPTED, REJECTED, REOPENED,
        UNPAIRED, PAIRED, REDIFFED } // ④ pair restructure / rediff

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long suggestionId;
    private Long actorId;
    private Instant at;
    @Enumerated(EnumType.STRING)
    private Type type;
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode data;

    protected SuggestionEvent() {
    }

    SuggestionEvent(long suggestionId, Long actorId, Instant at, Type type, JsonNode data) {
        this.suggestionId = suggestionId;
        this.actorId = actorId;
        this.at = at;
        this.type = type;
        this.data = data;
    }

    /** PAGE_VIEWED of that page, by that user (null = anyone). */
    boolean isPageView(int pageNo, Long userId) {
        return type == Type.PAGE_VIEWED && data != null && data.path("page_no").asInt() == pageNo
                && (userId == null || userId.equals(actorId));
    }

    public Long getId() { return id; }
    public Long getActorId() { return actorId; }
    public Instant getAt() { return at; }
    public Type getType() { return type; }
    public JsonNode getData() { return data; }
}
