package com.envpermit.obligation;

import com.envpermit.core.permit.PermitItem;
import com.envpermit.core.permit.PermitItemRepository;
import com.envpermit.law.LawCatalogService;
import com.envpermit.review.AiSuggestion;
import com.envpermit.review.Kind;
import com.envpermit.review.SuggestionApplier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * OBLIGATION_REVISION → the live obligation, per judgement (④ design §4.1). Everything but ADDED rewrites the same
 * row (id kept, so fulfilment history and deadlines continue) after logging what it was. The judgement and pair
 * come from the original payload (server-computed); only {@code new} may carry the reviewer's edit.
 */
@Component
class RevisionApplier implements SuggestionApplier {

    static final String STALE = "비교 후 의무사항이 수정됨";

    private final ObligationApplier obligationApplier;
    private final PermitObligationRepository obligations;
    private final PermitItemRepository permits;
    private final LawCatalogService laws;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ObligationTasks tasks;

    RevisionApplier(ObligationApplier obligationApplier, PermitObligationRepository obligations,
                    PermitItemRepository permits, LawCatalogService laws, JdbcTemplate jdbc, ObjectMapper json,
                    ObligationTasks tasks) {
        this.tasks = tasks;
        this.obligationApplier = obligationApplier;
        this.obligations = obligations;
        this.permits = permits;
        this.laws = laws;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Kind kind() {
        return Kind.OBLIGATION_REVISION;
    }

    @Override
    public void checkTarget(long projectId, Long permitItemId) {
        obligationApplier.checkTarget(projectId, permitItemId);
    }

    @Override
    public void validate(JsonNode payload) {
        if (payload.hasNonNull("new")) {
            obligationApplier.parse(payload.get("new"));
        }
    }

    @Override
    public long apply(AiSuggestion s, JsonNode payload, ApplyContext ctx) {
        JsonNode computed = s.getPayload();
        String type = computed.path("change_type").asText();
        if (type.equals("ADDED")) {
            return openSiteImplementation(s, obligationApplier.apply(s, payload.get("new"), ctx), ctx);
        }
        PermitObligation o = obligations.lock(computed.path("prev_obligation_id").asLong())
                .filter(r -> r.getPermitItemId().equals(s.getTargetParentId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "비교한 의무사항이 삭제되었습니다."));
        if (o.getVersion() != computed.path("prev_obligation_version").asLong()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, STALE);
        }
        if (type.equals("REMOVED")) {
            if (ctx.bulk()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "개별 확인 필요");
            }
            if (!ctx.confirmAbsent()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "v2 원문 확인 체크 필요");
            }
        }
        boolean active = o.getStatus() == PermitObligation.Status.ACTIVE; // a CLOSED partner stays CLOSED (V9)
        ObligationPayload next = type.equals("REMOVED") ? null : obligationApplier.parse(payload.get("new"));
        boolean dueChanges = next != null && type.equals("CHANGED") && active && !sameSchedule(o.payload(), next);
        if (dueChanges && ctx.firstDueDate() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "다음 기한일 필요");
        }
        log(o, s, type, ctx);
        Long doc = obligationApplier.sourceDocument(s);
        PermitItem permit = permits.findById(o.getPermitItemId()).orElseThrow();
        switch (type) {
            case "CHANGED" -> {
                o.revise(next, doc);
                if (!computed.path("numeric_changes").isEmpty()) { // F2 §2.6: a person re-checks the limit; its value is never changed here
                    jdbc.update("UPDATE permit_limit SET needs_review = TRUE WHERE obligation_id = ? "
                            + "AND (effective_to IS NULL OR effective_to >= CURRENT_DATE)", o.getId());
                }
                if (dueChanges) {
                    o.moveTo(ctx.firstDueDate());
                }
                laws.linkLegalBasis(permit.getId(), next.legalBasis() == null ? List.of() : next.legalBasis());
            }
            case "MINOR", "UNCHANGED" -> o.reword(next, doc);
            case "REMOVED" -> o.closeByRevision(ctx.now());
            default -> throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "알 수 없는 판정: " + type);
        }
        // one open task + one deadline per obligation: moved with the due date, cancelled/removed when REMOVED
        tasks.sync(o, permit, "DUE_SET", ctx.actorId());
        return type.equals("CHANGED") ? openSiteImplementation(s, o.getId(), ctx) : o.getId();
    }

    /** ④ §4.8: CHANGED/ADDED still has to be carried out on site; the approver owns it until reassigned. */
    private long openSiteImplementation(AiSuggestion s, long obligationId, ApplyContext ctx) {
        jdbc.update("INSERT INTO revision_implementation (suggestion_id, obligation_id, assignee_id) VALUES (?, ?, ?)",
                s.getId(), obligationId, ctx.actorId());
        return obligationId;
    }

    /** frequency, frequency_text, period_text: any change needs a new next due date. */
    private static boolean sameSchedule(ObligationPayload a, ObligationPayload b) {
        return a.frequency() == b.frequency() && Objects.equals(a.frequencyText(), b.frequencyText())
                && Objects.equals(a.periodText(), b.periodText());
    }

    /** Append-only "before" (design §2.2): payload fields + source document, due date and status. */
    private void log(PermitObligation o, AiSuggestion s, String type, ApplyContext ctx) {
        ObjectNode before = json.valueToTree(o.payload());
        before.put("source_document_id", o.getSourceDocumentId());
        before.put("next_due_date", o.getNextDueDate() == null ? null : o.getNextDueDate().toString());
        before.put("status", o.getStatus().name());
        jdbc.update("INSERT INTO permit_obligation_revision_log (obligation_id, suggestion_id, change_type, before, at, by) "
                        + "VALUES (?, ?, ?, ?::jsonb, ?, ?)",
                o.getId(), s.getId(), type, before.toString(), Timestamp.from(ctx.now()), ctx.actorId());
    }
}
