package com.envpermit.extraction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Scores one extraction against a hand-made answer sheet (review-extraction/04). Pure and deterministic.
 *
 * <p>Matching, per extracted item (many items may hit one condition, since the prompt splits a condition per
 * obligation): similarity = share of the shorter text's character bigrams found in the other (letters and digits
 * only), best of requirement_text and source_quote.
 * <ol>
 * <li>answer rows with the same 조건번호 ({@link CitationKey#normalize}): the most similar one, if ≥ {@link #SAME_NUMBER_MIN};
 * <li>otherwise the most similar row of all, if ≥ {@link #TEXT_MIN} (ties: scored row first, then sheet order);
 * <li>otherwise the item is unmatched (counts against precision).
 * </ol>
 * Rows marked 참고 are text the model may legitimately extract but the sheet does not score: items matched to
 * them count neither for nor against precision, and the rows are not in recall.
 */
final class EvalScorer {

    static final double SAME_NUMBER_MIN = 0.5;
    static final double TEXT_MIN = 0.6;

    /** frequencies: accepted {@code Frequency} names, empty when the document states none. */
    record Gold(String number, String text, Set<String> frequencies, boolean scored) {
    }

    record Item(String number, List<String> texts, String frequency) {
    }

    /**
     * found/gold = recall, hits/(items − optionalHits) = precision. numberExact/numberLoose are out of the found
     * rows that have a 조건번호 (numbered); frequencyOk out of the found rows that state a 주기 (frequencyGold).
     * match[i] = index of the answer row item i matched, −1 for none.
     */
    record Score(int gold, int found, int items, int hits, int optionalHits, int numbered, int numberExact,
                 int numberLoose, int frequencyGold, int frequencyOk, int[] match) {

        double recall() {
            return ratio(found, gold);
        }

        double precision() {
            return ratio(hits, items - optionalHits);
        }

        static double ratio(int a, int b) {
            return b == 0 ? Double.NaN : (double) a / b;
        }
    }

    private EvalScorer() {
    }

    static Score score(List<Gold> gold, List<Item> items) {
        List<Set<String>> goldGrams = gold.stream().map(g -> bigrams(g.text())).toList();
        int[] match = new int[items.size()];
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            List<Set<String>> grams = item.texts().stream().map(EvalScorer::bigrams).toList();
            String key = CitationKey.normalize(item.number());
            int sameNumber = -1;
            int any = -1;
            double sameNumberSim = 0;
            double anySim = 0;
            for (int g = 0; g < gold.size(); g++) {
                Set<String> gg = goldGrams.get(g);
                double sim = grams.stream().mapToDouble(t -> overlap(t, gg)).max().orElse(0);
                if (key != null && key.equals(CitationKey.normalize(gold.get(g).number())) && sim > sameNumberSim) {
                    sameNumber = g;
                    sameNumberSim = sim;
                }
                if (sim > anySim || sim == anySim && any >= 0 && gold.get(g).scored() && !gold.get(any).scored()) {
                    any = g;
                    anySim = sim;
                }
            }
            match[i] = sameNumberSim >= SAME_NUMBER_MIN ? sameNumber : anySim >= TEXT_MIN ? any : -1;
        }

        int scored = 0;
        int found = 0;
        int hits = 0;
        int optionalHits = 0;
        int numbered = 0;
        int numberExact = 0;
        int numberLoose = 0;
        int frequencyGold = 0;
        int frequencyOk = 0;
        for (int m : match) {
            if (m >= 0 && gold.get(m).scored()) {
                hits++;
            } else if (m >= 0) {
                optionalHits++;
            }
        }
        for (int g = 0; g < gold.size(); g++) {
            Gold row = gold.get(g);
            if (!row.scored()) {
                continue;
            }
            scored++;
            List<Item> matched = new ArrayList<>();
            for (int i = 0; i < match.length; i++) {
                if (match[i] == g) {
                    matched.add(items.get(i));
                }
            }
            if (matched.isEmpty()) {
                continue;
            }
            found++;
            String key = CitationKey.normalize(row.number());
            if (key != null) {
                numbered++;
                if (matched.stream().anyMatch(it -> key.equals(CitationKey.normalize(it.number())))) {
                    numberExact++;
                }
                if (matched.stream().anyMatch(it -> sameNumberLoosely(key, CitationKey.normalize(it.number())))) {
                    numberLoose++;
                }
            }
            if (!row.frequencies().isEmpty()) {
                frequencyGold++;
                if (matched.stream().anyMatch(it -> row.frequencies().contains(it.frequency()))) {
                    frequencyOk++;
                }
            }
        }
        return new Score(scored, found, items.size(), hits, optionalHits, numbered, numberExact, numberLoose,
                frequencyGold, frequencyOk, match);
    }

    /**
     * Equal, or one key's segments appear as a run inside the other's: "2-가" ~ "Ⅱ-2-가" ~ "2-가-동식물상".
     * For documents whose conditions are bullets under numbered headings, where models differ on how much of
     * the heading path they write.
     */
    static boolean sameNumberLoosely(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        List<String> x = Arrays.asList(a.split("-"));
        List<String> y = Arrays.asList(b.split("-"));
        return Collections.indexOfSubList(x.size() >= y.size() ? x : y, x.size() >= y.size() ? y : x) >= 0;
    }

    static double similarity(String a, String b) {
        return overlap(bigrams(a), bigrams(b));
    }

    private static double overlap(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> small = a.size() <= b.size() ? a : b;
        Set<String> big = small == a ? b : a;
        return (double) small.stream().filter(big::contains).count() / small.size();
    }

    private static Set<String> bigrams(String text) {
        StringBuilder s = new StringBuilder();
        text.toLowerCase(Locale.ROOT).codePoints().filter(Character::isLetterOrDigit).forEach(s::appendCodePoint);
        Set<String> grams = new HashSet<>();
        for (int i = 0; i + 2 <= s.length(); i++) {
            grams.add(s.substring(i, i + 2));
        }
        return grams;
    }

    /**
     * Answer sheet: CSV with header 조건번호,내용,주기[,구분[,…]]. 주기 = Frequency names joined by "|" (any of them
     * counts), empty when the document states none. 구분 "참고" = not scored; anything else is scored.
     */
    static List<Gold> answerSheet(String csv) {
        List<List<String>> rows = csv(csv);
        List<Gold> gold = new ArrayList<>();
        for (List<String> r : rows.subList(1, rows.size())) {
            if (r.size() < 2 || r.get(1).isBlank()) {
                continue;
            }
            String frequency = r.size() > 2 ? r.get(2).strip() : "";
            gold.add(new Gold(r.get(0).strip(), r.get(1), frequency.isEmpty() ? Set.of() : Set.of(frequency.split("\\|")),
                    r.size() < 4 || !"참고".equals(r.get(3).strip())));
        }
        return gold;
    }

    /** RFC 4180: quoted fields may hold commas, newlines and "" for a quote. A leading BOM is dropped. */
    static List<List<String>> csv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        String s = text.startsWith("﻿") ? text.substring(1) : text;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < s.length() && s.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else if (c != '\r') {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
