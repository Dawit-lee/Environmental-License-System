package com.envpermit.extraction;

import com.envpermit.schedule.ScheduleSuggestion;
import com.envpermit.schedule.ScheduleSuggestion.Evidence;
import com.envpermit.schedule.ScheduleSuggestion.Interval;
import com.envpermit.schedule.ScheduleSuggestion.Type;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Document → schedule eval (design-schedule/07, 설계 §4.3): {@code ./gradlew scheduleEval --args="<dir> [rule,claude,…]"}.
 * Not a test and not in CI. For every {@code X.txt} (first line {@code # docType: …}, pages split by {@code ---page---})
 * with an {@code X.answers.csv}, each extractor runs and is scored at date level: the set of "when" keys it found
 * (a date, a base-date rule like CONTRACT+15D, or a recurring interval) against the answer keys. Titles are not scored.
 *
 * <p>{@code rule} is a local regex baseline that sends nothing anywhere — the only extractor to use on confidential
 * documents. LLM extractors (key from the environment) run the app's own SchedulePrompt + Extraction.scheduleRows on
 * the text; use them on the fictional set (src/test/resources/schedule-eval) only.
 */
public final class ScheduleEval {

    private static final ObjectMapper JSON = Jackson2ObjectMapperBuilder.json().build();
    static final String PAGE_BREAK = "---page---";

    record Doc(String name, String docType, List<String> pages, Set<String> gold) {
    }

    record Score(int gold, int predicted, int hits) {
        double recall() {
            return gold == 0 ? Double.NaN : (double) hits / gold;
        }

        double precision() {
            return predicted == 0 ? Double.NaN : (double) hits / predicted;
        }

        Score plus(Score o) {
            return new Score(gold + o.gold, predicted + o.predicted, hits + o.hits);
        }
    }

    private ScheduleEval() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "src/test/resources/schedule-eval");
        List<String> extractors = List.of((args.length > 1 ? args[1] : "rule").split(","));
        List<Doc> docs = load(dir);
        System.out.println("| 문서 | 추출기 | 정답 | 추출 | 맞음 | 재현율 | 정밀도 | 놓침 | 오검출 |\n|---|---|---|---|---|---|---|---|---|");
        for (String ex : extractors) {
            LlmClient llm = ex.equals("rule") ? null : ExtractionEval.clients(ex).stream().findFirst().orElse(null);
            if (!ex.equals("rule") && llm == null) continue;
            Score total = new Score(0, 0, 0);
            for (Doc d : docs) {
                Set<String> found = keys(llm == null ? rules(d) : llm(llm, d));
                Score s = score(d.gold(), found);
                total = total.plus(s);
                Set<String> missed = new TreeSet<>(d.gold());
                missed.removeAll(found);
                Set<String> extra = new TreeSet<>(found);
                extra.removeAll(d.gold());
                System.out.printf("| %s | %s | %d | %d | %d | %s | %s | %s | %s |%n", d.name(), ex, s.gold(), s.predicted(),
                        s.hits(), pct(s.recall()), pct(s.precision()), String.join(" ", missed), String.join(" ", extra));
            }
            System.out.printf("| **전체 %d건** | %s | %d | %d | %d | **%s** | **%s** | | |%n", docs.size(), ex, total.gold(),
                    total.predicted(), total.hits(), pct(total.recall()), pct(total.precision()));
        }
    }

    static List<Doc> load(Path dir) throws Exception {
        List<Doc> docs = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path txt : files.filter(p -> p.toString().endsWith(".txt")).sorted().toList()) {
                Path answers = txt.resolveSibling(txt.getFileName().toString().replaceFirst("\\.txt$", ".answers.csv"));
                if (!Files.exists(answers)) continue;
                String text = Files.readString(txt, StandardCharsets.UTF_8);
                String first = text.lines().findFirst().orElse("");
                String type = first.startsWith("# docType:") ? first.substring(10).trim() : "TASK_SPEC";
                List<String> pages = List.of(text.substring(first.length()).split("\\R" + PAGE_BREAK + "\\R"));
                Set<String> gold = new LinkedHashSet<>(Files.readAllLines(answers).stream().skip(1).map(String::trim)
                        .filter(s -> !s.isEmpty()).toList());
                docs.add(new Doc(txt.getFileName().toString().replaceFirst("\\.txt$", ""), type, pages, gold));
            }
        }
        return docs;
    }

    static Score score(Set<String> gold, Set<String> found) {
        Set<String> hits = new LinkedHashSet<>(found);
        hits.retainAll(gold);
        return new Score(gold.size(), found.size(), hits.size());
    }

    /** "When" keys of rows: event/milestone date, task start and end, base-date rule, recurring interval. */
    static Set<String> keys(List<ScheduleSuggestion> rows) {
        Set<String> keys = new LinkedHashSet<>();
        for (ScheduleSuggestion r : rows) {
            if (r.type() == Type.RECURRING) {
                if (r.interval() != null) keys.add(r.interval().name());
                continue;
            }
            if (r.anchor() != null) {
                int n = r.offsetMonths() != null ? r.offsetMonths() : r.offsetDays() == null ? 0 : r.offsetDays();
                keys.add(r.anchor() + (n < 0 ? "" : "+") + n + (r.offsetMonths() != null ? "M" : "D"));
            }
            if (r.startsAt() != null) keys.add(r.startsAt().toLocalDate().toString());
            if (r.endsAt() != null) keys.add(r.endsAt().toLocalDate().toString()); // a trip's last day
            if (r.date() != null) keys.add(r.date().toString());
            if (r.type() == Type.TASK && r.startDate() != null) keys.add(r.startDate().toString());
        }
        return keys;
    }

    private static List<ScheduleSuggestion> llm(LlmClient llm, Doc d) throws Exception {
        StringBuilder text = new StringBuilder();
        Map<Integer, String> pageTexts = new HashMap<>();
        for (int i = 0; i < d.pages().size(); i++) {
            text.append("[").append(i + 1).append("쪽]\n").append(d.pages().get(i)).append("\n\n");
            pageTexts.put(i + 1, d.pages().get(i));
        }
        for (int attempt = 1; ; attempt++) {
            try {
                String raw = llm.send(SchedulePrompt.SYSTEM, new PreparedInput.Text(text.toString()),
                        SchedulePrompt.instruction(d.docType()), SchedulePrompt.SCHEMA);
                JsonNode res = JSON.readTree(raw);
                return Extraction.scheduleRows(JSON, llm.provider().text(res), pageTexts, d.name());
            } catch (Exception e) {
                if (attempt >= Extraction.ATTEMPTS) {
                    System.out.println("  " + d.name() + ": 실패 " + e.getMessage());
                    return List.of();
                }
            }
        }
    }

    // --- local rule-based baseline --------------------------------------------------------------------------------

    private static final Pattern FULL = Pattern.compile(
            "(20\\d{2})\\s*(?:[.\\-/]|년)\\s*(\\d{1,2})\\s*(?:[.\\-/]|월)\\s*(\\d{1,2})\\s*(?:일|\\.)?(?:\\s*\\([^)]{1,3}\\))?"
                    + "(?:\\s*(\\d{1,2})\\s*(?::|시)\\s*(\\d{2})?)?");
    private static final Pattern NO_YEAR = Pattern.compile(
            "(?<![\\d.])(\\d{1,2})\\s*(?:월\\s*(\\d{1,2})\\s*일|/(\\d{1,2})(?![\\d/]))");
    private static final Pattern RELATIVE = Pattern.compile(
            "(계약일|계약|착수일|착수|제출일|심의일|심의|준공일|준공|과업\\s*종료일)\\s*(?:로부터|부터|후|전까지|전)?\\s*(\\d+)\\s*(일|개월)\\s*(이내|내|전|후|까지)?");
    private static final Map<String, Interval> RECURRING = Map.of("매월", Interval.MONTHLY, "월 1회", Interval.MONTHLY,
            "격주", Interval.BIWEEKLY, "매주", Interval.WEEKLY, "분기", Interval.QUARTERLY);

    /** Every date-looking phrase becomes a row (no notion of 공고일 vs 마감일): the baseline the LLM must beat. */
    static List<ScheduleSuggestion> rules(Doc d) {
        List<ScheduleSuggestion> rows = new ArrayList<>();
        Integer year = null;
        for (int p = 0; p < d.pages().size(); p++) {
            String text = d.pages().get(p);
            List<int[]> taken = new ArrayList<>();
            Matcher m = FULL.matcher(text);
            while (m.find()) {
                int y = Integer.parseInt(m.group(1));
                year = year == null ? y : year;
                LocalDate date = safeDate(y, m.group(2), m.group(3));
                if (date == null) continue;
                taken.add(new int[] {m.start(), m.end()});
                LocalDateTime at = m.group(4) == null ? null
                        : date.atTime(Integer.parseInt(m.group(4)) % 24, m.group(5) == null ? 0 : Integer.parseInt(m.group(5)));
                rows.add(row(at != null ? Type.EVENT : Type.MILESTONE, date, at, null, null, false, p, text, m));
            }
            Matcher n = NO_YEAR.matcher(text);
            while (n.find()) {
                int start = n.start();
                if (taken.stream().anyMatch(t -> start >= t[0] && start < t[1]) || year == null) continue;
                LocalDate date = safeDate(year, n.group(1), n.group(2) != null ? n.group(2) : n.group(3));
                if (date != null) rows.add(row(Type.MILESTONE, date, null, null, null, true, p, text, n));
            }
            Matcher r = RELATIVE.matcher(text);
            while (r.find()) {
                String base = r.group(1).replace(" ", "");
                String anchor = base.startsWith("계약") ? "CONTRACT" : base.startsWith("착수") ? "KICKOFF"
                        : base.startsWith("제출") ? "SUBMISSION" : base.startsWith("심의") ? "REVIEW" : "COMPLETION";
                int value = Integer.parseInt(r.group(2));
                boolean before = "전".equals(r.group(4)) || r.group(0).contains("전까지");
                int signed = before ? -value : value;
                boolean months = "개월".equals(r.group(3));
                rows.add(new ScheduleSuggestion(Type.MILESTONE, null, null, r.group(0), null, null, null, null, null, null,
                        null, null, anchor, months ? null : signed, months ? signed : null, null, r.group(0), null, false,
                        new Evidence(p + 1, r.group(0)), "LOW", null, null, null, null, null, d.name()));
            }
            for (Map.Entry<String, Interval> e : RECURRING.entrySet()) {
                if (text.contains(e.getKey())) {
                    rows.add(new ScheduleSuggestion(Type.RECURRING, "CLIENT_REPORT", null, e.getKey(), null, null, null,
                            null, null, null, null, null, null, null, null, null, null, e.getValue(), false,
                            new Evidence(p + 1, e.getKey()), "LOW", null, null, null, null, null, d.name()));
                }
            }
        }
        return rows;
    }

    private static ScheduleSuggestion row(Type type, LocalDate date, LocalDateTime at, String anchor, Integer offset,
                                          boolean yearInferred, int page, String text, Matcher m) {
        String quote = text.substring(m.start(), m.end());
        return new ScheduleSuggestion(type, type == Type.EVENT ? "OTHER" : null, null, quote, null,
                at == null ? date : null, null, at, null, null, null, null, anchor, offset, null, null, null, null,
                yearInferred, new Evidence(page + 1, quote), "LOW", null, null, null, null, null, null);
    }

    private static LocalDate safeDate(int year, String month, String day) {
        try {
            return LocalDate.of(year, Integer.parseInt(month), Integer.parseInt(day));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String pct(double v) {
        return Double.isNaN(v) ? "—" : Math.round(v * 100) + "%";
    }
}
