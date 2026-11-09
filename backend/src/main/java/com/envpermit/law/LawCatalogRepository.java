package com.envpermit.law;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface LawCatalogRepository extends JpaRepository<LawCatalog, Long> {

    Optional<LawCatalog> findByLawId(String lawId);

    Optional<LawCatalog> findByNameAndKind(String name, LawCatalog.Kind kind);

    /** {@code key} has no spaces; names are compared with spaces removed. */
    @Query("select c from LawCatalog c where replace(c.name, ' ', '') like %?1% "
            + "or replace(coalesce(c.shortName, ''), ' ', '') like %?1% order by c.name")
    List<LawCatalog> autocomplete(String key, Limit limit);

    /** Attach orphan 시행령·시행규칙 to a newly added 법률 (name convention). */
    @Modifying
    @Query("update LawCatalog c set c.parentId = ?1 where c.parentId is null and c.name in (?2, ?3)")
    int adoptChildren(long parentId, String decreeName, String ruleName);
}
