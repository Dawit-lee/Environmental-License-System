package com.envpermit.extraction;

import com.envpermit.extraction.EvalScorer.Gold;
import com.envpermit.extraction.EvalScorer.Item;
import com.envpermit.extraction.EvalScorer.Score;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Manual extraction quality eval (review-extraction/04): {@code ./gradlew extractionEval --args="<dir> [providers]"}.
 * Not a test and not in CI: it calls the real APIs and costs money. See docs/EXTRACTION_EVAL.md.
 *
 * <p>For every {@code X.pdf} in the directory that has an {@code X.answers.csv}, each provider with an API key set
 * runs the app's own extraction steps (ClaudePdfDirectPreparer → LlmClient.send with ObligationPrompt → Provider
 * text/tokens → Extraction.parse, one retry like Extraction.run) and is scored by {@link EvalScorer}.
 */
public final class ExtractionEval {

    private static final ObjectMapper JSON = Jackson2ObjectMapperBuilder.json().build(); // Spring Boot's defaults, as in the app

    private ExtractionEval() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("rasterize")) {
            rasterize(Path.of(args[1]), Path.of(args[2]));
            return;
        }
        if (args.length >= 3 && args[0].equals("extract")) {
            extract(Path.of(args[1]), args.length > 3 ? args[3] : null, Path.of(args[2]));
            return;
        }
        if (args.length < 1 || args.length > 2) {
            System.out.println("usage: extractionEval --args=\"<dir> [claude,gemini,openai]\""
                    + " | --args=\"rasterize <in.pdf> <out.pdf>\"" + " | --args=\"extract <in.pdf> <out-dir> [provider]\"");
            return;
        }
        Path dir = Path.of(args[0]);
        List<LlmClient> clients = clients(args.length > 1 ? args[1] : "claude,gemini,openai");
        if (clients.isEmpty()) {
            System.out.println("API 키가 없어 실행하지 않습니다(ANTHROPIC_API_KEY / GEMINI_API_KEY / OPENAI_API_KEY).");
            return;
        }
        List<Path> pdfs;
        try (Stream<Path> files = Files.list(dir)) {
            pdfs = files.filter(p -> p.toString().endsWith(".pdf") && Files.exists(answers(p))).sorted().toList();
        }
        if (pdfs.isEmpty()) {
            System.out.println("정답표(X.answers.csv)가 있는 PDF(X.pdf)가 없습니다: " + dir);
            return;
        }
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        Path out = Files.createDirectories(dir.resolve("out"));
        StringBuilder table = new StringBuilder("""
                | 문서 | 제공자(모델) | 정답 조건 | 추출 | 재현율 | 정밀도 | 번호 일치(정확) | 번호 일치(포함) | 주기 일치 | 시간(초) | 토큰 in/out | 비용($) | 시도 |
                |---|---|---|---|---|---|---|---|---|---|---|---|---|
                """);
        for (Path pdf : pdfs) {
            String doc = pdf.getFileName().toString().replaceFirst("\\.pdf$", "");
            List<Gold> gold = EvalScorer.answerSheet(Files.readString(answers(pdf)));
            PreparedInput input = new ClaudePdfDirectPreparer().prepare(Files.readAllBytes(pdf));
            for (LlmClient llm : clients) {
                System.out.println(doc + " ← " + llm.provider() + " (" + llm.model() + ") ...");
                table.append(run(doc, gold, input, llm, validator, out)).append('\n');
            }
        }
        System.out.println();
        System.out.println(table);
        Files.writeString(out.resolve("results.md"), table);
        System.out.println("결과 표와 응답 원문·행별 대조: " + out);
    }

    private static String run(String doc, List<Gold> gold, PreparedInput input, LlmClient llm, Validator validator,
                              Path out) throws IOException {
        String name = llm.provider().name().toLowerCase(Locale.ROOT);
        String who = name + " (" + llm.model() + ")";
        int inTokens = 0;
        int outTokens = 0;
        String error = null;
        long started = System.nanoTime();
        for (int attempt = 1; attempt <= Extraction.ATTEMPTS; attempt++) {
            try {
                String raw = llm.send(ObligationPrompt.SYSTEM, input.llmContent(), ObligationPrompt.INSTRUCTION,
                        ObligationPrompt.SCHEMA);
                Files.writeString(out.resolve(doc + "." + name + ".response-" + attempt + ".json"), raw);
                JsonNode res = JSON.readTree(raw);
                inTokens += llm.provider().inputTokens(res);
                outTokens += llm.provider().outputTokens(res);
                List<Extraction.Item> found = Extraction.parse(JSON, validator, llm.provider().text(res)).obligations();
                double seconds = (System.nanoTime() - started) / 1e9;
                List<Item> items = found.stream()
                        .map(i -> new Item(i.citationRaw(), List.of(i.requirementText(), i.sourceQuote()), i.frequency().name()))
                        .toList();
                Score s = EvalScorer.score(gold, items);
                Files.writeString(out.resolve(doc + "." + name + ".detail.txt"), detail(gold, found, s));
                return String.format(Locale.ROOT, "| %s | %s | %d | %d | %s | %s | %s | %s | %s | %.1f | %d / %d | %s | %d |",
                        doc, who, s.gold(), s.items(), pct(s.found(), s.gold()), pct(s.hits(), s.items() - s.optionalHits()),
                        pct(s.numberExact(), s.numbered()), pct(s.numberLoose(), s.numbered()),
                        pct(s.frequencyOk(), s.frequencyGold()), seconds, inTokens, outTokens,
                        cost(name, inTokens, outTokens), attempt);
            } catch (Exception e) { // API error, refusal/truncation (BadResponse) or schema violation: retry once, like a run
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
                System.out.println("  시도 " + attempt + " 실패: " + error);
            }
        }
        return String.format(Locale.ROOT, "| %s | %s | 실패: %s | | | | | | | %.1f | %d / %d | %s | %d |", doc, who,
                error.replace('|', '/').replace('\n', ' '), (System.nanoTime() - started) / 1e9, inTokens, outTokens,
                cost(name, inTokens, outTokens), Extraction.ATTEMPTS);
    }

    /** One line per extracted item with the answer row it matched, then the scored rows nothing matched. */
    private static String detail(List<Gold> gold, List<Extraction.Item> found, Score s) {
        StringBuilder b = new StringBuilder("# 추출 항목 → 정답표 행(CSV 줄 번호. 머리글이 1줄)\n");
        boolean[] hit = new boolean[gold.size()];
        for (int i = 0; i < found.size(); i++) {
            Extraction.Item it = found.get(i);
            int m = s.match()[i];
            if (m >= 0) {
                hit[m] = true;
            }
            b.append(m < 0 ? "오답      " : (gold.get(m).scored() ? "정답 " : "참고 ") + String.format("%-5d", m + 2))
                    .append(" | ").append(it.citationRaw()).append(" | ").append(it.frequency()).append(" | p")
                    .append(it.pageNo()).append(" | ").append(it.sourceQuote().replace('\n', ' ')).append('\n');
        }
        b.append("\n# 놓친 정답 조건\n");
        for (int g = 0; g < gold.size(); g++) {
            if (gold.get(g).scored() && !hit[g]) {
                b.append(g + 2).append(" | ").append(gold.get(g).number()).append(" | ").append(gold.get(g).text()).append('\n');
            }
        }
        return b.toString();
    }

    private static String pct(int a, int b) {
        return b == 0 ? "-" : String.format(Locale.ROOT, "%.1f%% (%d/%d)", 100.0 * a / b, a, b);
    }

    /** EVAL_PRICES="claude=3/15,gemini=0.3/2.5" = USD per 1M input/output tokens; "-" for a provider not listed. */
    private static String cost(String provider, int inTokens, int outTokens) {
        for (String p : System.getenv().getOrDefault("EVAL_PRICES", "").split(",")) {
            String[] kv = p.strip().split("[=/]");
            if (kv.length == 3 && kv[0].equals(provider)) {
                return String.format(Locale.ROOT, "%.4f",
                        (inTokens * Double.parseDouble(kv[1]) + outTokens * Double.parseDouble(kv[2])) / 1e6);
            }
        }
        return "-";
    }

    /** The app's own adapters and model settings (application.yml, so CLAUDE_MODEL etc. apply); only providers with a key. */
    private static List<LlmClient> clients(String wanted) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(env.getPropertySources()::addLast);
        List<LlmClient> clients = new ArrayList<>();
        for (String w : wanted.split(",")) {
            Provider p = Provider.choose(w, System::getenv);
            String key = System.getenv(p.keyEnv);
            if (key == null || key.isBlank()) {
                System.out.println(p + ": " + p.keyEnv + " 없음, 건너뜀");
                continue;
            }
            String prefix = "app.extraction." + p.name().toLowerCase(Locale.ROOT);
            String model = env.getRequiredProperty(prefix + ".model");
            clients.add(switch (p) {
                case CLAUDE -> new AnthropicClaudeClient(JSON, model);
                case GEMINI -> new GeminiClient(JSON, env.getRequiredProperty(prefix + ".base-url"), model, key);
                case OPENAI -> new OpenAiClient(JSON, env.getRequiredProperty(prefix + ".base-url"), model, key);
            });
        }
        return clients;
    }

    /**
     * No answer sheet: one document through the app's provider (EXTRACTION_PROVIDER, else the first key set) and
     * its quote check, written as a sheet a person grades by hand. Appends to {@code <out>/extract.csv}.
     */
    private static void extract(Path pdf, String provider, Path out) throws IOException {
        List<LlmClient> llms = clients(provider != null ? provider
                : Provider.choose(System.getenv("EXTRACTION_PROVIDER"), System::getenv).name().toLowerCase(Locale.ROOT));
        if (llms.isEmpty()) {
            System.out.println("API 키가 없어 실행하지 않습니다.");
            return;
        }
        LlmClient llm = llms.get(0);
        String name = llm.provider().name().toLowerCase(Locale.ROOT);
        String doc = pdf.getFileName().toString().replaceFirst("\\.pdf$", "");
        Files.createDirectories(out);
        PreparedInput input = new ClaudePdfDirectPreparer().prepare(Files.readAllBytes(pdf));
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        int inTokens = 0;
        int outTokens = 0;
        long started = System.nanoTime();
        for (int attempt = 1; attempt <= Extraction.ATTEMPTS; attempt++) {
            try {
                String raw = llm.send(ObligationPrompt.SYSTEM, input.llmContent(), ObligationPrompt.INSTRUCTION,
                        ObligationPrompt.SCHEMA);
                Files.writeString(out.resolve(doc + "." + name + ".response-" + attempt + ".json"), raw);
                JsonNode res = JSON.readTree(raw);
                inTokens += llm.provider().inputTokens(res);
                outTokens += llm.provider().outputTokens(res);
                List<Extraction.Item> found = Extraction.parse(JSON, validator, llm.provider().text(res)).obligations();
                Path csv = out.resolve("extract.csv");
                StringBuilder b = new StringBuilder(Files.exists(csv) ? ""
                        : "\uFEFF문서,쪽,조건번호,AI가 뽑은 조건(요약),조건 본문,주기,근거 문장,원문 확인 결과,사용자 판정(맞음/틀림/애매),메모\n");
                int[] checks = new int[3];
                for (Extraction.Item i : found) {
                    var check = QuoteChecker.check(i.sourceQuote(), i.pageNo(), input.pageTexts());
                    checks[check.ordinal()]++;
                    b.append(String.join(",", csv(doc), String.valueOf(i.pageNo()), csv(i.citationRaw()), csv(i.summary()),
                            csv(i.requirementText()), i.frequency().name(), csv(i.sourceQuote()), check.name(), "", "")).append('\n');
                }
                Files.writeString(csv, b, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
                String line = String.format(Locale.ROOT, "| %s | %s (%s) | %d쪽 (글자층 %d쪽) | %d | %d | %d | %d | %.1f | %d / %d | %s | %d |",
                        doc, name, llm.model(), pageCount(pdf), input.pageTexts().size(), found.size(), checks[0], checks[1],
                        checks[2], (System.nanoTime() - started) / 1e9, inTokens, outTokens, cost(name, inTokens, outTokens), attempt);
                Files.writeString(out.resolve("extract.md"), line + "\n", StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                System.out.println(line);
                return;
            } catch (Exception e) { // API error, truncation or schema violation: retry once, like a run
                System.out.println("  시도 " + attempt + " 실패: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        String line = String.format(Locale.ROOT, "| %s | %s | 실패 | | | | | %.1f | %d / %d | %s | %d |", doc, name,
                (System.nanoTime() - started) / 1e9, inTokens, outTokens, cost(name, inTokens, outTokens), Extraction.ATTEMPTS);
        Files.writeString(out.resolve("extract.md"), line + "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        System.out.println(line);
    }

    private static int pageCount(Path pdf) throws IOException {
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            return d.getNumberOfPages();
        }
    }

    private static String csv(String s) {
        return s == null ? "" : "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static Path answers(Path pdf) {
        return pdf.resolveSibling(pdf.getFileName().toString().replaceFirst("\\.pdf$", ".answers.csv"));
    }

    /** Image-only copy of a text PDF (150 dpi grey JPEG pages), standing in for a scan when no real one is at hand. */
    private static void rasterize(Path in, Path out) throws IOException {
        try (PDDocument src = Loader.loadPDF(in.toFile()); PDDocument dst = new PDDocument()) {
            PDFRenderer renderer = new PDFRenderer(src);
            for (int p = 0; p < src.getNumberOfPages(); p++) {
                BufferedImage image = renderer.renderImageWithDPI(p, 150, ImageType.GRAY);
                PDRectangle box = src.getPage(p).getCropBox();
                PDPage page = new PDPage(new PDRectangle(box.getWidth(), box.getHeight()));
                dst.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(dst, page)) {
                    cs.drawImage(JPEGFactory.createFromImage(dst, image, 0.8f), 0, 0, box.getWidth(), box.getHeight());
                }
            }
            dst.save(out.toFile());
        }
        System.out.println(out + " (" + Files.size(out) / 1024 + " KB)");
    }
}
