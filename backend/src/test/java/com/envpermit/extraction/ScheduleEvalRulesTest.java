package com.envpermit.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The local rule baseline (design-schedule/07b): bid-stage base dates and week offsets become rules. */
class ScheduleEvalRulesTest {

    @Test
    void site_briefing_notice_and_groundbreaking_rules_are_found() {
        ScheduleEval.Doc doc = new ScheduleEval.Doc("x", "SITE_BRIEFING_DOC", List.of("""
                질의는 현장설명일로부터 10일 이내에 제출한다. 기본설계도서는 현장설명일로부터 150일까지 제출한다.
                실시설계적격자는 통지를 받은 후 7일 이내에 착수하고, 통보일로부터 150일 이내 실시설계를 제출한다.
                공사기간: 착공일로부터 36개월. 결과는 착수 후 8주 이내 공문으로 제출한다."""), Set.of(), Set.of());
        assertThat(ScheduleEval.keys(ScheduleEval.rules(doc))).containsExactlyInAnyOrder("SITE_BRIEFING+10D",
                "SITE_BRIEFING+150D", "NOTICE+7D", "NOTICE+150D", "CONSTRUCTION_START+36M", "KICKOFF+56D");
    }

    @Test
    void a_rule_or_its_resolved_date_hits_once_and_traps_are_counted() {
        ScheduleEval.Score s = ScheduleEval.score(Set.of("REVIEW-7D|2028-07-19", "2028-07-26"), Set.of("2028-07-03"),
                Set.of("REVIEW-7D", "2028-07-19", "2028-07-03"));
        assertThat(s).isEqualTo(new ScheduleEval.Score(2, 3, 1, 2, 1));
    }
}
