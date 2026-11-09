package com.envpermit.law;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Design §6: one amendment × one permit item's links → DIRECT, REFERENCE or no impact (null). */
final class ImpactRule {

    enum Level { DIRECT, REFERENCE }

    /** @param changedJoCodes 6-digit JO codes of the changed articles */
    record Amended(long lawId, Long parentId, String revisionType, Set<String> changedJoCodes) {
    }

    record Link(long lawId, String articleNo) {
    }

    /** Revisions that count for the whole law, whatever article a link names. */
    private static final List<String> WHOLE_LAW = List.of("폐지", "타법개정", "일괄개정", "전부개정");

    private ImpactRule() {
    }

    static boolean repealed(String revisionType) {
        return revisionType.contains("폐지"); // spelling unverified (design §11), same test as AmendmentBatch
    }

    /** Highest level over all links (DIRECT > REFERENCE); null = not affected. */
    static Level classify(Amended a, List<Link> links) {
        Level best = null;
        for (Link l : links) {
            Level v = level(a, l);
            if (v == Level.DIRECT) {
                return v;
            }
            if (v != null) {
                best = v;
            }
        }
        return best;
    }

    private static Level level(Amended a, Link l) {
        if (l.lawId() == a.lawId()) {
            String jo = ArticleNo.joCode(l.articleNo()); // null: no article, or 별표 (no change field for it yet)
            boolean wholeLaw = WHOLE_LAW.stream().anyMatch(a.revisionType()::contains);
            return wholeLaw || jo == null || a.changedJoCodes().contains(jo) ? Level.DIRECT : Level.REFERENCE;
        }
        return Objects.equals(a.parentId(), l.lawId()) ? Level.DIRECT : null; // linked 법률's 시행령·시행규칙
    }
}
