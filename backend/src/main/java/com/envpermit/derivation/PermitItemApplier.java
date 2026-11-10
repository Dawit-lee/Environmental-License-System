package com.envpermit.derivation;

import com.envpermit.core.permit.PermitChanged;
import com.envpermit.core.permit.PermitItem;
import com.envpermit.core.permit.PermitItemRepository;
import com.envpermit.core.permit.PermitStatusHistory;
import com.envpermit.core.permit.PermitStatusHistoryRepository;
import com.envpermit.law.ArticleNo;
import com.envpermit.law.LawCatalogRepository;
import com.envpermit.law.PermitLawLink;
import com.envpermit.law.PermitLawLinkRepository;
import com.envpermit.review.AiSuggestion;
import com.envpermit.review.Kind;
import com.envpermit.review.SuggestionApplier;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * PERMIT_ITEM → permit_item + permit_law_link(DERIVED) from law_refs (design §4.2, review §2.5).
 * ON_HOLD is never approved as is: re-run with more inputs, edit determination to APPLICABLE, or reject.
 * shortcut: payloads without law_refs (manual rows) get no links; wire the legal_basis name matcher when one exists.
 */
@Component
class PermitItemApplier implements SuggestionApplier {

    private final PermitItemRepository permits;
    private final PermitStatusHistoryRepository history;
    private final PermitLawLinkRepository links;
    private final LawCatalogRepository laws;
    private final ApplicationEventPublisher events;

    PermitItemApplier(PermitItemRepository permits, PermitStatusHistoryRepository history,
                      PermitLawLinkRepository links, LawCatalogRepository laws, ApplicationEventPublisher events) {
        this.permits = permits;
        this.history = history;
        this.links = links;
        this.laws = laws;
        this.events = events;
    }

    @Override
    public Kind kind() {
        return Kind.PERMIT_ITEM;
    }

    @Override
    public void checkTarget(long projectId, Long targetParentId) {
        if (targetParentId != null) {
            throw unprocessable("인허가 항목 제안은 상위 항목이 없습니다.");
        }
    }

    @Override
    public void validate(JsonNode p) {
        List<String> errors = new ArrayList<>();
        if (!"APPLICABLE".equals(p.path("determination").asText())) {
            errors.add("determination: 판정 보류 항목은 그대로 승인할 수 없습니다. 속성을 채워 다시 도출하거나, "
                    + "해당으로 수정해 승인하거나, 거절하세요.");
        }
        String name = p.path("permit_name").asText("").trim();
        if (name.isEmpty() || name.length() > 200) {
            errors.add("permit_name: 1~200자여야 합니다.");
        }
        if (regulation(p).length() > 300) {
            errors.add("legal_basis: 300자를 넘을 수 없습니다.");
        }
        for (JsonNode ref : p.path("law_refs")) {
            if (!laws.existsById(ref.path("law_catalog_id").asLong())) {
                errors.add("law_refs: 카탈로그에 없는 법령입니다 (" + ref.path("law_catalog_id").asText() + ").");
            }
        }
        if (!errors.isEmpty()) {
            throw unprocessable(String.join(", ", errors));
        }
    }

    @Override
    public long apply(AiSuggestion s, JsonNode p, ApplyContext ctx) {
        PermitItem item = permits.save(PermitItem.derived(s.getProjectId(), p.path("permit_name").asText(),
                category(p), blankToNull(regulation(p)), p.path("reason").asText(null),
                p.path("approval_stage").asText(null), nextAction(p)));
        history.save(new PermitStatusHistory(item.getId(), null, item.getStatus(), ctx.actorId(), ctx.now()));
        for (JsonNode ref : p.path("law_refs")) {
            long lawId = ref.path("law_catalog_id").asLong();
            String article = ArticleNo.normalize(ref.path("article_no").asText(null));
            if (links.findByPermitItemIdAndLawCatalogIdAndArticleNo(item.getId(), lawId, article).isEmpty()) {
                links.save(new PermitLawLink(item.getId(), lawId, article, PermitLawLink.Source.DERIVED, ctx.now()));
            }
        }
        events.publishEvent(new PermitChanged(item, false));
        return item.getId();
    }

    /** Rule category/field → the permit ledger's category. */
    static PermitItem.Category category(JsonNode p) {
        return switch (p.path("category").asText()) {
            case "SITE" -> PermitItem.Category.LOCATION;
            case "FACILITY" -> PermitItem.Category.FACILITY;
            default -> switch (p.path("field").asText()) {
                case "환경" -> PermitItem.Category.ENVIRONMENT;
                case "전기", "통신" -> PermitItem.Category.ELECTRICAL;
                default -> PermitItem.Category.CONSTRUCTION;
            };
        };
    }

    private static String regulation(JsonNode p) {
        List<String> parts = new ArrayList<>();
        p.path("legal_basis").forEach(b -> parts.add(b.asText()));
        return String.join(", ", parts);
    }

    private static String nextAction(JsonNode p) {
        String text = p.path("required_by_text").asText(null);
        String date = p.path("required_by_date").asText(null);
        if (text == null) {
            return null;
        }
        return date == null ? text : text + " (" + date + "까지)";
    }

    private static String blankToNull(String s) {
        return s.isBlank() ? null : s;
    }

    private static ResponseStatusException unprocessable(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
