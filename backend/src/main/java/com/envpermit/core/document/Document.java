package com.envpermit.core.document;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

/** A file attached to a permit item. The bytes are in {@link DocumentStorage} under storageKey. */
@Entity
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long permitItemId;
    private String fileName;
    private String contentType;
    private long sizeBytes;
    private String sha256;
    private String storageKey;
    private Long uploadedBy;
    private Instant uploadedAt;

    protected Document() {
    }

    Document(Long permitItemId, String fileName, String contentType, long sizeBytes, String sha256,
             String storageKey, Long uploadedBy, Instant uploadedAt) {
        this.permitItemId = permitItemId;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.storageKey = storageKey;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = uploadedAt;
    }

    public Long getId() { return id; }
    public Long getPermitItemId() { return permitItemId; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public String getStorageKey() { return storageKey; }
    public Long getUploadedBy() { return uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }
}
