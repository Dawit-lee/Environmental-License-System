package com.envpermit.law;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
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

    /** lawSearch.do?target=law&query= — partial name match, so callers must filter (see LawCatalogService). */
    public List<LawRow> search(String query) {
        String body = maskOc(fetchSearch(query));
        try {
            return parse(json, body);
        } catch (Exception e) {
            log.warn("법제처 응답 파싱 실패: {}", body);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "법제처 응답을 해석할 수 없습니다.");
        }
    }

    /** Raw response body. Overridden by the fixture fake in tests. */
    protected String fetchSearch(String query) {
        if (oc.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "법제처 API 키(LAW_API_OC)가 설정되지 않았습니다.");
        }
        URI uri = UriComponentsBuilder.fromUriString("https://www.law.go.kr/DRF/lawSearch.do")
                .queryParam("OC", oc).queryParam("target", "law").queryParam("type", "JSON")
                .queryParam("display", 100).queryParam("query", query)
                .encode(StandardCharsets.UTF_8).build().toUri();
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
        for (JsonNode n : law.isArray() ? law : law.isMissingNode() ? List.<JsonNode>of() : List.of(law)) {
            rows.add(new LawRow(n.path("법령ID").asText(), n.path("법령일련번호").asLong(), n.path("법령명한글").asText(),
                    n.path("법령구분명").asText(), blankToNull(n.path("법령약칭명").asText()),
                    date(n.path("공포일자").asText()), date(n.path("시행일자").asText()),
                    n.path("제개정구분명").asText(), n.path("현행연혁코드").asText()));
        }
        return rows;
    }

    private static LocalDate date(String ymd) {
        return ymd.isBlank() ? null : LocalDate.parse(ymd, YMD);
    }

    private static String blankToNull(String s) {
        return s.isBlank() ? null : s;
    }
}
