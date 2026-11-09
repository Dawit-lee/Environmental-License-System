package com.envpermit.review;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface SuggestionEventRepository extends JpaRepository<SuggestionEvent, Long> {
    List<SuggestionEvent> findBySuggestionIdOrderById(long suggestionId);
}
