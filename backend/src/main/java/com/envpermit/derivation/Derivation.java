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
import com.envpermit.review.Kind;
import com.envpermit.review.Review;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * store the full judgement (incl. NOT_APPLICABLE) in object storage → ai_run + PENDING PERMIT_ITEM suggestions.
 * shortcut: no re-run policy yet — every derive adds a fresh set of PENDING suggestions (permit-derivation/02).
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
        projects.findById(projectId).orElseThrow().setAttributes(clean);
        return clean;
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

        ArrayNode results = json.createArrayNode();
        List<JsonNode> payloads = new ArrayList<>();
        for (DerivationRule r : active) {
            Judgement j = Evaluator.judge(r.getCondition(), snapshot, r.getFacilityTypes(), r.getProjectKinds());
            ObjectNode row = row(r, j, rulesetVersion, lawNames);
            results.add(row);
            if (j.determination() == Determination.APPLICABLE || j.determination() == Determination.ON_HOLD) {
                payloads.add(row);
            }
        }

        Instant now = clock.instant();
        ObjectNode record = json.createObjectNode();
        record.put("rulesetVersion", rulesetVersion);
        record.put("ranAt", now.toString());
        for (Determination d : Determination.values()) {
            record.put(countKey(d), StreamSupport.stream(results.spliterator(), false)
                    .filter(x -> x.get("determination").asText().equals(d.name())).count());
        }
        record.set("attributes", snapshot);
        record.set("results", results);

        String key = "derivations/" + projectId + "/" + UUID.randomUUID() + ".json";
        byte[] bytes = bytes(record);
        storage.put(key, new ByteArrayInputStream(bytes), bytes.length, "application/json");
        AiRun run = runs.save(AiRun.start(project.getCompanyId(), projectId, Kind.PERMIT_ITEM, snapshot, "rule-engine",
                "ruleset-v" + rulesetVersion, "RULE_ENGINE", userId(jwt), now));
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
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "아직 도출한 적이 없습니다.");
        }
        try (InputStream in = storage.open((String) rows.get(0).get("raw_response_key"))) {
            return ((ObjectNode) json.readTree(in)).put("runId", ((Number) rows.get(0).get("id")).longValue());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** PERMIT_ITEM payload (review contract §2.5 + derivation additions §4.2); also the judgement record row. */
    private ObjectNode row(DerivationRule r, Judgement j, int rulesetVersion, Map<Long, String> lawNames) {
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
        p.putNull("required_by_date"); // computed from construction_start in permit-derivation/02
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

    private byte[] bytes(JsonNode n) {
        try {
            return json.writeValueAsBytes(n);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
