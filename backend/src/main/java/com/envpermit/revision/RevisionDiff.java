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
        List<ObligationPayload> rows = extracted.stream().map(n -> json.convertValue(n, ObligationPayload.class)).toList();
        return DiffEngine.diff(rows, old, cfg).stream().map(this::payload).toList();
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
