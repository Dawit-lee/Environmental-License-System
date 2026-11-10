package com.envpermit.obligation;

import com.envpermit.core.permit.PermitItem;
import com.envpermit.core.permit.PermitItemRepository;
import com.envpermit.notification.Deadlines;
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
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * OBLIGATION → permit_obligation + its first deadline (design §3.7).
 * shortcut: law links (step 3) are skipped until ② permit_law_link exists; manual rows have no source document.
 */
@Component
class ObligationApplier implements SuggestionApplier {

    static final String DEADLINE_KIND = "OBLIGATION";

    private final PermitObligationRepository obligations;
    private final PermitItemRepository permits;
    private final Deadlines deadlines;
    private final AiRunRepository runs;
    private final ObjectMapper json;
    private final Validator validator;

    ObligationApplier(PermitObligationRepository obligations, PermitItemRepository permits, Deadlines deadlines,
                      AiRunRepository runs, ObjectMapper json, Validator validator) {
        this.obligations = obligations;
        this.permits = permits;
        this.deadlines = deadlines;
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
        registerDeadline(o, permit);
        return o.getId();
    }

    /** Also used by "이행 완료"; a null next due date removes the deadline. */
    void registerDeadline(PermitObligation o, PermitItem permit) {
        String title = permit.getName() + " · " + o.getSummary();
        deadlines.register(DEADLINE_KIND, o.getId(), permit.getProjectId(), title.substring(0, Math.min(300, title.length())),
                o.getNextDueDate(), null);
    }

    /** The extracted document (version diff's old set); null for manually added rows. */
    private Long sourceDocument(AiSuggestion s) {
        JsonNode ref = runs.findById(s.getRunId()).map(AiRun::getInputRef).orElse(null);
        return ref != null && ref.path("document_id").isNumber() ? ref.get("document_id").asLong() : null;
    }

    private ObligationPayload parse(JsonNode payload) {
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
