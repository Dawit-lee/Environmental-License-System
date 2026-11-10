package com.envpermit.obligation;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PermitObligationRepository extends JpaRepository<PermitObligation, Long> {

    /** ④ revision approval: lock the row, then compare its version with the one the diff saw. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PermitObligation o where o.id = ?1")
    Optional<PermitObligation> lock(long id);

    List<PermitObligation> findByPermitItemIdAndSourceDocumentIdInOrderById(long permitItemId, Collection<Long> documentIds);
}
