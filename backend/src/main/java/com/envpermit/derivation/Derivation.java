package com.envpermit.derivation;

import static com.envpermit.core.project.ProjectAccess.userId;

import com.envpermit.core.document.DocumentStorage;
import com.envpermit.core.project.Project;
import com.envpermit.core.project.ProjectAccess;
import com.envpermit.core.project.ProjectRepository;
import com.envpermit.derivation.Evaluator.Determination;
import com.envpermit.derivation.Evaluator.Judgement;
import com.envpermit.law.LawCatalog;
import com.envpermit.law.LawCatalogRepository;
import com.envpermit.review.AiRun;
import com.envpermit.review.AiRunRepository;
import com.envpermit.review.AiSuggestion;
import com.envpermit.review.AiSuggestion.Status;
import com.envpermit.review.Kind;
import com.envpermit.review.Review;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Derive flow (design §4.1): validate attributes → ACTIVE rules for this facility/kind → evaluate each →
 * store the full judgement (incl. NOT_APPLICABLE) in object storage → ai_run + PENDING PERMIT_ITEM suggestions,
 * filtered by the re-run policy ({@link Rerun}) against the previous suggestion of each rule_key.
 */
@Service
class Derivation {

    static final Map<String, String> STAGE = Map.of("PLANNING", "기본계획·입지", "DESIGN", "설계",
            "CONSTRUCTION", "공사 중", "COMMISSIONING", "시운전", "OPERATION_START", "사용개시");

    /** 400 with the missing keys and their screen names, so the form can point at them. */
    static class MissingInputs extends RuntimeException {
        final List<String> fields;

        MissingInputs(List<String> fields) {
            super("필수 속성이 없습니다: " + fields.stream().map(f -> Fields.get(f).label()).collect(Collectors.joining(", ")));
            this.fields = fields;
        }
    }

    /** One line of the "추가 정보 필요" panel: a field and the PENDING ON_HOLD permits waiting for it. */
    record Missing(String field, String label, Fields.Type type, String unit, Map<String, String> values,
                   List<String> rules) {
    }

    private final ProjectRepository projects;
    private final ProjectAccess access;
    private final DerivationRuleRepository rules;
    private final LawCatalogRepository laws;
    private final Review review;
    private final AiRunRepository runs;
    private final DocumentStorage storage;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    Derivation(ProjectRepository projects, ProjectAccess access, DerivationRuleRepository rules,
               LawCatalogRepository laws, Review review, AiRunRepository runs, DocumentStorage storage, JdbcTemplate jdbc,
               ObjectMapper json, Clock clock) {
        this.projects = projects;
        this.access = access;
        this.rules = rules;
        this.laws = laws;
        this.review = review;
        this.runs = runs;
        this.storage = storage;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    JsonNode saveAttributes(long projectId, Jwt jwt, JsonNode attrs) {
        access.member(projectId, jwt);
        List<String> errors = Fields.validate(attrs);
        if (!errors.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(", ", errors));
        }
        ObjectNode clean = json.createObjectNode();
        attrs.fields().forEachRemaining(e -> {
            if (!e.getValue().isNull()) {
                clean.set(e.getKey(), e.getValue());
            }
        });
        Project project = projects.findById(projectId).orElseThrow();
        boolean startChanged = !Objects.equals(project.getAttributes().get("construction_start"),
                clean.get("construction_start"));
        project.setAttributes(clean);
        if (startChanged) {
            refreshRequiredByDates(projectId, date(clean));
        }
        return clean;
    }

    /** PENDING suggestions only; edited_payload is left alone, approved items are edited live (design §4.5). */
    private void refreshRequiredByDates(long projectId, LocalDate start) {
        for (Map<String, Object> row : jdbc.queryForList("SELECT id, payload::text AS payload FROM ai_suggestion "
                + "WHERE project_id = ? AND kind = 'PERMIT_ITEM' AND status = 'PENDING'", projectId)) {
            ObjectNode p = (ObjectNode) read((String) row.get("payload"));
            LocalDate d = Rerun.requiredByDate(p.get("required_by"), start);
            JsonNode value = d == null ? NullNode.getInstance() : TextNode.valueOf(d.toString());
            if (!value.equals(p.get("required_by_date"))) {
                p.set("required_by_date", value);
                // version bump: an approval screen still showing the old date gets 409 instead of approving it
                jdbc.update("UPDATE ai_suggestion SET payload = ?::jsonb, version = version + 1 WHERE id = ?",
                        p.toString(), row.get("id"));
            }
        }
    }

    @Transactional
    ObjectNode derive(long projectId, Jwt jwt) {
        access.member(projectId, jwt);
        Project project = projects.findById(projectId).orElseThrow();
        JsonNode attrs = project.getAttributes();
        List<String> missing = Fields.missingRequired(attrs);
        if (!missing.isEmpty()) {
            throw new MissingInputs(missing);
        }
        int rulesetVersion = rules.rulesetVersion();
        String facility = attrs.get("facility_type").asText();
        String kind = attrs.get("project_kind").asText();
        List<DerivationRule> active = rules.findByStatusOrderByRuleKey("ACTIVE").stream()
                .filter(r -> r.appliesTo(facility, kind)).toList();
        if (active.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "규칙이 없습니다.");
        }
        ObjectNode snapshot = Fields.withDerived(attrs);
        Map<Long, String> lawNames = lawNames(active);
        LocalDate start = date(attrs);
        Instant now = clock.instant();
        AiRun run = runs.save(AiRun.start(project.getCompanyId(), projectId, Kind.PERMIT_ITEM, snapshot, "rule-engine",
                "ruleset-v" + rulesetVersion, "RULE_ENGINE", userId(jwt), now));
        Map<String, AiSuggestion> previous = previousByRule(projectId, jwt);

        ArrayNode results = json.createArrayNode();
        ArrayNode warnings = json.createArrayNode();
        List<JsonNode> payloads = new ArrayList<>();
        for (DerivationRule r : active) {
            Judgement j = Evaluator.judge(r.getCondition(), snapshot, r.getFacilityTypes(), r.getProjectKinds());
            ObjectNode row = row(r, j, rulesetVersion, lawNames, start);
            results.add(row);
            if (j.determination() == Determination.ERROR) {
                continue; // a broken rule must not close what people are reviewing
            }
            AiSuggestion prev = previous.get(r.getRuleKey());
            switch (Rerun.decide(prev(prev), j.determination(), j.reason())) {
                case CREATE -> payloads.add(row);
                case CREATE_WITH_WARNING -> payloads.add(row.deepCopy().put("warning", "결재 중인 이전 판정 있음"));
                case SUPERSEDE -> {
                    review.rejectBySystem(prev.getId(), "재실행으로 대체: " + label(j.determination()) + " (run "
                            + run.getId() + ")");
                    payloads.add(row);
                }
                case CLOSE -> review.rejectBySystem(prev.getId(), "재실행으로 대체: 해당없음 — " + j.reason());
                case WARN_NOT_APPLICABLE -> warnings.addObject().put("rule_key", r.getRuleKey())
                        .put("permit_name", r.getPermitName()).put("permit_item_id", prev.getAppliedEntityId())
                        .put("message", "현재 규칙상 해당없음: " + j.reason());
                case NONE -> { }
            }
        }

        ObjectNode record = json.createObjectNode();
        record.put("rulesetVersion", rulesetVersion);
        record.put("ranAt", now.toString());
        for (Determination d : Determination.values()) {
            record.put(countKey(d), StreamSupport.stream(results.spliterator(), false)
                    .filter(x -> x.get("determination").asText().equals(d.name())).count());
        }
        record.set("attributes", snapshot);
        record.set("results", results);
        record.set("warnings", warnings);

        String key = "derivations/" + projectId + "/" + UUID.randomUUID() + ".json";
        byte[] bytes = bytes(record);
        storage.put(key, new ByteArrayInputStream(bytes), bytes.length, "application/json");
        review.addAi(run, null, payloads);
        run.finish(null, key, 0, 0, now); // deterministic: no tokens
        return record.put("runId", run.getId());
    }

    /** The last judgement of this project, as stored (incl. NOT_APPLICABLE rows). */
    ObjectNode latest(long projectId, Jwt jwt) {
        access.member(projectId, jwt);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, raw_response_key FROM ai_run "
                + "WHERE project_id = ? AND preparer = 'RULE_ENGINE' ORDER BY id DESC LIMIT 1", projectId);
        if (rows.isEmpty()) {
            return null; // never derived (seeded or hand-made project): an empty answer, not an error on every project page
        }
        try (InputStream in = storage.open((String) rows.get(0).get("raw_response_key"))) {
            return ((ObjectNode) json.readTree(in)).put("runId", ((Number) rows.get(0).get("id")).longValue());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Grouped by field, most-waited first (design §4.4). Saving a value + re-deriving is the client's two calls. */
    List<Missing> missingInputs(long projectId, Jwt jwt) {
        Map<String, List<String>> byField = new TreeMap<>();
        for (AiSuggestion s : review.list(projectId, jwt, null, Status.PENDING)) {
            JsonNode p = s.effectivePayload();
            if (s.getKind() == Kind.PERMIT_ITEM && "ON_HOLD".equals(p.path("determination").asText())) {
                for (JsonNode f : p.path("hold_info").path("missing_fields")) {
                    byField.computeIfAbsent(f.asText(), k -> new ArrayList<>()).add(p.path("permit_name").asText());
                }
            }
        }
        return byField.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, List<String>> e) -> -e.getValue().size()))
                .map(e -> {
                    Fields.Field f = Fields.get(e.getKey());
                    return new Missing(f.key(), f.label(), f.type(), f.unit(), f.values(), e.getValue());
                })
                .toList();
    }

    /** The latest AI suggestion per rule_key in this project (what the re-run policy compares against). */
    private Map<String, AiSuggestion> previousByRule(long projectId, Jwt jwt) {
        Map<String, AiSuggestion> out = new HashMap<>();
        for (AiSuggestion s : review.list(projectId, jwt, null, null)) {
            if (s.getKind() == Kind.PERMIT_ITEM && s.getOrigin() == AiSuggestion.Origin.AI) {
                out.merge(s.getPayload().path("rule_key").asText(), s, (a, b) -> a.getId() > b.getId() ? a : b);
            }
        }
        return out;
    }

    private static Rerun.Prev prev(AiSuggestion s) {
        if (s == null) {
            return null;
        }
        JsonNode p = s.getPayload();
        // reviewed_by null on a REJECTED row = the SYSTEM re-run path (review exception 1)
        return new Rerun.Prev(s.getStatus(), s.getReviewedBy() == null,
                Determination.valueOf(p.path("determination").asText()), p.path("reason").asText(null));
    }

    private static String label(Determination d) {
        return d == Determination.APPLICABLE ? "해당" : "판정 보류";
    }

    private static LocalDate date(JsonNode attrs) {
        JsonNode v = attrs.get("construction_start");
        return v == null || v.isNull() ? null : LocalDate.parse(v.asText());
    }

    /** PERMIT_ITEM payload (review contract §2.5 + derivation additions §4.2); also the judgement record row. */
    private ObjectNode row(DerivationRule r, Judgement j, int rulesetVersion, Map<Long, String> lawNames,
                           LocalDate start) {
        ObjectNode p = json.createObjectNode();
        p.put("permit_name", r.getPermitName());
        p.put("field", r.getField());
        p.putArray("legal_basis").add(r.getLegalBasisText());
        p.put("reason", j.reason());
        p.put("approval_stage", STAGE.get(r.getApprovalStage()));
        p.put("required_by_text", r.getRequiredBy() == null ? null : r.getRequiredBy().path("text").asText(null));
        p.put("determination", j.determination().name());
        if (j.determination() == Determination.ON_HOLD) {
            ObjectNode hold = p.putObject("hold_info");
            j.missingInputs().forEach(hold.putArray("missing_inputs")::add);
            j.missingFields().forEach(hold.putArray("missing_fields")::add);
            hold.put("criterion", r.getCriterionText());
        } else {
            p.putNull("hold_info");
        }
        p.put("rule_key", r.getRuleKey());
        p.put("rule_version", r.getVersion());
        p.put("ruleset_version", rulesetVersion);
        p.put("category", r.getCategory());
        p.set("law_refs", r.getLawRefs());
        p.set("required_by", r.getRequiredBy());
        LocalDate due = Rerun.requiredByDate(r.getRequiredBy(), start);
        p.put("required_by_date", due == null ? null : due.toString());
        p.put("criterion", r.getCriterionText());
        p.put("note", r.getNote());
        ArrayNode names = p.putArray("law_names");
        r.getLawRefs().forEach(ref -> names.add(lawNames.get(ref.path("law_catalog_id").asLong())));
        return p;
    }

    private Map<Long, String> lawNames(List<DerivationRule> active) {
        List<Long> ids = active.stream().flatMap(r -> StreamSupport.stream(r.getLawRefs().spliterator(), false))
                .map(ref -> ref.path("law_catalog_id").asLong()).distinct().toList();
        return laws.findAllById(ids).stream().collect(Collectors.toMap(LawCatalog::getId, LawCatalog::getName,
                (a, b) -> a));
    }

    private static String countKey(Determination d) {
        return switch (d) {
            case APPLICABLE -> "applicable";
            case ON_HOLD -> "onHold";
            case NOT_APPLICABLE -> "notApplicable";
            case ERROR -> "errors";
        };
    }

    private JsonNode read(String s) {
        try {
            return json.readTree(s);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private byte[] bytes(JsonNode n) {
        try {
            return json.writeValueAsBytes(n);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
