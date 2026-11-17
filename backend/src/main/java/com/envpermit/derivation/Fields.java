package com.envpermit.derivation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The field dictionary (auto-derivation design §2): the only variables a rule condition may reference.
 * Project attributes are stored as one JSON object keyed by these keys.
 */
public final class Fields {

    public enum Type { NUMBER, ENUM, ENUM_LIST, BOOL, DATE, TEXT }

    public record Field(String key, String label, Type type, String unit, Map<String, String> values,
                        boolean required) {
        String display(String code) {
            return values.getOrDefault(code, code);
        }
    }

    private static final Map<String, String> SIDO = codes("서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종",
            "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주");

    public static final Map<String, Field> ALL = index(
            // required (§2.1)
            req("facility_type", "시설유형", Type.ENUM, Map.of("INCINERATION", "소각시설")),
            req("capacity_tpd", "시설용량", Type.NUMBER, "톤/일"),
            req("sido", "시도", Type.ENUM, SIDO),
            req("address", "지번", Type.TEXT, ""),
            req("proponent", "추진주체", Type.ENUM, labels("MUNICIPAL", "지자체", "REGIONAL", "광역", "PRIVATE", "민간")),
            req("project_kind", "사업구분", Type.ENUM, labels("NEW", "신설", "EXPANSION", "증설")),
            opt("existing_capacity_tpd", "기존 용량", Type.NUMBER, "톤/일"), // required when EXPANSION
            // optional (§2.2)
            opt("incineration_type", "소각 방식", Type.ENUM, labels("GENERAL", "일반", "HIGH_TEMP", "고온·열분해·용융")),
            opt("waste_types", "처리대상폐기물", Type.ENUM_LIST, labels("MUNICIPAL_SOLID", "생활폐기물",
                    "INDUSTRIAL", "사업장폐기물", "FOOD", "음식물류폐기물", "SEWAGE_SLUDGE", "하수슬러지",
                    "DESIGNATED", "지정폐기물")),
            opt("construction_start", "착공 예정일", Type.DATE, ""),
            opt("site_area_m2", "부지면적", Type.NUMBER, "㎡"),
            opt("zoning", "용도지역", Type.ENUM, labels("URBAN", "도시지역", "MGMT_CONSERVATION", "보전관리지역",
                    "MGMT_PRODUCTION", "생산관리지역", "MGMT_PLANNING", "계획관리지역", "AGRI_FOREST", "농림지역",
                    "NATURE_CONSERVATION", "자연환경보전지역", "GREENBELT", "개발제한구역")),
            opt("land_categories", "지목", Type.ENUM_LIST, labels("FOREST", "임야", "FARMLAND", "전·답·과수원",
                    "RIVER", "하천·소하천", "ROAD", "도로", "PASTURE", "초지·목장용지", "SEWER", "하수도",
                    "PARK", "공원·녹지")),
            opt("urban_facility_decided", "도시계획시설 기결정", Type.BOOL, ""),
            opt("construction_cost_krw", "총공사비", Type.NUMBER, "원"),
            opt("total_budget_krw", "총사업비", Type.NUMBER, "원"),
            opt("national_budget_krw", "국비", Type.NUMBER, "원"),
            opt("local_budget_krw", "지방비", Type.NUMBER, "원"),
            opt("air_emission_tpy", "대기 발생량", Type.NUMBER, "톤/년"),
            opt("wastewater_m3pd", "폐수량", Type.NUMBER, "㎥/일"),
            opt("gross_floor_area_m2", "연면적", Type.NUMBER, "㎡"),
            opt("hvac_area_m2", "냉난방면적", Type.NUMBER, "㎡"),
            opt("building_height_m", "건축물 높이", Type.NUMBER, "m"),
            opt("excavation_depth_m", "굴착깊이", Type.NUMBER, "m"),
            opt("has_power_generation", "발전설비(증기터빈)", Type.BOOL, ""),
            opt("has_boiler_pressure_vessel", "보일러·압력용기", Type.BOOL, ""),
            opt("uses_hazardous_chemicals", "유해화학물질 사용", Type.BOOL, ""),
            opt("has_hazardous_material_storage", "위험물 저장", Type.BOOL, ""),
            opt("sells_electricity", "전력 판매(전력시장 공급)", Type.BOOL, ""),
            opt("receiving_capacity_kw", "수전 용량", Type.NUMBER, "kW"),
            opt("has_elevator", "승강기 설치", Type.BOOL, ""),
            // derived (§2.3), computed by the server — not accepted as input
            opt("expansion_ratio", "증설 비율", Type.NUMBER, ""));

    public static final String DERIVED = "expansion_ratio";

    private Fields() {
    }

    public static Field get(String key) {
        Field f = ALL.get(key);
        if (f == null) {
            throw new IllegalArgumentException("모르는 필드: " + key);
        }
        return f;
    }

    /** Problems with a submitted attribute object (unknown key, wrong type, value not in the enum). */
    public static List<String> validate(JsonNode attrs) {
        List<String> errors = new ArrayList<>();
        if (attrs == null || !attrs.isObject()) {
            return List.of("속성은 JSON 객체여야 합니다.");
        }
        attrs.fields().forEachRemaining(e -> {
            Field f = ALL.get(e.getKey());
            JsonNode v = e.getValue();
            if (f == null || f.key().equals(DERIVED)) {
                errors.add(e.getKey() + ": 모르는 필드");
            } else if (!v.isNull() && !valid(f, v)) {
                errors.add(f.label() + ": 값 형식이 맞지 않습니다");
            }
        });
        return errors;
    }

    private static boolean valid(Field f, JsonNode v) {
        return switch (f.type()) {
            case NUMBER -> v.isNumber();
            case BOOL -> v.isBoolean();
            case TEXT -> v.isTextual() && v.textValue().length() <= 300;
            case ENUM -> v.isTextual() && f.values().containsKey(v.textValue());
            case ENUM_LIST -> {
                boolean ok = v.isArray();
                for (JsonNode x : v) {
                    ok &= x.isTextual() && f.values().containsKey(x.textValue());
                }
                yield ok;
            }
            case DATE -> {
                try {
                    yield v.isTextual() && LocalDate.parse(v.textValue()) != null;
                } catch (RuntimeException e) {
                    yield false;
                }
            }
        };
    }

    /** Required keys without a value (§2.1); existing capacity only for an expansion. */
    public static List<String> missingRequired(JsonNode attrs) {
        List<String> out = new ArrayList<>();
        ALL.values().stream().filter(Field::required).filter(f -> absent(attrs, f.key())).forEach(f -> out.add(f.key()));
        if ("EXPANSION".equals(attrs.path("project_kind").asText()) && absent(attrs, "existing_capacity_tpd")) {
            out.add("existing_capacity_tpd");
        }
        return out;
    }

    /** Copy with derived fields added. Derived = no value when any source is missing (or existing = 0). */
    public static ObjectNode withDerived(JsonNode attrs) {
        ObjectNode out = attrs.deepCopy();
        out.remove(DERIVED);
        JsonNode cap = attrs.get("capacity_tpd");
        JsonNode existing = attrs.get("existing_capacity_tpd");
        if (cap != null && cap.isNumber() && existing != null && existing.isNumber() && existing.decimalValue().signum() != 0) {
            BigDecimal e = existing.decimalValue();
            out.put(DERIVED, cap.decimalValue().subtract(e).divide(e, MathContext.DECIMAL64).stripTrailingZeros());
        }
        return out;
    }

    static boolean absent(JsonNode attrs, String key) {
        JsonNode v = attrs.get(key);
        return v == null || v.isNull() || (v.isTextual() && v.textValue().isBlank());
    }

    // --- builders ---

    private static Field req(String key, String label, Type type, Map<String, String> values) {
        return new Field(key, label, type, "", values, true);
    }

    private static Field req(String key, String label, Type type, String unit) {
        return new Field(key, label, type, unit == null ? "" : unit, Map.of(), true);
    }

    private static Field opt(String key, String label, Type type, Map<String, String> values) {
        return new Field(key, label, type, "", values, false);
    }

    private static Field opt(String key, String label, Type type, String unit) {
        return new Field(key, label, type, unit == null ? "" : unit, Map.of(), false);
    }

    private static Map<String, String> codes(String... names) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String n : names) {
            m.put(n, n);
        }
        return m;
    }

    private static Map<String, String> labels(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    private static Map<String, Field> index(Field... fields) {
        Map<String, Field> m = new LinkedHashMap<>();
        for (Field f : fields) {
            m.put(f.key(), f);
        }
        return m;
    }
}
