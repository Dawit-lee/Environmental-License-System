package com.envpermit.law;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermitLawLinkRepository extends JpaRepository<PermitLawLink, Long> {

    List<PermitLawLink> findByPermitItemIdOrderById(long permitItemId);

    Optional<PermitLawLink> findByIdAndPermitItemId(long id, long permitItemId);

    /** articleNo null matches IS NULL (same as the NULLS NOT DISTINCT unique key). */
    Optional<PermitLawLink> findByPermitItemIdAndLawCatalogIdAndArticleNo(long permitItemId, long lawCatalogId,
                                                                          String articleNo);
}
