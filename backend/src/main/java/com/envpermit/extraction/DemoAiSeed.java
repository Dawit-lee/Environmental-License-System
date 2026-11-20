package com.envpermit.extraction;

import com.envpermit.core.document.DocumentStorage;
import com.envpermit.review.AiRun;
import com.envpermit.review.AiRunRepository;
import com.envpermit.review.AiSuggestion;
import com.envpermit.review.AiSuggestion.Status;
import com.envpermit.review.Review;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Public demo only ("demo" profile): the AI review flow on 새벽시 for the read-only demo login, without calling any AI.
 * The four fictional sample PDFs (docs/samples) go through the real pipeline as dev@ would use it: upload,
 * extraction answered by the Claude responses recorded with DemoRecorder, then review decisions. Result: v1 permit
 * conditions approved into obligations and deadlines, 협의의견서 rows pending with one rejected, one bid-notice event
 * approved into the calendar, and the v1 → v2 revision pending except one approved change.
 * Runs on every start: the PDFs are put back into storage, the rest only once per database (reset-demo.sh drops it).
 */
@Component
class DemoAiSeed {

    private static final Logger log = LoggerFactory.getLogger(DemoAiSeed.class);

    /** A fictional sample document. docType set = schedule extraction, else obligation extraction on permit. */
    record Doc(String key, String fileName, String docType, String permit) {
        String pdfPath() {
            return "/demo/documents/" + key + ".pdf";
        }

        String responsePath() {
            return "/demo/ai/" + key + ".json";
        }

        String storageKey() {
            return "demo/" + key + ".pdf";
        }
    }

    static final String PROJECT = "새벽시 매립가스 자원화시설";
    static final String PERMIT = "환경오염시설 통합허가";
    static final String EIA = "환경영향평가 협의";

    static final Doc BID = new Doc("01-bid-notice", "(예시) 새벽시 매립가스 자원화시설 입찰공고.pdf", "BID_NOTICE", null);
    static final Doc V1 = new Doc("02-permit-v1", "(예시) 환경오염시설 허가서.pdf", null, PERMIT);
    static final Doc V2 = new Doc("03-permit-v2", "(예시) 환경오염시설 변경허가서(전체본).pdf", null, PERMIT);
    static final Doc OPINION = new Doc("04-consultation-opinion", "(예시) 환경영향평가 협의의견서.pdf", null, EIA);
    static final List<Doc> DOCS = List.of(BID, V1, V2, OPINION);

    static final String REJECT_REASON =
            "(예시) 시공사가 공사 현장에서 지키는 사항이라 의무 목록 대신 공사 감독 점검표로 관리합니다.";

    private final JdbcTemplate jdbc;
    private final DocumentStorage storage;
    private final Extraction extraction;
    private final AiRunRepository runs;
    private final Review review;
    private final EntityManager em;
    private final TransactionTemplate tx;
    private final Clock clock;

    DemoAiSeed(JdbcTemplate jdbc, DocumentStorage storage, Extraction extraction, AiRunRepository runs, Review review,
               EntityManager em, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.extraction = extraction;
        this.runs = runs;
        this.review = review;
        this.em = em;
        this.tx = tx;
        this.clock = clock;
    }

    @EventListener(value = ApplicationReadyEvent.class, condition = "@environment.matchesProfiles('demo')")
    public void seed() {
        try { // an exception from a ready listener fails startup: the demo must come up without the AI flow instead
            seedOnce();
        } catch (RuntimeException e) {
            log.warn("Demo AI review seed failed; the demo runs without it and the next start retries", e);
        }
    }

    private void seedOnce() {
        List<Map<String, Object>> found = jdbc.queryForList("""
                SELECT pr.id, pr.company_id, u.id AS dev FROM project pr
                JOIN project_member m ON m.project_id = pr.id AND m.can_self_approve
                JOIN users u ON u.id = m.user_id AND u.email = 'dev@envpermit.local'
                WHERE pr.name = ?""", PROJECT);
        if (found.isEmpty()) {
            return; // no dev seed
        }
        for (Doc d : DOCS) { // a wiped store gets the files back, so the review screen can show the cited pages
            byte[] pdf = read(d.pdfPath());
            storage.put(d.storageKey(), new ByteArrayInputStream(pdf), pdf.length, Extraction.PDF);
        }
        long pid = ((Number) found.get(0).get("id")).longValue();
        if (jdbc.queryForObject("SELECT count(*) FROM document WHERE project_id = ? AND storage_key LIKE 'demo/%'",
                Long.class, pid) > 0) {
            return; // already seeded
        }
        long co = ((Number) found.get(0).get("company_id")).longValue();
        long dev = ((Number) found.get(0).get("dev")).longValue();
        tx.executeWithoutResult(s -> seed(pid, co, dev)); // all or nothing: a half-seeded demo would never retry
        log.info("Demo AI review flow seeded on {}", PROJECT);
    }

    private void seed(long pid, long co, long dev) {
        Jwt jwt = Jwt.withTokenValue("demo-seed").header("alg", "none").subject(Long.toString(dev)).build();
        LocalDate d = LocalDate.now(clock);
        long permit = permit(pid, dev, PERMIT, "FACILITY", "환경오염시설의 통합관리에 관한 법률 제6조", d.minusDays(20));
        long eia = permit(pid, dev, EIA, "ENVIRONMENT", "환경영향평가법 제27조", d.minusDays(90));
        boolean aiWasOn = jdbc.queryForObject("SELECT ai_extraction_enabled FROM company WHERE id = ?", Boolean.class, co);
        // extraction refuses while the company setting is off; the demo company keeps it off for real uploads
        jdbc.update("UPDATE company SET ai_extraction_enabled = TRUE WHERE id = ?", co);

        // 허가서 v1: every condition read against its page and approved; periodic ones get a first due date
        long v1 = upload(pid, permit, V1, dev, null);
        List<AiSuggestion> conditions = pending(pid, run(extraction.start(pid, permit, v1, jwt, false), V1), jwt);
        for (int i = 0; i < conditions.size(); i++) {
            AiSuggestion s = conditions.get(i);
            JsonNode p = s.getPayload();
            review.pageViewed(s.getId(), jwt, p.path("page_no").asInt());
            review.accept(s.getId(), jwt, firstDue(d, p.path("frequency").asText(), i), false, s.getVersion(), false);
        }

        // 협의의견서: waits for review, except one row a reviewer turned down
        long opinion = upload(pid, eia, OPINION, dev, null);
        AiSuggestion washLog = first(pending(pid, run(extraction.start(pid, eia, opinion, jwt, false), OPINION), jwt),
                p -> p.path("summary").asText().contains("세륜시설 운영일지"));
        review.reject(washLog.getId(), jwt, REJECT_REASON, washLog.getVersion());

        // 입찰공고: the site briefing goes on the calendar; the rules relative to it wait for review
        long bid = upload(pid, null, BID, dev, null);
        AiSuggestion briefing = first(pending(pid, run(extraction.startSchedule(pid, bid, jwt), BID), jwt),
                p -> p.path("title").asText().equals("현장설명회"));
        review.accept(briefing.getId(), jwt, null, false, briefing.getVersion(), false);

        // 허가서 v2 (전체본): compared with the approved v1; the changed measuring frequency is approved, the rest waits
        long v2 = upload(pid, permit, V2, dev, v1);
        AiSuggestion changed = first(pending(pid, run(extraction.startRevision(pid, permit, v2, jwt), V2), jwt),
                p -> p.path("change_type").asText().equals("CHANGED")
                        && p.path("new").path("summary").asText().contains("질소산화물"));
        review.accept(changed.getId(), jwt, d.plusDays(3), false, changed.getVersion(), false); // 주 1회: next due date

        jdbc.update("UPDATE company SET ai_extraction_enabled = ? WHERE id = ?", aiWasOn, co);
    }

    /** A permit item of the project as PermitController creates it (row + creation history). */
    private long permit(long pid, long dev, String name, String category, String regulation, LocalDate acquired) {
        Long id = jdbc.queryForObject("""
                INSERT INTO permit_item (project_id, name, category, regulation, authority, reason, approval_date,
                                         acquisition_date, status, next_action)
                VALUES (?, ?, ?, ?, '새벽지방환경청', '(예시) 시연용 가상 인허가입니다.', '가동 전', ?, 'COMPLETED',
                        '허가조건을 의무사항으로 관리합니다.')
                RETURNING id""", Long.class, pid, name, category, regulation, acquired);
        jdbc.update("INSERT INTO permit_status_history (permit_item_id, from_status, to_status, changed_by, changed_at) "
                + "VALUES (?, NULL, 'COMPLETED', ?, ?)", id, dev, Timestamp.from(clock.instant()));
        return id;
    }

    /** A document row as DocumentController writes it on upload; the bytes are already in storage. */
    private long upload(long pid, Long permit, Doc doc, long dev, Long supersedes) {
        byte[] pdf = read(doc.pdfPath());
        return jdbc.queryForObject("""
                INSERT INTO document (project_id, permit_item_id, doc_type, file_name, content_type, size_bytes, sha256,
                                      storage_key, uploaded_by, uploaded_at, supersedes_document_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, pid, permit, doc.docType(),
                doc.fileName(), Extraction.PDF, pdf.length, sha256(pdf), doc.storageKey(), dev,
                Timestamp.from(clock.instant()), supersedes);
    }

    /** Finishes the started run with the recorded response instead of an API call. */
    private long run(AiRun started, Doc doc) {
        String recorded = new String(read(doc.responsePath()), StandardCharsets.UTF_8);
        extraction.run(started.getId(), new Replay(recorded));
        // read the new rows back as a later request would: a fresh payload node can differ in number types from
        // the stored one, which in this one long transaction would bump the version on every flush
        em.flush();
        em.clear();
        AiRun done = runs.findById(started.getId()).orElseThrow();
        if (!AiRun.SUCCEEDED.equals(done.getStatus())) {
            throw new IllegalStateException(doc.key() + ": " + done.getErrorMessage());
        }
        return done.getId();
    }

    private List<AiSuggestion> pending(long pid, long runId, Jwt jwt) {
        return review.list(pid, jwt, runId, Status.PENDING).stream()
                .sorted(Comparator.comparing(AiSuggestion::getId)).toList();
    }

    private static AiSuggestion first(List<AiSuggestion> rows, Predicate<JsonNode> payload) {
        return rows.stream().filter(s -> payload.test(s.getPayload())).findFirst()
                .orElseThrow(() -> new IllegalStateException("recorded response lacks the expected row"));
    }

    /** Spread over the coming weeks so the dashboard and calendar show near deadlines; null = no cycle to track. */
    static LocalDate firstDue(LocalDate today, String frequency, int i) {
        return switch (frequency) {
            case "MONTHLY" -> today.plusDays(5 + i);
            case "QUARTERLY" -> today.plusDays(20 + i);
            case "SEMIANNUAL" -> today.plusDays(45 + i);
            case "ANNUAL" -> today.plusDays(100 + i);
            default -> null;
        };
    }

    private static byte[] read(String path) {
        try (InputStream in = DemoAiSeed.class.getResourceAsStream(path)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Answers with the recorded Claude response. */
    private static final class Replay implements LlmClient {
        private final String recorded;

        Replay(String recorded) {
            this.recorded = recorded;
        }

        @Override
        public Provider provider() {
            return Provider.CLAUDE;
        }

        @Override
        public String model() {
            return "recorded"; // unused: the run keeps the model of the server's client, as a real run does
        }

        @Override
        public String send(String system, PreparedInput.Content content, String instruction, JsonNode schema) {
            return recorded;
        }
    }
}
