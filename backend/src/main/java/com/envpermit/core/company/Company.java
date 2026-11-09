package com.envpermit.core.company;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

@Entity
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private boolean aiExtractionEnabled;
    private Long aiExtractionChangedBy;
    private Instant aiExtractionChangedAt;

    protected Company() {
    }

    public Company(String name) {
        this.name = name;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public boolean isAiExtractionEnabled() { return aiExtractionEnabled; }
    public Long getAiExtractionChangedBy() { return aiExtractionChangedBy; }
    public Instant getAiExtractionChangedAt() { return aiExtractionChangedAt; }
    public void rename(String name) { this.name = name; }

    public void setAiExtraction(boolean enabled, long userId, Instant now) {
        this.aiExtractionEnabled = enabled;
        this.aiExtractionChangedBy = userId;
        this.aiExtractionChangedAt = now;
    }
}
