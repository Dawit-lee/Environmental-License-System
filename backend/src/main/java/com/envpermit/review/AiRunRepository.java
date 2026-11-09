package com.envpermit.review;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface AiRunRepository extends JpaRepository<AiRun, Long> {

    Optional<AiRun> findFirstByProjectIdAndKindAndPreparerOrderById(long projectId, Kind kind, String preparer);

    List<AiRun> findByProjectIdAndPreparerNotOrderByIdDesc(long projectId, String preparer);

    /** Same file (SHA-256) already extracted, or being extracted, in this project. Failed runs don't count. */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM ai_run WHERE project_id = ?1 AND input_ref ->> 'sha256' = ?2 "
            + "AND status <> 'FAILED')", nativeQuery = true)
    boolean extractedBefore(long projectId, String sha256);

    /** Orphans of a restart: RUNNING since before {@code before}. */
    @Transactional
    @Modifying
    @Query("update AiRun r set r.status = 'FAILED', r.errorMessage = ?2, r.finishedAt = ?3 "
            + "where r.status = 'RUNNING' and r.startedAt < ?1")
    int failRunningSince(Instant before, String message, Instant now);
}
