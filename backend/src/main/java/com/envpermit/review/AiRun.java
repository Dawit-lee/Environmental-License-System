package com.envpermit.review;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

/** One AI run (prompt→response audit unit). Manually added suggestions hang off a per-project MANUAL run. */
@Entity
public class AiRun {

    public static final String MANUAL = "MANUAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long companyId;
    private Long projectId;
    @Enumerated(EnumType.STRING)
    private Kind kind;
    private String preparer;
    private String status;
    private Long startedBy;
    private Instant startedAt;
    private Instant finishedAt;

    protected AiRun() {
    }

    static AiRun manual(long companyId, long projectId, Kind kind, long userId, Instant now) {
        AiRun r = new AiRun();
        r.companyId = companyId;
        r.projectId = projectId;
        r.kind = kind;
        r.preparer = MANUAL;
        r.status = "SUCCEEDED";
        r.startedBy = userId;
        r.startedAt = r.finishedAt = now;
        return r;
    }

    public Long getId() { return id; }
    public Long getProjectId() { return projectId; }
    public Kind getKind() { return kind; }
}
