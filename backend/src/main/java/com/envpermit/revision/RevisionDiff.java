package com.envpermit.revision;

import com.envpermit.core.document.DocumentRepository;
import com.envpermit.obligation.ObligationPayload;
import com.envpermit.obligation.PermitObligation;
import com.envpermit.obligation.PermitObligation.ClosedReason;
import com.envpermit.obligation.PermitObligationRepository;
import com.envpermit.revision.DiffEngine.Change;
import com.envpermit.revision.DiffEngine.MatchConfig;
import com.envpermit.revision.DiffEngine.Snapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Extracted rows of a new version → OBLIGATION_REVISION payloads (④ design §2.3), compared with the live
 * obligations of the previous version line as they are now. Runs inside the run's finishing transaction.
 */
@Component
public class RevisionDiff {

    // a partial amendment notice or change table yields few rows, and every missing one would be proposed as REMOVED
    static final int GUARD_MIN_OLD = 5;
    static final double GUARD_MIN_RATIO = 0.2;

    /** The new version yielded too few rows to diff; the run fails with this message and no suggestions. */
    public static class TooFewRows extends RuntimeException {
        TooFewRows(String message) {
            super(message);
        }
    }

    private final DocumentRepository documents;
    private final PermitObligationRepository obligations;
    private final ObjectMapper json;
    private final MatchConfig cfg;

    RevisionDiff(DocumentRepository documents, PermitObligationRepository obligations, ObjectMapper json,
                 @Value("${app.revision.match.t-pair}") double tPair,
                 @Value("${app.revision.match.t-suggest}") double tSuggest) {
        this.documents = documents;
        this.obligations = obligations;
        this.json = json;
        this.cfg = new MatchConfig(tPair, tSuggest);
    }

    public List<JsonNode> payloads(long permitId, long prevDocumentId, List<JsonNode> extracted) {
        // old set (design §3.3): the whole previous line, CLOSED too, except rows an earlier revision removed
        List<Snapshot> old = obligations
                .findByPermitItemIdAndSourceDocumentIdInOrderById(permitId, documents.versionLine(prevDocumentId))
                .stream().filter(o -> o.getClosedReason() != ClosedReason.REVISION_REMOVED)
                .map(RevisionDiff::snapshot).toList();
        String tooFew = tooFew(old.size(), extracted.size());
        if (tooFew != null) {
            throw new TooFewRows(tooFew);
        }
        List<ObligationPayload> rows = extracted.stream().map(n -> json.convertValue(n, ObligationPayload.class)).toList();
        return DiffEngine.diff(rows, old, cfg).stream().map(this::payload).toList();
    }

    /** null when the counts can be diffed; otherwise the user message. */
    static String tooFew(int old, int extracted) {
        boolean few = old > 0 && (extracted == 0 || old >= GUARD_MIN_OLD && extracted < old * GUARD_MIN_RATIO);
        return !few ? null : "새 문서에서 찾은 조건이 너무 적습니다(이전 %d건 → %d건). 일부만 바꾼 변경신고나 변경 이력표로 보입니다. 허가 전체본을 올려 주세요."
                .formatted(old, extracted);
    }

    static Snapshot snapshot(PermitObligation o) {
        return new Snapshot(o.getId(), o.getVersion(), o.getStatus() == PermitObligation.Status.CLOSED, o.payload());
    }

    JsonNode payload(Change c) {
        ObjectNode p = json.createObjectNode();
        p.put("change_type", c.type().name());
        p.put("prev_obligation_id", c.old() == null ? null : c.old().id());
        p.put("prev_obligation_version", c.old() == null ? null : c.old().version());
        p.put("prev_obligation_closed", c.old() != null && c.old().closed());
        p.set("prev", c.old() == null ? null : json.valueToTree(c.old().payload())); // for the review screen
        p.set("new", c.next() == null ? null : json.valueToTree(c.next()));
        ObjectNode match = p.putObject("match");
        match.put("method", c.method() == null ? null : c.method().name());
        match.put("similarity", c.similarity());
        match.set("flags", json.valueToTree(c.flags()));
        p.set("changed_fields", json.valueToTree(c.changedFields()));
        p.set("numeric_changes", json.valueToTree(c.numericChanges()));
        return p;
    }
}
