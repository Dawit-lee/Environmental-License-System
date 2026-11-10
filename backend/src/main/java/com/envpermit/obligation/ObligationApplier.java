package com.envpermit.obligation;

import com.envpermit.core.permit.PermitItem;
import com.envpermit.core.permit.PermitItemRepository;
import com.envpermit.law.LawCatalogService;
import com.envpermit.review.AiRun;
import com.envpermit.review.AiRunRepository;
import com.envpermit.review.AiSuggestion;
import com.envpermit.review.Kind;
import com.envpermit.review.SuggestionApplier;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * OBLIGATION → permit_obligation + its first task and deadline + legal_basis law links (design §3.7).
 * shortcut: manual rows have no source document.
 */
@Component
class ObligationApplier implements SuggestionApplier {

    private final PermitObligationRepository obligations;
    private final PermitItemRepository permits;
    private final ObligationTasks tasks;
    private final AiRunRepository runs;
    private final ObjectMapper json;
    private final Validator validator;
    private final LawCatalogService laws;

    ObligationApplier(PermitObligationRepository obligations, PermitItemRepository permits, ObligationTasks tasks,
                      AiRunRepository runs, ObjectMapper json, Validator validator, LawCatalogService laws) {
        this.laws = laws;
        this.obligations = obligations;
        this.permits = permits;
        this.tasks = tasks;
        this.runs = runs;
        this.json = json;
        this.validator = validator;
    }

    @Override
    public Kind kind() {
        return Kind.OBLIGATION;
    }

    @Override
    public void checkTarget(long projectId, Long permitItemId) {
        if (permitItemId == null || permits.findById(permitItemId).filter(p -> p.getProjectId() == projectId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "이 프로젝트의 인허가 항목이 아닙니다.");
        }
    }

    @Override
    public void validate(JsonNode payload) {
        parse(payload);
    }

    @Override
    public long apply(AiSuggestion s, JsonNode payload, ApplyContext ctx) {
        ObligationPayload p = parse(payload);
        PermitItem permit = permits.findById(s.getTargetParentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "인허가 항목이 삭제되었습니다."));
        PermitObligation o = obligations.save(new PermitObligation(permit.getId(), sourceDocument(s), s.getId(), p, ctx.firstDueDate()));
        tasks.sync(o, permit, "ACCEPT", ctx.actorId()); // first due date → the first cycle's task + D-day
        laws.linkLegalBasis(permit.getId(), p.legalBasis() == null ? List.of() : p.legalBasis());
        return o.getId();
    }

    /** The extracted document (version diff's old set); null for manually added rows. */
    Long sourceDocument(AiSuggestion s) {
        JsonNode ref = runs.findById(s.getRunId()).map(AiRun::getInputRef).orElse(null);
        return ref != null && ref.path("document_id").isNumber() ? ref.get("document_id").asLong() : null;
    }

    ObligationPayload parse(JsonNode payload) {
        ObligationPayload p;
        try {
            p = json.treeToValue(payload, ObligationPayload.class);
        } catch (JsonProcessingException e) {
            String field = e instanceof JsonMappingException m && !m.getPath().isEmpty()
                    ? m.getPath().get(0).getFieldName() : "payload";
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, field + ": 형식이 잘못되었습니다.");
        }
        String errors = validator.validate(p).stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted().collect(Collectors.joining(", "));
        if (!errors.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, errors);
        }
        return p;
    }
}
