package com.envpermit.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PasteParserTest {

    static final Map<String, String> ITEMS = Map.of("CO", "일산화탄소", "NOX", "질소산화물", "DUST", "먼지");

    // pasted text → one string per row: "line|date|item|value" or "line|!error"
    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("tab separated", "2026-01-05\tCO\t12.5\n2026-01-06\tNOX\t40",
                        List.of("1|2026-01-05|CO|12.5", "2|2026-01-06|NOX|40")),
                Arguments.of("comma separated", "2026-01-05,CO,12.5", List.of("1|2026-01-05|CO|12.5")),
                Arguments.of("header row is skipped", "측정일\t항목\t값\n2026-01-05\tCO\t12", List.of("2|2026-01-05|CO|12")),
                Arguments.of("english header, comma", "date,item,value\n2026-01-05,CO,12", List.of("2|2026-01-05|CO|12")),
                Arguments.of("only the first row can be a header", "2026-01-05\tCO\t12\n측정일\t항목\t값",
                        List.of("1|2026-01-05|CO|12", "2|!날짜 형식 오류, 모르는 항목, 숫자가 아닌 값")),
                Arguments.of("code or display name, any case, padded", "2026-01-05\t 일산화탄소 \t12\n2026-01-05\tnox\t40\n2026-01-05\t먼지\t3",
                        List.of("1|2026-01-05|CO|12", "2|2026-01-05|NOX|40", "3|2026-01-05|DUST|3")),
                Arguments.of("date formats", "2026.1.5\tCO\t1\n2026/01/06\tCO\t1\n2026. 1. 7.\tCO\t1",
                        List.of("1|2026-01-05|CO|1", "2|2026-01-06|CO|1", "3|2026-01-07|CO|1")),
                Arguments.of("bad dates", "2026-02-30\tCO\t1\n01/05/2026\tCO\t1\n26-1-5\tCO\t1\n\tCO\t1",
                        List.of("1|!날짜 형식 오류", "2|!날짜 형식 오류", "3|!날짜 형식 오류", "4|!날짜 형식 오류")),
                Arguments.of("unknown and blank item", "2026-01-05\tBOD\t1\n2026-01-05\t\t1", List.of("1|!모르는 항목", "2|!빈 항목")),
                Arguments.of("blank and bad values", "2026-01-05\tCO\t\n2026-01-05\tCO\t불검출\n2026-01-05\tCO\t-1\n2026-01-05,CO,",
                        List.of("1|!빈 값", "2|!숫자가 아닌 값", "3|!음수 값", "4|!빈 값")),
                Arguments.of("thousands comma only when tab separated", "2026-01-05\tCO\t1,234.5\n2026-01-06,CO,1,234",
                        List.of("1|2026-01-05|CO|1234.5", "2|!측정일·항목·값 3열이 아닙니다")),
                Arguments.of("wrong column count", "2026-01-05\tCO\n2026-01-05\tCO\t1\tppm",
                        List.of("1|!측정일·항목·값 3열이 아닙니다", "2|!측정일·항목·값 3열이 아닙니다")),
                Arguments.of("several errors in one row", "2026-01-05\tCO\t1\n어제\t수은\tabc",
                        List.of("1|2026-01-05|CO|1", "2|!날짜 형식 오류, 모르는 항목, 숫자가 아닌 값")),
                Arguments.of("blank lines keep line numbers", "\r\n2026-01-05\tCO\t1\r\n\t\t\r\n\r\n2026-01-06\tCO\t2\r\n",
                        List.of("2|2026-01-05|CO|1", "5|2026-01-06|CO|2")),
                Arguments.of("duplicate day and item", "2026-01-05\tCO\t1\n2026-01-05\t일산화탄소\t2\n2026-01-05\tNOX\t2\n2026-01-06\tCO\t1",
                        List.of("1|2026-01-05|CO|1", "2|!1행과 측정일·항목 중복", "3|2026-01-05|NOX|2", "4|2026-01-06|CO|1")),
                Arguments.of("an error row does not make a later row a duplicate", "2026-01-05\tCO\t\n2026-01-05\tCO\t2",
                        List.of("1|!빈 값", "2|2026-01-05|CO|2")),
                Arguments.of("nothing", " \n\n", List.of()),
                Arguments.of("header only", "측정일\t항목\t값", List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void rows_or_row_errors(String name, String text, List<String> expected) {
        assertThat(PasteParser.parse(text, ITEMS).stream().map(r -> r.line() + "|" + (r.error() != null ? "!" + r.error()
                : r.measuredOn() + "|" + r.pollutant() + "|" + r.value())).toList()).isEqualTo(expected);
    }

    @Test
    void at_most_500_data_rows_error_rows_and_header_included_or_not() {
        String rows = "2026-01-05\tCO\t1\n".repeat(PasteParser.MAX_ROWS); // 499 of them are duplicates: still rows
        assertThat(PasteParser.parse("측정일\t항목\t값\n" + rows, ITEMS)).hasSize(500);
        assertThatThrownBy(() -> PasteParser.parse(rows + "2026-01-05\tCO\t1", ITEMS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("500");
    }

    @Test
    void the_raw_line_is_kept_for_the_preview() {
        assertThat(PasteParser.parse("  2026년\tCO\t1  ", ITEMS).get(0).raw()).isEqualTo("2026년\tCO\t1");
    }
}
