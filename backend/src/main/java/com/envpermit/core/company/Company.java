package com.envpermit.core.company;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.security.SecureRandom;
import java.time.Instant;

@Entity
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O, 1/I
    private static final SecureRandom RANDOM = new SecureRandom();

    private String name;
    private String inviteCode;
    private boolean aiExtractionEnabled;
    private Long aiExtractionChangedBy;
    private Instant aiExtractionChangedAt;

    protected Company() {
    }

    public Company(String name) {
        this.name = name;
        this.inviteCode = newCode();
    }

    /** 10 chars of 32 = 50 bits: short to type, not guessable. */
    private static String newCode() {
        StringBuilder b = new StringBuilder(10);
        for (int i = 0; i < 10; i++) b.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        return b.toString();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public boolean isAiExtractionEnabled() { return aiExtractionEnabled; }
    public Long getAiExtractionChangedBy() { return aiExtractionChangedBy; }
    public Instant getAiExtractionChangedAt() { return aiExtractionChangedAt; }
    public String getInviteCode() { return inviteCode; }
    public void rename(String name) { this.name = name; }
    public void regenerateInviteCode() { this.inviteCode = newCode(); }

    public void setAiExtraction(boolean enabled, long userId, Instant now) {
        this.aiExtractionEnabled = enabled;
        this.aiExtractionChangedBy = userId;
        this.aiExtractionChangedAt = now;
    }
}
