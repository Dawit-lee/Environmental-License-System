package com.envpermit.review;

import static com.envpermit.core.project.ProjectAccess.userId;

import com.envpermit.core.project.Project;
import com.envpermit.core.project.ProjectAccess;
import com.envpermit.core.project.ProjectMember;
import com.envpermit.core.project.ProjectRepository;
import com.envpermit.review.AiSuggestion.Decision;
import com.envpermit.review.AiSuggestion.Origin;
import com.envpermit.review.AiSuggestion.Status;
import com.envpermit.review.SuggestionApplier.ApplyContext;
import com.envpermit.review.SuggestionEvent.Type;
import com.envpermit.review.Transition.Action;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The review pipeline (design §2). Every decision is one transaction: row lock + version check, state machine,
 * the kind's Applier, status update and an append-only event. Nothing here is read by deadlines/notifications.
 */
@Service
public class Review {

    /** Pair restructure reasons from version diff (④). Suggestions closed this way can never be reopened. */
    public enum Restructure {
        SPLIT("매칭 분리"), MERGE("매칭 결합");

        final String reason;

        Restructure(String reason) {
            this.reason = reason;
        }

        static boolean isRestructure(String reason) {
            return reason != null && Set.of(SPLIT.reason, MERGE.reason).contains(reason);
        }
    }

    private final AiSuggestionRepository suggestions;
    private final AiRunRepository runs;
    private final SuggestionEventRepository events;
    private final ProjectRepository projects;
    private final ProjectAccess access;
    private final Map<Kind, SuggestionApplier> appliers;
    private final ObjectMapper json;
    private final Clock clock;

    Review(AiSuggestionRepository suggestions, AiRunRepository runs, SuggestionEventRepository events,
           ProjectRepository projects, ProjectAccess access, List<SuggestionApplier> appliers, ObjectMapper json,
           Clock clock) {
        this.suggestions = suggestions;
        this.runs = runs;
        this.events = events;
        this.projects = projects;
        this.access = access;
        this.appliers = appliers.stream().collect(Collectors.toMap(SuggestionApplier::kind, Function.identity()));
        this.json = json;
        this.clock = clock;
    }

    /** origin=MANUAL row added by a reviewer. Without runId it goes to the project's "manual entry" run. */
    @Transactional
    public AiSuggestion addManual(long projectId, Jwt jwt, Long runId, Kind kind, Long targetParentId,
                                  JsonNode payload) {
        access.member(projectId, jwt);
        SuggestionApplier applier = applier(kind);
        applier.checkTarget(projectId, targetParentId);
        applier.validate(payload);
        Project project = projects.findById(projectId).orElseThrow();
        long run = runId != null ? runInProject(runId, projectId, kind)
                // shortcut: two first-ever manual adds at once may create two manual runs; harmless, both list fine
                : runs.findFirstByProjectIdAndKindAndPreparerOrderById(projectId, kind, AiRun.MANUAL)
                        .orElseGet(() -> runs.save(AiRun.manual(project.getCompanyId(), projectId, kind, userId(jwt),
                                now())))
                        .getId();
        AiSuggestion s = suggestions.save(new AiSuggestion(project.getCompanyId(), projectId, run, kind,
                targetParentId, Origin.MANUAL, payload));
        event(s, userId(jwt), Type.CREATED, null);
        return s;
    }

    /** AI output of a finished run: PENDING suggestions, CREATED by SYSTEM. Runs in the caller's transaction. */
    @Transactional
    public void addAi(AiRun run, long targetParentId, List<JsonNode> payloads) {
        for (JsonNode payload : payloads) {
            AiSuggestion s = suggestions.save(new AiSuggestion(run.getCompanyId(), run.getProjectId(), run.getId(),
                    run.getKind(), targetParentId, Origin.AI, payload));
            event(s, null, Type.CREATED, null);
        }
    }

    /** Riskiest first (design §4.1): quote_check MISMATCH → UNVERIFIABLE → VERIFIED → none, then oldest first. */
    public List<AiSuggestion> list(long projectId, Jwt jwt, Long runId, Status status) {
        access.member(projectId, jwt);
        return suggestions.findByProjectIdOrderById(projectId).stream()
                .filter(s -> runId == null || runId.equals(s.getRunId()))
                .filter(s -> status == null || status == s.getStatus())
                .sorted(Comparator.comparingInt(Review::risk))
                .toList();
    }

    private static int risk(AiSuggestion s) {
        String qc = s.effectivePayload().path("quote_check").asText("");
        return switch (qc) {
            case "MISMATCH" -> 0;
            case "UNVERIFIABLE" -> 1;
            case "VERIFIED" -> 2;
            default -> 3;
        };
    }

    public List<SuggestionEvent> events(long id, Jwt jwt) {
        AiSuggestion s = suggestions.findById(id).orElseThrow(Review::notFound);
        access.member(s.getProjectId(), jwt);
        return events.findBySuggestionIdOrderById(id);
    }

    @Transactional
    public AiSuggestion edit(long id, Jwt jwt, JsonNode edited, long version) {
        AiSuggestion s = lock(id, version);
        ProjectMember m = access.member(s.getProjectId(), jwt);
        Transition.next(s.getStatus(), Action.EDIT, m.canSelfApprove(), true);
        applier(s.getKind()).validate(edited);
        JsonNode diff = diff(s.effectivePayload(), edited);
        s.edit(edited);
        event(s, m.getUserId(), Type.EDITED, diff);
        return s;
    }

    @Transactional
    public AiSuggestion submit(long id, Jwt jwt, Decision decision, String reason, long version) {
        AiSuggestion s = lock(id, version);
        ProjectMember m = access.member(s.getProjectId(), jwt);
        Transition.next(s.getStatus(), Action.SUBMIT, m.canSelfApprove(), false);
        s.submit(decision, m.getUserId(), now());
        event(s, m.getUserId(), Type.SUBMITTED, json.createObjectNode()
                .put("proposed_decision", decision.name()).put("reason", reason));
        return s;
    }

    /** @param version null only for bulk approval (each item is decided on its current version). */
    @Transactional
    public AiSuggestion accept(long id, Jwt jwt, LocalDate firstDueDate, Long version, boolean bulk) {
        AiSuggestion s = version == null ? lock(id) : lock(id, version);
        ProjectMember m = access.member(s.getProjectId(), jwt);
        Status next = Transition.next(s.getStatus(), Action.ACCEPT, m.canSelfApprove(), s.getEditedPayload() != null);
        SuggestionApplier applier = applier(s.getKind());
        JsonNode payload = s.effectivePayload();
        applier.validate(payload);
        long liveId = applier.apply(s, payload, new ApplyContext(m.getUserId(), firstDueDate, now()));
        s.decide(next, m.getUserId(), now(), null, liveId);
        event(s, m.getUserId(), Type.ACCEPTED, json.createObjectNode()
                .put("first_due_date", firstDueDate == null ? null : firstDueDate.toString()).put("bulk", bulk));
        return s;
    }

    @Transactional
    public AiSuggestion reject(long id, Jwt jwt, String reason, long version) {
        AiSuggestion s = lock(id, version);
        ProjectMember m = access.member(s.getProjectId(), jwt);
        Status next = Transition.next(s.getStatus(), Action.REJECT, m.canSelfApprove(), false);
        return close(s, next, m.getUserId(), reason);
    }

    @Transactional
    public AiSuggestion returnToPending(long id, Jwt jwt, String reason, long version) {
        AiSuggestion s = lock(id, version);
        ProjectMember m = access.member(s.getProjectId(), jwt);
        Transition.next(s.getStatus(), Action.RETURN, m.canSelfApprove(), false);
        s.backToPending();
        event(s, m.getUserId(), Type.RETURNED, json.createObjectNode().put("reason", reason));
        return s;
    }

    @Transactional
    public AiSuggestion reopen(long id, Jwt jwt, long version) {
        AiSuggestion s = lock(id, version);
        ProjectMember m = access.member(s.getProjectId(), jwt);
        Transition.next(s.getStatus(), Action.REOPEN, m.canSelfApprove(), false);
        if (Restructure.isRestructure(s.getRejectReason())) {
            // a replacement suggestion already exists; reviving this one would duplicate it
            throw new ResponseStatusException(HttpStatus.CONFLICT, "짝 조정으로 닫힌 제안은 재검토할 수 없습니다.");
        }
        s.backToPending();
        event(s, m.getUserId(), Type.REOPENED, null);
        return s;
    }

    /** Bulk approval needs can_bulk_approve in every project the ids belong to (else 403 for the whole call). */
    public void requireBulk(Collection<Long> ids, Jwt jwt) {
        Set<Long> projectIds = new HashSet<>();
        suggestions.findAllById(ids).forEach(s -> projectIds.add(s.getProjectId()));
        for (long pid : projectIds) {
            if (!access.member(pid, jwt).canBulkApprove()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "일괄 승인 권한이 없습니다.");
            }
        }
    }

    /** Exception 1 (design §2.2): derivation re-run supersedes a PENDING suggestion. Internal, actor = SYSTEM. */
    @Transactional
    public AiSuggestion rejectBySystem(long id, String reason) {
        AiSuggestion s = lock(id);
        requirePending(s);
        return close(s, Status.REJECTED, null, reason);
    }

    /**
     * Exception 2 (design §2.2): version diff split/merge is a restructure, not a decision. Any member who may edit
     * can do it without can_self_approve; the original closes as REJECTED and can never be reopened.
     */
    @Transactional
    public AiSuggestion closeForRestructure(long id, long actorId, Restructure how) {
        AiSuggestion s = lock(id);
        access.member(s.getProjectId(), actorId);
        requirePending(s);
        return close(s, Status.REJECTED, actorId, how.reason);
    }

    private AiSuggestion close(AiSuggestion s, Status next, Long actorId, String reason) {
        s.decide(next, actorId, now(), reason, null);
        event(s, actorId, Type.REJECTED, json.createObjectNode().put("reason", reason));
        return s;
    }

    private static void requirePending(AiSuggestion s) {
        if (s.getStatus() != Status.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "검토 대기 상태가 아닙니다.");
        }
    }

    private AiSuggestion lock(long id) {
        return suggestions.lock(id).orElseThrow(Review::notFound);
    }

    private AiSuggestion lock(long id, long version) {
        AiSuggestion s = lock(id);
        if (s.getVersion() != version) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "다른 사람이 먼저 변경했습니다. 새로고침 후 다시 시도하세요.");
        }
        return s;
    }

    private long runInProject(long runId, long projectId, Kind kind) {
        return runs.findById(runId).filter(r -> r.getProjectId() == projectId && r.getKind() == kind)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "run이 이 프로젝트에 없습니다."))
                .getId();
    }

    private SuggestionApplier applier(Kind kind) {
        SuggestionApplier a = appliers.get(kind);
        if (a == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "지원하지 않는 종류입니다: " + kind);
        }
        return a;
    }

    private void event(AiSuggestion s, Long actorId, Type type, JsonNode data) {
        events.save(new SuggestionEvent(s.getId(), actorId, now(), type, data));
    }

    /** Top-level field diff for the EDITED event: {field: {from, to}}. */
    private JsonNode diff(JsonNode before, JsonNode after) {
        ObjectNode out = json.createObjectNode();
        Set<String> fields = new HashSet<>();
        before.fieldNames().forEachRemaining(fields::add);
        after.fieldNames().forEachRemaining(fields::add);
        for (String f : fields) {
            if (!Objects.equals(before.get(f), after.get(f))) {
                ObjectNode change = out.putObject(f);
                change.set("from", before.get(f));
                change.set("to", after.get(f));
            }
        }
        return out;
    }

    private Instant now() {
        return clock.instant();
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
