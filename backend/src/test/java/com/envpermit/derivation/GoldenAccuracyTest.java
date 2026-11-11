package com.envpermit.derivation;

import static org.assertj.core.api.Assertions.assertThat;

import com.envpermit.derivation.Evaluator.Judgement;
import com.envpermit.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Golden accuracy of the seeded rule set (design §6.3, docs/golden-accuracy.md): run the engine on each golden case
 * and compare with the permits the project really needed. The committed case is synthetic; real cases are
 * confidential and are read only from GOLDEN_DIR (outside the repo), where the reports are written too.
 */
class GoldenAccuracyTest extends IntegrationTest {

    /** Order = how good the engine's answer is for an expected permit; the best matching rule wins. */
    enum State { APPLICABLE, ON_HOLD, NOT_APPLICABLE, ERROR, SCOPE_EXCLUDED, NO_RULE }

    record Out(DerivationRule rule, State state, Judgement j) {
    }

    static class Tally {
        int cases, expected, strict, relaxed, applicable, onHold, tp, fp, unverified, outOfScope;
        final Map<String, Integer> causes = new TreeMap<>();

        String table() {
            StringBuilder s = new StringBuilder("| 지표 | 값 |\n|---|---|\n");
            s.append("| 재현율(엄격) | ").append(ratio(strict, expected)).append(" |\n");
            s.append("| 재현율(완화, 보류 포함) | ").append(ratio(relaxed, expected)).append(" |\n");
            s.append("| 정밀도(정답과 대조된 출력 기준) | ").append(ratio(tp, tp + fp)).append(" |\n");
            s.append("| 정답에 있는 출력 / 전체 해당 출력 | ").append(ratio(tp, applicable)).append(" |\n");
            s.append("| 오탐(해당 없음 정답을 해당으로) | ").append(fp).append(" |\n");
            s.append("| 미확인 출력(정답셋에 없음) | ").append(unverified).append(" |\n");
            s.append("| 보류(ON_HOLD) | ").append(onHold).append(" (보류율 ").append(ratio(onHold, applicable + onHold))
                    .append(") |\n");
            s.append("| OUT_OF_SCOPE 정답 | ").append(outOfScope).append(" |\n");
            if (!causes.isEmpty()) {
                s.append("| 놓친 원인(사람이 단 분류) | ").append(causes).append(" |\n");
            }
            return s.toString();
        }
    }

    @Autowired DerivationRuleRepository rules;
    @Autowired ObjectMapper json;

    @Test
    void syntheticCaseMeasuresTheSeededRules() throws Exception {
        Tally t = evaluate(Path.of(getClass().getResource("/golden/cases").toURI()), Path.of("build/reports/golden"));

        assertThat(t.cases).isEqualTo(1);
        assertThat(List.of(t.strict, t.relaxed, t.expected)).containsExactly(8, 9, 11);
        assertThat(List.of(t.tp, t.fp, t.unverified, t.onHold)).containsExactly(8, 1, 0, 1);
        assertThat(t.causes).containsEntry("MISSING_RULE", 2).containsEntry("INPUT_MISSING", 1);
        assertThat(Files.readString(Path.of("build/reports/golden/result-synthetic-incineration.md")))
                .contains("비산먼지 발생사업 신고").contains("대기 발생량");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "GOLDEN_DIR", matches = ".+")
    void localGoldenCases() throws Exception {
        Path dir = Path.of(System.getenv("GOLDEN_DIR"));
        assertThat(evaluate(dir, dir).cases).as("golden case files (*.json) in GOLDEN_DIR").isPositive();
    }

    /** Every *.json in {@code dir} is a case; writes result-&lt;file&gt;.md per case and result-total.md to {@code out}. */
    Tally evaluate(Path dir, Path out) throws IOException {
        Map<String, Set<String>> aliases = new TreeMap<>();
        json.readTree(getClass().getResource("/golden/aliases.json")).fields().forEachRemaining(e -> {
            Set<String> names = new HashSet<>();
            e.getValue().forEach(a -> names.add(norm(a.asText())));
            aliases.put(e.getKey(), names);
        });
        List<DerivationRule> active = rules.findByStatusOrderByRuleKey("ACTIVE");
        Files.createDirectories(out);
        Tally total = new Tally();
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        for (Path file : files) {
            String stem = file.getFileName().toString().replaceFirst("\\.json$", "");
            Files.writeString(out.resolve("result-" + stem + ".md"),
                    evaluateCase(json.readTree(file.toFile()), active, aliases, total));
        }
        Files.writeString(out.resolve("result-total.md"), "# 골든 평가 합계 (" + total.cases + "개 사례, 규칙 세트 v"
                + rules.rulesetVersion() + ")\n\n" + total.table());
        return total;
    }

    private String evaluateCase(JsonNode c, List<DerivationRule> active, Map<String, Set<String>> aliases, Tally total) {
        JsonNode attrs = c.get("attributes");
        // a golden case goes through the same gate as the form: no unknown keys, no missing required input
        assertThat(Fields.validate(attrs)).as("attributes of " + c.path("name").asText()).isEmpty();
        assertThat(Fields.missingRequired(attrs)).as("required attributes of " + c.path("name").asText()).isEmpty();
        JsonNode snapshot = Fields.withDerived(attrs);
        String facility = attrs.get("facility_type").asText();
        String kind = attrs.get("project_kind").asText();

        List<Out> outs = new ArrayList<>();
        for (DerivationRule r : active) {
            if (!r.appliesTo(facility, kind)) { // Derivation drops these before judging
                outs.add(new Out(r, State.SCOPE_EXCLUDED, null));
                continue;
            }
            Judgement j = Evaluator.judge(r.getCondition(), snapshot, r.getFacilityTypes(), r.getProjectKinds());
            outs.add(new Out(r, State.valueOf(j.determination().name()), j));
        }

        Tally t = new Tally();
        t.cases = 1;
        StringBuilder hits = new StringBuilder();
        StringBuilder misses = new StringBuilder();
        StringBuilder negatives = new StringBuilder();
        StringBuilder outOfScope = new StringBuilder();
        Set<DerivationRule> correct = new HashSet<>();
        Set<DerivationRule> wrong = new HashSet<>();
        for (JsonNode e : c.get("expected")) {
            Set<String> names = new HashSet<>(Set.of(norm(e.get("permit_name").asText())));
            e.path("aliases").forEach(a -> names.add(norm(a.asText())));
            List<Out> matched = outs.stream().filter(o -> matches(o.rule(), names, aliases)).toList();
            Out best = matched.stream().min((a, b) -> a.state().compareTo(b.state())).orElse(null);
            State state = best == null ? State.NO_RULE : best.state();
            String cells = "| " + cell(e.get("permit_name").asText()) + " | " + state + " | " + cell(why(best)) + " | ";
            boolean applicable = !"NOT_APPLICABLE".equals(e.path("expected").asText("APPLICABLE"));
            List<DerivationRule> derived = matched.stream().filter(o -> o.state() == State.APPLICABLE).map(Out::rule)
                    .toList();
            if (applicable) {
                correct.addAll(derived); // a hit on an OUT_OF_SCOPE permit is still a correct output
            } else {
                wrong.addAll(derived);
            }
            if ("OUT_OF_SCOPE".equals(e.path("scope").asText())) {
                t.outOfScope++;
                outOfScope.append(cells).append(cell(e.path("scope_reason").asText())).append(" |\n");
            } else if (!applicable) {
                negatives.append(cells).append(derived.isEmpty() ? "맞음" : "오탐").append(" |\n");
            } else {
                t.expected++;
                if (state == State.APPLICABLE) {
                    t.strict++;
                    t.relaxed++;
                    hits.append("- ").append(e.get("permit_name").asText()).append(" ← ")
                            .append(best.rule().getRuleKey()).append('\n');
                } else {
                    if (state == State.ON_HOLD) {
                        t.relaxed++;
                    }
                    String cause = e.path("cause").asText("");
                    if (!cause.isEmpty()) {
                        t.causes.merge(cause, 1, Integer::sum);
                    }
                    misses.append(cells).append(cell(cause)).append(" | ").append(cell(e.path("note").asText()))
                            .append(" |\n");
                }
            }
        }

        StringBuilder holds = new StringBuilder();
        StringBuilder extras = new StringBuilder();
        for (Out o : outs) {
            if (o.state() == State.ON_HOLD) {
                t.onHold++;
                holds.append("| ").append(o.rule().getPermitName()).append(" | ")
                        .append(String.join(", ", o.j().missingInputs())).append(" |\n");
            } else if (o.state() == State.APPLICABLE) {
                t.applicable++;
                if (wrong.contains(o.rule())) {
                    t.fp++;
                } else if (correct.contains(o.rule())) {
                    t.tp++;
                } else {
                    t.unverified++;
                }
                if (!correct.contains(o.rule()) || wrong.contains(o.rule())) {
                    extras.append("| ").append(o.rule().getPermitName()).append(" | ")
                            .append(wrong.contains(o.rule()) ? "오탐" : "미확인(정답셋에 없음)").append(" | ")
                            .append(cell(o.j().reason())).append(" |\n");
                }
            }
        }

        add(total, t);
        long inScope = outs.stream().filter(o -> o.state() != State.SCOPE_EXCLUDED).count();
        StringBuilder s = new StringBuilder("# 골든 평가 — " + c.path("name").asText() + "\n\n");
        s.append("- 정답 기준일: ").append(c.path("as_of").asText("-")).append('\n');
        s.append("- 규칙 세트 v").append(rules.rulesetVersion()).append(": ACTIVE ").append(outs.size())
                .append("개 중 이 사례(").append(facility).append(", ").append(kind).append(")에 적용 ").append(inScope)
                .append("개\n");
        List<String> absent = Fields.ALL.keySet().stream()
                .filter(k -> !k.equals(Fields.DERIVED) && Fields.absent(attrs, k)).map(k -> Fields.get(k).label()).toList();
        s.append("- 비어 있는 속성: ").append(absent.isEmpty() ? "없음" : String.join(", ", absent)).append("\n\n");
        s.append("## 지표\n\n").append(t.table());
        section(s, "놓친 정답", "| 정답 인허가 | 엔진 | 엔진 사유 | 원인 | 메모 |\n|---|---|---|---|---|\n", misses);
        section(s, "정답과 다른 출력", "| 엔진 출력 | 구분 | 엔진 사유 |\n|---|---|---|\n", extras);
        section(s, "보류(ON_HOLD)", "| 규칙 | 빠진 입력 |\n|---|---|\n", holds);
        section(s, "맞춘 정답", "", hits);
        section(s, "해당 없음 정답", "| 정답 인허가 | 엔진 | 엔진 사유 | 결과 |\n|---|---|---|---|\n", negatives);
        section(s, "OUT_OF_SCOPE", "| 정답 인허가 | 엔진 | 엔진 사유 | 제외 사유 |\n|---|---|---|---|\n", outOfScope);
        if (c.has("notes")) {
            s.append("\n## 메모\n\n");
            c.get("notes").forEach(n -> s.append("- ").append(n.asText()).append('\n'));
        }
        return s.toString();
    }

    private static boolean matches(DerivationRule r, Set<String> expectedNames, Map<String, Set<String>> aliases) {
        return expectedNames.contains(norm(r.getPermitName()))
                || aliases.getOrDefault(r.getPermitName(), Set.of()).stream().anyMatch(expectedNames::contains);
    }

    private static String why(Out o) {
        if (o == null) {
            return "규칙 없음";
        }
        if (o.state() == State.SCOPE_EXCLUDED) {
            return "규칙 적용 범위 밖 (시설 " + o.rule().getFacilityTypes() + ", 사업구분 " + o.rule().getProjectKinds() + ")";
        }
        return o.rule().getRuleKey() + ": " + o.j().reason();
    }

    /** Design §6.3: NFC, then drop whitespace, brackets and middle dots. */
    static String norm(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC).replaceAll("[\\s()\\[\\]·ㆍ・]", "");
    }

    private static String cell(String s) {
        return s == null ? "" : s.replace('|', '/').replace('\n', ' ');
    }

    private static void section(StringBuilder s, String title, String header, StringBuilder rows) {
        s.append("\n## ").append(title).append("\n\n").append(rows.isEmpty() ? "없음\n" : header + rows);
    }

    private static String ratio(int n, int d) {
        return d == 0 ? "- (0/0)" : String.format("%.1f%% (%d/%d)", 100.0 * n / d, n, d);
    }

    private static void add(Tally total, Tally t) {
        total.cases += t.cases;
        total.expected += t.expected;
        total.strict += t.strict;
        total.relaxed += t.relaxed;
        total.applicable += t.applicable;
        total.onHold += t.onHold;
        total.tp += t.tp;
        total.fp += t.fp;
        total.unverified += t.unverified;
        total.outOfScope += t.outOfScope;
        t.causes.forEach((k, v) -> total.causes.merge(k, v, Integer::sum));
    }
}
