package com.envpermit.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.envpermit.extraction.EvalScorer.Gold;
import com.envpermit.extraction.EvalScorer.Item;
import com.envpermit.extraction.EvalScorer.Score;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** Made-up conditions only; no real document text. */
class EvalScorerTest {

    private static final String DUST = "공사 시 비산먼지가 발생하지 않도록 살수차를 운행하고 세륜시설을 설치하여야 함";
    private static final String WATER = "방류수 수질을 분기 1회 측정하고 그 결과를 승인기관에 제출하여야 함";
    private static final String NOISE = "가설방음판넬을 설치하고 소음 측정 결과를 전광판으로 공개하여야 함";
    private static final String SUMMARY = "평가서에 제시된 저감방안 요약: 침사지 및 가배수로 설치";

    private static final List<Gold> SHEET = List.of(
            new Gold("2. 가.", DUST, Set.of(), true),
            new Gold("2. 나.", WATER, Set.of("QUARTERLY"), true),
            new Gold("2. 나.", NOISE, Set.of(), true),
            new Gold("Ⅲ. 1.", SUMMARY, Set.of(), false));

    private static Item item(String number, String text, String frequency) {
        return new Item(number, List.of("한 줄 정리", text), frequency); // like requirement_text + source_quote
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                // name, items, found, hits, optionalHits, numberExact, numberLoose, frequencyOk, match
                Arguments.of("모두 맞음", List.of(item("2. 가.", DUST, "ONCE"), item("2. 나.", WATER, "QUARTERLY"),
                        item("2. 나.", NOISE, "ONCE")), 3, 3, 0, 3, 3, 1, new int[] {0, 1, 2}),
                Arguments.of("한 조건을 둘로 나눠도 조건 1건", List.of(item("2. 나.", "방류수 수질을 분기 1회 측정", "QUARTERLY"),
                        item("2. 나.", "그 결과를 승인기관에 제출하여야 함", "QUARTERLY")), 1, 2, 0, 1, 1, 1, new int[] {1, 1}),
                Arguments.of("번호가 같으면 그 안에서 글이 가까운 쪽", List.of(item("2. 나.", NOISE, "ONCE")),
                        1, 1, 0, 1, 1, 0, new int[] {2}),
                Arguments.of("번호가 달라도 글이 같으면 일치, 번호는 불일치", List.of(item("5", WATER, "ANNUAL")),
                        1, 1, 0, 0, 0, 0, new int[] {1}),
                Arguments.of("상위 목차를 붙여 쓴 번호는 포함 일치만", List.of(item("Ⅱ. 2. 가. 대기질", DUST, "ONCE")),
                        1, 1, 0, 0, 1, 0, new int[] {0}),
                Arguments.of("번호가 없는 추출", List.of(item("", DUST, "ONCE")), 1, 1, 0, 0, 0, 0, new int[] {0}),
                Arguments.of("문서에 없는 의무는 오답", List.of(item("9", "매월 대표자가 환경 교육을 이수하고 수료증을 보관한다", "MONTHLY")),
                        0, 0, 0, 0, 0, 0, new int[] {-1}),
                Arguments.of("번호만 같고 글이 다르면 오답", List.of(item("2. 가.", "매월 대표자가 환경 교육을 이수하고 수료증을 보관한다", "MONTHLY")),
                        0, 0, 0, 0, 0, 0, new int[] {-1}),
                Arguments.of("참고 행은 정밀도에서 뺀다", List.of(item("Ⅲ. 1.", "침사지 및 가배수로 설치", "ONCE"), item("2. 가.", DUST, "ONCE")),
                        1, 1, 1, 1, 1, 0, new int[] {3, 0}),
                Arguments.of("추출 없음", List.of(), 0, 0, 0, 0, 0, 0, new int[] {}));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void scores(String name, List<Item> items, int found, int hits, int optionalHits, int numberExact, int numberLoose,
                int frequencyOk, int[] match) {
        Score s = EvalScorer.score(SHEET, items);

        assertThat(s.match()).containsExactly(match);
        assertThat(List.of(s.gold(), s.found(), s.items(), s.hits(), s.optionalHits()))
                .containsExactly(3, found, items.size(), hits, optionalHits);
        assertThat(List.of(s.numbered(), s.numberExact(), s.numberLoose())).containsExactly(found, numberExact, numberLoose);
        assertThat(s.frequencyOk()).isEqualTo(frequencyOk);
    }

    @Test
    void ratios() {
        Score s = EvalScorer.score(SHEET, List.of(item("2. 가.", DUST, "ONCE"), item("Ⅲ. 1.", SUMMARY, "ONCE"),
                item("9", "매월 대표자가 환경 교육을 이수하고 수료증을 보관한다", "MONTHLY")));

        assertThat(s.recall()).isEqualTo(1 / 3.0);
        assertThat(s.precision()).isEqualTo(0.5); // 참고 hit left out: 1 of 2
        assertThat(EvalScorer.score(SHEET, List.of()).precision()).isNaN();
    }

    @ParameterizedTest
    @CsvSource({
            "2-가, 2-가, true", "2-가, Ⅱ-2-가, true", "2-가-동식물상, 2-가, true", "가, 2-가, true",
            "2-가, 2-나, false", "2-1, 21, false", "Ⅱ-가, Ⅱ-2-가, false"})
    void looseNumbers(String a, String b, boolean same) {
        assertThat(EvalScorer.sameNumberLoosely(a, b)).isEqualTo(same);
        assertThat(EvalScorer.sameNumberLoosely(b, a)).isEqualTo(same);
    }

    @Test
    void similarityIgnoresSpacingAndPunctuation() {
        assertThat(EvalScorer.similarity("살수차를 운행하고, 세륜시설을 설치", DUST)).isEqualTo(1.0);
        assertThat(EvalScorer.similarity("살수차를\n운행 하고 세륜 시설을 설치", DUST)).isEqualTo(1.0);
        assertThat(EvalScorer.similarity(DUST, WATER)).isLessThan(EvalScorer.SAME_NUMBER_MIN);
    }

    @Test
    void answerSheetCsv() {
        List<Gold> gold = EvalScorer.answerSheet("﻿조건번호,내용,주기,구분,확인\r\n"
                + "2. 가.,\"살수차 운행, \"\"세륜\"\"시설\n설치\",,채점,\r\n"
                + "2. 나.,수질 측정,QUARTERLY|SEMIANNUAL,채점,주기 2개\r\n"
                + "Ⅲ. 1.,요약,,참고,\r\n"
                + "3,구분 열이 없는 행\r\n"
                + ",,,,\r\n");

        assertThat(gold).containsExactly(
                new Gold("2. 가.", "살수차 운행, \"세륜\"시설\n설치", Set.of(), true),
                new Gold("2. 나.", "수질 측정", Set.of("QUARTERLY", "SEMIANNUAL"), true),
                new Gold("Ⅲ. 1.", "요약", Set.of(), false),
                new Gold("3", "구분 열이 없는 행", Set.of(), true));
    }
}
