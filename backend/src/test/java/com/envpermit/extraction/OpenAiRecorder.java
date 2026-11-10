package com.envpermit.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Manual, real-API recording (costs a call; CI never runs it): sends /gemini/consultation-opinion.pdf with the
 * real prompt and schema and saves the response (encrypted reasoning scrubbed) as /openai/obligations-recorded.json.
 * Run: RECORD_OPENAI=1 OPENAI_API_KEY=... ./gradlew test --tests '*OpenAiRecorder'
 */
@EnabledIfEnvironmentVariable(named = "RECORD_OPENAI", matches = "1")
class OpenAiRecorder {

    @Test
    void record() throws Exception {
        String key = System.getenv("OPENAI_API_KEY");
        String model = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-6.1-terra");
        ObjectMapper json = new ObjectMapper();
        byte[] pdf = OpenAiRecorder.class.getResourceAsStream("/gemini/consultation-opinion.pdf").readAllBytes();

        String raw = new OpenAiClient(json, "https://api.openai.com", model, key)
                .send(ObligationPrompt.SYSTEM, new PreparedInput.Pdf(pdf), ObligationPrompt.INSTRUCTION,
                        ObligationPrompt.SCHEMA);

        assertThat(raw).doesNotContain(key); // scrub check: the key must never land in a fixture
        JsonNode res = json.readTree(raw);
        // opaque encrypted reasoning blob (store=false); not read by the app, so not kept
        res.path("output").forEach(item -> {
            if (item.has("encrypted_content")) {
                ((ObjectNode) item).put("encrypted_content", "<scrubbed>");
            }
        });
        String pretty = json.writerWithDefaultPrettyPrinter().writeValueAsString(res);
        Files.writeString(Path.of("src/test/resources/openai/obligations-recorded.json"), pretty + "\n",
                StandardCharsets.UTF_8);
    }
}
