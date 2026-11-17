package com.envpermit.extraction;

import static com.envpermit.core.project.ProjectAccess.userId;

import com.envpermit.core.company.CompanyRepository;
import com.envpermit.core.document.Document;
import com.envpermit.core.document.DocumentRepository;
import com.envpermit.core.document.DocumentStorage;
import com.envpermit.core.permit.PermitItemRepository;
import com.envpermit.core.project.Project;
import com.envpermit.core.project.ProjectAccess;
import com.envpermit.core.project.ProjectRepository;
import com.envpermit.obligation.Frequency;
import com.envpermit.obligation.ObligationPayload;
import com.envpermit.obligation.ObligationPayload.Category;
import com.envpermit.obligation.ObligationPayload.Type;
import com.envpermit.review.AiRun;
import com.envpermit.review.AiRunRepository;
import com.envpermit.review.Kind;
import com.envpermit.review.Review;
import com.envpermit.revision.RevisionDiff;
import com.envpermit.schedule.ScheduleSuggestion;
import com.envpermit.schedule.ScheduleSuggestions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * ① obligation extraction (design §3.1): start() records a RUNNING ai_run, run() does the slow part off the
 * request thread: prepare input → LLM (1 retry on API error / schema violation) → citation_key + quote_check
 * → PENDING suggestions and SUCCEEDED in one transaction. A failed run keeps no suggestions.
 */
@Service
public class Extraction {

    private static final Logger log = LoggerFactory.getLogger(Extraction.class);
    static final String PDF = "application/pdf";
    static final int ATTEMPTS = 2;

    private final ProjectAccess access;
    private final ProjectRepository projects;
    private final CompanyRepository companies;
    private final PermitItemRepository permits;
    private final DocumentRepository documents;
    private final DocumentStorage storage;
    private final AiRunRepository runs;
    private final Review review;
    private final RevisionDiff revisions;
    private final ScheduleSuggestions scheduleSuggestions;
    private final ExtractionInputPreparer preparer;
    private final LlmClient llm;
    private final ObjectMapper json;
    private final Validator validator;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration staleAfter;
    private final int dailyLimit;
    private final PageImages pageImages;

    Extraction(ProjectAccess access, ProjectRepository projects, CompanyRepository companies,
               PermitItemRepository permits, DocumentRepository documents, DocumentStorage storage,
               AiRunRepository runs, Review review, RevisionDiff revisions, ScheduleSuggestions scheduleSuggestions,
               ExtractionInputPreparer preparer, LlmClient llm,
               ObjectMapper json, Validator validator, TransactionTemplate tx, Clock clock,
               @Value("${app.extraction.stale-after}") Duration staleAfter,
               @Value("${app.extraction.daily-limit}") int dailyLimit, PageImages pageImages) {
        this.pageImages = pageImages;
        this.dailyLimit = dailyLimit;
        this.access = access;
        this.projects = projects;
        this.companies = companies;
        this.permits = permits;
        this.documents = documents;
        this.storage = storage;
        this.runs = runs;
        this.review = review;
        this.revisions = revisions;
        this.scheduleSuggestions = scheduleSuggestions;
        this.preparer = preparer;
        this.llm = llm;
        this.json = json;
        this.validator = validator;
        this.tx = tx;
        this.clock = clock;
        this.staleAfter = staleAfter;
    }

    /** What the model must return (ObligationPrompt.SCHEMA). Bad enum / missing field = schema violation. */
    record ModelOutput(@NotNull List<@Valid @NotNull Item> obligations) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record Item(String citationRaw, @NotBlank String requirementText, @NotBlank String summary, @NotNull Type type,
                @NotNull Category category, String responsibleParty, @NotNull Frequency frequency,
                String frequencyText, String periodText, List<String> legalBasis, @NotNull @Positive Integer pageNo,
                @NotBlank String sourceQuote) {
    }

    /** Thrown for a response that is not usable; the message ends up in ai_run.error_message. */
    static class BadResponse extends RuntimeException {
        BadResponse(String message) {
            super(message);
        }
    }

    @Transactional
    public AiRun start(long projectId, long permitId, long documentId, Jwt jwt,
                       boolean confirmDuplicate) {
        Document doc = startable(projectId, permitId, documentId, jwt);
        if (doc.getSupersedesDocumentId() != null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "개정 문서는 [개정 비교]로 처리합니다.");
        }
        if (!confirmDuplicate && runs.extractedBefore(projectId, doc.getSha256())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "이미 추출한 문서입니다. 확인하면 새로 추출합니다(이전 결과는 그대로 남습니다).");
        }
        return save(projectId, Kind.OBLIGATION, doc, jwt);
    }

    /** ④ version diff (design §3.1): same extraction, then DiffEngine against the previous version line. */
    @Transactional
    public AiRun startRevision(long projectId, long permitId, long documentId, Jwt jwt) {
        Document doc = startable(projectId, permitId, documentId, jwt);
        if (doc.getSupersedesDocumentId() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "대체할 이전 버전이 없는 문서입니다.");
        }
        documents.requireNoOpenReview(doc.getId());
        return save(projectId, Kind.OBLIGATION_REVISION, doc, jwt);
    }

    private Document startable(long projectId, long permitId, long documentId, Jwt jwt) {
        access.member(projectId, jwt);
        permits.findByIdAndProjectId(permitId, projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Document doc = documents.findByIdAndPermitItemId(documentId, permitId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return extractable(projectId, doc);
    }

    /** Company AI setting on (403), under the company's daily run limit (429, M4) and a PDF (422). */
    private Document extractable(long projectId, Document doc) {
        Project project = projects.findById(projectId).orElseThrow();
        if (!companies.findById(project.getCompanyId()).orElseThrow().isAiExtractionEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "회사 설정에서 AI 추출이 꺼져 있습니다. 회사 마스터가 켤 수 있습니다.");
        }
        Instant todayStart = LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant();
        if (runs.countByCompanyIdAndStartedAtGreaterThanEqualAndPreparerNot(project.getCompanyId(), todayStart,
                AiRun.MANUAL) >= dailyLimit) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "오늘 회사의 AI 추출 한도(%d건)를 다 썼습니다. 내일 다시 시도해 주세요.".formatted(dailyLimit));
        }
        if (!PDF.equals(doc.getContentType())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "PDF 문서만 AI 추출할 수 있습니다.");
        }
        return doc;
    }

    /**
     * Document → schedule rows (design-schedule/07): any project document whose type has a prompt. No duplicate guard:
     * a second run over the same document is matched against live items at approval, so it adds nothing twice.
     */
    @Transactional
    public AiRun startSchedule(long projectId, long documentId, Jwt jwt) {
        access.member(projectId, jwt);
        Document doc = extractable(projectId, documents.findByIdAndProjectId(documentId, projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        if (!SchedulePrompt.supports(doc.getDocType())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "일정 추출은 공고문·현장설명서·과업내용서·공문·회의록 문서만 할 수 있습니다.");
        }
        ObjectNode inputRef = json.createObjectNode().put("document_id", doc.getId()).put("sha256", doc.getSha256())
                .put("doc_type", doc.getDocType());
        long companyId = projects.findById(projectId).orElseThrow().getCompanyId();
        return runs.save(AiRun.start(companyId, projectId, Kind.SCHEDULE_ITEM, inputRef, llm.model(),
                SchedulePrompt.version(doc.getDocType()), preparer.name(), userId(jwt), clock.instant())
                .provider(llm.provider().name()));
    }

    private AiRun save(long projectId, Kind kind, Document doc, Jwt jwt) {
        ObjectNode inputRef = json.createObjectNode().put("document_id", doc.getId())
                .put("permit_item_id", doc.getPermitItemId()).put("sha256", doc.getSha256());
        if (kind == Kind.OBLIGATION_REVISION) {
            inputRef.put("prev_document_id", doc.getSupersedesDocumentId());
        }
        long companyId = projects.findById(projectId).orElseThrow().getCompanyId();
        return runs.save(AiRun.start(companyId, projectId, kind, inputRef, llm.model(), ObligationPrompt.VERSION,
                preparer.name(), userId(jwt), clock.instant()).provider(llm.provider().name()));
    }

    /** The worker queue was full (M4): the run never starts. */
    public void rejectQueued(long runId) {
        tx.executeWithoutResult(s -> runs.findById(runId).ifPresent(r ->
                r.finish("대기열이 가득 차 시작하지 못했습니다.", null, null, null, clock.instant())));
    }

    /** The slow part. Never throws: every outcome ends as SUCCEEDED or FAILED on the run. */
    public void run(long runId) {
        AiRun run = runs.findById(runId).orElseThrow();
        boolean schedule = run.getKind() == Kind.SCHEDULE_ITEM;
        String docType = run.getInputRef().path("doc_type").asText();
        long permitId = schedule ? run.getProjectId() : run.getInputRef().get("permit_item_id").asLong();
        String rawKey = null;
        int inTokens = 0;
        int outTokens = 0;
        String error = null;
        List<JsonNode> payloads = null;
        try {
            Document doc = documents.findById(run.getInputRef().get("document_id").asLong())
                    .orElseThrow(() -> new BadResponse("문서가 삭제되었습니다."));
            byte[] bytes;
            try (InputStream in = storage.open(doc.getStorageKey())) {
                bytes = in.readAllBytes();
            }
            PreparedInput input = preparer.prepare(bytes);
            for (int attempt = 1; attempt <= ATTEMPTS && payloads == null; attempt++) {
                String raw;
                try {
                    raw = schedule
                            ? llm.send(SchedulePrompt.SYSTEM, input.llmContent(), SchedulePrompt.instruction(docType),
                                    SchedulePrompt.SCHEMA)
                            : llm.send(ObligationPrompt.SYSTEM, input.llmContent(), ObligationPrompt.INSTRUCTION,
                                    ObligationPrompt.SCHEMA);
                } catch (RuntimeException e) {
                    log.warn("LLM call failed (run {}, attempt {})", runId, attempt, e);
                    // our clients throw IllegalStateException with a curated message; SDK/transport text stays in the log (M10)
                    error = "AI 서비스 호출에 실패했습니다" + (e instanceof IllegalStateException ? ": " + e.getMessage() : ".");
                    continue;
                }
                rawKey = "companies/" + run.getCompanyId() + "/ai-runs/" + runId + "/response-" + attempt + ".json";
                byte[] rawBytes = raw.getBytes(StandardCharsets.UTF_8);
                storage.put(rawKey, new ByteArrayInputStream(rawBytes), rawBytes.length, "application/json");
                try {
                    JsonNode res = json.readTree(raw);
                    inTokens += llm.provider().inputTokens(res);
                    outTokens += llm.provider().outputTokens(res);
                    String text = llm.provider().text(res);
                    payloads = schedule ? scheduleRows(json, text, input.pageTexts(), doc.getFileName()).stream()
                            // via text: a Long field would stay a LongNode, unequal to the IntNode Hibernate reads
                            // back, so the row would look dirty and bump its version before anyone saw it
                            .map(r -> asReadBack(scheduleSuggestions.annotate(run.getProjectId(), r))).toList()
                            : payloads(parse(json, validator, text), input.pageTexts());
                    error = null;
                } catch (BadResponse e) {
                    log.warn("Unusable LLM response (run {}, attempt {}): {}", runId, attempt, e.getMessage());
                    error = e.getMessage();
                } catch (Exception e) {
                    log.warn("Unusable LLM response (run {}, attempt {})", runId, attempt, e);
                    error = "AI 응답 형식이 올바르지 않습니다.";
                }
            }
        } catch (IllegalArgumentException | BadResponse e) {
            error = e.getMessage();
        } catch (Exception e) {
            log.error("Extraction run {} failed", runId, e);
            error = "추출 중 오류가 발생했습니다.";
        }
        finish(runId, permitId, payloads, error, rawKey, inTokens, outTokens);
    }

    private void finish(long runId, long permitId, List<JsonNode> payloads, String error, String rawKey,
                        int inTokens, int outTokens) {
        tx.executeWithoutResult(status -> {
            AiRun run = runs.findById(runId).orElseThrow();
            if (!AiRun.RUNNING.equals(run.getStatus())) {
                return; // already closed as stale; don't resurrect it
            }
            if (payloads != null && run.getKind() == Kind.SCHEDULE_ITEM) {
                for (Kind kind : List.of(Kind.SCHEDULE_ITEM, Kind.PROJECT_EVENT)) {
                    review.addAi(run, kind, run.getProjectId(), payloads.stream()
                            .filter(p -> rowKind(p) == kind).toList());
                }
            } else if (payloads != null) {
                review.addAi(run, permitId, run.getKind() != Kind.OBLIGATION_REVISION ? payloads
                        : revisions.payloads(permitId, run.getInputRef().get("prev_document_id").asLong(), payloads));
            }
            run.finish(payloads != null ? null : error, rawKey, inTokens, outTokens, clock.instant());
        });
    }

    /** Static so the manual quality eval (ExtractionEval, test sources) reads responses exactly as a run does. */
    static ModelOutput parse(ObjectMapper json, Validator validator, String text) throws Exception {
        ModelOutput out = json.readValue(text, ModelOutput.class);
        String errors = validator.validate(out).stream().map(v -> v.getPropertyPath() + " " + v.getMessage())
                .sorted().collect(Collectors.joining(", "));
        if (!errors.isEmpty()) {
            throw new BadResponse("AI 응답이 스키마와 맞지 않습니다: " + errors);
        }
        return out;
    }

    private JsonNode asReadBack(Object value) {
        try {
            return json.readTree(json.writeValueAsString(value));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Kind rowKind(JsonNode payload) {
        String type = payload.path("type").asText();
        return type.equals("EVENT") || type.equals("RECURRING") ? Kind.PROJECT_EVENT : Kind.SCHEDULE_ITEM;
    }

    /** What the model returns for a schedule extraction (SchedulePrompt.SCHEMA); "" and 0 mean absent. */
    record ScheduleOutput(@NotNull List<ScheduleRow> rows) {
    }

    record ScheduleRow(ScheduleSuggestion.Type type, String kind, String subkind, String title, String discipline,
                       String date, String startDate, String startsAt, String endsAt, String location,
                       String counterpartOrg, String purpose, String anchor, Integer offset, String offsetUnit,
                       Integer durationDays, String rule, String interval, Boolean yearInferred, Evidence evidence,
                       String confidence) {
    }

    record Evidence(Integer page, String quote) {
    }

    /**
     * Model rows → suggestion payloads (before matching). A row without evidence (page + quote), title or type, or
     * with a date that does not parse, is dropped. Static so the manual eval reads responses exactly as a run does.
     */
    static List<ScheduleSuggestion> scheduleRows(ObjectMapper json, String text, Map<Integer, String> pageTexts,
                                                 String documentName) throws Exception {
        ScheduleOutput out = json.readValue(text, ScheduleOutput.class);
        if (out.rows() == null) {
            throw new BadResponse("AI 응답이 스키마와 맞지 않습니다: rows 없음");
        }
        List<ScheduleSuggestion> result = new ArrayList<>();
        int dropped = 0;
        for (ScheduleRow r : out.rows()) {
            try {
                if (r == null || r.type() == null || blankToNull(r.title()) == null || r.evidence() == null
                        || r.evidence().page() == null || r.evidence().page() < 1
                        || blankToNull(r.evidence().quote()) == null) {
                    dropped++;
                    continue;
                }
                boolean event = r.type() == ScheduleSuggestion.Type.EVENT || r.type() == ScheduleSuggestion.Type.RECURRING;
                String anchor = blankToNull(r.anchor());
                boolean months = "MONTHS".equals(r.offsetUnit());
                Integer offset = anchor == null ? null : r.offset() == null ? 0 : r.offset();
                LocalDateTime startsAt = dateTime(r.startsAt());
                LocalDate date = date(r.date());
                if (startsAt == null && date == null && blankToNull(r.startsAt()) != null) {
                    date = date(r.startsAt()); // a day without time given as startsAt
                }
                String interval = blankToNull(r.interval());
                result.add(new ScheduleSuggestion(r.type(), event ? blankToNull(r.kind()) : null, blankToNull(r.subkind()),
                        r.title().trim(), blankToNull(r.discipline()), date, date(r.startDate()), startsAt,
                        dateTime(r.endsAt()), blankToNull(r.location()), blankToNull(r.counterpartOrg()),
                        blankToNull(r.purpose()), anchor, months ? null : offset, months ? offset : null,
                        r.durationDays() != null && r.durationDays() > 0 ? r.durationDays() : null,
                        blankToNull(r.rule()), interval == null ? null : ScheduleSuggestion.Interval.valueOf(interval),
                        Boolean.TRUE.equals(r.yearInferred()), new ScheduleSuggestion.Evidence(r.evidence().page(),
                        r.evidence().quote()), blankToNull(r.confidence()),
                        QuoteChecker.check(r.evidence().quote(), r.evidence().page(), pageTexts).name(), null, null,
                        null, null, documentName));
            } catch (DateTimeParseException | IllegalArgumentException e) {
                dropped++;
            }
        }
        if (dropped > 0) {
            log.info("Dropped {} schedule row(s) without evidence or with unreadable dates", dropped);
        }
        return result;
    }

    private static LocalDate date(String s) {
        String v = blankToNull(s);
        return v == null ? null : LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
    }

    private static LocalDateTime dateTime(String s) {
        String v = blankToNull(s);
        return v == null || v.length() <= 10 ? null : LocalDateTime.parse(v);
    }

    /** Server-side post-processing: citation_key (synthetic when unnumbered) and quote_check. */
    private List<JsonNode> payloads(ModelOutput out, Map<Integer, String> pageTexts) {
        List<JsonNode> result = new ArrayList<>();
        Map<Integer, Integer> perPage = new HashMap<>();
        for (Item i : out.obligations()) {
            String key = CitationKey.normalize(i.citationRaw());
            boolean synthetic = key == null;
            if (synthetic) {
                key = CitationKey.synthetic(i.pageNo(), perPage.merge(i.pageNo(), 1, Integer::sum));
            }
            result.add(json.valueToTree(new ObligationPayload(blankToNull(i.citationRaw()), key, synthetic,
                    i.requirementText(), i.summary(), i.type(), i.category(), blankToNull(i.responsibleParty()),
                    i.frequency(), blankToNull(i.frequencyText()), blankToNull(i.periodText()),
                    i.legalBasis() == null ? List.of() : i.legalBasis().stream().filter(s -> !s.isBlank()).toList(),
                    i.pageNo(), i.sourceQuote(), QuoteChecker.check(i.sourceQuote(), i.pageNo(), pageTexts))));
        }
        return result;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Page of the suggestion's source PDF as PNG for the review screen's viewer (design §4.1). */
    public byte[] renderPage(long suggestionId, int pageNo, Jwt jwt) {
        JsonNode ref = review.runOf(suggestionId, jwt).getInputRef();
        Document doc = documents.findById(ref == null ? -1 : ref.path("document_id").asLong())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "원문 문서가 없습니다."));
        return pageImages.render(doc.getStorageKey(), pageNo);
    }

    /** After a restart nothing is working on RUNNING rows any more (design §3.9). */
    @EventListener(ApplicationReadyEvent.class)
    public int failStale() {
        int n = runs.failRunningSince(clock.instant().minus(staleAfter), "중단됨", clock.instant());
        if (n > 0) {
            log.warn("Marked {} orphaned extraction run(s) as FAILED", n);
        }
        return n;
    }
}
