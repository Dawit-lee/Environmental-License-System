package com.envpermit.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Gemini generateContent over REST: PDF as inline data + JSON-schema-constrained output (responseJsonSchema).
 * The key comes only from env var GEMINI_API_KEY (passed in by {@link ExtractionConfig}) and goes in a header,
 * never in the URL or body. No retries here: {@link Extraction} retries once, as design §3.9 says.
 */
class GeminiClient implements LlmClient {

    private static final Duration TIMEOUT = Duration.ofMinutes(10);

    private final ObjectMapper json;
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    GeminiClient(ObjectMapper json, String baseUrl, String model, String apiKey) {
        this.json = json;
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
    }

    @Override
    public Provider provider() {
        return Provider.GEMINI;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public String send(String system, PreparedInput.Content content, String instruction, JsonNode schema) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("서버에 GEMINI_API_KEY가 설정되지 않았습니다.");
        }
        ObjectNode body = json.createObjectNode();
        body.putObject("systemInstruction").putArray("parts").addObject().put("text", system);
        ObjectNode user = body.putArray("contents").addObject().put("role", "user");
        ArrayNode parts = user.putArray("parts");
        if (content instanceof PreparedInput.Pdf pdf) {
            parts.addObject().putObject("inlineData").put("mimeType", "application/pdf")
                    .put("data", Base64.getEncoder().encodeToString(pdf.bytes()));
        } else {
            parts.addObject().put("text", ((PreparedInput.Text) content).text());
        }
        parts.addObject().put("text", instruction);
        body.putObject("generationConfig").put("responseMimeType", "application/json").set("responseJsonSchema", schema);

        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/v1beta/models/" + model + ":generateContent"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Gemini 호출이 중단되었습니다.", e);
        }
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("Gemini API " + res.statusCode() + ": " + errorStatus(res.body()));
        }
        return res.body();
    }

    // error.status (e.g. RESOURCE_EXHAUSTED) + message; the body never holds the key
    private String errorStatus(String body) {
        try {
            JsonNode e = json.readTree(body).path("error");
            return e.path("status").asText() + " " + e.path("message").asText();
        } catch (IOException ex) {
            return body.length() > 200 ? body.substring(0, 200) : body;
        }
    }
}
