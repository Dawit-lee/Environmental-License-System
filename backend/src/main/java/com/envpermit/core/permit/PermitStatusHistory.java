package com.envpermit.core.permit;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

/** Append-only log of permit status changes. fromStatus is null for the creation entry. */
@Entity
public class PermitStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long permitItemId;

    @Enumerated(EnumType.STRING)
    private PermitItem.Status fromStatus;

    @Enumerated(EnumType.STRING)
    private PermitItem.Status toStatus;

    private Long changedBy;
    private Instant changedAt;

    protected PermitStatusHistory() {
    }

    PermitStatusHistory(Long permitItemId, PermitItem.Status from, PermitItem.Status to, Long changedBy,
                        Instant changedAt) {
        this.permitItemId = permitItemId;
        this.fromStatus = from;
        this.toStatus = to;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
    }

    public Long getId() { return id; }
    public Long getPermitItemId() { return permitItemId; }
    public PermitItem.Status getFromStatus() { return fromStatus; }
    public PermitItem.Status getToStatus() { return toStatus; }
    public Long getChangedBy() { return changedBy; }
    public Instant getChangedAt() { return changedAt; }
}
