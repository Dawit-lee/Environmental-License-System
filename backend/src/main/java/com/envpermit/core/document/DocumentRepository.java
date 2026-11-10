package com.envpermit.core.document;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    String LINE = "WITH RECURSIVE line(id) AS (SELECT CAST(?1 AS BIGINT) UNION "
            + "SELECT d.supersedes_document_id FROM document d JOIN line ON d.id = line.id "
            + "WHERE d.supersedes_document_id IS NOT NULL) ";

    List<Document> findByPermitItemIdOrderByUploadedAtDescIdDesc(long permitItemId);

    List<Document> findByPermitItemIdInOrderByUploadedAtDescIdDesc(Collection<Long> permitItemIds);

    Optional<Document> findByIdAndPermitItemId(long id, long permitItemId);

    boolean existsBySupersedesDocumentId(long documentId);

    /** The document and every version it supersedes, directly or not (v2 → v2, v1, v0). */
    @Query(value = LINE + "SELECT id FROM line", nativeQuery = true)
    List<Long> versionLine(long documentId);

    /** Kinds of extraction/revision runs on the version line that are running or still have undecided suggestions. */
    @Query(value = LINE + "SELECT DISTINCT r.kind FROM ai_run r WHERE r.kind IN ('OBLIGATION', 'OBLIGATION_REVISION') "
            + "AND CAST(r.input_ref ->> 'document_id' AS BIGINT) IN (SELECT id FROM line) AND (r.status = 'RUNNING' "
            + "OR EXISTS (SELECT 1 FROM ai_suggestion s WHERE s.run_id = r.id AND s.status IN ('PENDING', 'SUBMITTED')))",
            nativeQuery = true)
    List<String> openReviewKinds(long documentId);

    /** ④ guard (design §3.2): a new version starts only when the line has nothing left to review. 422 otherwise. */
    default void requireNoOpenReview(long documentId) {
        List<String> kinds = openReviewKinds(documentId);
        if (kinds.contains("OBLIGATION_REVISION")) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "진행 중인 개정을 먼저 끝내세요.");
        }
        if (!kinds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "이전 버전 검토를 먼저 끝내세요.");
        }
    }
}
