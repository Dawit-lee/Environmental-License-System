package com.envpermit.review;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
    private String model;
    private String promptVersion;
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode inputRef;
    private String rawResponseKey;
    private Integer inputTokens;
    private Integer outputTokens;
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

    /** Deterministic rule-engine run (auto-derivation §4.1): no tokens, finished when created. */
    public static AiRun ruleEngine(long companyId, long projectId, long userId, int rulesetVersion,
                                   JsonNode attributes, String judgementKey, Instant now) {
        AiRun r = new AiRun();
        r.companyId = companyId;
        r.projectId = projectId;
        r.kind = Kind.PERMIT_ITEM;
        r.model = "rule-engine";
        r.promptVersion = "ruleset-v" + rulesetVersion;
        r.preparer = "RULE_ENGINE";
        r.inputRef = attributes;
        r.rawResponseKey = judgementKey;
        r.inputTokens = r.outputTokens = 0;
        r.status = "SUCCEEDED";
        r.startedBy = userId;
        r.startedAt = r.finishedAt = now;
        return r;
    }

    public Long getId() { return id; }
    public Long getCompanyId() { return companyId; }
    public Long getProjectId() { return projectId; }
    public Kind getKind() { return kind; }
}
