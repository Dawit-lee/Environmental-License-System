package com.envpermit.review;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One reviewable unit. Status changes go through {@link Review}; see {@link Transition} for the rules. */
@Entity
public class AiSuggestion {

    public enum Status { PENDING, SUBMITTED, ACCEPTED, EDITED_ACCEPTED, REJECTED }
    public enum Origin { AI, MANUAL }
    public enum Decision { ACCEPT, REJECT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long companyId;
    private Long projectId;
    private Long runId;
    @Enumerated(EnumType.STRING)
    private Kind kind;
    private Long targetParentId;
    @Enumerated(EnumType.STRING)
    private Origin origin;
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode payload;
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode editedPayload;
    @Enumerated(EnumType.STRING)
    private Status status;
    @Enumerated(EnumType.STRING)
    private Decision proposedDecision;
    private Long submittedBy;
    private Instant submittedAt;
    private Long reviewedBy;
    private Instant reviewedAt;
    private String rejectReason;
    private Long appliedEntityId;
    @Version
    private long version;

    protected AiSuggestion() {
    }

    AiSuggestion(long companyId, long projectId, long runId, Kind kind, Long targetParentId, Origin origin,
                 JsonNode payload) {
        this.companyId = companyId;
        this.projectId = projectId;
        this.runId = runId;
        this.kind = kind;
        this.targetParentId = targetParentId;
        this.origin = origin;
        this.payload = payload;
        this.status = Status.PENDING;
    }

    public JsonNode effectivePayload() {
        return editedPayload != null ? editedPayload : payload;
    }

    void edit(JsonNode edited) {
        this.editedPayload = edited;
    }

    void submit(Decision decision, long userId, Instant now) {
        this.status = Status.SUBMITTED;
        this.proposedDecision = decision;
        this.submittedBy = userId;
        this.submittedAt = now;
    }

    void decide(Status next, Long userId, Instant now, String rejectReason, Long appliedEntityId) {
        this.status = next;
        this.reviewedBy = userId;
        this.reviewedAt = now;
        this.rejectReason = rejectReason;
        this.appliedEntityId = appliedEntityId;
    }

    /** RETURNED / REOPENED: back to PENDING, previous decision data cleared. */
    void backToPending() {
        this.status = Status.PENDING;
        this.proposedDecision = null;
        this.rejectReason = null;
    }

    public Long getId() { return id; }
    public Long getCompanyId() { return companyId; }
    public Long getProjectId() { return projectId; }
    public Long getRunId() { return runId; }
    public Kind getKind() { return kind; }
    public Long getTargetParentId() { return targetParentId; }
    public Origin getOrigin() { return origin; }
    public JsonNode getPayload() { return payload; }
    public JsonNode getEditedPayload() { return editedPayload; }
    public Status getStatus() { return status; }
    public Decision getProposedDecision() { return proposedDecision; }
    public Long getSubmittedBy() { return submittedBy; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Long getReviewedBy() { return reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public String getRejectReason() { return rejectReason; }
    public Long getAppliedEntityId() { return appliedEntityId; }
    public long getVersion() { return version; }
}
