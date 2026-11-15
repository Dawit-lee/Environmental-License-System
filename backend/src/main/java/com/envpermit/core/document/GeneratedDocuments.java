package com.envpermit.core.document;

import com.envpermit.core.permit.PermitItemRepository;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Files the app itself writes into a permit item's documents (the compliance report draft). */
@Service
public class GeneratedDocuments {

    private final DocumentRepository documents;
    private final DocumentStorage storage;
    private final PermitItemRepository permits;
    private final Clock clock;

    GeneratedDocuments(DocumentRepository documents, DocumentStorage storage, PermitItemRepository permits, Clock clock) {
        this.permits = permits;
        this.documents = documents;
        this.storage = storage;
        this.clock = clock;
    }

    /** Stores the bytes and adds the document row; returns the document id. */
    public long saveXlsx(long permitItemId, String fileName, byte[] bytes, long userId) {
        String key = "permits/" + permitItemId + "/" + UUID.randomUUID();
        storage.put(key, new ByteArrayInputStream(bytes), bytes.length, DocumentType.XLSX.contentType);
        return documents.save(new Document(permits.findById(permitItemId).orElseThrow().getProjectId(),
                permitItemId, null, fileName, DocumentType.XLSX.contentType, bytes.length,
                DocumentController.sha256(bytes), key, userId, clock.instant(), null, null)).getId();
    }
}
