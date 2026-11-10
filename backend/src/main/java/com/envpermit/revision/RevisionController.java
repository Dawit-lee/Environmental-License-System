package com.envpermit.revision;

import com.envpermit.review.AiSuggestion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** ④ pair restructure and rediff. Responses are suggestions in the same shape as the review API. */
@RestController
class RevisionController {

    private final RevisionPairing pairing;

    RevisionController(RevisionPairing pairing) {
        this.pairing = pairing;
    }

    record VersionRequest(@NotNull Long version) {
    }

    record PairRequest(@NotNull Long addedId, @NotNull Long removedId, @NotNull Long addedVersion,
                       @NotNull Long removedVersion) {
    }

    @PostMapping("/api/suggestions/{id}/unpair")
    List<AiSuggestion> unpair(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody VersionRequest req) {
        return pairing.unpair(id, req.version(), jwt);
    }

    @PostMapping("/api/suggestions/pair")
    AiSuggestion pair(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PairRequest req) {
        return pairing.pair(req.addedId(), req.addedVersion(), req.removedId(), req.removedVersion(), jwt);
    }

    @PostMapping("/api/suggestions/{id}/rediff")
    AiSuggestion rediff(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody VersionRequest req) {
        return pairing.rediff(id, req.version(), jwt);
    }
}
