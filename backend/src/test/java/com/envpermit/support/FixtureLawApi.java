package com.envpermit.support;

import com.envpermit.law.LawApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 법제처 fake: serves bodies recorded from the real API on 2026-11-09/10 (OC scrubbed). No network.
 * Unknown searches get the recorded zero-result body; unrecorded lsHstInf days are empty.
 * Each call is logged in {@link #calls} as a key (search:Q, lsHstInf:YYYYMMDD:PAGE, law:MST, oldAndNew:MST);
 * a key in {@link #failing} throws instead.
 */
public class FixtureLawApi extends LawApiClient {

    private static final Map<String, String> SEARCH = Map.of(
            "폐기물관리법", "search-waste.json", // 9 rows incl. 방사성폐기물·해양폐기물 partial matches
            "환경영향평가법 시행규칙", "search-eia-rule.json", // single result → object, not array
            "하수도법", "search-sewerage.json");
    private static final String NO_CHANGES = "{\"LawSearch\":{\"target\":\"lsHstInf\",\"totalCnt\":\"0\"}}";

    public final List<String> calls = new ArrayList<>();
    public final Set<String> failing = new HashSet<>();

    public FixtureLawApi() {
        super("test-oc", new ObjectMapper());
    }

    public void reset() {
        calls.clear();
        failing.clear();
    }

    @Override
    protected String fetch(String service, Map<String, String> q) {
        String target = q.get("target");
        String key = switch (target) {
            case "lsHstInf" -> "lsHstInf:" + q.get("regDt") + ":" + q.get("page");
            case "law" -> service.equals("lawSearch.do") ? "search:" + q.get("query") : "law:" + q.get("MST");
            default -> target + ":" + q.get("MST");
        };
        calls.add(key);
        if (failing.contains(key)) {
            throw new IllegalStateException("simulated 법제처 failure: " + key);
        }
        return switch (target) {
            case "lsHstInf" -> {
                String f = "/lawapi/hst-" + q.get("regDt") + "-p" + q.get("page") + ".json";
                yield FixtureLawApi.class.getResource(f) == null ? NO_CHANGES : read(f);
            }
            case "law" -> service.equals("lawSearch.do")
                    ? read("/lawapi/" + SEARCH.getOrDefault(q.get("query"), "search-empty.json"))
                    : read("/lawapi/law-" + q.get("MST") + ".json");
            default -> read("/lawapi/oldnew-" + q.get("MST") + ".json");
        };
    }

    public static String read(String path) {
        try (InputStream in = FixtureLawApi.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("no fixture " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
