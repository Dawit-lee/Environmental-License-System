package com.envpermit.core.signup;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface JoinRequestRepository extends JpaRepository<JoinRequest, Long> {

    Optional<JoinRequest> findFirstByEmailOrderByIdDesc(String email);

    List<JoinRequest> findByCompanyIdAndStatusAndCreatedAtAfterOrderByIdAsc(long companyId, JoinRequest.Status status,
                                                                             Instant after);

    long countByCompanyIdAndIpAndCreatedAtAfter(long companyId, String ip, Instant after);

    /** Frees the email of requests nobody answered in 14 days. */
    @Modifying
    @Query("update JoinRequest r set r.status = 'EXPIRED' where r.status = 'PENDING' and r.createdAt <= ?1")
    void expireCreatedBefore(Instant cutoff);
}
