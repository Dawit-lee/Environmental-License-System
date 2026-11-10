package com.envpermit.revision;

import static com.envpermit.core.project.ProjectAccess.userId;

import com.envpermit.obligation.ObligationPayload;
import com.envpermit.obligation.PermitObligation;
import com.envpermit.obligation.PermitObligation.ClosedReason;
import com.envpermit.obligation.PermitObligationRepository;
import com.envpermit.review.AiSuggestion;
import com.envpermit.review.Kind;
import com.envpermit.review.Review;
import com.envpermit.review.Review.Restructure;
import com.envpermit.revision.DiffEngine.ChangeType;
import com.envpermit.revision.DiffEngine.Change;
import com.envpermit.revision.DiffEngine.Flag;
import com.envpermit.revision.DiffEngine.Method;
import com.envpermit.revision.DiffEngine.Snapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Split / merge / rediff of PENDING revision suggestions (④ design §4.3–4.4). A restructure, not a decision: any
 * member may do it and live tables never change. Originals close through ③ exception 2 and can't be reopened.
 */
@Service
public class RevisionPairing {

    private static final Set<String> PAIRED = Set.of("CHANGED", "MINOR", "UNCHANGED");

    private final Review review;
    private final RevisionDiff diff;
    private final PermitObligationRepository obligations;
    private final ObjectMapper json;

    RevisionPairing(Review review, RevisionDiff diff, PermitObligationRepository obligations, ObjectMapper json) {
        this.review = review;
        this.diff = diff;
        this.obligations = obligations;
        this.json = json;
    }

    /** One pair → ADDED (its new row) + REMOVED (its old obligation, as compared). */
    @Transactional
    public List<AiSuggestion> unpair(long id, long version, Jwt jwt) {
        AiSuggestion s = revision(review.lockForRestructure(id, version, jwt), PAIRED, "짝이 있는 제안만 분리할 수 있습니다.");
        JsonNode p = s.getPayload();
        Snapshot old = new Snapshot(p.path("prev_obligation_id").asLong(), p.path("prev_obligation_version").asLong(),
                p.path("prev_obligation_closed").asBoolean(), row(p.get("prev")));
        List<JsonNode> replacements = List.of(
                diff.payload(lone(ChangeType.ADDED, null, row(s.effectivePayload().get("new")))),
                diff.payload(lone(ChangeType.REMOVED, old, null)));
        return review.restructure(List.of(s), userId(jwt), Restructure.SPLIT, replacements);
    }

    /** ADDED + REMOVED of the same run → one pair judged against the obligation as it is now (method MANUAL). */
    @Transactional
    public AiSuggestion pair(long addedId, long addedVersion, long removedId, long removedVersion, Jwt jwt) {
        AiSuggestion added = revision(review.lockForRestructure(addedId, addedVersion, jwt), Set.of("ADDED"),
                "결합할 추가 제안이 아닙니다.");
        AiSuggestion removed = revision(review.lockForRestructure(removedId, removedVersion, jwt), Set.of("REMOVED"),
                "결합할 삭제 제안이 아닙니다.");
        if (!added.getRunId().equals(removed.getRunId())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "같은 비교 결과의 제안끼리만 결합할 수 있습니다.");
        }
        Snapshot old = live(removed.getPayload().path("prev_obligation_id").asLong());
        ObligationPayload next = row(added.effectivePayload().get("new"));
        double sim = DiffEngine.similarity(old.payload().requirementText(), next.requirementText());
        JsonNode merged = diff.payload(DiffEngine.judge(old, next, Method.MANUAL, sim, List.of()));
        return review.restructure(List.of(added, removed), userId(jwt), Restructure.MERGE, List.of(merged)).get(0);
    }

    /** Judge again against the obligation's current values; a partner closed by a revision meanwhile → ADDED. */
    @Transactional
    public AiSuggestion rediff(long id, long version, Jwt jwt) {
        AiSuggestion s = revision(review.lockForRestructure(id, version, jwt), Set.of("CHANGED", "MINOR", "UNCHANGED",
                "REMOVED"), "다시 비교할 짝이 없습니다.");
        JsonNode p = s.getPayload();
        PermitObligation o = obligations.findById(p.path("prev_obligation_id").asLong()).orElse(null);
        Change c = recompute(p, s.effectivePayload().get("new"), o);
        JsonNode computed = withNew(diff.payload(c), p.get("new"));
        JsonNode edited = s.getEditedPayload() == null ? null : withNew(diff.payload(c), s.getEditedPayload().get("new"));
        ObjectNode data = json.createObjectNode().put("from", p.path("change_type").asText()).put("to", c.type().name());
        data.put("prev_obligation_version", c.old() == null ? null : c.old().version());
        return review.rediff(s, userId(jwt), computed, edited, data);
    }

    private Change recompute(JsonNode p, JsonNode effectiveNew, PermitObligation o) {
        boolean gone = o == null || o.getClosedReason() == ClosedReason.REVISION_REMOVED;
        if (p.path("change_type").asText().equals("REMOVED")) {
            if (gone) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "이미 개정으로 종료된 의무사항입니다.");
            }
            return lone(ChangeType.REMOVED, RevisionDiff.snapshot(o), null);
        }
        ObligationPayload next = row(effectiveNew);
        if (gone) {
            return lone(ChangeType.ADDED, null, next);
        }
        Snapshot old = RevisionDiff.snapshot(o);
        JsonNode match = p.path("match");
        List<Flag> flags = new ArrayList<>();
        match.path("flags").forEach(f -> flags.add(Flag.valueOf(f.asText())));
        return DiffEngine.judge(old, next, Method.valueOf(match.path("method").asText("MANUAL")),
                DiffEngine.similarity(old.payload().requirementText(), next.requirementText()), flags);
    }

    /** The computed payload, keeping the stored (original or edited) `new` rather than the judged one. */
    private static JsonNode withNew(JsonNode computed, JsonNode next) {
        if (next != null && !next.isNull()) {
            ((ObjectNode) computed).set("new", next);
        }
        return computed;
    }

    private Snapshot live(long obligationId) {
        PermitObligation o = obligations.findById(obligationId)
                .filter(r -> r.getClosedReason() != ClosedReason.REVISION_REMOVED)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "이미 개정으로 종료된 의무사항입니다."));
        return RevisionDiff.snapshot(o);
    }

    private static Change lone(ChangeType type, Snapshot old, ObligationPayload next) {
        return new Change(type, old, next, null, null, List.of(), List.of(), List.of());
    }

    private ObligationPayload row(JsonNode node) {
        return json.convertValue(node, ObligationPayload.class);
    }

    private static AiSuggestion revision(AiSuggestion s, Set<String> types, String message) {
        if (s.getKind() != Kind.OBLIGATION_REVISION || !types.contains(s.getPayload().path("change_type").asText())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
        }
        return s;
    }
}
