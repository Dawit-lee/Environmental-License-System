package com.envpermit.law;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDate;

/** A law the app knows (법률 / 시행령 / 시행규칙). Seeded by migration or added from a 법제처 search. */
@Entity
public class LawCatalog {

    public enum Kind { LAW, DECREE, RULE, ORDINANCE }

    public enum Source { SEED, USER_SEARCH }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String lawId;
    private String name;

    @Enumerated(EnumType.STRING)
    private Kind kind;

    private String shortName;
    private Long parentId;
    private Long lastMst;
    private LocalDate lastPromulgatedOn;
    private LocalDate lastEnforcedOn;
    private boolean repealed;

    @Enumerated(EnumType.STRING)
    private Source source;

    protected LawCatalog() {
    }

    LawCatalog(LawApiClient.LawRow r, Long parentId) {
        lawId = r.lawId();
        name = r.name();
        kind = kindOf(r.kindName());
        shortName = r.shortName();
        this.parentId = parentId;
        lastMst = r.mst();
        lastPromulgatedOn = r.promulgatedOn();
        lastEnforcedOn = r.enforcedOn();
        source = Source.USER_SEARCH;
    }

    /** 법령구분명 → kind. 대통령령 = DECREE; 부령·총리령 and other 규칙 = RULE. */
    static Kind kindOf(String kindName) {
        return switch (kindName) {
            case "법률" -> Kind.LAW;
            case "대통령령" -> Kind.DECREE;
            default -> Kind.RULE;
        };
    }

    public Long getId() { return id; }
    public String getLawId() { return lawId; }
    public String getName() { return name; }
    public Kind getKind() { return kind; }
    public String getShortName() { return shortName; }
    public Long getParentId() { return parentId; }
    public Long getLastMst() { return lastMst; }
    public LocalDate getLastPromulgatedOn() { return lastPromulgatedOn; }
    public LocalDate getLastEnforcedOn() { return lastEnforcedOn; }
    public boolean isRepealed() { return repealed; }
    public Source getSource() { return source; }
}
