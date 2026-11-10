package com.envpermit.core.signup;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Duration;
import java.time.Instant;

/** Someone asking to join a company by its invite code. Not a user until a company admin approves. */
@Entity
public class JoinRequest {

    public enum Status { PENDING, APPROVED, REJECTED, EXPIRED }

    static final Duration TTL = Duration.ofDays(14);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long companyId;
    private String email;
    private String name;
    private String passwordHash;
    @Enumerated(EnumType.STRING)
    private Status status;
    private String rejectReason;
    private String ip;
    private Instant createdAt;
    private Long decidedBy;
    private Instant decidedAt;

    protected JoinRequest() {
    }

    JoinRequest(long companyId, String email, String name, String passwordHash, String ip, Instant now) {
        this.companyId = companyId;
        this.email = email;
        this.name = name;
        this.passwordHash = passwordHash;
        this.ip = ip;
        this.createdAt = now;
        this.status = Status.PENDING;
    }

    /** Pending and not yet 14 days old. */
    public boolean isOpen(Instant now) {
        return status == Status.PENDING && createdAt.plus(TTL).isAfter(now);
    }

    void decide(Status status, String rejectReason, long by, Instant now) {
        this.status = status;
        this.rejectReason = rejectReason;
        this.decidedBy = by;
        this.decidedAt = now;
    }

    public Long getId() { return id; }
    public Long getCompanyId() { return companyId; }
    public String getEmail() { return email; }
    public String getName() { return name; }
    public String getPasswordHash() { return passwordHash; }
    public Status getStatus() { return status; }
    public String getRejectReason() { return rejectReason; }
    public Instant getCreatedAt() { return createdAt; }
}
