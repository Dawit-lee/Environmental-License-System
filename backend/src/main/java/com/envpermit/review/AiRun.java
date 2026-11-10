package com.envpermit.review;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One AI run (prompt→response audit unit). Manually added suggestions hang off a per-project MANUAL run. */
@Entity
public class AiRun {

    public static final String MANUAL = "MANUAL";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long companyId;
    private Long projectId;
    @Enumerated(EnumType.STRING)
    private Kind kind;
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode inputRef;
    private String provider;
    private String model;
    private String promptVersion;
    private String preparer;
    private String rawResponseKey;
    private Integer inputTokens;
    private Integer outputTokens;
    private String status;
    private String errorMessage;
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
        r.status = SUCCEEDED;
        r.startedBy = userId;
        r.startedAt = r.finishedAt = now;
        return r;
    }

    public static AiRun start(long companyId, long projectId, Kind kind, JsonNode inputRef, String model,
                              String promptVersion, String preparer, long userId, Instant now) {
        AiRun r = new AiRun();
        r.companyId = companyId;
        r.projectId = projectId;
        r.kind = kind;
        r.inputRef = inputRef;
        r.model = model;
        r.promptVersion = promptVersion;
        r.preparer = preparer;
        r.status = RUNNING;
        r.startedBy = userId;
        r.startedAt = now;
        return r;
    }

    /** LLM provider (CLAUDE|GEMINI) of an extraction run; null for manual and rule-engine runs. */
    public AiRun provider(String provider) {
        this.provider = provider;
        return this;
    }

    /** Raw response and token counts are kept on failure too (cost and debugging). */
    public void finish(String error, String rawResponseKey, Integer inputTokens, Integer outputTokens, Instant now) {
        this.status = error == null ? SUCCEEDED : FAILED;
        this.errorMessage = error;
        this.rawResponseKey = rawResponseKey;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.finishedAt = now;
    }

    public Long getId() { return id; }
    public Long getCompanyId() { return companyId; }
    public Long getProjectId() { return projectId; }
    public Kind getKind() { return kind; }
    public JsonNode getInputRef() { return inputRef; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public String getPromptVersion() { return promptVersion; }
    public String getPreparer() { return preparer; }
    public String getRawResponseKey() { return rawResponseKey; }
    public Integer getInputTokens() { return inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public String getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }
    public Long getStartedBy() { return startedBy; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
}
