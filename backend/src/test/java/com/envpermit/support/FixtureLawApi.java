package com.envpermit.support;

import com.envpermit.law.LawApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 법제처 fake: serves lawSearch.do bodies recorded from the real API on 2026-11-09 (OC scrubbed).
 * Unknown queries get the recorded zero-result body. No network.
 */
public class FixtureLawApi extends LawApiClient {

    private static final Map<String, String> SEARCH = Map.of(
            "폐기물관리법", "search-waste.json", // 9 rows incl. 방사성폐기물·해양폐기물 partial matches
            "환경영향평가법 시행규칙", "search-eia-rule.json", // single result → object, not array
            "하수도법", "search-sewerage.json");

    public FixtureLawApi() {
        super("test-oc", new ObjectMapper());
    }

    @Override
    protected String fetchSearch(String query) {
        return read("/lawapi/" + SEARCH.getOrDefault(query, "search-empty.json"));
    }

    public static String read(String path) {
        try (InputStream in = FixtureLawApi.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
