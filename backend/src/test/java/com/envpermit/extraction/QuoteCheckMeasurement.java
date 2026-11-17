package com.envpermit.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.envpermit.obligation.ObligationPayload.QuoteCheck;
import com.envpermit.support.Measure;
import com.envpermit.support.SyntheticPermit;
import com.envpermit.support.SyntheticPermit.Condition;
import com.envpermit.support.SyntheticPermit.Template;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Measurement (docs/measurements/CONTRIBUTIONS.md §1): how many invented or altered quotes does
 * {@link QuoteChecker} flag, and how many real ones does it flag by mistake? No LLM, no DB.
 * The label of a sample is how it was made (copied from the page / made from a pair the document does not have /
 * copied then altered), never what QuoteChecker says.
 */
@Tag("measurement")
class QuoteCheckMeasurement {

    static final int DOCS = 10, PAGES = 6, PER_PAGE = 10;

    record Doc(Map<Integer, String> pages, List<Condition> used, List<Integer> pageOf, List<Condition> unused,
               List<String> split) { // split.get(p - 1): the sentence cut between page p and p + 1
    }

    record Sample(String group, String kind, Doc doc, String quote, Integer page) {
    }

    @Test
    void quote_check_on_synthetic_permits() {
        Random rnd = new Random(20261111);
        List<Doc> docs = new ArrayList<>();
        for (int d = 0; d < DOCS; d++) {
            docs.add(doc(rnd));
        }
        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < 60; i++) { // --- real quotes, copied from the page
            Doc doc = docs.get(i % DOCS);
            int c = rnd.nextInt(doc.used().size());
            String text = doc.used().get(c).text();
            int page = doc.pageOf().get(c);
            switch (i % 3) {
                case 0 -> samples.add(new Sample("정상", "그대로 복사", doc, text, page));
                case 1 -> samples.add(new Sample("정상", "띄어쓰기만 다름", doc, respace(text, rnd), page));
                default -> samples.add(new Sample("정상", "쪽 번호 1쪽 어긋남", doc, text, page == 1 ? 2 : page - 1));
            }
        }
        for (int i = 0; i < 20; i++) {
            Doc doc = docs.get(i % DOCS);
            int p = 1 + rnd.nextInt(PAGES - 1);
            samples.add(new Sample("정상", "쪽 경계에 걸친 문장", doc, doc.split().get(p - 1), p + i % 2));
        }
        for (int i = 0; i < 60; i++) { // --- invented: a (facility, pollutant) pair this document does not have
            Doc doc = docs.get(i % DOCS);
            samples.add(new Sample("환각", "원문에 없는 조건", doc, doc.unused().get(i / DOCS).text(), 1 + rnd.nextInt(PAGES)));
        }
        for (int i = 0; i < 60; i++) { // --- copied, then the meaning changed
            Doc doc = docs.get(i % DOCS);
            int kind = i % 3;
            int c = pick(doc, rnd, kind == 0 ? null : kind == 1 ? Template.MEASURE : Template.LIMIT);
            Condition cond = doc.used().get(c);
            String altered = switch (kind) {
                case 0 -> cond.template() == Template.MEASURE ? cond.withTimes(cond.times() + 1).text()
                        : cond.withNumber(cond.number() + (cond.template() == Template.KEEP ? 2 : 20)).text();
                case 1 -> cond.withPeriod(otherPeriod(cond.period(), rnd)).text();
                default -> cond.text().replace("이하로", "미만으로");
            };
            samples.add(new Sample("변조", List.of("숫자 변경", "주기 변경", "이하→미만").get(kind), doc, altered, doc.pageOf().get(c)));
        }
        for (int i = 0; i < 60; i++) { // --- copied, only the notation changed (same meaning)
            Doc doc = docs.get(i % DOCS);
            int c = rnd.nextInt(doc.used().size());
            String text = doc.used().get(c).text();
            String[] made = switch (i % 5) {
                case 0 -> new String[] {"어미 변경(하여야 한다→할 것)", text.replace("하여야 한다.", "할 것")};
                case 1 -> new String[] {"전각 숫자", fullWidth(text)};
                case 2 -> new String[] {"마침표 생략", text.substring(0, text.length() - 1)};
                case 3 -> new String[] {"자모 분리(NFD)", Normalizer.normalize(text, Normalizer.Form.NFD)};
                default -> new String[] {"조사 정리(을(를)→을, 의→ )", text.replace("을(를)", "을").replaceFirst("의 ", " ")};
            };
            assertThat(made[1]).isNotEqualTo(text);
            samples.add(new Sample("표기", made[0], doc, made[1], doc.pageOf().get(c)));
        }
        for (int i = 0; i < 20; i++) { // --- real quote, page number off by two or more
            Doc doc = docs.get(i % DOCS);
            int c = rnd.nextInt(doc.used().size());
            int page = doc.pageOf().get(c);
            samples.add(new Sample("쪽오류", "쪽 번호 2쪽 이상 어긋남", doc, doc.used().get(c).text(), page <= 3 ? page + 2 + rnd.nextInt(2) : page - 2));
        }
        Doc scanned = new Doc(Map.of(), docs.get(0).used(), docs.get(0).pageOf(), List.of(), List.of());
        for (int i = 0; i < 10; i++) { // --- scanned document: no text layer
            samples.add(new Sample("스캔", "글자층 없음", scanned, scanned.used().get(i).text(), scanned.pageOf().get(i)));
        }

        Map<String, int[]> byKind = new LinkedHashMap<>(); // group|kind → VERIFIED, MISMATCH, UNVERIFIABLE
        Map<String, int[]> byGroup = new LinkedHashMap<>();
        long t0 = System.nanoTime();
        for (Sample s : samples) {
            QuoteCheck r = QuoteChecker.check(s.quote(), s.page(), s.doc().pages());
            byKind.computeIfAbsent(s.group() + " | " + s.kind(), k -> new int[3])[r.ordinal()]++;
            byGroup.computeIfAbsent(s.group(), k -> new int[3])[r.ordinal()]++;
        }
        long nanos = System.nanoTime() - t0;

        Measure.log("## 1. 인용문 원문 대조 (QuoteChecker) — 합성 허가증 %d건 × %d쪽, 표본 %d건, seed 20261111",
                DOCS, PAGES, samples.size());
        Measure.log("| 분류 | 만든 방법 | 표본 | 원문에 있음 | 환각 의심 | 확인 불가 |");
        byKind.forEach((k, v) -> Measure.log("| %s | %d | %d | %d | %d |", k, v[0] + v[1] + v[2], v[0], v[1], v[2]));
        int[] real = byGroup.get("정상"), fake = byGroup.get("환각"), altered = byGroup.get("변조"), notation = byGroup.get("표기");
        Measure.log("지어낸 인용문을 환각 의심으로 표시: %s", Measure.pct(fake[1], sum(fake)));
        Measure.log("뜻을 바꾼 인용문(숫자·주기·이하/미만)을 환각 의심으로 표시: %s", Measure.pct(altered[1], sum(altered)));
        Measure.log("정상 인용문을 잘못 의심: %s", Measure.pct(real[1], sum(real)));
        Measure.log("표기만 다른 인용문을 의심으로 표시(엄격 일치의 대가): %s", Measure.pct(notation[1], sum(notation)));
        Measure.log("대조 %d건 총 %.1fms", samples.size(), nanos / 1e6);
        assertThat(samples).hasSize(290);
    }

    /** PAGES pages of PER_PAGE conditions; the last condition of each page but the final one is cut onto the next page. */
    private static Doc doc(Random rnd) {
        List<Condition> all = SyntheticPermit.conditions(rnd);
        int n = PAGES * PER_PAGE;
        List<Condition> used = new ArrayList<>(), unused = all.subList(n + PAGES - 1, all.size());
        List<Integer> pageOf = new ArrayList<>();
        List<String> split = new ArrayList<>();
        Map<Integer, StringBuilder> pages = new HashMap<>();
        int next = 0;
        for (int p = 1; p <= PAGES; p++) {
            StringBuilder page = pages.computeIfAbsent(p, k -> new StringBuilder());
            page.append("환경기초시설 설치·운영 허가조건 (").append(p).append("쪽)\n");
            for (int i = 0; i < PER_PAGE; i++) {
                Condition c = all.get(next++);
                used.add(c);
                pageOf.add(p);
                page.append(i + 1).append(". ").append(c.text()).append('\n');
            }
            if (p < PAGES) { // one more sentence, cut in the middle by the page break
                String s = all.get(n + p - 1).text();
                int cut = s.length() / 2;
                page.append(PER_PAGE + 1).append(". ").append(s, 0, cut).append('\n');
                pages.computeIfAbsent(p + 1, k -> new StringBuilder()).append(s.substring(cut)).append('\n');
                split.add(s);
            }
        }
        Map<Integer, String> text = new HashMap<>();
        pages.forEach((p, b) -> text.put(p, b.toString()));
        return new Doc(text, used, pageOf, unused, split);
    }

    private static int pick(Doc doc, Random rnd, Template template) {
        while (true) {
            int c = rnd.nextInt(doc.used().size());
            if (template == null || doc.used().get(c).template() == template) {
                return c;
            }
        }
    }

    private static String otherPeriod(String period, Random rnd) {
        List<String> others = SyntheticPermit.PERIODS.stream().filter(p -> !p.equals(period)).toList();
        return others.get(rnd.nextInt(others.size()));
    }

    /** What a broken PDF text layer does: spaces dropped, doubled or turned into line breaks. */
    private static String respace(String text, Random rnd) {
        StringBuilder b = new StringBuilder();
        for (char ch : text.toCharArray()) {
            if (ch == ' ') {
                b.append(switch (rnd.nextInt(3)) {
                    case 0 -> "";
                    case 1 -> "  ";
                    default -> "\n";
                });
            } else {
                b.append(ch);
                if (rnd.nextInt(12) == 0) {
                    b.append(' ');
                }
            }
        }
        return b.toString();
    }

    private static String fullWidth(String text) {
        StringBuilder b = new StringBuilder();
        for (char ch : text.toCharArray()) {
            b.append(ch >= '0' && ch <= '9' ? (char) (ch - '0' + '０') : ch);
        }
        return b.toString();
    }

    private static int sum(int[] v) {
        return v[0] + v[1] + v[2];
    }
}
