package com.envpermit.law;

import com.envpermit.law.LawApiClient.LawRow;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Name matching against 법제처 search results, and adding a searched law to the catalog. */
@Service
public class LawCatalogService {

    static final String DECREE = " 시행령";
    static final String RULE = " 시행규칙";

    private final LawApiClient api;
    private final LawCatalogRepository catalog;

    public LawCatalogService(LawApiClient api, LawCatalogRepository catalog) {
        this.api = api;
        this.catalog = catalog;
    }

    /** Spaces removed; names are compared on this key. */
    static String key(String name) {
        return name.replaceAll("\\s", "");
    }

    /**
     * 현행 rows whose name is exactly {@code query} or its 시행령/시행규칙. 법제처 matches partially
     * ("폐기물관리법" also returns 방사성폐기물 관리법), so everything else is dropped.
     */
    public List<LawRow> candidates(String query) {
        String k = key(query);
        return api.search(query.trim()).stream()
                .filter(r -> "현행".equals(r.status()))
                .filter(r -> {
                    String n = key(r.name());
                    return n.equals(k) || n.equals(k + key(DECREE)) || n.equals(k + key(RULE));
                })
                .toList();
    }

    /** Adds the 법제처 law {@code lawId} (re-found by searching {@code query}); returns the existing row if known. */
    @Transactional
    public LawCatalog add(String lawId, String query) {
        return catalog.findByLawId(lawId).orElseGet(() -> {
            LawRow row = candidates(query).stream().filter(r -> r.lawId().equals(lawId)).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "법제처에서 해당 현행 법령을 찾지 못했습니다."));
            Long parentId = parentName(row.name()) == null ? null
                    : catalog.findByNameAndKind(parentName(row.name()), LawCatalog.Kind.LAW).map(LawCatalog::getId).orElse(null);
            LawCatalog added = catalog.save(new LawCatalog(row, parentId));
            if (added.getKind() == LawCatalog.Kind.LAW) {
                catalog.adoptChildren(added.getId(), added.getName() + DECREE, added.getName() + RULE);
            }
            return added;
        });
    }

    /** "X 시행령" / "X 시행규칙" → "X", else null. Grouping by name only; 소관부처 is not used (it changes). */
    static String parentName(String name) {
        for (String suffix : List.of(DECREE, RULE)) {
            if (name.endsWith(suffix)) {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        return null;
    }
}
