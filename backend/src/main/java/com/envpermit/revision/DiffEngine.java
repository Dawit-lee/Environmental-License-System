package com.envpermit.revision;

import com.envpermit.obligation.ObligationPayload;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Version diff (④ design §3.4–3.6): pairs new extracted rows with the previous version's live obligations and
 * judges each pair. Pure and deterministic: no DB, no LLM. Table: DiffEngineTest.
 */
public final class DiffEngine {

    public enum ChangeType { ADDED, REMOVED, CHANGED, MINOR, UNCHANGED }

    public enum Method { KEY, SIMILARITY, MANUAL }

    public enum Flag { NEEDS_CONFIRM, KEY_SAME_TEXT_DIFF, DUPLICATE_KEY }

    /** T_pair checks a same-key pair, T_suggest pairs leftovers by similarity alone. */
    public record MatchConfig(double tPair, double tSuggest) {
    }

    /** A live obligation of the previous version line at diff time. */
    public record Snapshot(long id, long version, boolean closed, ObligationPayload payload) {
        String citationKey() {
            return payload.citationKey();
        }
    }

    public record NumericChange(String before, String after) {
    }

    /** old is null for ADDED, next is null for REMOVED; method/similarity are null for both. */
    public record Change(ChangeType type, Snapshot old, ObligationPayload next, Method method, Double similarity,
                         List<Flag> flags, List<String> changedFields, List<NumericChange> numericChanges) {
        /** Only a flag-free UNCHANGED goes behind "변경 없음 N건". */
        public boolean hidden() {
            return type == ChangeType.UNCHANGED && flags.isEmpty();
        }
    }

    // longest first so "mg/L" wins over "m"; shortcut: extend from the gold-set documents (design §9)
    static final List<String> UNITS = List.of("㎎/S㎥", "mg/Sm3", "mg/S㎥", "㎎/㎥", "mg/㎥", "mg/m3", "㎎/L", "mg/L",
            "ng-TEQ/S㎥", "dB(A)", "개월", "ppm", "ppb", "시간", "dB", "㎥", "%", "℃", "회", "일", "주", "년", "톤", 
            "㎞", "km", "m");
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*("
            + UNITS.stream().map(Pattern::quote).collect(Collectors.joining("|")) + ")?\\s*(이하|이상|초과|미만)?");
    private static final Pattern THOUSANDS = Pattern.compile("(?<=\\d),(?=\\d{3})");
    private static final Pattern SPACE = Pattern.compile("[\\s\\p{Z}]+");

    private DiffEngine() {
    }

    public static List<Change> diff(List<ObligationPayload> newRows, List<Snapshot> oldRows, MatchConfig cfg) {
        Map<Integer, Pair> byNew = new HashMap<>();
        Set<Snapshot> usedOld = Collections.newSetFromMap(new IdentityHashMap<>()); // equal snapshots are still different rows
        Map<String, Integer> newKeyCount = count(newRows.stream().filter(r -> !r.citationSynthetic())
                .map(ObligationPayload::citationKey).toList());
        Map<String, Integer> oldKeyCount = count(oldRows.stream().filter(o -> !o.payload().citationSynthetic())
                .map(Snapshot::citationKey).toList());

        // 1. same unique key + text similar enough
        Map<Integer, Snapshot> keyCandidate = new HashMap<>();
        for (int i = 0; i < newRows.size(); i++) {
            ObligationPayload n = newRows.get(i);
            if (n.citationSynthetic() || newKeyCount.get(nz(n.citationKey())) != 1) {
                continue;
            }
            for (Snapshot o : oldRows) {
                if (!o.payload().citationSynthetic() && oldKeyCount.get(nz(o.citationKey())) == 1
                        && Objects.equals(o.citationKey(), n.citationKey())) {
                    double sim = similarity(o.payload().requirementText(), n.requirementText());
                    if (sim >= cfg.tPair()) {
                        byNew.put(i, new Pair(o, Method.KEY, sim, EnumSet.noneOf(Flag.class)));
                        usedOld.add(o);
                    } else {
                        keyCandidate.put(i, o);
                    }
                }
            }
        }

        // 2. pool: best similarity first, ties by old key then new key (then input order)
        record Cand(Snapshot o, int oi, int ni, double sim) {
        }
        List<Cand> pool = new ArrayList<>();
        for (int oi = 0; oi < oldRows.size(); oi++) {
            Snapshot o = oldRows.get(oi);
            if (usedOld.contains(o)) {
                continue;
            }
            for (int ni = 0; ni < newRows.size(); ni++) {
                if (!byNew.containsKey(ni)) {
                    double sim = similarity(o.payload().requirementText(), newRows.get(ni).requirementText());
                    if (sim >= cfg.tSuggest()) {
                        pool.add(new Cand(o, oi, ni, sim));
                    }
                }
            }
        }
        pool.sort(Comparator.comparingDouble(Cand::sim).reversed()
                .thenComparing(c -> nz(c.o().citationKey()))
                .thenComparing(c -> nz(newRows.get(c.ni()).citationKey()))
                .thenComparingInt(Cand::oi).thenComparingInt(Cand::ni));
        for (Cand c : pool) {
            if (usedOld.contains(c.o()) || byNew.containsKey(c.ni())) {
                continue;
            }
            EnumSet<Flag> flags = EnumSet.of(Flag.NEEDS_CONFIRM);
            if (keyCandidate.get(c.ni()) == c.o()) {
                flags.add(Flag.KEY_SAME_TEXT_DIFF);
            }
            byNew.put(c.ni(), new Pair(c.o(), Method.SIMILARITY, c.sim(), flags));
            usedOld.add(c.o());
        }

        // 3. judge pairs; unpaired new = ADDED, unpaired old = REMOVED
        List<Change> out = new ArrayList<>();
        for (int i = 0; i < newRows.size(); i++) {
            ObligationPayload n = newRows.get(i);
            boolean dup = !n.citationSynthetic() && newKeyCount.get(nz(n.citationKey())) > 1;
            Pair p = byNew.get(i);
            if (p == null) {
                out.add(new Change(ChangeType.ADDED, null, n, null, null,
                        dup ? List.of(Flag.DUPLICATE_KEY) : List.of(), List.of(), List.of()));
                continue;
            }
            if (dup) {
                p.flags().add(Flag.DUPLICATE_KEY);
            }
            out.add(judge(p.old(), n, p.method(), p.sim(), List.copyOf(p.flags())));
        }
        for (Snapshot o : oldRows) {
            if (!usedOld.contains(o)) {
                out.add(new Change(ChangeType.REMOVED, o, null, null, null, List.of(), List.of(), List.of()));
            }
        }
        return out;
    }

    private record Pair(Snapshot old, Method method, double sim, EnumSet<Flag> flags) {
    }

    /** Judgement of one pair (design §3.5); also what a manual pair or a rediff will use. */
    public static Change judge(Snapshot old, ObligationPayload n, Method method, double sim, List<Flag> flags) {
        ObligationPayload o = old.payload();
        List<String> changed = new ArrayList<>();
        boolean textSame = norm(o.requirementText()).equals(norm(n.requirementText()));
        if (!textSame) {
            changed.add("requirement_text");
        }
        diffField(changed, "summary", o.summary(), n.summary());
        boolean structural = diffField(changed, "type", o.type(), n.type())
                | diffField(changed, "category", o.category(), n.category())
                | diffField(changed, "responsible_party", o.responsibleParty(), n.responsibleParty())
                | diffField(changed, "frequency", o.frequency(), n.frequency());
        diffField(changed, "frequency_text", o.frequencyText(), n.frequencyText());
        diffField(changed, "period_text", o.periodText(), n.periodText());
        structural |= diffField(changed, "legal_basis", set(o.legalBasis()), set(n.legalBasis()));

        List<NumericChange> numeric = numericChanges(numberTokens(o.requirementText()), numberTokens(n.requirementText()));
        ChangeType type = textSame && !structural ? ChangeType.UNCHANGED
                : structural || !numeric.isEmpty() ? ChangeType.CHANGED : ChangeType.MINOR;
        return new Change(type, old, n, method, sim, flags, changed, numeric);
    }

    /** Dice coefficient of the whitespace-free character bigram multisets. */
    public static double similarity(String a, String b) {
        Map<String, Integer> x = bigrams(norm(a));
        Map<String, Integer> y = bigrams(norm(b));
        int total = x.values().stream().mapToInt(Integer::intValue).sum() + y.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) {
            return norm(a).equals(norm(b)) ? 1.0 : 0.0;
        }
        int common = 0;
        for (Map.Entry<String, Integer> e : x.entrySet()) {
            common += Math.min(e.getValue(), y.getOrDefault(e.getKey(), 0));
        }
        return 2.0 * common / total;
    }

    /** "CO 50 ppm 이하" → ["50ppm이하"]: number, optional unit and comparison word, whitespace dropped. */
    static List<String> numberTokens(String text) {
        String s = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFC);
        StringBuilder half = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            half.append(c >= '！' && c <= '～' ? (char) (c - 0xFEE0) : c == '　' ? ' ' : c);
        }
        Matcher m = NUMBER.matcher(THOUSANDS.matcher(half).replaceAll(""));
        List<String> out = new ArrayList<>();
        while (m.find()) {
            out.add(m.group(1) + nz(m.group(2)) + nz(m.group(3)));
        }
        return out;
    }

    /** Same function as ① quote_check: NFC, all whitespace removed. */
    static String norm(String s) {
        return SPACE.matcher(Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFC)).replaceAll("");
    }

    /** Multiset difference, paired up in text order: [{removed token, added token}, ...]. */
    private static List<NumericChange> numericChanges(List<String> before, List<String> after) {
        List<String> gone = new ArrayList<>(before);
        List<String> come = new ArrayList<>();
        for (String t : after) {
            if (!gone.remove(t)) {
                come.add(t);
            }
        }
        List<NumericChange> out = new ArrayList<>();
        for (int i = 0; i < Math.max(gone.size(), come.size()); i++) {
            out.add(new NumericChange(i < gone.size() ? gone.get(i) : null, i < come.size() ? come.get(i) : null));
        }
        return out;
    }

    private static boolean diffField(List<String> changed, String name, Object a, Object b) {
        boolean differs = !Objects.equals(a, b);
        if (differs) {
            changed.add(name);
        }
        return differs;
    }

    private static Set<String> set(List<String> l) {
        return l == null ? Set.of() : Set.copyOf(l);
    }

    private static Map<String, Integer> bigrams(String s) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i + 2 <= s.length(); i++) {
            m.merge(s.substring(i, i + 2), 1, Integer::sum);
        }
        return m;
    }

    private static Map<String, Integer> count(List<String> keys) {
        Map<String, Integer> m = new HashMap<>();
        keys.forEach(k -> m.merge(nz(k), 1, Integer::sum));
        return m;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
