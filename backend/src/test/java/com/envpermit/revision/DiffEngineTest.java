package com.envpermit.revision;

import static com.envpermit.revision.DiffEngine.ChangeType.ADDED;
import static com.envpermit.revision.DiffEngine.ChangeType.CHANGED;
import static com.envpermit.revision.DiffEngine.ChangeType.MINOR;
import static com.envpermit.revision.DiffEngine.ChangeType.REMOVED;
import static com.envpermit.revision.DiffEngine.ChangeType.UNCHANGED;
import static com.envpermit.revision.DiffEngine.Flag.DUPLICATE_KEY;
import static com.envpermit.revision.DiffEngine.Flag.KEY_SAME_TEXT_DIFF;
import static com.envpermit.revision.DiffEngine.Flag.NEEDS_CONFIRM;
import static org.assertj.core.api.Assertions.assertThat;

import com.envpermit.obligation.Frequency;
import com.envpermit.obligation.ObligationPayload;
import com.envpermit.obligation.ObligationPayload.Category;
import com.envpermit.obligation.ObligationPayload.Type;
import com.envpermit.revision.DiffEngine.Change;
import com.envpermit.revision.DiffEngine.Method;
import com.envpermit.revision.DiffEngine.Snapshot;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DiffEngineTest {

    static final DiffEngine.MatchConfig CFG = new DiffEngine.MatchConfig(0.6, 0.5);

    static ObligationPayload row(String key, String text) {
        return row(key, text, Frequency.QUARTERLY, List.of("대기환경보전법 제39조"));
    }

    static ObligationPayload row(String key, String text, Frequency f, List<String> legal) {
        return new ObligationPayload(key, key, key.startsWith("p"), text, "요약", Type.MEASURE_SURVEY,
                Category.AIR_QUALITY, "사업자", f, null, null, legal, 1, text, null);
    }

    static Snapshot old(long id, String key, String text) {
        return new Snapshot(id, 3, false, row(key, text));
    }

    static Change only(List<ObligationPayload> next, List<Snapshot> prev) {
        List<Change> out = DiffEngine.diff(next, prev, CFG);
        assertThat(out).hasSize(1);
        return out.get(0);
    }

    // --- judgement ---------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} → {1} = {2}")
    @CsvSource(delimiter = '|', textBlock = """
            CO 농도를 50ppm 이하로 유지할 것          | CO 농도를 50 ppm  이하로 유지할 것        | UNCHANGED
            소음을 분기 1회 측정하여야 한다           | 소음을 분기 1회 측정할 것                 | MINOR
            CO 농도를 50ppm 이하로 유지할 것          | CO 농도를 30ppm 이하로 유지할 것          | CHANGED
            CO 농도를 50ppm 이하로 유지할 것          | CO 농도를 50ppm 미만으로 유지할 것        | CHANGED
            배출가스 온도는 850℃ 이상 유지할 것      | 배출가스 온도는 ８５０℃ 이상 유지할 것   | MINOR
            연간 처리량 1,200톤 이하로 운영할 것      | 연간 처리량 1200톤 이하로 운영하여야 함   | MINOR
            """)
    void judgement(String before, String after, DiffEngine.ChangeType expected) {
        Change c = only(List.of(row("대기질-4", after)), List.of(old(1, "대기질-4", before)));

        assertThat(c.type()).isEqualTo(expected);
        assertThat(c.method()).isEqualTo(Method.KEY);
        assertThat(c.flags()).isEmpty();
    }

    @Test
    void numeric_change_is_reported_with_its_comparison_word() {
        Change c = only(List.of(row("대기질-4", "CO 농도를 30ppm 이하로 유지할 것")),
                List.of(old(1, "대기질-4", "CO 농도를 50ppm 이하로 유지할 것")));

        assertThat(c.numericChanges()).containsExactly(new DiffEngine.NumericChange("50ppm이하", "30ppm이하"));
        assertThat(c.changedFields()).containsExactly("requirement_text");
    }

    @Test
    void frequency_change_alone_is_changed() {
        Change c = only(List.of(row("대기질-1", "다이옥신을 측정할 것", Frequency.MONTHLY, List.of("대기환경보전법 제39조"))),
                List.of(old(1, "대기질-1", "다이옥신을 측정할 것")));

        assertThat(c.type()).isEqualTo(CHANGED);
        assertThat(c.changedFields()).containsExactly("frequency");
    }

    @Test
    void legal_basis_order_does_not_matter() {
        Snapshot before = new Snapshot(1, 0, false, row("대기질-1", "측정할 것", Frequency.QUARTERLY, List.of("A법", "B법")));

        Change c = only(List.of(row("대기질-1", "측정할 것", Frequency.QUARTERLY, List.of("B법", "A법"))), List.of(before));

        assertThat(c.type()).isEqualTo(UNCHANGED);
    }

    @Test
    void the_old_side_is_carried_with_its_version_and_closed_flag() {
        Snapshot closed = new Snapshot(7, 4, true, row("대기질-1", "측정할 것"));

        Change c = only(List.of(row("대기질-1", "측정할 것")), List.of(closed));

        assertThat(c.old()).isEqualTo(closed);
        assertThat(c.type()).isEqualTo(UNCHANGED);
    }

    // --- number tokens -----------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            CO 50ppm 이하                    | 50ppm이하
            먼지 ２０㎎/S㎥ 미만             | 20㎎/S㎥미만
            1,200톤 초과                     | 1200톤초과
            pH 5.8 이상                      | 5.8이상
            소음 65 dB 이하                  | 65dB이하
            850 ℃ 이상                       | 850℃이상
            3개월마다 1회                    | 3개월,1회
            12.5 % 이하, 5 mg/L, 10 m        | 12.5%이하,5mg/L,10m
            """)
    void number_tokens(String text, String tokens) {
        assertThat(String.join(",", DiffEngine.numberTokens(text))).isEqualTo(tokens);
    }

    // --- matching ----------------------------------------------------------------------------------------

    @Test
    void renumbered_condition_is_paired_by_similarity_and_the_new_number_is_added() {
        Snapshot dioxin = old(1, "대기질-3-2", "소각시설 굴뚝에서 다이옥신을 분기 1회 측정할 것");
        ObligationPayload nox = row("대기질-3-2", "질소산화물을 연속 자동측정기기로 측정할 것");
        ObligationPayload dioxinV2 = row("대기질-3-3", "소각시설 굴뚝에서 다이옥신을 월 1회 측정할 것");

        List<Change> out = DiffEngine.diff(List.of(nox, dioxinV2), List.of(dioxin), CFG);

        assertThat(out).extracting(Change::type).containsExactly(ADDED, MINOR);
        assertThat(out.get(0).next()).isEqualTo(nox);
        Change pair = out.get(1);
        assertThat(pair.old().id()).isEqualTo(1);
        assertThat(pair.method()).isEqualTo(Method.SIMILARITY);
        assertThat(pair.flags()).containsExactly(NEEDS_CONFIRM);
        assertThat(pair.similarity()).isBetween(0.5, 1.0);
    }

    @Test
    void same_key_with_different_text_goes_to_the_pool_and_is_flagged_if_paired_again() {
        Snapshot before = old(1, "수질-2", "방류수 수질을 월 1회 측정하고 결과를 보고할 것");

        List<Change> out = DiffEngine.diff(List.of(row("수질-2", "방류수 수질을 매월 측정하고 그 결과를 시장에게 보고할 것")),
                List.of(before), new DiffEngine.MatchConfig(0.8, 0.5)); // sim ≈ 0.62
        Change c = out.get(0);

        assertThat(out).hasSize(1);

        assertThat(c.method()).isEqualTo(Method.SIMILARITY);
        assertThat(c.flags()).containsExactly(NEEDS_CONFIRM, KEY_SAME_TEXT_DIFF);
    }

    @Test
    void same_key_with_unrelated_text_is_removed_plus_added() {
        List<Change> out = DiffEngine.diff(List.of(row("수질-2", "부지경계에서 악취를 반기 1회 측정할 것")),
                List.of(old(1, "수질-2", "방류수 수질을 월 1회 측정할 것")), CFG);

        assertThat(out).extracting(Change::type).containsExactly(ADDED, REMOVED);
        assertThat(out.get(1).old().id()).isEqualTo(1);
        assertThat(out.get(1).next()).isNull();
    }

    @Test
    void duplicate_new_keys_are_flagged_and_matched_in_the_pool() {
        Snapshot before = old(1, "악취-5", "부지경계에서 악취를 반기 1회 측정할 것");

        List<Change> out = DiffEngine.diff(List.of(row("악취-5", "부지경계에서 악취를 반기 1회 측정할 것"),
                row("악취-5", "악취 민원 발생 시 즉시 조치할 것")), List.of(before), CFG);

        assertThat(out).extracting(Change::type).containsExactly(UNCHANGED, ADDED);
        assertThat(out.get(0).flags()).containsExactly(NEEDS_CONFIRM, DUPLICATE_KEY);
        assertThat(out.get(1).flags()).containsExactly(DUPLICATE_KEY);
    }

    @Test
    void synthetic_keys_never_key_match_but_pair_by_similarity() {
        Change c = only(List.of(row("p2-1", "TMS 기록을 5년간 보존할 것")), List.of(old(1, "p2-1", "TMS 기록을 5년간 보존할 것")));

        assertThat(c.type()).isEqualTo(UNCHANGED);
        assertThat(c.method()).isEqualTo(Method.SIMILARITY);
        assertThat(c.flags()).containsExactly(NEEDS_CONFIRM);
    }

    @Test
    void ties_break_by_old_key_then_new_key() {
        List<Snapshot> prev = List.of(old(2, "b", "같은 문장"), old(1, "a", "같은 문장"));
        List<ObligationPayload> next = List.of(row("y", "같은 문장"), row("x", "같은 문장"));

        List<Change> out = DiffEngine.diff(next, prev, CFG);

        assertThat(out).extracting(c -> c.old().citationKey() + "→" + c.next().citationKey())
                .containsExactly("b→y", "a→x");
        assertThat(DiffEngine.diff(next, prev, CFG)).isEqualTo(out);
    }

    @Test
    void both_sides_empty_and_one_side_empty() {
        assertThat(DiffEngine.diff(List.of(), List.of(), CFG)).isEmpty();
        assertThat(DiffEngine.diff(List.of(row("a", "측정")), List.of(), CFG)).extracting(Change::type)
                .containsExactly(ADDED);
        assertThat(DiffEngine.diff(List.of(), List.of(old(1, "a", "측정")), CFG)).extracting(Change::type)
                .containsExactly(REMOVED);
    }

    @Test
    void flagged_unchanged_is_visible_and_plain_unchanged_is_hidden() {
        Change keyed = only(List.of(row("a-1", "측정할 것")), List.of(old(1, "a-1", "측정할 것")));
        Change renumbered = only(List.of(row("a-2", "측정할 것")), List.of(old(1, "a-1", "측정할 것")));

        assertThat(keyed.hidden()).isTrue();
        assertThat(renumbered.type()).isEqualTo(UNCHANGED);
        assertThat(renumbered.hidden()).isFalse();
    }

    @Test
    void similarity_is_whitespace_free_bigram_dice() {
        assertThat(DiffEngine.similarity("가 나다", "가나다")).isEqualTo(1.0);
        assertThat(DiffEngine.similarity("가나다", "가나라")).isEqualTo(0.5);
        assertThat(DiffEngine.similarity("가", "나")).isEqualTo(0.0);
    }
}
