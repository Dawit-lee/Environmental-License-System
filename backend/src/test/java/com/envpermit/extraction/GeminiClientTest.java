package com.envpermit.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The real HTTP adapter against a local stand-in server (no network): request shape, key header, errors. */
class GeminiClientTest {

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> key = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private int status = 200;
    private String reply = "{\"candidates\":[]}";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            path.set(ex.getRequestURI().getPath());
            key.set(ex.getRequestHeaders().getFirst("x-goog-api-key"));
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private GeminiClient client(String apiKey) {
        return new GeminiClient(json, "http://127.0.0.1:" + server.getAddress().getPort(), "gemini-test", apiKey);
    }

    @Test
    void sends_the_pdf_inline_with_the_schema_and_returns_the_raw_body() throws Exception {
        byte[] pdf = {'%', 'P', 'D', 'F'};

        String raw = client("test-key").send("sys", new PreparedInput.Pdf(pdf), "do it", ObligationPrompt.SCHEMA);

        assertThat(raw).isEqualTo(reply);
        assertThat(path.get()).isEqualTo("/v1beta/models/gemini-test:generateContent");
        assertThat(key.get()).isEqualTo("test-key");
        JsonNode req = json.readTree(body.get());
        assertThat(req.at("/systemInstruction/parts/0/text").asText()).isEqualTo("sys");
        assertThat(req.at("/contents/0/role").asText()).isEqualTo("user");
        assertThat(req.at("/contents/0/parts/0/inlineData/mimeType").asText()).isEqualTo("application/pdf");
        assertThat(req.at("/contents/0/parts/0/inlineData/data").asText())
                .isEqualTo(Base64.getEncoder().encodeToString(pdf));
        assertThat(req.at("/contents/0/parts/1/text").asText()).isEqualTo("do it");
        assertThat(req.at("/generationConfig/responseMimeType").asText()).isEqualTo("application/json");
        assertThat(req.at("/generationConfig/responseJsonSchema")).isEqualTo(ObligationPrompt.SCHEMA);
        assertThat(body.get()).doesNotContain("test-key");
    }

    @Test
    void ocr_text_goes_as_a_text_part() throws Exception {
        client("k").send("sys", new PreparedInput.Text("page one"), "do it", ObligationPrompt.SCHEMA);

        assertThat(json.readTree(body.get()).at("/contents/0/parts/0/text").asText()).isEqualTo("page one");
    }

    @Test
    void http_error_is_a_runtime_error_without_the_key() {
        status = 429;
        reply = "{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\"}}";

        assertThatThrownBy(() -> client("secret-key").send("s", new PreparedInput.Text("t"), "i", ObligationPrompt.SCHEMA))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("429").hasMessageContaining("RESOURCE_EXHAUSTED")
                .hasMessageNotContaining("secret-key");
    }

    @Test
    void missing_key_fails_the_call_clearly() {
        assertThatThrownBy(() -> client(" ").send("s", new PreparedInput.Text("t"), "i", ObligationPrompt.SCHEMA))
                .isInstanceOf(IllegalStateException.class).hasMessage("서버에 GEMINI_API_KEY가 설정되지 않았습니다.");
        assertThat(path.get()).isNull();
    }
}
