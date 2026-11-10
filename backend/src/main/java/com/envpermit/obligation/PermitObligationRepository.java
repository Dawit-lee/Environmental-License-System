package com.envpermit.obligation;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermitObligationRepository extends JpaRepository<PermitObligation, Long> {

    List<PermitObligation> findByPermitItemIdAndSourceDocumentIdInOrderById(long permitItemId, Collection<Long> documentIds);
}
