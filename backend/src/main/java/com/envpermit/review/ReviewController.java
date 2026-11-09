package com.envpermit.review;

import com.envpermit.review.AiSuggestion.Decision;
import com.envpermit.review.AiSuggestion.Origin;
import com.envpermit.review.AiSuggestion.Status;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class ReviewController {

    private static final Logger log = LoggerFactory.getLogger(ReviewController.class);

    private final Review review;

    ReviewController(Review review) {
        this.review = review;
    }

    record CreateRequest(Long runId, Kind kind, @NotNull Long permitItemId, @NotNull JsonNode payload) {
    }

    record EditRequest(@NotNull JsonNode editedPayload, @NotNull Long version) {
    }

    record SubmitRequest(@NotNull Decision proposedDecision, @Size(max = 500) String reason, @NotNull Long version) {
    }

    record AcceptRequest(LocalDate firstDueDate, @NotNull Long version) {
    }

    record ReasonRequest(@NotBlank @Size(max = 500) String reason, @NotNull Long version) {
    }

    record VersionRequest(@NotNull Long version) {
    }

    record BulkRequest(@NotEmpty @Size(max = 200) List<Long> ids) {
    }

    record BulkResult(long id, boolean ok, String reason) {
    }

    record SuggestionView(long id, Long projectId, long runId, Kind kind, Long targetParentId, Origin origin,
                          JsonNode payload, JsonNode editedPayload, Status status, Decision proposedDecision,
                          Long submittedBy, Instant submittedAt, Long reviewedBy, Instant reviewedAt,
                          String rejectReason, Long appliedEntityId, long version) {
        static SuggestionView of(AiSuggestion s) {
            return new SuggestionView(s.getId(), s.getProjectId(), s.getRunId(), s.getKind(), s.getTargetParentId(),
                    s.getOrigin(), s.getPayload(), s.getEditedPayload(), s.getStatus(), s.getProposedDecision(),
                    s.getSubmittedBy(), s.getSubmittedAt(), s.getReviewedBy(), s.getReviewedAt(),
                    s.getRejectReason(), s.getAppliedEntityId(), s.getVersion());
        }
    }

    record EventView(long id, Long actorId, Instant at, SuggestionEvent.Type type, JsonNode data) {
    }

    @GetMapping("/api/projects/{projectId}/suggestions")
    List<SuggestionView> list(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId) {
        return review.list(projectId, jwt).stream().map(SuggestionView::of).toList();
    }

    @PostMapping("/api/projects/{projectId}/suggestions")
    ResponseEntity<SuggestionView> add(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId,
                                       @Valid @RequestBody CreateRequest req) {
        Kind kind = req.kind() == null ? Kind.OBLIGATION : req.kind();
        AiSuggestion s = review.addManual(projectId, jwt, req.runId(), kind, req.permitItemId(), req.payload());
        return ResponseEntity.status(HttpStatus.CREATED).body(SuggestionView.of(s));
    }

    @GetMapping("/api/suggestions/{id}/events")
    List<EventView> events(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return review.events(id, jwt).stream()
                .map(e -> new EventView(e.getId(), e.getActorId(), e.getAt(), e.getType(), e.getData())).toList();
    }

    @PatchMapping("/api/suggestions/{id}")
    SuggestionView edit(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody EditRequest req) {
        return SuggestionView.of(review.edit(id, jwt, req.editedPayload(), req.version()));
    }

    @PostMapping("/api/suggestions/{id}/submit")
    SuggestionView submit(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                          @Valid @RequestBody SubmitRequest req) {
        return SuggestionView.of(review.submit(id, jwt, req.proposedDecision(), req.reason(), req.version()));
    }

    @PostMapping("/api/suggestions/{id}/accept")
    SuggestionView accept(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                          @Valid @RequestBody AcceptRequest req) {
        return SuggestionView.of(review.accept(id, jwt, req.firstDueDate(), req.version(), false));
    }

    @PostMapping("/api/suggestions/{id}/reject")
    SuggestionView reject(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                          @Valid @RequestBody ReasonRequest req) {
        return SuggestionView.of(review.reject(id, jwt, req.reason(), req.version()));
    }

    @PostMapping("/api/suggestions/{id}/return")
    SuggestionView returnToPending(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                   @Valid @RequestBody ReasonRequest req) {
        return SuggestionView.of(review.returnToPending(id, jwt, req.reason(), req.version()));
    }

    @PostMapping("/api/suggestions/{id}/reopen")
    SuggestionView reopen(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                          @Valid @RequestBody VersionRequest req) {
        return SuggestionView.of(review.reopen(id, jwt, req.version()));
    }

    /** One transaction per item (review.accept is transactional); partial success allowed. No first due date. */
    @PostMapping("/api/suggestions/bulk-accept")
    List<BulkResult> bulkAccept(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BulkRequest req) {
        review.requireBulk(req.ids(), jwt);
        List<BulkResult> out = new ArrayList<>();
        for (long id : req.ids()) {
            try {
                review.accept(id, jwt, null, null, true);
                out.add(new BulkResult(id, true, null));
            } catch (ResponseStatusException e) {
                out.add(new BulkResult(id, false, e.getReason() != null ? e.getReason() : e.getStatusCode().toString()));
            } catch (RuntimeException e) {
                log.error("bulk accept failed for suggestion {}", id, e);
                out.add(new BulkResult(id, false, "처리 중 오류가 발생했습니다."));
            }
        }
        return out;
    }
}
