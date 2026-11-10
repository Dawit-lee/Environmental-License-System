package com.envpermit.derivation;

import com.envpermit.derivation.Fields.Field;
import com.envpermit.derivation.Fields.Type;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure 3-valued evaluator for the rule condition DSL (design §3.2–3.4). Same condition + same attributes →
 * same judgement and the same sentences. A malformed condition throws IllegalArgumentException.
 */
public final class Evaluator {

    public enum Truth { TRUE, FALSE, UNKNOWN }

    public enum Determination { APPLICABLE, ON_HOLD, NOT_APPLICABLE, ERROR }

    public record Judgement(Determination determination, String reason, List<String> missingFields,
                            List<String> missingInputs) {
    }

    /** A comparison that decided a result, with its own truth value. */
    private record Atom(JsonNode node, Truth truth) {
    }

    private record Result(Truth truth, List<Atom> why) {
    }

    private static final Map<String, String> SCOPE = Map.of("INCINERATION", "소각시설", "NEW", "신설", "EXPANSION", "증설");

    private Evaluator() {
    }

    public static Truth evaluate(JsonNode condition, JsonNode attrs) {
        return eval(condition, attrs).truth();
    }

    public static Judgement judge(JsonNode condition, JsonNode attrs, List<String> facilityTypes,
                                  List<String> projectKinds) {
        Result r;
        try {
            r = eval(condition, attrs);
        } catch (RuntimeException e) {
            return new Judgement(Determination.ERROR, "규칙 오류: " + e.getMessage(), List.of(), List.of());
        }
        return switch (r.truth()) {
            case TRUE -> new Judgement(Determination.APPLICABLE,
                    r.why().isEmpty() ? scopeSentence(facilityTypes, projectKinds) : sentences(r.why(), attrs),
                    List.of(), List.of());
            case FALSE -> new Judgement(Determination.NOT_APPLICABLE, sentences(r.why(), attrs), List.of(), List.of());
            case UNKNOWN -> {
                Set<String> keys = new LinkedHashSet<>();
                r.why().forEach(a -> keys.add(a.node().get("field").asText()));
                List<String> labels = keys.stream().map(k -> Fields.get(k).label()).toList();
                yield new Judgement(Determination.ON_HOLD, String.join(", ", labels) + " 필요", List.copyOf(keys),
                        labels);
            }
        };
    }

    // --- evaluation ---------------------------------------------------------------------------------------------

    private static Result eval(JsonNode n, JsonNode attrs) {
        if (n == null || n.isNull()) {
            return new Result(Truth.TRUE, List.of());
        }
        if (n.has("all")) {
            return combine(children(n, "all", attrs), Truth.FALSE, Truth.TRUE);
        }
        if (n.has("any")) {
            return combine(children(n, "any", attrs), Truth.TRUE, Truth.FALSE);
        }
        if (n.has("not")) {
            Result c = eval(n.get("not"), attrs);
            Truth t = switch (c.truth()) {
                case TRUE -> Truth.FALSE;
                case FALSE -> Truth.TRUE;
                case UNKNOWN -> Truth.UNKNOWN;
            };
            return new Result(t, c.why());
        }
        if (n.has("field") && n.has("op")) {
            Truth t = compare(n, attrs);
            return new Result(t, List.of(new Atom(n, t)));
        }
        throw new IllegalArgumentException("모르는 노드: " + n);
    }

    private static List<Result> children(JsonNode n, String key, JsonNode attrs) {
        JsonNode list = n.get(key);
        if (!list.isArray() || list.isEmpty()) {
            throw new IllegalArgumentException(key + "는 비어 있지 않은 배열이어야 합니다");
        }
        List<Result> out = new ArrayList<>();
        list.forEach(c -> out.add(eval(c, attrs)));
        return out;
    }

    /**
     * all: decisive = FALSE, otherwise TRUE. any: decisive = TRUE, otherwise FALSE.
     * Why: the first decisive child; else all UNKNOWN children; else every child.
     */
    private static Result combine(List<Result> cs, Truth decisive, Truth otherwise) {
        for (Result c : cs) {
            if (c.truth() == decisive) {
                return new Result(decisive, c.why());
            }
        }
        List<Result> unknown = cs.stream().filter(c -> c.truth() == Truth.UNKNOWN).toList();
        List<Result> used = unknown.isEmpty() ? cs : unknown;
        return new Result(unknown.isEmpty() ? otherwise : Truth.UNKNOWN,
                used.stream().flatMap(c -> c.why().stream()).toList());
    }

    private static Truth compare(JsonNode n, JsonNode attrs) {
        Field f = Fields.get(n.get("field").asText());
        String op = n.get("op").asText();
        JsonNode want = n.get("value");
        JsonNode have = attrs.get(f.key());
        if (have == null || have.isNull()) {
            return Truth.UNKNOWN;
        }
        boolean r = switch (op) {
            case ">=", ">", "<", "<=" -> {
                requireType(f, Type.NUMBER, op);
                int c = have.decimalValue().compareTo(number(want));
                yield switch (op) {
                    case ">=" -> c >= 0;
                    case ">" -> c > 0;
                    case "<" -> c < 0;
                    default -> c <= 0;
                };
            }
            case "==", "!=" -> equal(f, have, want) == op.equals("==");
            case "in" -> {
                requireType(f, Type.ENUM, op);
                yield texts(want).contains(have.asText());
            }
            case "contains_any" -> {
                requireType(f, Type.ENUM_LIST, op);
                Set<String> w = texts(want);
                yield texts(have).stream().anyMatch(w::contains);
            }
            case "is_true" -> {
                requireType(f, Type.BOOL, op);
                yield have.asBoolean();
            }
            default -> throw new IllegalArgumentException("모르는 연산자: " + op);
        };
        return r ? Truth.TRUE : Truth.FALSE;
    }

    private static boolean equal(Field f, JsonNode have, JsonNode want) {
        if (f.type() == Type.NUMBER) {
            return have.decimalValue().compareTo(number(want)) == 0;
        }
        if (f.type() != Type.ENUM && f.type() != Type.TEXT) {
            throw new IllegalArgumentException(f.key() + "에는 ==를 쓸 수 없습니다");
        }
        return have.asText().equals(want == null ? null : want.asText());
    }

    private static void requireType(Field f, Type t, String op) {
        if (f.type() != t) {
            throw new IllegalArgumentException(f.key() + "에는 " + op + "를 쓸 수 없습니다");
        }
    }

    private static BigDecimal number(JsonNode v) {
        if (v == null || !v.isNumber()) {
            throw new IllegalArgumentException("숫자 기준값이 필요합니다: " + v);
        }
        return v.decimalValue();
    }

    private static Set<String> texts(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            throw new IllegalArgumentException("목록 값이 필요합니다: " + arr);
        }
        Set<String> s = new LinkedHashSet<>();
        arr.forEach(x -> s.add(x.asText()));
        return s;
    }

    // --- sentences ----------------------------------------------------------------------------------------------

    private static String sentences(List<Atom> atoms, JsonNode attrs) {
        return atoms.stream().map(a -> sentence(a, attrs)).distinct().collect(Collectors.joining(" · "));
    }

    /** `{화면명} {실제값}{단위} {연산자} {기준값}{단위}`; a FALSE comparison shows the negated operator. */
    private static String sentence(Atom a, JsonNode attrs) {
        Field f = Fields.get(a.node().get("field").asText());
        String op = a.node().get("op").asText();
        JsonNode want = a.node().get("value");
        JsonNode have = attrs.get(f.key());
        boolean t = a.truth() == Truth.TRUE;
        String label = f.label();
        return switch (op) {
            case ">=", ">", "<", "<=" -> label + " " + num(have) + f.unit() + " " + symbol(op, t) + " "
                    + num(want) + f.unit();
            case "==", "!=" -> (op.equals("==") == t)
                    ? label + " = " + show(f, want)
                    : label + " " + show(f, have) + " ≠ " + show(f, want);
            case "in" -> t ? label + " = " + show(f, have)
                    : label + " " + show(f, have) + " ∉ " + list(f, texts(want));
            case "contains_any" -> {
                Set<String> w = texts(want);
                yield t ? label + " " + list(f, texts(have).stream().filter(w::contains).toList()) + " 포함"
                        : label + "에 " + list(f, w) + " 없음";
            }
            case "is_true" -> label + (t ? " 있음" : " 없음");
            default -> throw new IllegalArgumentException(op);
        };
    }

    private static String symbol(String op, boolean truth) {
        String shown = truth ? op : switch (op) {
            case ">=" -> "<";
            case ">" -> "<=";
            case "<" -> ">=";
            default -> ">";
        };
        return switch (shown) {
            case ">=" -> "≥";
            case "<=" -> "≤";
            default -> shown;
        };
    }

    private static String show(Field f, JsonNode v) {
        return f.type() == Type.NUMBER ? num(v) + f.unit() : f.display(v.asText());
    }

    private static String list(Field f, java.util.Collection<String> codes) {
        return codes.stream().map(f::display).collect(Collectors.joining("/"));
    }

    private static String num(JsonNode v) {
        return new DecimalFormat("#,##0.####").format(v.decimalValue());
    }

    private static String scopeSentence(List<String> facilityTypes, List<String> projectKinds) {
        String scope = String.join(" ", facilityTypes.stream().map(x -> SCOPE.getOrDefault(x, x)).collect(
                Collectors.joining("·")), projectKinds.stream().map(x -> SCOPE.getOrDefault(x, x)).collect(
                Collectors.joining("·"))).trim();
        return scope.isEmpty() ? "공통 해당" : scope + " 시 공통 해당";
    }
}
