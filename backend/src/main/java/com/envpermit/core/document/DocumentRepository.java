package com.envpermit.core.document;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    List<Document> findByPermitItemIdOrderByUploadedAtDescIdDesc(long permitItemId);

    Optional<Document> findByIdAndPermitItemId(long id, long permitItemId);
}
