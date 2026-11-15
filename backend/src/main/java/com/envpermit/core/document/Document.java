package com.envpermit.core.document;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A project file, attached to one of its permit items or not (공고문·과업내용서·회의록 …). docType selects the
 * schedule-extraction prompt (design-schedule/07). The bytes are in {@link DocumentStorage} under storageKey.
 */
@Entity
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long projectId;
    private Long permitItemId;
    private String docType;
    private String fileName;
    private String contentType;
    private long sizeBytes;
    private String sha256;
    private String storageKey;
    private Long uploadedBy;
    private Instant uploadedAt;
    private Long supersedesDocumentId;
    private LocalDate effectiveDate;

    protected Document() {
    }

    Document(long projectId, Long permitItemId, String docType, String fileName, String contentType, long sizeBytes, String sha256,
             String storageKey, Long uploadedBy, Instant uploadedAt, Long supersedesDocumentId,
             LocalDate effectiveDate) {
        this.projectId = projectId;
        this.permitItemId = permitItemId;
        this.docType = docType;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.storageKey = storageKey;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = uploadedAt;
        this.supersedesDocumentId = supersedesDocumentId;
        this.effectiveDate = effectiveDate;
    }

    public Long getId() { return id; }
    public Long getProjectId() { return projectId; }
    public Long getPermitItemId() { return permitItemId; }
    public String getDocType() { return docType; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public String getStorageKey() { return storageKey; }
    public Long getUploadedBy() { return uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }
    public Long getSupersedesDocumentId() { return supersedesDocumentId; }
    public LocalDate getEffectiveDate() { return effectiveDate; }
}
