package com.envpermit.obligation;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A live obligation under a permit item. Created only by {@link ObligationApplier} (or direct entry later). */
@Entity
public class PermitObligation {

    public enum Status { ACTIVE, CLOSED }

    public enum ClosedReason { MANUAL, REVISION_REMOVED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long permitItemId;
    private Long sourceDocumentId;
    private Long suggestionId;
    private String citationRaw;
    private String citationKey;
    private boolean citationSynthetic;
    private String requirementText;
    private String summary;
    @Enumerated(EnumType.STRING)
    private ObligationPayload.Type type;
    @Enumerated(EnumType.STRING)
    private ObligationPayload.Category category;
    private String responsibleParty;
    @Enumerated(EnumType.STRING)
    private Frequency frequency;
    private String frequencyText;
    private String periodText;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> legalBasis;
    private Integer pageNo;
    private String sourceQuote;
    @Enumerated(EnumType.STRING)
    private ObligationPayload.QuoteCheck quoteCheck;
    private LocalDate firstDueDate;
    private LocalDate nextDueDate;
    @Enumerated(EnumType.STRING)
    private Status status;
    @Enumerated(EnumType.STRING)
    private ClosedReason closedReason;
    private Instant closedAt;
    private Long defaultAssigneeId; // assignee of each new cycle's task; null → project EM → PM
    @Version
    private long version; // live edits and revision approval both bump it (④ V11)

    protected PermitObligation() {
    }

    PermitObligation(long permitItemId, Long sourceDocumentId, Long suggestionId, ObligationPayload p,
                     LocalDate firstDueDate) {
        this.permitItemId = permitItemId;
        this.sourceDocumentId = sourceDocumentId;
        this.suggestionId = suggestionId;
        this.citationRaw = p.citationRaw();
        this.citationKey = p.citationKey();
        this.citationSynthetic = p.citationSynthetic();
        this.requirementText = p.requirementText();
        this.summary = p.summary();
        this.type = p.type();
        this.category = p.category();
        this.responsibleParty = p.responsibleParty();
        this.frequency = p.frequency();
        this.frequencyText = p.frequencyText();
        this.periodText = p.periodText();
        this.legalBasis = p.legalBasis() == null ? List.of() : p.legalBasis();
        this.pageNo = p.pageNo();
        this.sourceQuote = p.sourceQuote();
        this.quoteCheck = p.quoteCheck();
        this.firstDueDate = this.nextDueDate = firstDueDate;
        this.status = Status.ACTIVE;
    }

    void assignDefault(Long userId) {
        this.defaultAssigneeId = userId;
    }

    void moveTo(LocalDate next) {
        this.nextDueDate = next;
    }

    void close(Instant now) {
        this.status = Status.CLOSED;
        this.closedReason = ClosedReason.MANUAL;
        this.closedAt = now;
        this.nextDueDate = null;
    }

    /** ④ CHANGED: every payload field and the source document; id (and so the fulfilment history) stays. */
    void revise(ObligationPayload p, Long sourceDocumentId) {
        reword(p, sourceDocumentId);
        this.type = p.type();
        this.category = p.category();
        this.responsibleParty = p.responsibleParty();
        this.frequency = p.frequency();
        this.frequencyText = p.frequencyText();
        this.periodText = p.periodText();
        this.legalBasis = p.legalBasis() == null ? List.of() : p.legalBasis();
    }

    /** ④ MINOR / UNCHANGED: wording, citation and source move to the new version; deadlines are untouched. */
    void reword(ObligationPayload p, Long sourceDocumentId) {
        this.sourceDocumentId = sourceDocumentId;
        this.citationRaw = p.citationRaw();
        this.citationKey = p.citationKey();
        this.citationSynthetic = p.citationSynthetic();
        this.requirementText = p.requirementText();
        this.summary = p.summary();
        this.pageNo = p.pageNo();
        this.sourceQuote = p.sourceQuote();
        this.quoteCheck = p.quoteCheck();
    }

    /** ④ REMOVED: closed for good by the revision; the row (and what was fulfilled) is kept. */
    void closeByRevision(Instant now) {
        this.status = Status.CLOSED;
        this.closedReason = ClosedReason.REVISION_REMOVED;
        this.closedAt = now;
        this.nextDueDate = null;
    }

    /** The row as an OBLIGATION payload (version diff compares these). */
    public ObligationPayload payload() {
        return new ObligationPayload(citationRaw, citationKey, citationSynthetic, requirementText, summary, type,
                category, responsibleParty, frequency, frequencyText, periodText, legalBasis, pageNo, sourceQuote,
                quoteCheck);
    }

    public Long getId() { return id; }
    public Long getPermitItemId() { return permitItemId; }
    public Long getSourceDocumentId() { return sourceDocumentId; }
    public Long getSuggestionId() { return suggestionId; }
    public String getCitationRaw() { return citationRaw; }
    public String getCitationKey() { return citationKey; }
    public String getSummary() { return summary; }
    public ObligationPayload.Type getType() { return type; }
    public Frequency getFrequency() { return frequency; }
    public LocalDate getFirstDueDate() { return firstDueDate; }
    public LocalDate getNextDueDate() { return nextDueDate; }
    public Long getDefaultAssigneeId() { return defaultAssigneeId; }
    public Status getStatus() { return status; }
    public ClosedReason getClosedReason() { return closedReason; }
    public long getVersion() { return version; }
}
