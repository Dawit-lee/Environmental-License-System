package com.envpermit.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.envpermit.core.company.Company;
import com.envpermit.core.company.CompanyRepository;
import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import com.envpermit.review.AiRun;
import com.envpermit.review.AiRunRepository;
import com.envpermit.review.Kind;
import com.envpermit.support.InMemoryDocumentStorage;
import com.envpermit.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Text-PDF extraction through the REST API: real PostgreSQL, LLM answered from /claude, /gemini or /openai fixtures, run inline.
 * master = company ADMIN (may switch AI extraction), kim = project creator, park = other company.
 */
class ExtractionApiTest extends IntegrationTest {

    /** Text matching the fixtures; the quote of condition 3-2 crosses from page 1 into page 2. */
    static final byte[] PERMIT_PDF = pdf(
            "Condition 3-2. Measure noise and vibration once per quarter during construction",
            "and submit the results to the city. Condition 5. Keep TMS records for 5 years.");

    /** What a scanner produces: each page is one image, no text layer. Same wording as PERMIT_PDF. */
    static final byte[] SCANNED_PDF = scanned(
            "Condition 3-2. Measure noise and vibration once per quarter",
            "Condition 5. Keep TMS records for 5 years.");

    // usage of the recorded Gemini response: promptTokenCount, candidatesTokenCount + thoughtsTokenCount
    static final int GEMINI_IN = 960;
    static final int GEMINI_OUT = 617 + 954;
    // usage of the recorded OpenAI response: input_tokens, output_tokens (reasoning included)
    static final int OPENAI_IN = 873;
    static final int OPENAI_OUT = 731;

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired AiRunRepository runs;
    @Autowired Extraction extraction;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;

    long companyId;
    long kimId;
    String master;
    String kim;
    String park;
    String lee; // plain member: no approval rights
    long projectId;
    long permitId;
    long docId;

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        companyId = companies.save(new Company("A환경")).getId();
        long b = companies.save(new Company("B엔지니어링")).getId();
        users.save(new User("master@a.com", encoder.encode("pw"), "마스터", User.Role.ADMIN, companyId));
        kimId = users.save(new User("kim@a.com", encoder.encode("pw"), "김에이", User.Role.USER, companyId)).getId();
        users.save(new User("park@b.com", encoder.encode("pw"), "박비", User.Role.USER, b));
        long leeId = users.save(new User("lee@a.com", encoder.encode("pw"), "이에이", User.Role.USER, companyId)).getId();
        lee = login("lee@a.com");
        master = login("master@a.com");
        kim = login("kim@a.com");
        park = login("park@b.com");
        projectId = id(call(kim, json(post("/api/projects"), """
                {"name":"광명 자원회수시설","facilityType":"INCINERATION","location":"광명"}""")));
        permitId = id(call(kim, json(post("/api/projects/" + projectId + "/permits"), """
                {"name":"환경영향평가 협의","category":"ENVIRONMENT","status":"COMPLETED"}""")));
        call(kim, json(post("/api/projects/" + projectId + "/members"), "{\"userId\":" + leeId + "}"))
                .andExpect(status().isCreated());
        docId = upload("협의의견서.pdf", PERMIT_PDF);
    }

    // --- company setting ----------------------------------------------------------------------------------

    @Test
    void extraction_is_refused_while_the_company_setting_is_off() throws Exception {
        call(kim, get("/api/company")).andExpect(jsonPath("$.aiExtractionEnabled").value(false));

        extract(kim).andExpect(status().isForbidden())
                .andExpect(status().reason("회사 설정에서 AI 추출이 꺼져 있습니다. 회사 마스터가 켤 수 있습니다."));
        assertThat(llm.calls).isEmpty();
        assertThat(runs.count()).isZero();
    }

    @Test
    void only_a_master_switches_ai_extraction_and_who_and_when_is_kept() throws Exception {
        call(kim, json(put("/api/company/ai-extraction"), "{\"enabled\":true}")).andExpect(status().isForbidden());

        clock.advance(Duration.ofMinutes(10));
        call(master, json(put("/api/company/ai-extraction"), "{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiExtractionEnabled").value(true))
                .andExpect(jsonPath("$.aiExtractionChangedBy").isNumber())
                .andExpect(jsonPath("$.aiExtractionChangedAt").value("2026-01-05T00:10:00Z"));
        call(kim, get("/api/company")).andExpect(jsonPath("$.aiExtractionEnabled").value(true));
    }

    // --- extraction ---------------------------------------------------------------------------------------

    @Test
    void text_pdf_becomes_pending_suggestions_with_keys_and_quote_checks_mismatch_first() throws Exception {
        enable();
        llm.answer("obligations-ok.json");

        long runId = runId(extract(kim).andExpect(status().isAccepted()));

        call(kim, get("/api/ai-runs/" + runId))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.provider").value("CLAUDE"))
                .andExpect(jsonPath("$.model").value("claude-sonnet-5-5"))
                .andExpect(jsonPath("$.promptVersion").value("obligation-v1"))
                .andExpect(jsonPath("$.preparer").value("CLAUDE_PDF_DIRECT"))
                .andExpect(jsonPath("$.inputTokens").value(3120))
                .andExpect(jsonPath("$.outputTokens").value(640))
                .andExpect(jsonPath("$.inputRef.document_id").value(docId))
                .andExpect(jsonPath("$.rawResponseKey").value(startsWith("companies/" + companyId + "/ai-runs/" + runId + "/")));
        // the raw response is in S3 as returned
        String rawKey = runs.findById(runId).orElseThrow().getRawResponseKey();
        assertThat(new String(((InMemoryDocumentStorage) storage).objects.get(rawKey))).contains("msg_01Fixture0kThreeObligations");
        // the PDF itself was sent, with the schema-constrained output
        assertThat(llm.calls).hasSize(1);
        assertThat(((PreparedInput.Pdf) llm.calls.get(0).content()).bytes()).isEqualTo(PERMIT_PDF);
        assertThat(llm.calls.get(0).schema().at("/properties/obligations/items/properties/category/enum").size())
                .isEqualTo(22);

        call(kim, get("/api/projects/" + projectId + "/suggestions?runId=" + runId))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[*].payload.quote_check", contains("MISMATCH", "VERIFIED", "VERIFIED")))
                .andExpect(jsonPath("$[*].payload.citation_key", contains("5", "3-2", "P2-1")))
                .andExpect(jsonPath("$[*].payload.citation_synthetic", contains(false, false, true)))
                .andExpect(jsonPath("$[*].status", contains("PENDING", "PENDING", "PENDING")))
                .andExpect(jsonPath("$[*].origin", contains("AI", "AI", "AI")))
                .andExpect(jsonPath("$[*].targetParentId", contains((int) permitId, (int) permitId, (int) permitId)))
                .andExpect(jsonPath("$[1].payload.category").value("NOISE_VIBRATION"))
                .andExpect(jsonPath("$[1].payload.legal_basis[0]").value("소음·진동관리법 제22조"))
                .andExpect(jsonPath("$[2].payload.citation_raw").doesNotExist());

        // an AI row goes through the same review: approve the verified 3-2
        long id = ((Number) JsonPath.read(call(kim, get("/api/projects/" + projectId + "/suggestions?runId=" + runId))
                .andReturn().getResponse().getContentAsString(), "$[1].id")).longValue();
        call(kim, json(post("/api/suggestions/" + id + "/accept"), "{\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        call(kim, get("/api/suggestions/" + id + "/events"))
                .andExpect(jsonPath("$[0].type").value("CREATED"))
                .andExpect(jsonPath("$[0].actorId").doesNotExist());
    }

    @Test
    void gemini_reads_the_same_pdf_into_the_same_rows_and_the_run_records_provider_and_tokens() throws Exception {
        enable();
        llm.use(Provider.GEMINI, "gemini-3.6-flash");
        call(kim, get("/api/ai-provider")).andExpect(jsonPath("$.provider").value("GEMINI"))
                .andExpect(jsonPath("$.label").value("Google Gemini"));
        byte[] pdf = ExtractionApiTest.class.getResourceAsStream("/gemini/consultation-opinion.pdf").readAllBytes();
        docId = upload("합성-협의의견서.pdf", pdf);
        llm.answer("obligations-recorded.json"); // recorded from the real API on that PDF (see /gemini/README.md)

        long runId = runId(extract(kim).andExpect(status().isAccepted()));

        call(kim, get("/api/ai-runs/" + runId))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.provider").value("GEMINI"))
                .andExpect(jsonPath("$.model").value("gemini-3.6-flash"))
                .andExpect(jsonPath("$.promptVersion").value("obligation-v1"))
                .andExpect(jsonPath("$.inputTokens").value(GEMINI_IN))
                .andExpect(jsonPath("$.outputTokens").value(GEMINI_OUT));
        assertThat(((PreparedInput.Pdf) llm.calls.get(0).content()).bytes()).isEqualTo(pdf);
        call(kim, get("/api/projects/" + projectId + "/suggestions?runId=" + runId))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[*].payload.citation_key", containsInAnyOrder("3-2", "4-1", "5-3")))
                .andExpect(jsonPath("$[*].payload.quote_check", contains("VERIFIED", "VERIFIED", "VERIFIED")));
    }

    @Test
    void openai_reads_the_same_pdf_into_the_same_rows_and_the_run_records_provider_and_tokens() throws Exception {
        enable();
        llm.use(Provider.OPENAI, "gpt-6.1-terra");
        call(kim, get("/api/ai-provider")).andExpect(jsonPath("$.provider").value("OPENAI"))
                .andExpect(jsonPath("$.label").value("OpenAI"));
        byte[] pdf = ExtractionApiTest.class.getResourceAsStream("/gemini/consultation-opinion.pdf").readAllBytes();
        docId = upload("합성-협의의견서.pdf", pdf);
        llm.answer("obligations-recorded.json"); // recorded from the real API on that PDF (see /openai/README.md)

        long runId = runId(extract(kim).andExpect(status().isAccepted()));

        call(kim, get("/api/ai-runs/" + runId))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.provider").value("OPENAI"))
                .andExpect(jsonPath("$.model").value("gpt-6.1-terra"))
                .andExpect(jsonPath("$.promptVersion").value("obligation-v1"))
                .andExpect(jsonPath("$.inputTokens").value(OPENAI_IN))
                .andExpect(jsonPath("$.outputTokens").value(OPENAI_OUT));
        assertThat(((PreparedInput.Pdf) llm.calls.get(0).content()).bytes()).isEqualTo(pdf);
        call(kim, get("/api/projects/" + projectId + "/suggestions?runId=" + runId))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[*].payload.citation_key", containsInAnyOrder("3-2", "4-1", "5-3")))
                .andExpect(jsonPath("$[*].payload.quote_check", contains("VERIFIED", "VERIFIED", "VERIFIED")));
    }

    @Test
    void zero_obligations_is_a_success_with_no_rows() throws Exception {
        enable();
        llm.answer("obligations-empty.json");

        long runId = runId(extract(kim));

        call(kim, get("/api/ai-runs/" + runId)).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        call(kim, get("/api/projects/" + projectId + "/suggestions?runId=" + runId))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void schema_violation_is_retried_once_then_the_run_fails_and_can_be_run_again() throws Exception {
        enable();
        llm.answer("obligations-schema-violation.json", "obligations-schema-violation.json");

        long runId = runId(extract(kim));

        assertThat(llm.calls).hasSize(2);
        call(kim, get("/api/ai-runs/" + runId))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorMessage").value(startsWith("AI 응답")))
                .andExpect(jsonPath("$.inputTokens").value(6240)) // both attempts are paid for
                .andExpect(jsonPath("$.rawResponseKey").value(startsWith("companies/" + companyId)));
        call(kim, get("/api/projects/" + projectId + "/suggestions")).andExpect(jsonPath("$.length()").value(0));

        // "다시 실행": a failed run does not count as "already extracted"
        llm.answer("obligations-ok.json");
        long again = runId(extract(kim).andExpect(status().isAccepted()));
        call(kim, get("/api/ai-runs/" + again)).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        call(kim, get("/api/projects/" + projectId + "/ai-runs"))
                .andExpect(jsonPath("$[*].status", contains("SUCCEEDED", "FAILED")));
    }

    @Test
    void api_error_is_retried_once() throws Exception {
        enable();
        llm.answer(new RuntimeException("overloaded_error"), "obligations-ok.json");

        long runId = runId(extract(kim));

        call(kim, get("/api/ai-runs/" + runId)).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        call(kim, get("/api/projects/" + projectId + "/suggestions")).andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void two_api_errors_fail_the_run() throws Exception {
        enable();
        llm.answer(new RuntimeException("timeout"), new RuntimeException("timeout"));

        long runId = runId(extract(kim));

        call(kim, get("/api/ai-runs/" + runId))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorMessage").value("AI 서비스 호출에 실패했습니다: timeout"));
    }

    @Test
    void same_file_again_needs_confirmation() throws Exception {
        enable();
        llm.answer("obligations-ok.json", "obligations-ok.json");
        runId(extract(kim));
        long sameBytes = upload("협의의견서(사본).pdf", PERMIT_PDF);

        call(kim, post(path(sameBytes))).andExpect(status().isConflict())
                .andExpect(status().reason(startsWith("이미 추출한 문서입니다.")));
        call(kim, json(post(path(sameBytes)), "{\"confirmDuplicate\":true}")).andExpect(status().isAccepted());
        call(kim, get("/api/projects/" + projectId + "/suggestions")).andExpect(jsonPath("$.length()").value(6));
    }

    @Test
    void only_pdfs_of_the_project_can_be_extracted() throws Exception {
        enable();
        long png = upload("현장.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3});

        call(kim, post(path(png))).andExpect(status().isUnprocessableEntity());
        extract(park).andExpect(status().isForbidden());
        call(kim, post("/api/projects/" + projectId + "/permit-items/" + permitId + "/documents/999/extractions"))
                .andExpect(status().isNotFound());
        assertThat(llm.calls).isEmpty();
    }

    @Test
    void run_is_visible_to_project_members_only() throws Exception {
        enable();
        llm.answer("obligations-empty.json");
        long runId = runId(extract(kim));

        call(park, get("/api/ai-runs/" + runId)).andExpect(status().isForbidden());
    }

    @Test
    void orphaned_running_runs_are_failed_at_startup() throws Exception {
        AiRun old = runs.save(AiRun.start(companyId, projectId, Kind.OBLIGATION, null, "m", "v", "CLAUDE_PDF_DIRECT",
                kimId, clock.instant()));
        clock.advance(Duration.ofMinutes(31));
        AiRun recent = runs.save(AiRun.start(companyId, projectId, Kind.OBLIGATION, null, "m", "v",
                "CLAUDE_PDF_DIRECT", kimId, clock.instant()));

        assertThat(extraction.failStale()).isEqualTo(1);

        call(kim, get("/api/ai-runs/" + old.getId()))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorMessage").value("중단됨"));
        call(kim, get("/api/ai-runs/" + recent.getId())).andExpect(jsonPath("$.status").value("RUNNING"));
    }

    // --- scanned PDF (design D7) ----------------------------------------------------------------------------

    static final String NOT_VIEWED_P1 = "원문 미열람 스캔본: 1쪽 원문을 열어 본 뒤 승인할 수 있습니다.";

    @Test
    void scanned_pages_are_unverifiable_and_approval_waits_until_the_page_is_opened() throws Exception {
        String list = scannedRun();
        call(kim, get("/api/projects/" + projectId + "/suggestions"))
                .andExpect(jsonPath("$[*].payload.quote_check", contains("UNVERIFIABLE", "UNVERIFIABLE", "UNVERIFIABLE")));
        long onP1 = idOf(list, "3-2");

        call(kim, json(post("/api/suggestions/" + onP1 + "/accept"), "{\"version\":0}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(status().reason(NOT_VIEWED_P1));
        // EDITED approval is gated the same way
        ObjectNode edited = (ObjectNode) payloadOf(list, "3-2");
        edited.put("summary", "공사 중 소음·진동 분기 측정");
        call(kim, json(patch("/api/suggestions/" + onP1), "{\"editedPayload\":" + edited + ",\"version\":0}"))
                .andExpect(status().isOk());
        call(kim, json(post("/api/suggestions/" + onP1 + "/accept"), "{\"version\":1}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(status().reason(NOT_VIEWED_P1));
        // another page does not count
        call(kim, json(post("/api/suggestions/" + onP1 + "/page-views"), "{\"pageNo\":2}")).andExpect(status().isNoContent());
        call(kim, json(post("/api/suggestions/" + onP1 + "/accept"), "{\"version\":1}")).andExpect(status().isUnprocessableEntity());
        // anyone in the project may be the one who opened it; once per user and page
        call(lee, json(post("/api/suggestions/" + onP1 + "/page-views"), "{\"pageNo\":1}")).andExpect(status().isNoContent());
        call(lee, json(post("/api/suggestions/" + onP1 + "/page-views"), "{\"pageNo\":1}")).andExpect(status().isNoContent());
        call(park, json(post("/api/suggestions/" + onP1 + "/page-views"), "{\"pageNo\":1}")).andExpect(status().isForbidden());
        call(kim, get("/api/suggestions/" + onP1 + "/events"))
                .andExpect(jsonPath("$[?(@.type=='PAGE_VIEWED')].data.page_no", contains(2, 1)));

        call(kim, json(post("/api/suggestions/" + onP1 + "/accept"), "{\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EDITED_ACCEPTED"));
        // rejection needs no view
        call(kim, json(post("/api/suggestions/" + idOf(list, "5") + "/reject"), "{\"reason\":\"중복\",\"version\":0}"))
                .andExpect(status().isOk());
    }

    @Test
    void bulk_approval_leaves_out_unviewed_scans_with_the_reason() throws Exception {
        String list = scannedRun();
        long viewed = idOf(list, "5");
        long unviewed = idOf(list, "3-2");
        call(lee, json(post("/api/suggestions/" + viewed + "/page-views"), "{\"pageNo\":2}")).andExpect(status().isNoContent());

        call(kim, json(post("/api/suggestions/bulk-accept"), "{\"ids\":[%d,%d]}".formatted(viewed, unviewed)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].ok", contains(true, false)))
                .andExpect(jsonPath("$[1].reason").value(NOT_VIEWED_P1));
        call(kim, get("/api/suggestions/" + unviewed + "/events")).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void review_screen_gets_the_stored_page_as_an_image() throws Exception {
        String list = scannedRun();
        long id = idOf(list, "3-2");

        byte[] png = call(lee, get("/api/suggestions/" + id + "/pages/1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(png, 1, 3)).isEqualTo("PNG");
        call(lee, get("/api/suggestions/" + id + "/pages/3")).andExpect(status().isNotFound());
        call(park, get("/api/suggestions/" + id + "/pages/1")).andExpect(status().isForbidden());
    }

    /** Runs extraction on an image-only PDF and returns the suggestion list JSON. */
    private String scannedRun() throws Exception {
        enable();
        long scanId = upload("협의의견서-스캔.pdf", SCANNED_PDF);
        llm.answer("obligations-ok.json");
        call(kim, post(path(scanId))).andExpect(status().isAccepted());
        return call(kim, get("/api/projects/" + projectId + "/suggestions")).andReturn().getResponse()
                .getContentAsString();
    }

    private long idOf(String list, String citationKey) throws Exception {
        for (JsonNode s : mapper.readTree(list)) {
            if (citationKey.equals(s.path("payload").path("citation_key").asText())) {
                return s.get("id").asLong();
            }
        }
        throw new AssertionError("no suggestion " + citationKey);
    }

    private JsonNode payloadOf(String list, String citationKey) throws Exception {
        for (JsonNode s : mapper.readTree(list)) {
            if (citationKey.equals(s.path("payload").path("citation_key").asText())) {
                return s.get("payload");
            }
        }
        throw new AssertionError("no suggestion " + citationKey);
    }

    // --- helpers --------------------------------------------------------------------------------------------

    private void enable() throws Exception {
        call(master, json(put("/api/company/ai-extraction"), "{\"enabled\":true}")).andExpect(status().isOk());
    }

    private String path(long documentId) {
        return "/api/projects/" + projectId + "/permit-items/" + permitId + "/documents/" + documentId + "/extractions";
    }

    private ResultActions extract(String token) throws Exception {
        return call(token, post(path(docId)));
    }

    private long upload(String name, byte[] bytes) throws Exception {
        return id(call(kim, multipart("/api/projects/" + projectId + "/permits/" + permitId + "/documents")
                .file(new MockMultipartFile("file", name, "application/octet-stream", bytes)))
                .andExpect(status().isCreated()));
    }

    private static long runId(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.runId")).longValue();
    }

    /** One page per string, Helvetica (text layer only; Korean needs an embedded font, covered by QuoteCheckerTest). */
    static byte[] pdf(String... pages) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : pages) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream s = new PDPageContentStream(doc, page)) {
                    s.beginText();
                    s.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    s.newLineAtOffset(40, 700);
                    s.showText(text);
                    s.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] scanned(String... pages) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : pages) {
                BufferedImage img = new BufferedImage(612, 792, BufferedImage.TYPE_BYTE_GRAY);
                Graphics2D g = img.createGraphics();
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, 612, 792);
                g.setColor(Color.BLACK);
                g.drawString(text, 40, 90);
                g.dispose();
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream s = new PDPageContentStream(doc, page)) {
                    s.drawImage(LosslessFactory.createFromImage(doc, img), 0, 0, 612, 792);
                }
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private ResultActions call(String token, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + token));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, String body) {
        return req.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static long id(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private String login(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"pw\"}".formatted(email)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
