package com.envpermit.law;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

/**
 * Permit item ↔ law (optionally one article). Shared contract: review/derivation write
 * AI_EXTRACTED / DERIVED rows here. articleNo is always {@link ArticleNo#normalize} output.
 */
@Entity
public class PermitLawLink {

    public enum Source { MANUAL, AI_EXTRACTED, DERIVED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long permitItemId;
    private Long lawCatalogId;
    private String articleNo;

    @Enumerated(EnumType.STRING)
    private Source source;

    private Instant createdAt;

    protected PermitLawLink() {
    }

    public PermitLawLink(long permitItemId, long lawCatalogId, String articleNo, Source source, Instant createdAt) {
        this.permitItemId = permitItemId;
        this.lawCatalogId = lawCatalogId;
        this.articleNo = ArticleNo.normalize(articleNo);
        this.source = source;
        this.createdAt = createdAt;
    }

    /** A person's edit: the link is theirs now, whatever created it. */
    public void editArticle(String articleNo) {
        this.articleNo = ArticleNo.normalize(articleNo);
        this.source = Source.MANUAL;
    }

    public Long getId() { return id; }
    public Long getPermitItemId() { return permitItemId; }
    public Long getLawCatalogId() { return lawCatalogId; }
    public String getArticleNo() { return articleNo; }
    public Source getSource() { return source; }
    public Instant getCreatedAt() { return createdAt; }
}
