package com.envpermit.derivation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.envpermit.derivation.Evaluator.Determination;
import com.envpermit.derivation.Evaluator.Judgement;
import com.envpermit.derivation.Evaluator.Truth;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** Table tests for the pure evaluator: 3-valued logic, operators, sentences. */
class EvaluatorTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode j(String s) {
        try {
            return JSON.readTree(s.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalArgumentException(s, e);
        }
    }

    // T = capacity_tpd >= 100 (true at 300), F = capacity_tpd >= 1000, U = site_area_m2 >= 1 (missing)
    static final String T = "{'field':'capacity_tpd','op':'>=','value':100}";
    static final String F = "{'field':'capacity_tpd','op':'>=','value':1000}";
    static final String U = "{'field':'site_area_m2','op':'>=','value':1}";
    static final JsonNode ATTRS = j("{'capacity_tpd':300,'sido':'경기','proponent':'MUNICIPAL',"
            + "'land_categories':['FOREST','ROAD'],'has_power_generation':true,'hvac_area_m2':476}");

    static String sub(String x) {
        return switch (x) {
            case "T" -> T;
            case "F" -> F;
            case "U" -> U;
            default -> throw new IllegalArgumentException(x);
        };
    }

    @ParameterizedTest(name = "{0}({1},{2}) = {3}")
    @CsvSource({
            "all,T,T,TRUE", "all,T,F,FALSE", "all,T,U,UNKNOWN", "all,F,T,FALSE", "all,F,F,FALSE",
            "all,F,U,FALSE", "all,U,T,UNKNOWN", "all,U,F,FALSE", "all,U,U,UNKNOWN",
            "any,T,T,TRUE", "any,T,F,TRUE", "any,T,U,TRUE", "any,F,T,TRUE", "any,F,F,FALSE",
            "any,F,U,UNKNOWN", "any,U,T,TRUE", "any,U,F,UNKNOWN", "any,U,U,UNKNOWN"})
    void truthTable(String node, String a, String b, Truth expected) {
        JsonNode c = j("{'" + node + "':[" + sub(a) + "," + sub(b) + "]}");
        assertThat(Evaluator.evaluate(c, ATTRS)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"T,FALSE", "F,TRUE", "U,UNKNOWN"})
    void not(String a, Truth expected) {
        assertThat(Evaluator.evaluate(j("{'not':" + sub(a) + "}"), ATTRS)).isEqualTo(expected);
    }

    @Test
    void noConditionIsTrue() {
        assertThat(Evaluator.evaluate(null, ATTRS)).isEqualTo(Truth.TRUE);
        assertThat(Evaluator.evaluate(JSON.nullNode(), ATTRS)).isEqualTo(Truth.TRUE);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "{'field':'capacity_tpd','op':'>=','value':300}|TRUE",
            "{'field':'capacity_tpd','op':'>','value':300}|FALSE",
            "{'field':'capacity_tpd','op':'<','value':300.5}|TRUE",
            "{'field':'capacity_tpd','op':'<=','value':299}|FALSE",
            "{'field':'capacity_tpd','op':'==','value':300.0}|TRUE",
            "{'field':'capacity_tpd','op':'!=','value':300}|FALSE",
            "{'field':'sido','op':'==','value':'경기'}|TRUE",
            "{'field':'sido','op':'!=','value':'경기'}|FALSE",
            "{'field':'proponent','op':'in','value':['MUNICIPAL','REGIONAL']}|TRUE",
            "{'field':'proponent','op':'in','value':['PRIVATE']}|FALSE",
            "{'field':'land_categories','op':'contains_any','value':['ROAD','RIVER']}|TRUE",
            "{'field':'land_categories','op':'contains_any','value':['RIVER']}|FALSE",
            "{'field':'has_power_generation','op':'is_true'}|TRUE",
            "{'field':'has_boiler_pressure_vessel','op':'is_true'}|UNKNOWN",
            "{'field':'zoning','op':'in','value':['URBAN']}|UNKNOWN"})
    void operators(String condition, Truth expected) {
        assertThat(Evaluator.evaluate(j(condition), ATTRS)).isEqualTo(expected);
    }

    @Test
    void brokenConditionThrows() {
        assertThatThrownBy(() -> Evaluator.evaluate(j("{'field':'capacity_tpd','op':'~','value':1}"), ATTRS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Evaluator.evaluate(j("{'field':'sido','op':'>=','value':1}"), ATTRS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Evaluator.evaluate(j("{'field':'nope','op':'>=','value':1}"), ATTRS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "{0} / {1}")
    @CsvSource(delimiter = '|', value = {
            "{'capacity_tpd':130,'existing_capacity_tpd':100}|0.3",
            "{'capacity_tpd':130,'existing_capacity_tpd':0}|",
            "{'capacity_tpd':130}|",
            "{'existing_capacity_tpd':100}|"})
    void expansionRatio(String attrs, Double expected) {
        JsonNode r = Fields.withDerived(j(attrs)).get("expansion_ratio");
        if (expected == null) {
            assertThat(r).isNull();
        } else {
            assertThat(r.doubleValue()).isEqualTo(expected);
        }
    }

    // --- sentences -------------------------------------------------------------------------------------------

    static final String EIA = "{'any':[{'field':'capacity_tpd','op':'>=','value':100},"
            + "{'all':[{'field':'sido','op':'==','value':'경기'},{'field':'capacity_tpd','op':'>=','value':50}]}]}";

    static Judgement judge(String condition, String attrs) {
        return Evaluator.judge(condition == null ? null : j(condition), j(attrs), List.of("INCINERATION"),
                List.of("NEW"));
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource(delimiter = '|', value = {
            "{'capacity_tpd':300,'sido':'경기'}|APPLICABLE|시설용량 300톤/일 ≥ 100톤/일",
            "{'capacity_tpd':60,'sido':'경기'}|APPLICABLE|시도 = 경기 · 시설용량 60톤/일 ≥ 50톤/일",
            "{'capacity_tpd':60,'sido':'서울'}|NOT_APPLICABLE|시설용량 60톤/일 < 100톤/일 · 시도 서울 ≠ 경기",
            "{'capacity_tpd':30,'sido':'경기'}|NOT_APPLICABLE|시설용량 30톤/일 < 100톤/일 · 시설용량 30톤/일 < 50톤/일",
            "{'capacity_tpd':60}|ON_HOLD|시도 필요"})
    void eiaSentences(String attrs, Determination d, String reason) {
        Judgement r = judge(EIA, attrs);
        assertThat(r.determination()).isEqualTo(d);
        assertThat(r.reason()).isEqualTo(reason);
    }

    @Test
    void noConditionReasonFromScope() {
        assertThat(judge(null, "{}").reason()).isEqualTo("소각시설 신설 시 공통 해당");
        assertThat(Evaluator.judge(null, j("{}"), List.of(), List.of()).reason()).isEqualTo("공통 해당");
        assertThat(Evaluator.judge(null, j("{}"), List.of(), List.of("NEW")).reason()).isEqualTo("신설 시 공통 해당");
    }

    @Test
    void onHoldListsOnlyDecisiveMissingInputs() {
        // cost is missing too, but the first branch is already FALSE → only the area matters
        String c = "{'all':[{'field':'site_area_m2','op':'>=','value':5000},"
                + "{'any':[{'field':'zoning','op':'==','value':'URBAN'},{'field':'construction_cost_krw','op':'>=','value':1}]}]}";
        Judgement r = judge(c, "{'zoning':'URBAN'}");
        assertThat(r.determination()).isEqualTo(Determination.ON_HOLD);
        assertThat(r.missingFields()).containsExactly("site_area_m2");
        assertThat(r.missingInputs()).containsExactly("부지면적");

        Judgement both = judge(c, "{'zoning':'GREENBELT'}");
        assertThat(both.missingFields()).containsExactly("site_area_m2", "construction_cost_krw");
        assertThat(both.reason()).isEqualTo("부지면적, 총공사비 필요");

        assertThat(judge(c, "{'site_area_m2':4000}").determination()).isEqualTo(Determination.NOT_APPLICABLE);
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource(delimiter = '|', value = {
            "{'field':'hvac_area_m2','op':'>=','value':500}|냉난방면적 476㎡ < 500㎡",
            "{'field':'construction_cost_krw','op':'>=','value':10000000000}|총공사비 12,000,000,000원 ≥ 10,000,000,000원",
            "{'field':'proponent','op':'in','value':['MUNICIPAL','REGIONAL']}|추진주체 = 지자체",
            "{'field':'proponent','op':'in','value':['REGIONAL','PRIVATE']}|추진주체 지자체 ∉ 광역/민간",
            "{'field':'land_categories','op':'contains_any','value':['ROAD','RIVER']}|지목 도로 포함",
            "{'field':'land_categories','op':'contains_any','value':['RIVER']}|지목에 하천·소하천 없음",
            "{'field':'has_power_generation','op':'is_true'}|발전설비(증기터빈) 있음",
            "{'not':{'field':'has_power_generation','op':'is_true'}}|발전설비(증기터빈) 있음"})
    void comparisonSentences(String condition, String reason) {
        JsonNode attrs = j("{'hvac_area_m2':476,'construction_cost_krw':12000000000,'proponent':'MUNICIPAL',"
                + "'land_categories':['FOREST','ROAD'],'has_power_generation':true}");
        assertThat(Evaluator.judge(j(condition), attrs, List.of(), List.of()).reason()).isEqualTo(reason);
    }

    @Test
    void errorWhenRuleIsBroken() {
        Judgement r = judge("{'field':'capacity_tpd','op':'~','value':1}", "{'capacity_tpd':1}");
        assertThat(r.determination()).isEqualTo(Determination.ERROR);
    }
}
