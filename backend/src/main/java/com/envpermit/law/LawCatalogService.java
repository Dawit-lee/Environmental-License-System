package com.envpermit.law;

import com.envpermit.law.LawApiClient.LawRow;
import java.time.Clock;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Name matching against 법제처 search results, and adding a searched law to the catalog. */
@Service
public class LawCatalogService {

    static final String DECREE = " 시행령";
    static final String RULE = " 시행규칙";

    /** First article reference in a key: 제N조(의M) or 별표N(의M). Anything after it (제1항 …) is ignored. */
    private static final Pattern ARTICLE_REF = Pattern.compile("제\\d+조(?:의\\d+)?|별표\\d+(?:의\\d+)?");

    private final LawApiClient api;
    private final LawCatalogRepository catalog;
    private final PermitLawLinkRepository links;
    private final Clock clock;

    public LawCatalogService(LawApiClient api, LawCatalogRepository catalog, PermitLawLinkRepository links,
                             Clock clock) {
        this.api = api;
        this.catalog = catalog;
        this.links = links;
        this.clock = clock;
    }

    /** A legal_basis string as a name key (spaces and 「」 removed) and a normalized article (null = whole law). */
    record Ref(String key, String articleNo) {
    }

    static Ref ref(String legalBasis) {
        String s = key(legalBasis).replaceAll("[「」]", "");
        Matcher m = ARTICLE_REF.matcher(s);
        return m.find() ? new Ref(s.substring(0, m.start()), ArticleNo.normalize(m.group())) : new Ref(s, null);
    }

    /**
     * Links each legal_basis law that matches exactly one catalog row by name or short name (spaces ignored)
     * to the permit item as AI_EXTRACTED; an existing link is kept as is. Never throws for a bad string:
     * a match failure must not block approval, and the string stays on the obligation.
     * shortcut: no 법제처 search fallback (spec step 4) or field abbreviation dictionary; add when misses show up.
     */
    public void linkLegalBasis(long permitItemId, List<String> legalBasis) {
        for (String raw : legalBasis) {
            Ref r;
            try {
                r = ref(raw);
            } catch (IllegalArgumentException e) { // e.g. 제0조
                continue;
            }
            List<LawCatalog> hits = catalog.findByNameKey(r.key());
            if (hits.size() == 1 && links.findByPermitItemIdAndLawCatalogIdAndArticleNo(
                    permitItemId, hits.get(0).getId(), r.articleNo()).isEmpty()) {
                links.save(new PermitLawLink(permitItemId, hits.get(0).getId(), r.articleNo(),
                        PermitLawLink.Source.AI_EXTRACTED, clock.instant()));
            }
        }
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
