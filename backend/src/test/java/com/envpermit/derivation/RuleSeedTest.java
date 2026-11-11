package com.envpermit.derivation;

import static org.assertj.core.api.Assertions.assertThat;

import com.envpermit.derivation.Evaluator.Judgement;
import com.envpermit.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The seeded rules of ruleset v2 (V290) against made-up attribute sets: each threshold at and just under its
 * boundary, and the ON_HOLD case when the input is missing. Attributes are fictional (no real project).
 */
class RuleSeedTest extends IntegrationTest {

    @Autowired DerivationRuleRepository rules;

    /** attrs are merged over {facility_type: INCINERATION, project_kind: NEW}. */
    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource(delimiter = '|', value = {
            // A: widened to every project kind
            "BUILDING_PERMIT|{'project_kind':'EXPANSION'}|APPLICABLE",
            "BUILDING_PERMIT|{}|APPLICABLE",
            "BUILDING_USE_APPROVAL|{'project_kind':'EXPANSION'}|APPLICABLE",
            "INCINERATOR_INSPECTION|{'project_kind':'EXPANSION'}|APPLICABLE",
            "WASTE_USE_START_REPORT|{'project_kind':'EXPANSION'}|APPLICABLE",
            // still NEW-only: the law treats an expansion differently
            "WASTE_INSTALL_APPROVAL|{'project_kind':'EXPANSION','proponent':'MUNICIPAL','capacity_tpd':200}|SCOPE_EXCLUDED",
            "SITE_SELECTION|{'project_kind':'EXPANSION','proponent':'MUNICIPAL','capacity_tpd':200}|SCOPE_EXCLUDED",
            // A: 변경승인 — capacity up by 30% or more of an approved (≥100 t/d) facility
            "WASTE_INSTALL_CHANGE_APPROVAL|{'project_kind':'EXPANSION','proponent':'MUNICIPAL','capacity_tpd':130,'existing_capacity_tpd':100}|APPLICABLE",
            "WASTE_INSTALL_CHANGE_APPROVAL|{'project_kind':'EXPANSION','proponent':'MUNICIPAL','capacity_tpd':129,'existing_capacity_tpd':100}|NOT_APPLICABLE",
            "WASTE_INSTALL_CHANGE_APPROVAL|{'project_kind':'EXPANSION','proponent':'MUNICIPAL','capacity_tpd':130,'existing_capacity_tpd':99}|NOT_APPLICABLE",
            "WASTE_INSTALL_CHANGE_APPROVAL|{'project_kind':'EXPANSION','proponent':'PRIVATE','capacity_tpd':200,'existing_capacity_tpd':100}|NOT_APPLICABLE",
            "WASTE_INSTALL_CHANGE_APPROVAL|{'project_kind':'EXPANSION','proponent':'MUNICIPAL','capacity_tpd':200}|ON_HOLD",
            "WASTE_INSTALL_CHANGE_APPROVAL|{'proponent':'MUNICIPAL','capacity_tpd':200}|SCOPE_EXCLUDED",
            // B: 지하안전 10 m / 20 m
            "UNDERGROUND_SAFETY|{'excavation_depth_m':20}|APPLICABLE",
            "UNDERGROUND_SAFETY|{'excavation_depth_m':19.9}|NOT_APPLICABLE",
            "UNDERGROUND_SAFETY|{}|ON_HOLD",
            "UNDERGROUND_SAFETY_SMALL|{'excavation_depth_m':10}|APPLICABLE",
            "UNDERGROUND_SAFETY_SMALL|{'excavation_depth_m':19.9}|APPLICABLE",
            "UNDERGROUND_SAFETY_SMALL|{'excavation_depth_m':9.9}|NOT_APPLICABLE",
            "UNDERGROUND_SAFETY_SMALL|{'excavation_depth_m':20}|NOT_APPLICABLE",
            "UNDERGROUND_SAFETY_SMALL|{}|ON_HOLD",
            // B: 설계 VE — 발주청, 100억
            "DESIGN_VE|{'proponent':'MUNICIPAL','construction_cost_krw':10000000000}|APPLICABLE",
            "DESIGN_VE|{'proponent':'REGIONAL','construction_cost_krw':9999999999}|NOT_APPLICABLE",
            "DESIGN_VE|{'proponent':'PRIVATE','construction_cost_krw':90000000000}|NOT_APPLICABLE",
            "DESIGN_VE|{'proponent':'MUNICIPAL'}|ON_HOLD",
            // B: 설계 안전성 검토 / 안전관리계획 — 굴착 10 m
            "DESIGN_SAFETY_REVIEW|{'proponent':'MUNICIPAL','excavation_depth_m':10}|APPLICABLE",
            "DESIGN_SAFETY_REVIEW|{'proponent':'MUNICIPAL','excavation_depth_m':9.9}|NOT_APPLICABLE",
            "DESIGN_SAFETY_REVIEW|{'proponent':'PRIVATE','excavation_depth_m':15}|NOT_APPLICABLE",
            "DESIGN_SAFETY_REVIEW|{'proponent':'MUNICIPAL'}|ON_HOLD",
            "SAFETY_MGMT_PLAN|{'excavation_depth_m':10}|APPLICABLE",
            "SAFETY_MGMT_PLAN|{'excavation_depth_m':9.9}|NOT_APPLICABLE",
            "SAFETY_MGMT_PLAN|{}|ON_HOLD",
            // B: 설계안전보건대장 — 50억
            "DESIGN_SAFETY_LEDGER|{'construction_cost_krw':5000000000}|APPLICABLE",
            "DESIGN_SAFETY_LEDGER|{'construction_cost_krw':4999999999}|NOT_APPLICABLE",
            "DESIGN_SAFETY_LEDGER|{}|ON_HOLD",
            // B: 유해위험방지계획서 — 31 m / 3만㎡ / 10 m, any of them
            "HAZARD_PREVENTION_PLAN|{'building_height_m':31}|APPLICABLE",
            "HAZARD_PREVENTION_PLAN|{'gross_floor_area_m2':30000}|APPLICABLE",
            "HAZARD_PREVENTION_PLAN|{'excavation_depth_m':10}|APPLICABLE",
            "HAZARD_PREVENTION_PLAN|{'building_height_m':30.9,'gross_floor_area_m2':29999,'excavation_depth_m':9.9}|NOT_APPLICABLE",
            "HAZARD_PREVENTION_PLAN|{'building_height_m':30.9,'gross_floor_area_m2':29999}|ON_HOLD",
            // B: 에너지절약계획서 — 500㎡ and some cooling/heating
            "ENERGY_SAVING_PLAN|{'gross_floor_area_m2':500,'hvac_area_m2':1}|APPLICABLE",
            "ENERGY_SAVING_PLAN|{'gross_floor_area_m2':499,'hvac_area_m2':300}|NOT_APPLICABLE",
            "ENERGY_SAVING_PLAN|{'gross_floor_area_m2':8000,'hvac_area_m2':0}|NOT_APPLICABLE",
            "ENERGY_SAVING_PLAN|{'gross_floor_area_m2':8000}|ON_HOLD",
            // B: 녹색건축 — 공공, 3,000㎡
            "GREEN_BUILDING_CERT|{'proponent':'MUNICIPAL','gross_floor_area_m2':3000,'hvac_area_m2':900}|APPLICABLE",
            "GREEN_BUILDING_CERT|{'proponent':'MUNICIPAL','gross_floor_area_m2':2999,'hvac_area_m2':900}|NOT_APPLICABLE",
            "GREEN_BUILDING_CERT|{'proponent':'PRIVATE','gross_floor_area_m2':9000,'hvac_area_m2':900}|NOT_APPLICABLE",
            "GREEN_BUILDING_CERT|{'proponent':'MUNICIPAL','gross_floor_area_m2':9000}|ON_HOLD",
            // B: ZEB — 공공, 500㎡
            "ZEB_CERT|{'proponent':'REGIONAL','gross_floor_area_m2':500,'hvac_area_m2':100}|APPLICABLE",
            "ZEB_CERT|{'proponent':'REGIONAL','gross_floor_area_m2':499,'hvac_area_m2':100}|NOT_APPLICABLE",
            "ZEB_CERT|{'proponent':'PRIVATE','gross_floor_area_m2':5000,'hvac_area_m2':100}|NOT_APPLICABLE",
            "ZEB_CERT|{'proponent':'REGIONAL','gross_floor_area_m2':5000}|ON_HOLD",
            // B: yes/no inputs
            "HAZMAT_INSTALL_PERMIT|{'has_hazardous_material_storage':true}|APPLICABLE",
            "HAZMAT_INSTALL_PERMIT|{'has_hazardous_material_storage':false}|NOT_APPLICABLE",
            "HAZMAT_INSTALL_PERMIT|{}|ON_HOLD",
            "HAZMAT_COMPLETION_INSPECTION|{'has_hazardous_material_storage':true}|APPLICABLE",
            "HAZMAT_COMPLETION_INSPECTION|{}|ON_HOLD",
            "HAZMAT_SAFETY_MANAGER|{'has_hazardous_material_storage':true}|APPLICABLE",
            "HAZMAT_SAFETY_MANAGER|{'has_hazardous_material_storage':false}|NOT_APPLICABLE",
            "CHEM_ACCIDENT_PLAN|{'uses_hazardous_chemicals':true}|APPLICABLE",
            "CHEM_ACCIDENT_PLAN|{'uses_hazardous_chemicals':false}|NOT_APPLICABLE",
            "CHEM_ACCIDENT_PLAN|{}|ON_HOLD",
            "CHEM_FACILITY_INSPECTION|{'uses_hazardous_chemicals':true}|APPLICABLE",
            "CHEM_FACILITY_INSPECTION|{}|ON_HOLD",
            "BOILER_INSTALL_INSPECTION|{'has_boiler_pressure_vessel':true}|APPLICABLE",
            "BOILER_INSTALL_INSPECTION|{'has_boiler_pressure_vessel':false}|NOT_APPLICABLE",
            "BOILER_INSTALL_INSPECTION|{}|ON_HOLD",
            // C: 통합허가 사업장 — 20 t/y or 700 ㎥/d
            "INTEGRATED_START_REPORT|{'air_emission_tpy':20}|APPLICABLE",
            "INTEGRATED_START_REPORT|{'air_emission_tpy':19.9,'wastewater_m3pd':700}|APPLICABLE",
            "INTEGRATED_START_REPORT|{'air_emission_tpy':19.9,'wastewater_m3pd':699}|NOT_APPLICABLE",
            "INTEGRATED_START_REPORT|{'air_emission_tpy':19.9}|ON_HOLD",
            "INTEGRATED_ENV_PERMIT|{'project_kind':'EXPANSION','wastewater_m3pd':700}|APPLICABLE",
            // D: 비산먼지 1,000㎡, 소방 동의 400㎡
            "FUGITIVE_DUST_REPORT|{'gross_floor_area_m2':1000}|APPLICABLE",
            "FUGITIVE_DUST_REPORT|{'gross_floor_area_m2':999}|NOT_APPLICABLE",
            "FUGITIVE_DUST_REPORT|{}|ON_HOLD",
            "FIRE_CONSENT|{'gross_floor_area_m2':400}|APPLICABLE",
            "FIRE_CONSENT|{'gross_floor_area_m2':399}|NOT_APPLICABLE",
            "FIRE_CONSENT|{}|ON_HOLD"})
    void seededRule(String ruleKey, String attrs, String expected) {
        DerivationRule r = rules.findByStatusOrderByRuleKey("ACTIVE").stream()
                .filter(x -> x.getRuleKey().equals(ruleKey)).findFirst().orElseThrow();
        ObjectNode a = (ObjectNode) EvaluatorTest.j("{'facility_type':'INCINERATION','project_kind':'NEW'}");
        a.setAll((ObjectNode) EvaluatorTest.j(attrs));
        assertThat(Fields.validate(a)).isEmpty();
        JsonNode snapshot = Fields.withDerived(a);
        String got;
        if (!r.appliesTo(a.get("facility_type").asText(), a.get("project_kind").asText())) {
            got = "SCOPE_EXCLUDED";
        } else {
            Judgement j = Evaluator.judge(r.getCondition(), snapshot, r.getFacilityTypes(), r.getProjectKinds());
            got = j.determination().name();
        }
        assertThat(got).isEqualTo(expected);
    }

    @Test
    void rulesetV2KeepsOneActiveVersionPerKeyAndEveryRuleCitesALaw() {
        var active = rules.findByStatusOrderByRuleKey("ACTIVE");
        assertThat(rules.rulesetVersion()).isEqualTo(2);
        assertThat(active).hasSize(34); // 14 from v1 (5 of them re-issued as version 2) + 20 new
        assertThat(active).extracting(DerivationRule::getRuleKey).doesNotHaveDuplicates();
        assertThat(active).allSatisfy(r -> assertThat(r.getLawRefs().size()).isPositive());
    }
}
