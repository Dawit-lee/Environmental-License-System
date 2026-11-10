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
 * OpenAI Responses API over REST: PDF as an inline input_file + strict JSON-schema output (text.format), store=false.
 * The key comes only from env var OPENAI_API_KEY (passed in by {@link ExtractionConfig}) and goes in the
 * Authorization header, never in the URL or body. No retries here: {@link Extraction} retries once (design §3.9).
 */
class OpenAiClient implements LlmClient {

    private static final Duration TIMEOUT = Duration.ofMinutes(10);

    private final ObjectMapper json;
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    OpenAiClient(ObjectMapper json, String baseUrl, String model, String apiKey) {
        this.json = json;
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
    }

    @Override
    public Provider provider() {
        return Provider.OPENAI;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public String send(String system, PreparedInput.Content content, String instruction, JsonNode schema) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("서버에 OPENAI_API_KEY가 설정되지 않았습니다.");
        }
        ObjectNode body = json.createObjectNode().put("model", model).put("instructions", system).put("store", false);
        ArrayNode parts = body.putArray("input").addObject().put("role", "user").putArray("content");
        if (content instanceof PreparedInput.Pdf pdf) {
            parts.addObject().put("type", "input_file").put("filename", "permit.pdf")
                    .put("file_data", "data:application/pdf;base64," + Base64.getEncoder().encodeToString(pdf.bytes()));
        } else {
            parts.addObject().put("type", "input_text").put("text", ((PreparedInput.Text) content).text());
        }
        parts.addObject().put("type", "input_text").put("text", instruction);
        body.putObject("text").putObject("format").put("type", "json_schema").put("name", "obligations")
                .put("strict", true).set("schema", schema);

        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/responses"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenAI 호출이 중단되었습니다.", e);
        }
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("OpenAI API " + res.statusCode() + ": " + errorCode(res.body()));
        }
        return res.body();
    }

    // error.type / error.code (e.g. rate_limit_error slow_down) + message; the body never holds the key
    private String errorCode(String body) {
        try {
            JsonNode e = json.readTree(body).path("error");
            return e.path("type").asText() + " " + e.path("code").asText() + " " + e.path("message").asText();
        } catch (IOException ex) {
            return body.length() > 200 ? body.substring(0, 200) : body;
        }
    }
}
