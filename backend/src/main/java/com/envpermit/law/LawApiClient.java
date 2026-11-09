package com.envpermit.law;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 법제처 DRF client (https://www.law.go.kr/DRF/, type=JSON). The OC key comes only from env var LAW_API_OC.
 * Every response is OC-masked before anything parses, logs or stores it. Calls are spaced ≥1s apart.
 */
@Component
public class LawApiClient {

    private static final Logger log = LoggerFactory.getLogger(LawApiClient.class);
    private static final Pattern OC = Pattern.compile("OC=[^&\"\\\\\\s]*");
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    /** One row of lawSearch.do. {@code status} is 현행연혁코드 (현행 / 연혁 / ...). */
    public record LawRow(String lawId, long mst, String name, String kindName, String shortName,
                         LocalDate promulgatedOn, LocalDate enforcedOn, String revisionType, String status) {
    }

    private final String oc;
    private final ObjectMapper json;
    private final RestClient http = RestClient.create();
    private long lastCallNanos;

    public LawApiClient(@Value("${LAW_API_OC:}") String oc, ObjectMapper json) {
        this.oc = oc;
        this.json = json;
    }

    /** One article whose 조문변경여부 is Y in a law body. {@code changeType} is 조문제개정유형 (개정 / 신설 / ...). */
    public record ChangedArticle(String articleNo, String joCode, String changeType, LocalDate enforcedOn) {
    }

    /** What the batch needs from lawService.do?target=law: changed articles and 제개정이유. */
    public record LawBody(String reason, List<ChangedArticle> changed) {
    }

    @FunctionalInterface
    interface Parser<T> {
        T parse(ObjectMapper json, String body) throws Exception;
    }

    public boolean configured() {
        return !oc.isBlank();
    }

    /** lawSearch.do?target=law&query= — partial name match, so callers must filter (see LawCatalogService). */
    public List<LawRow> search(String query) {
        return call("lawSearch.do", Map.of("target", "law", "display", "100", "query", query), LawApiClient::parse);
    }

    /** lsHstInf for one regDt, every page until totalCnt. Mixes 현행 and old 연혁 rows. */
    public List<LawRow> changes(LocalDate regDt) {
        List<LawRow> all = new ArrayList<>();
        for (int page = 1; ; page++) {
            int[] total = new int[1];
            List<LawRow> rows = call("lawSearch.do", Map.of("target", "lsHstInf", "regDt", regDt.format(YMD),
                    "display", "100", "page", String.valueOf(page)), (json, body) -> {
                total[0] = json.readTree(body).path("LawSearch").path("totalCnt").asInt();
                return parse(json, body);
            });
            all.addAll(rows);
            if (rows.isEmpty() || all.size() >= total[0]) {
                return all;
            }
        }
    }

    /** lawService.do?target=law&MST= */
    public LawBody body(long mst) {
        return call("lawService.do", Map.of("target", "law", "MST", String.valueOf(mst)), LawApiClient::parseBody);
    }

    /** lawService.do?target=oldAndNew&MST= as OC-masked JSON text. Fails unless it holds OldAndNewService. */
    public String oldAndNew(long mst) {
        return call("lawService.do", Map.of("target", "oldAndNew", "MST", String.valueOf(mst)), (json, body) -> {
            if (!json.readTree(body).has("OldAndNewService")) {
                throw new IllegalArgumentException("no OldAndNewService");
            }
            return body;
        });
    }

    private <T> T call(String service, Map<String, String> params, Parser<T> parser) {
        String body = maskOc(fetch(service, params));
        try {
            return parser.parse(json, body);
        } catch (Exception e) {
            log.warn("법제처 응답 파싱 실패 ({} {}): {}", service, params.get("target"), body);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "법제처 응답을 해석할 수 없습니다.");
        }
    }

    /** Raw response body. Overridden by the fixture fake in tests. */
    protected String fetch(String service, Map<String, String> params) {
        if (oc.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "법제처 API 키(LAW_API_OC)가 설정되지 않았습니다.");
        }
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString("https://www.law.go.kr/DRF/" + service)
                .queryParam("OC", oc).queryParam("type", "JSON");
        params.forEach(b::queryParam);
        URI uri = b.encode(StandardCharsets.UTF_8).build().toUri();
        throttle();
        try {
            return http.get().uri(uri).retrieve().body(String.class);
        } catch (Exception e) {
            // the exception message can carry the request URL, so mask and drop the cause
            log.warn("법제처 호출 실패: {}", maskOc(String.valueOf(e.getMessage())));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "법제처 호출에 실패했습니다.");
        }
    }

    private synchronized void throttle() {
        long wait = lastCallNanos + 1_000_000_000L - System.nanoTime();
        if (lastCallNanos != 0 && wait > 0) {
            try {
                Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastCallNanos = System.nanoTime();
    }

    static String maskOc(String s) {
        return s == null ? null : OC.matcher(s).replaceAll("OC=***");
    }

    /** One result comes back as an object, several as an array, none as a missing "law" key. */
    static List<LawRow> parse(ObjectMapper json, String body) throws Exception {
        JsonNode law = json.readTree(body).path("LawSearch");
        if (law.isMissingNode()) {
            throw new IllegalArgumentException("no LawSearch");
        }
        law = law.path("law");
        List<LawRow> rows = new ArrayList<>();
        for (JsonNode n : list(law)) {
            rows.add(new LawRow(n.path("법령ID").asText(), n.path("법령일련번호").asLong(), n.path("법령명한글").asText(),
                    n.path("법령구분명").asText(), blankToNull(n.path("법령약칭명").asText()),
                    date(n.path("공포일자").asText()), date(n.path("시행일자").asText()),
                    n.path("제개정구분명").asText(), n.path("현행연혁코드").asText()));
        }
        return rows;
    }

    /** Changed = 조문여부 조문 (not a chapter heading) with 조문변경여부 Y. 제개정이유 lines are joined. */
    static LawBody parseBody(ObjectMapper json, String body) throws Exception {
        JsonNode law = json.readTree(body).path("법령");
        JsonNode units = law.path("조문").path("조문단위");
        if (units.isMissingNode()) {
            throw new IllegalArgumentException("no 조문단위");
        }
        List<ChangedArticle> changed = new ArrayList<>();
        for (JsonNode a : list(units)) {
            if ("조문".equals(a.path("조문여부").asText()) && "Y".equals(a.path("조문변경여부").asText())) {
                String branch = a.path("조문가지번호").asText();
                String jo = "%04d%02d".formatted(a.path("조문번호").asInt(), branch.isBlank() ? 0 : Integer.parseInt(branch));
                changed.add(new ChangedArticle(ArticleNo.normalize(jo), jo,
                        blankToNull(a.path("조문제개정유형").asText()), date(a.path("조문시행일자").asText())));
            }
        }
        List<String> reason = new ArrayList<>();
        law.path("제개정이유").path("제개정이유내용").forEach(group -> list(group).forEach(line -> reason.add(line.asText())));
        return new LawBody(reason.isEmpty() ? null : String.join("\n", reason), changed);
    }

    private static Iterable<JsonNode> list(JsonNode n) {
        return n.isArray() ? n : n.isMissingNode() ? List.of() : List.of(n);
    }

    private static LocalDate date(String ymd) {
        return ymd.isBlank() ? null : LocalDate.parse(ymd, YMD);
    }

    private static String blankToNull(String s) {
        return s.isBlank() ? null : s;
    }
}
