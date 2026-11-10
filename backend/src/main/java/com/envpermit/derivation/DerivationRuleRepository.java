package com.envpermit.derivation;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface DerivationRuleRepository extends JpaRepository<DerivationRule, Long> {

    List<DerivationRule> findByStatusOrderByRuleKey(String status);

    @Query(value = "SELECT version FROM ruleset_state WHERE id = 1", nativeQuery = true)
    int rulesetVersion();
}
