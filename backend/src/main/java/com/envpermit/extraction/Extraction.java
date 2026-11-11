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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
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
    private final ExtractionInputPreparer preparer;
    private final LlmClient llm;
    private final ObjectMapper json;
    private final Validator validator;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration staleAfter;

    Extraction(ProjectAccess access, ProjectRepository projects, CompanyRepository companies,
               PermitItemRepository permits, DocumentRepository documents, DocumentStorage storage,
               AiRunRepository runs, Review review, RevisionDiff revisions, ExtractionInputPreparer preparer, LlmClient llm,
               ObjectMapper json, Validator validator, TransactionTemplate tx, Clock clock,
               @Value("${app.extraction.stale-after}") Duration staleAfter) {
        this.access = access;
        this.projects = projects;
        this.companies = companies;
        this.permits = permits;
        this.documents = documents;
        this.storage = storage;
        this.runs = runs;
        this.review = review;
        this.revisions = revisions;
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
        Project project = projects.findById(projectId).orElseThrow();
        if (!companies.findById(project.getCompanyId()).orElseThrow().isAiExtractionEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "회사 설정에서 AI 추출이 꺼져 있습니다. 회사 마스터가 켤 수 있습니다.");
        }
        if (!PDF.equals(doc.getContentType())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "PDF 문서만 AI 추출할 수 있습니다.");
        }
        return doc;
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

    /** The slow part. Never throws: every outcome ends as SUCCEEDED or FAILED on the run. */
    public void run(long runId) {
        AiRun run = runs.findById(runId).orElseThrow();
        long permitId = run.getInputRef().get("permit_item_id").asLong();
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
                    raw = llm.send(ObligationPrompt.SYSTEM, input.llmContent(), ObligationPrompt.INSTRUCTION,
                            ObligationPrompt.SCHEMA);
                } catch (RuntimeException e) {
                    log.warn("LLM call failed (run {}, attempt {})", runId, attempt, e);
                    error = "AI 서비스 호출에 실패했습니다: " + e.getMessage();
                    continue;
                }
                rawKey = "companies/" + run.getCompanyId() + "/ai-runs/" + runId + "/response-" + attempt + ".json";
                byte[] rawBytes = raw.getBytes(StandardCharsets.UTF_8);
                storage.put(rawKey, new ByteArrayInputStream(rawBytes), rawBytes.length, "application/json");
                try {
                    JsonNode res = json.readTree(raw);
                    inTokens += llm.provider().inputTokens(res);
                    outTokens += llm.provider().outputTokens(res);
                    payloads = payloads(parse(json, validator, llm.provider().text(res)), input.pageTexts());
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
            if (payloads != null) {
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
        // shortcut: renders on every request, no cache; add one if viewers get slow on big scans
        try (InputStream in = storage.open(doc.getStorageKey());
             PDDocument pdf = Loader.loadPDF(in.readAllBytes());
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (pageNo < 1 || pageNo > pdf.getNumberOfPages()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "페이지가 없습니다.");
            }
            ImageIO.write(new PDFRenderer(pdf).renderImageWithDPI(pageNo - 1, 110), "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "PDF를 읽을 수 없습니다.");
        }
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
