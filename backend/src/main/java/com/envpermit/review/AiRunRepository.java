package com.envpermit.review;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface AiRunRepository extends JpaRepository<AiRun, Long> {
    Optional<AiRun> findFirstByProjectIdAndKindAndPreparerOrderById(long projectId, Kind kind, String preparer);
}
