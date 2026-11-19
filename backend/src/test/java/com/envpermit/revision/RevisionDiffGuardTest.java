package com.envpermit.revision;

import static org.assertj.core.api.Assertions.assertThat;

import com.envpermit.obligation.ObligationPayload;
import com.envpermit.revision.DiffEngine.ChangeType;
import com.envpermit.revision.DiffEngine.Snapshot;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class RevisionDiffGuardTest {

    @ParameterizedTest(name = "이전 {0} → 새 {1}: 막음 {2}")
    @CsvSource({
            "51, 0, true",
            "3, 0, true",
            "10, 1, true",
            "10, 2, false",
            "4, 1, false",
            "10, 9, false",
            "0, 0, false",
    })
    void guard_threshold(int old, int extracted, boolean blocked) {
        assertThat(RevisionDiff.tooFew(old, extracted) != null).isEqualTo(blocked);
    }

    @Test
    void message_names_both_counts() {
        assertThat(RevisionDiff.tooFew(51, 0)).startsWith("새 문서에서 찾은 조건이 너무 적습니다(이전 51건 → 0건).");
    }

    @Test
    void ordinary_removal_of_one_row_is_still_proposed() {
        List<Snapshot> old = IntStream.rangeClosed(1, 10)
                .mapToObj(i -> DiffEngineTest.old(i, "가-" + i, "가상 조건 " + i + "번을 지킬 것")).toList();
        List<ObligationPayload> next = IntStream.rangeClosed(1, 9)
                .mapToObj(i -> DiffEngineTest.row("가-" + i, "가상 조건 " + i + "번을 지킬 것")).toList();

        assertThat(RevisionDiff.tooFew(old.size(), next.size())).isNull();
        List<DiffEngine.Change> removed = DiffEngine.diff(next, old, DiffEngineTest.CFG).stream()
                .filter(c -> c.type() == ChangeType.REMOVED).toList();
        assertThat(removed).hasSize(1);
        assertThat(removed.get(0).old().id()).isEqualTo(10);
    }
}
