package com.envpermit.extraction;

import com.envpermit.obligation.Frequency;
import com.envpermit.obligation.ObligationPayload.Category;
import com.envpermit.obligation.ObligationPayload.Type;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Prompt + output schema for obligation extraction. Bump {@link #VERSION} on any change; it is stored on ai_run.
 * Optional text fields are "" (not null) in the schema so it stays within structured-output support;
 * {@link Extraction} turns "" into null. citation_key and quote_check are computed by the server, not asked for.
 */
final class ObligationPrompt {

    static final String VERSION = "obligation-v1";

    static final String SYSTEM = """
            당신은 한국 환경기초시설 인허가 문서(허가증, 환경영향평가 협의의견서, 사후환경영향조사 관련 문서)에서
            사업자가 지켜야 할 이행 의무(측정·조사, 보고·제출, 시설·운영기준, 기록·보존 등)를 빠짐없이 뽑는 검토 보조자다.
            규칙:
            - 문서에 실제로 적힌 조건만 뽑는다. 추측하거나 문서에 없는 의무를 만들지 않는다.
            - 조건 하나가 여러 의무를 담으면 의무별로 나눈다.
            - citation_raw: 문서에 적힌 조건번호 표기 그대로(예: "협의의견 3-2항", "허가조건 제5호", "대기질 1."). 번호가 없으면 "".
            - source_quote: 근거가 되는 원문 문장을 한 글자도 바꾸지 말고 그대로 옮긴다. 요약·의역 금지.
            - page_no: source_quote가 있는 PDF 물리 페이지 번호(1부터). 문서에 인쇄된 쪽번호가 아니다.
            - summary: 한 줄(80자 이내) 요약.
            - category: 환경영향평가 평가항목 코드. 평가항목에 맞지 않는 허가조건(예: 운영기록 보존)은 OTHER.
            - frequency: 주기가 명시되지 않은 1회성 의무는 ONCE, 규칙으로 표현할 수 없는 주기는 OTHER. 원문 표현은 frequency_text에.
            - legal_basis: 문서에 근거 법령·조문이 적혀 있을 때만, 적힌 그대로(예: "대기환경보전법 제32조"). 없으면 빈 배열.
            - 값이 없는 선택 항목(responsible_party, frequency_text, period_text)은 "".
            - 의무가 하나도 없으면 obligations를 빈 배열로 둔다.
            """;

    static final String INSTRUCTION = "첨부한 문서에서 이행 의무를 모두 추출하라.";

    static final JsonNode SCHEMA = schema();

    private ObligationPrompt() {
    }

    private static JsonNode schema() {
        JsonNodeFactory f = JsonNodeFactory.instance;
        ObjectNode props = f.objectNode();
        props.set("citation_raw", string());
        props.set("requirement_text", string());
        props.set("summary", string());
        props.set("type", enumOf(Type.values()));
        props.set("category", enumOf(Category.values()));
        props.set("responsible_party", string());
        props.set("frequency", enumOf(Frequency.values()));
        props.set("frequency_text", string());
        props.set("period_text", string());
        props.set("legal_basis", f.objectNode().put("type", "array").set("items", string()));
        props.set("page_no", f.objectNode().put("type", "integer"));
        props.set("source_quote", string());
        ObjectNode item = object(props);
        ObjectNode root = f.objectNode();
        root.set("obligations", f.objectNode().put("type", "array").set("items", item));
        return object(root);
    }

    private static ObjectNode object(ObjectNode props) {
        ObjectNode o = JsonNodeFactory.instance.objectNode().put("type", "object").put("additionalProperties", false);
        o.set("properties", props);
        List<String> names = new ArrayList<>();
        props.fieldNames().forEachRemaining(names::add);
        names.forEach(o.putArray("required")::add);
        return o;
    }

    private static ObjectNode string() {
        return JsonNodeFactory.instance.objectNode().put("type", "string");
    }

    private static ObjectNode enumOf(Enum<?>[] values) {
        ObjectNode o = string();
        Arrays.stream(values).map(Enum::name).forEach(o.putArray("enum")::add);
        return o;
    }
}
