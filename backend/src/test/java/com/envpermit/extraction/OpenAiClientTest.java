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
class OpenAiClientTest {

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private int status = 200;
    private String reply = "{\"status\":\"completed\",\"output\":[]}";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            path.set(ex.getRequestURI().getPath());
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
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

    private OpenAiClient client(String apiKey) {
        return new OpenAiClient(json, "http://127.0.0.1:" + server.getAddress().getPort(), "gpt-test", apiKey);
    }

    @Test
    void sends_the_pdf_as_a_file_with_the_strict_schema_and_returns_the_raw_body() throws Exception {
        byte[] pdf = {'%', 'P', 'D', 'F'};

        String raw = client("test-key").send("sys", new PreparedInput.Pdf(pdf), "do it", ObligationPrompt.SCHEMA);

        assertThat(raw).isEqualTo(reply);
        assertThat(path.get()).isEqualTo("/v1/responses");
        assertThat(auth.get()).isEqualTo("Bearer test-key");
        JsonNode req = json.readTree(body.get());
        assertThat(req.at("/model").asText()).isEqualTo("gpt-test");
        assertThat(req.at("/instructions").asText()).isEqualTo("sys");
        assertThat(req.at("/store").asBoolean(true)).isFalse();
        assertThat(req.at("/input/0/role").asText()).isEqualTo("user");
        assertThat(req.at("/input/0/content/0/type").asText()).isEqualTo("input_file");
        assertThat(req.at("/input/0/content/0/filename").asText()).isEqualTo("permit.pdf");
        assertThat(req.at("/input/0/content/0/file_data").asText())
                .isEqualTo("data:application/pdf;base64," + Base64.getEncoder().encodeToString(pdf));
        assertThat(req.at("/input/0/content/1/type").asText()).isEqualTo("input_text");
        assertThat(req.at("/input/0/content/1/text").asText()).isEqualTo("do it");
        assertThat(req.at("/text/format/type").asText()).isEqualTo("json_schema");
        assertThat(req.at("/text/format/strict").asBoolean()).isTrue();
        assertThat(req.at("/text/format/schema")).isEqualTo(ObligationPrompt.SCHEMA);
        assertThat(body.get()).doesNotContain("test-key");
    }

    @Test
    void ocr_text_goes_as_input_text() throws Exception {
        client("k").send("sys", new PreparedInput.Text("page one"), "do it", ObligationPrompt.SCHEMA);

        JsonNode req = json.readTree(body.get());
        assertThat(req.at("/input/0/content/0/type").asText()).isEqualTo("input_text");
        assertThat(req.at("/input/0/content/0/text").asText()).isEqualTo("page one");
    }

    @Test
    void http_error_is_a_runtime_error_without_the_key() {
        status = 429;
        reply = "{\"error\":{\"type\":\"rate_limit_error\",\"code\":\"slow_down\",\"message\":\"wait\"}}";

        assertThatThrownBy(() -> client("secret-key").send("s", new PreparedInput.Text("t"), "i", ObligationPrompt.SCHEMA))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("429").hasMessageContaining("slow_down")
                .hasMessageNotContaining("secret-key");
    }

    @Test
    void missing_key_fails_the_call_clearly() {
        assertThatThrownBy(() -> client(null).send("s", new PreparedInput.Text("t"), "i", ObligationPrompt.SCHEMA))
                .isInstanceOf(IllegalStateException.class).hasMessage("서버에 OPENAI_API_KEY가 설정되지 않았습니다.");
        assertThat(path.get()).isNull();
    }
}
