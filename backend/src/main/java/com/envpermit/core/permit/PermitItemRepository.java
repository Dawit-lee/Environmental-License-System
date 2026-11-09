package com.envpermit.core.permit;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PermitItemRepository extends JpaRepository<PermitItem, Long> {

    List<PermitItem> findByProjectIdOrderById(long projectId);

    Optional<PermitItem> findByIdAndProjectId(long id, long projectId);

    record PermitCounts(long projectId, long total, long completed, long nearExpiry) {
        public static PermitCounts none(long projectId) {
            return new PermitCounts(projectId, 0, 0, 0);
        }
    }

    @Query("select new com.envpermit.core.permit.PermitItemRepository$PermitCounts(p.projectId, count(p), "
            + "sum(case when p.status = com.envpermit.core.permit.PermitItem$Status.COMPLETED then 1 else 0 end), "
            + "sum(case when p.status = com.envpermit.core.permit.PermitItem$Status.NEAR_EXPIRY then 1 else 0 end)) "
            + "from PermitItem p where p.projectId in ?1 group by p.projectId")
    List<PermitCounts> countByProject(Collection<Long> projectIds);
}
