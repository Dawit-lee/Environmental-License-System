package com.envpermit.law;

import static com.envpermit.law.ImpactRule.Level.DIRECT;
import static com.envpermit.law.ImpactRule.Level.REFERENCE;
import static org.assertj.core.api.Assertions.assertThat;

import com.envpermit.law.ImpactRule.Amended;
import com.envpermit.law.ImpactRule.Level;
import com.envpermit.law.ImpactRule.Link;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Design §6, one row per rule. Amended law 2 (시행규칙, parent 1); 제10조 changed. */
class ImpactRuleTest {

    static final long ACT = 1, RULE = 2, OTHER = 3;

    static Stream<Arguments> table() {
        return Stream.of(
                row("조문 미지정", "일부개정", DIRECT, link(RULE, null)),
                row("지정 조문 변경", "일부개정", DIRECT, link(RULE, "제10조")),
                row("다른 조문만 변경", "일부개정", REFERENCE, link(RULE, "제11조")),
                row("가지조문은 다른 조문", "일부개정", REFERENCE, link(RULE, "제10조의2")),
                row("별표는 조문으로 해석 불가", "일부개정", DIRECT, link(RULE, "별표3")),
                row("하위 법령 개정", "일부개정", DIRECT, link(ACT, "제29조")),
                row("폐지", "폐지", DIRECT, link(RULE, "제11조")),
                row("타법개정", "타법개정", DIRECT, link(RULE, "제11조")),
                row("일괄개정", "일괄개정", DIRECT, link(RULE, "제11조")),
                row("전부개정", "전부개정", DIRECT, link(RULE, "제11조")),
                row("관계없는 법령", "일부개정", null, link(OTHER, null)),
                row("여러 링크 중 최고 등급", "일부개정", DIRECT, link(RULE, "제11조"), link(RULE, "제10조")),
                row("참고만 여럿", "일부개정", REFERENCE, link(OTHER, null), link(RULE, "제11조")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("table")
    void classifies(String name, String revisionType, Level expected, List<Link> links) {
        Amended a = new Amended(RULE, ACT, revisionType, Set.of("001000"));
        assertThat(ImpactRule.classify(a, links)).isEqualTo(expected);
    }

    private static Arguments row(String name, String revisionType, Level expected, Link... links) {
        return Arguments.of(name, revisionType, expected, Arrays.asList(links));
    }

    private static Link link(long lawId, String articleNo) {
        return new Link(lawId, articleNo);
    }
}
