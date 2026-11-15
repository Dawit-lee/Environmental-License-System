package com.envpermit.extraction;

import com.envpermit.schedule.ScheduleSuggestion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Document → schedule prompts (design-schedule/07, 설계 §4.2): one per document type, versioned on ai_run.prompt_version
 * as {@code schedule-<type>-<VERSION>}; bump {@link #VERSION} on any change. Same output schema for all. Missing
 * optional values are "" / 0 (structured-output friendly); {@link Extraction} turns them into nulls.
 */
final class SchedulePrompt {

    static final String VERSION = "v2"; // v2: 현장설명일·적격자 통보일·착공일 기준일

    /** What each document type is read for. WEEKLY_REPORT is stored but not extracted in v1. */
    static final Map<String, String> TARGETS = Map.of(
            "BID_NOTICE", "현장설명회 일시·장소(EVENT SITE_BRIEFING), 질의 마감(EVENT QNA_DEADLINE), 입찰·제출일(EVENT SUBMISSION), "
                    + "설계(공사)기간(TASK)",
            "SITE_BRIEFING_DOC", "사업기간·공사기간(TASK 또는 기준일 규칙), 현장설명회 일시·장소가 있으면 EVENT SITE_BRIEFING, "
                    + "질의 기한·설계도서 제출·실시설계 착수 같은 현장설명일·통보일·착공일 기준 기한(MILESTONE/TASK, 기준일 규칙)",
            "TASK_SPEC", "과업기간(TASK), 착수·중간·최종 보고회 규칙(MILESTONE, 기준일+오프셋), 성과품·보고서 제출 기한(MILESTONE), "
                    + "월간 보고 같은 정기 보고 주기(RECURRING CLIENT_REPORT)",
            "OFFICIAL_LETTER", "통보된 보고회·현장조사·심의·회의의 일시·장소·참석 대상(EVENT CLIENT_REPORT·TRIP·REVIEW·MEETING), "
                    + "요청된 자료·결과 제출 기한(MILESTONE, 날짜 또는 기준일 규칙)",
            "MINUTES", "다음 회의 일시(EVENT MEETING), 회의에서 합의된 제출 기한(MILESTONE)");

    static final String SYSTEM = """
            당신은 설계사(환경기초시설 설계) 일정 담당자를 돕는 검토 보조자다. 공고문·현장설명서·과업내용서·공문·회의록에서
            프로젝트 일정에 넣을 날짜를 빠짐없이, 문서에 적힌 것만 뽑는다. 결과는 사람이 한 행씩 승인한다.
            행 종류(type):
            - EVENT: 특정 일시에 열리는 일(현장설명회, 질의 마감, 제출, 보고회, 심의, 회의, 출장·현장조사).
              kind: SITE_BRIEFING|QNA_DEADLINE|SUBMISSION|CLIENT_REPORT|REVIEW|MEETING|TRIP|OTHER.
              startsAt "YYYY-MM-DDTHH:mm"(시각이 있을 때) 또는 date "YYYY-MM-DD"(종일). 끝 시각이 적혀 있으면 endsAt.
            - MILESTONE: 하루짜리 기한(제출 기한, 보고회 기한). date 또는 기준일 규칙.
            - TASK: 기간(과업기간, 설계기간). startDate와 date(종료일), 또는 기준일 규칙과 durationDays.
              기준일 규칙의 offset은 기준일부터 종료일까지의 일수다("착수일로부터 300일" = KICKOFF, offset 300, durationDays 300).
            - RECURRING: 정기 보고(월 1회 등). kind(보통 CLIENT_REPORT), interval: WEEKLY|BIWEEKLY|MONTHLY|QUARTERLY.
            규칙:
            - "계약일로부터 15일 이내", "착수 후 4개월" 같은 상대 기한은 날짜로 계산하지 말고 anchor + offset으로 낸다.
              anchor: CONTRACT(계약일)|KICKOFF(착수일)|SUBMISSION(입찰·제출일)|COMPLETION(완료일·준공일)|REVIEW(심의일)
              |SITE_BRIEFING(현장설명일)|NOTICE(실시설계적격자 통보·통지일)|CONSTRUCTION_START(착공일).
              "준공일까지"처럼 기준일 당일이면 offset 0. "8주"는 offset 56(DAYS). 이 여덟 기준일이 아닌 날 기준 규칙은 내지 않는다.
              정정표·추가내용으로 바뀐 값이 있으면 바뀐 뒤의 값만 낸다.
              offsetUnit: DAYS|MONTHS, offset: 정수(이전이면 음수). 원문 규칙은 rule에 그대로. 상대 기한이 아니면 anchor "", offset 0.
            - 연도가 없는 날짜는 문서의 다른 날짜로 연도를 정하고 yearInferred=true.
            - evidence.quote: 근거 문장을 한 글자도 바꾸지 말고 그대로. evidence.page: 그 문장이 있는 PDF 물리 페이지(1부터).
              근거를 댈 수 없는 날짜는 내지 않는다.
            - title: 짧은 이름(예: "현장설명회", "착수보고회", "중간보고서 제출"). discipline: 문서에 분야가 적혀 있을 때만.
            - 값이 없는 문자열은 "", 없는 정수는 0. confidence: HIGH|MEDIUM|LOW.
            - 날짜가 하나도 없으면 rows를 빈 배열로 둔다.
            """;

    static final JsonNode SCHEMA = schema();

    private SchedulePrompt() {
    }

    static boolean supports(String docType) {
        return docType != null && TARGETS.containsKey(docType);
    }

    static String version(String docType) {
        return "schedule-" + docType.toLowerCase() + "-" + VERSION;
    }

    static String instruction(String docType) {
        return "첨부한 문서에서 다음 날짜를 모두 추출하라: " + TARGETS.get(docType);
    }

    private static JsonNode schema() {
        JsonNodeFactory f = JsonNodeFactory.instance;
        ObjectNode props = f.objectNode();
        props.set("type", enumOf(ScheduleSuggestion.Type.values()));
        for (String s : List.of("kind", "subkind", "title", "discipline", "date", "startDate", "startsAt", "endsAt",
                "location", "counterpartOrg", "purpose", "anchor", "offsetUnit", "rule", "interval", "confidence")) {
            props.set(s, f.objectNode().put("type", "string"));
        }
        props.set("offset", f.objectNode().put("type", "integer"));
        props.set("durationDays", f.objectNode().put("type", "integer"));
        props.set("yearInferred", f.objectNode().put("type", "boolean"));
        ObjectNode evidence = f.objectNode();
        evidence.set("page", f.objectNode().put("type", "integer"));
        evidence.set("quote", f.objectNode().put("type", "string"));
        props.set("evidence", object(evidence));
        ObjectNode root = f.objectNode();
        root.set("rows", f.objectNode().put("type", "array").set("items", object(props)));
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

    private static ObjectNode enumOf(Enum<?>[] values) {
        ObjectNode o = JsonNodeFactory.instance.objectNode().put("type", "string");
        Arrays.stream(values).map(Enum::name).forEach(o.putArray("enum")::add);
        return o;
    }
}
