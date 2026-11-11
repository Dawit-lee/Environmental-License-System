package com.envpermit.revision;

import static org.assertj.core.api.Assertions.assertThat;

import com.envpermit.obligation.Frequency;
import com.envpermit.obligation.ObligationPayload;
import com.envpermit.obligation.ObligationPayload.Category;
import com.envpermit.obligation.ObligationPayload.Type;
import com.envpermit.revision.DiffEngine.Change;
import com.envpermit.revision.DiffEngine.ChangeType;
import com.envpermit.revision.DiffEngine.Snapshot;
import com.envpermit.support.Measure;
import com.envpermit.support.SyntheticPermit;
import com.envpermit.support.SyntheticPermit.Condition;
import com.envpermit.support.SyntheticPermit.Template;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Measurement (docs/measurements/CONTRIBUTIONS.md §2): does {@link DiffEngine} give each condition of a
 * synthetic old/new permit pair the class its edit implies? The expected class is the edit the generator made
 * (number / period / wording / dropped / new), never what DiffEngine says. No LLM, no DB; app thresholds 0.6 / 0.5.
 */
@Tag("measurement")
class DiffMeasurement {

    static final DiffEngine.MatchConfig CFG = new DiffEngine.MatchConfig(0.6, 0.5); // application.yml defaults
    static final int PAIRS = 3, OLD = 100, ADDED = 10;
    static final List<String> EDITS = List.of("그대로", "띄어쓰기만", "숫자 변경", "이하→미만", "주기 변경", "어미 변경",
            "전각 숫자·조사 정리", "삭제", "번호만 변경", "그대로");

    record Expect(String edit, ChangeType type, Long oldId, String newKey) {
    }

    @Test
    void classification_on_synthetic_permit_pairs() {
        for (boolean frequencyFollows : new boolean[] {true, false}) {
            Map<String, int[]> tally = new LinkedHashMap<>(); // edit → correct, total
            Map<String, Map<String, Integer>> wrong = new LinkedHashMap<>();
            long nanos = 0;
            for (int pair = 0; pair < PAIRS; pair++) {
                Random rnd = new Random(20261111 + pair);
                List<Condition> all = SyntheticPermit.conditions(rnd);
                List<Snapshot> old = new ArrayList<>();
                List<ObligationPayload> next = new ArrayList<>();
                List<Expect> expects = new ArrayList<>();
                for (int i = 0; i < OLD; i++) {
                    String edit = EDITS.get(i % EDITS.size());
                    Condition c = all.get(i);
                    c = switch (edit) { // the edit needs a sentence that has the thing it changes
                        case "주기 변경" -> c.withTemplate(Template.MEASURE);
                        case "이하→미만" -> c.withTemplate(Template.LIMIT).withNumber(10 * (1 + rnd.nextInt(30)));
                        default -> c;
                    };
                    String key = "허가조건-" + (i + 1);
                    old.add(new Snapshot(i, 1, false, row(key, c.text(), frequency(c))));
                    Condition n = c;
                    String text = c.text();
                    String newKey = key;
                    ChangeType type = ChangeType.UNCHANGED;
                    switch (edit) {
                        case "띄어쓰기만" -> text = text.replace(" ", "  ").replaceFirst("  ", "");
                        case "숫자 변경" -> {
                            n = c.template() == Template.MEASURE ? c.withTimes(c.times() + 1)
                                    : c.withNumber(c.number() + (c.template() == Template.KEEP ? 2 : 20));
                            text = n.text();
                            type = ChangeType.CHANGED;
                        }
                        case "이하→미만" -> {
                            text = text.replace("이하로", "미만으로");
                            type = ChangeType.CHANGED;
                        }
                        case "주기 변경" -> {
                            String was = c.period();
                            List<String> others = SyntheticPermit.PERIODS.stream().filter(p -> !p.equals(was)).toList();
                            n = c.withPeriod(others.get(rnd.nextInt(others.size())));
                            text = n.text();
                            type = ChangeType.CHANGED;
                        }
                        case "어미 변경" -> {
                            text = text.replace("하여야 한다.", "할 것");
                            type = ChangeType.MINOR;
                        }
                        case "전각 숫자·조사 정리" -> {
                            text = fullWidth(text).replace("을(를)", "을");
                            type = ChangeType.MINOR;
                        }
                        case "번호만 변경" -> newKey = "허가조건-" + (500 + i);
                        default -> {
                        }
                    }
                    if (edit.equals("삭제")) {
                        expects.add(new Expect(edit, ChangeType.REMOVED, (long) i, null));
                        continue;
                    }
                    next.add(row(newKey, text, frequencyFollows ? frequency(n) : frequency(c)));
                    expects.add(new Expect(edit, type, (long) i, newKey));
                }
                for (int i = 0; i < ADDED; i++) { // pairs the old version does not have
                    String key = "허가조건-" + (900 + i);
                    Condition c = all.get(OLD + i);
                    next.add(row(key, c.text(), frequency(c)));
                    expects.add(new Expect("추가", ChangeType.ADDED, null, key));
                }

                long t0 = System.nanoTime();
                List<Change> out = DiffEngine.diff(next, old, CFG);
                nanos += System.nanoTime() - t0;
                for (Expect e : expects) {
                    Change got = out.stream().filter(ch -> e.newKey() != null
                            ? ch.next() != null && ch.next().citationKey().equals(e.newKey())
                            : ch.old() != null && ch.old().id() == e.oldId()).findFirst().orElseThrow();
                    boolean ok = got.type() == e.type()
                            && (e.type() == ChangeType.ADDED || e.type() == ChangeType.REMOVED || got.old().id() == e.oldId());
                    int[] t = tally.computeIfAbsent(e.edit() + " → " + e.type(), k -> new int[2]);
                    t[1]++;
                    if (ok) {
                        t[0]++;
                    } else {
                        String what = got.type() + (got.old() != null && got.next() != null && !Long.valueOf(got.old().id()).equals(e.oldId())
                                ? " (다른 조건과 짝)" : "") + (got.flags().contains(DiffEngine.Flag.NEEDS_CONFIRM) ? " · 짝 확인 필요 표시" : " · 표시 없음");
                        wrong.computeIfAbsent(e.edit(), k -> new LinkedHashMap<>()).merge(what, 1, Integer::sum);
                    }
                }
            }
            Measure.log("## 2. 버전 비교 (DiffEngine) — 합성 허가증 쌍 %d개, 조건 %d개(기존 %d + 추가 %d씩), 주기 필드가 %s",
                    PAIRS, PAIRS * (OLD + ADDED), OLD, ADDED, frequencyFollows ? "본문을 따라 바뀜" : "이전 값 그대로(추출이 놓친 경우)");
            Measure.log("| 생성기가 한 수정 → 기대 분류 | 일치 | 불일치 내용 |");
            int correct = 0, total = 0, missed = 0, numeric = 0;
            for (Map.Entry<String, int[]> e : tally.entrySet()) {
                String edit = e.getKey().substring(0, e.getKey().indexOf(" → "));
                Measure.log("| %s | %s | %s |", e.getKey(), Measure.pct(e.getValue()[0], e.getValue()[1]),
                        wrong.getOrDefault(edit, Map.of()));
                correct += e.getValue()[0];
                total += e.getValue()[1];
                if (e.getKey().endsWith("→ CHANGED")) {
                    numeric += e.getValue()[1];
                    missed += e.getValue()[1] - e.getValue()[0];
                }
            }
            Measure.log("전체 일치: %s", Measure.pct(correct, total));
            Measure.log("숫자·주기·이하/미만 변경 %d건 중 '변경'으로 분류하지 못한 건수: %d", numeric, missed);
            Measure.log("비교 %d회 총 %.1fms", PAIRS, nanos / 1e6);
            assertThat(total).isEqualTo(PAIRS * (OLD + ADDED));
        }
    }

    /** One condition inserted at the top, so every number moves by one and each key now names its neighbour's text. */
    @Test
    void every_number_shifted_by_one() {
        int right = 0, total = 0;
        Map<String, Integer> wrong = new LinkedHashMap<>();
        for (int pair = 0; pair < PAIRS; pair++) {
            List<Condition> all = SyntheticPermit.conditions(new Random(20261111 + pair));
            List<Snapshot> old = new ArrayList<>();
            List<ObligationPayload> next = new ArrayList<>();
            next.add(row("허가조건-1", all.get(OLD).text(), frequency(all.get(OLD))));
            for (int i = 0; i < OLD; i++) {
                old.add(new Snapshot(i, 1, false, row("허가조건-" + (i + 1), all.get(i).text(), frequency(all.get(i)))));
                next.add(row("허가조건-" + (i + 2), all.get(i).text(), frequency(all.get(i))));
            }
            List<Change> out = DiffEngine.diff(next, old, CFG);
            for (int i = 0; i < OLD; i++) {
                long id = i;
                String key = "허가조건-" + (i + 2);
                total++;
                Change got = out.stream().filter(ch -> ch.old() != null && ch.old().id() == id).findFirst().orElseThrow();
                if (got.type() == ChangeType.UNCHANGED && got.next().citationKey().equals(key)) {
                    right++;
                } else {
                    wrong.merge(got.type() + (got.flags().contains(DiffEngine.Flag.NEEDS_CONFIRM) ? " · 짝 확인 필요 표시" : " · 표시 없음"),
                            1, Integer::sum);
                }
            }
        }
        Measure.log("## 2-b. 맨 앞에 조건 1개가 추가되어 번호가 전부 1씩 밀린 경우 (쌍 %d개, 본문은 그대로)", PAIRS);
        Measure.log("원래 조건과 다시 짝지어 '변경 없음'으로 본 조건: %s, 나머지: %s", Measure.pct(right, total), wrong);
    }

    private static Frequency frequency(Condition c) {
        if (c.template() != Template.MEASURE) {
            return Frequency.OTHER;
        }
        return switch (c.period()) {
            case "월" -> Frequency.MONTHLY;
            case "분기" -> Frequency.QUARTERLY;
            case "반기" -> Frequency.SEMIANNUAL;
            default -> Frequency.ANNUAL;
        };
    }

    private static ObligationPayload row(String key, String text, Frequency f) {
        return new ObligationPayload(key, key, false, text, "요약", Type.MEASURE_SURVEY, Category.AIR_QUALITY, "사업자", f,
                null, null, List.of("대기환경보전법 제39조"), 1, text, null);
    }

    private static String fullWidth(String text) {
        StringBuilder b = new StringBuilder();
        for (char ch : text.toCharArray()) {
            b.append(ch >= '0' && ch <= '9' ? (char) (ch - '0' + '０') : ch);
        }
        return b.toString();
    }
}
