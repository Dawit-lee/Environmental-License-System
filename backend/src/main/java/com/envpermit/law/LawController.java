package com.envpermit.law;

import com.envpermit.core.permit.PermitItem;
import com.envpermit.core.permit.PermitItemRepository;
import com.envpermit.core.project.ProjectAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class LawController {

    private final LawCatalogRepository catalog;
    private final LawCatalogService service;
    private final PermitLawLinkRepository links;
    private final PermitItemRepository permits;
    private final ProjectAccess access;
    private final Clock clock;

    public LawController(LawCatalogRepository catalog, LawCatalogService service, PermitLawLinkRepository links,
                         PermitItemRepository permits, ProjectAccess access, Clock clock) {
        this.catalog = catalog;
        this.service = service;
        this.links = links;
        this.permits = permits;
        this.access = access;
        this.clock = clock;
    }

    record LawView(long id, String lawId, String name, LawCatalog.Kind kind, String shortName, Long parentId,
                   LocalDate lastPromulgatedOn, LocalDate lastEnforcedOn, LawCatalog.Source source) {
        static LawView of(LawCatalog c) {
            return new LawView(c.getId(), c.getLawId(), c.getName(), c.getKind(), c.getShortName(), c.getParentId(),
                    c.getLastPromulgatedOn(), c.getLastEnforcedOn(), c.getSource());
        }
    }

    /** A 법제처 search hit; catalogId is set when the law is already in the catalog. */
    record CandidateView(String lawId, String name, LawCatalog.Kind kind, LocalDate promulgatedOn,
                         LocalDate enforcedOn, String revisionType, Long catalogId) {
    }

    /** query = the search text the candidate came from (it is re-run to verify the pick). */
    record AddLawRequest(@NotBlank String lawId, @NotBlank String query) {
    }

    record LinkRequest(@NotNull Long lawCatalogId, String articleNo) {
    }

    record LinkView(long id, long lawCatalogId, String lawId, String name, LawCatalog.Kind kind, String articleNo,
                    PermitLawLink.Source source, LocalDate lastPromulgatedOn, LocalDate lastEnforcedOn,
                    Instant createdAt) {
    }

    // --- catalog (shared reference data, any signed-in user) ---

    @GetMapping("/api/laws")
    List<LawView> autocomplete(@RequestParam(defaultValue = "") String query) {
        return catalog.autocomplete(LawCatalogService.key(query), Limit.of(20)).stream().map(LawView::of).toList();
    }

    @PostMapping("/api/laws/search")
    List<CandidateView> search(@RequestParam String query) {
        if (query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "검색어를 입력하세요.");
        }
        return service.candidates(query).stream().map(r -> new CandidateView(r.lawId(), r.name(),
                LawCatalog.kindOf(r.kindName()), r.promulgatedOn(), r.enforcedOn(), r.revisionType(),
                catalog.findByLawId(r.lawId()).map(LawCatalog::getId).orElse(null))).toList();
    }

    @PostMapping("/api/laws")
    LawView add(@Valid @RequestBody AddLawRequest req) {
        return LawView.of(service.add(req.lawId(), req.query()));
    }

    // --- permit item ↔ law links (project members only) ---

    @GetMapping("/api/permit-items/{id}/law-links")
    List<LinkView> links(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        checkMember(id, jwt);
        List<PermitLawLink> rows = links.findByPermitItemIdOrderById(id);
        Map<Long, LawCatalog> laws = catalog.findAllById(rows.stream().map(PermitLawLink::getLawCatalogId).toList())
                .stream().collect(Collectors.toMap(LawCatalog::getId, Function.identity()));
        return rows.stream().map(l -> view(l, laws.get(l.getLawCatalogId()))).toList();
    }

    /** Re-adding the same law + article returns the existing link (200) instead of a duplicate. */
    @PostMapping("/api/permit-items/{id}/law-links")
    @Transactional
    ResponseEntity<LinkView> link(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                  @Valid @RequestBody LinkRequest req) {
        checkMember(id, jwt);
        LawCatalog law = catalog.findById(req.lawCatalogId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "카탈로그에 없는 법령입니다."));
        String articleNo;
        try {
            articleNo = ArticleNo.normalize(req.articleNo());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        var existing = links.findByPermitItemIdAndLawCatalogIdAndArticleNo(id, law.getId(), articleNo);
        if (existing.isPresent()) {
            return ResponseEntity.ok(view(existing.get(), law));
        }
        PermitLawLink l = links.save(new PermitLawLink(id, law.getId(), articleNo, PermitLawLink.Source.MANUAL,
                clock.instant()));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(l, law));
    }

    @DeleteMapping("/api/permit-items/{id}/law-links/{linkId}")
    @Transactional
    ResponseEntity<Void> unlink(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @PathVariable long linkId) {
        checkMember(id, jwt);
        links.delete(links.findByIdAndPermitItemId(linkId, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        return ResponseEntity.noContent().build();
    }

    /** Unknown permit id → 403 as well, so other companies' ids are not revealed. */
    private void checkMember(long permitItemId, Jwt jwt) {
        access.member(permits.findById(permitItemId).map(PermitItem::getProjectId).orElse(-1L), jwt);
    }

    private static LinkView view(PermitLawLink l, LawCatalog c) {
        return new LinkView(l.getId(), c.getId(), c.getLawId(), c.getName(), c.getKind(), l.getArticleNo(),
                l.getSource(), c.getLastPromulgatedOn(), c.getLastEnforcedOn(), l.getCreatedAt());
    }
}
