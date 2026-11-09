package com.envpermit.core.permit;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermitStatusHistoryRepository extends JpaRepository<PermitStatusHistory, Long> {
    List<PermitStatusHistory> findByPermitItemIdOrderByChangedAtDescIdDesc(long permitItemId);
}
