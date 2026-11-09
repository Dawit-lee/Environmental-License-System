package com.envpermit.review;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface AiSuggestionRepository extends JpaRepository<AiSuggestion, Long> {

    List<AiSuggestion> findByProjectIdOrderById(long projectId);

    /** Row lock for decisions: a concurrent second decision waits, then sees the new version → 409. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AiSuggestion s where s.id = ?1")
    Optional<AiSuggestion> lock(long id);
}
