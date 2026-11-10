package com.envpermit.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Manual, real-API recording (costs a call; CI never runs it): sends /gemini/consultation-opinion.pdf with the
 * real prompt and schema and saves the response as /gemini/obligations-recorded.json.
 * Run: RECORD_GEMINI=1 GEMINI_API_KEY=... ./gradlew test --tests '*GeminiRecorder'
 */
@EnabledIfEnvironmentVariable(named = "RECORD_GEMINI", matches = "1")
class GeminiRecorder {

    @Test
    void record() throws Exception {
        String key = System.getenv("GEMINI_API_KEY");
        String model = System.getenv().getOrDefault("GEMINI_MODEL", "gemini-3.6-flash");
        ObjectMapper json = new ObjectMapper();
        byte[] pdf = GeminiRecorder.class.getResourceAsStream("/gemini/consultation-opinion.pdf").readAllBytes();

        String raw = new GeminiClient(json, "https://generativelanguage.googleapis.com", model, key)
                .send(ObligationPrompt.SYSTEM, new PreparedInput.Pdf(pdf), ObligationPrompt.INSTRUCTION,
                        ObligationPrompt.SCHEMA);

        assertThat(raw).doesNotContain(key); // scrub check: the key must never land in a fixture
        String pretty = json.writerWithDefaultPrettyPrinter().writeValueAsString(json.readTree(raw));
        Files.writeString(Path.of("src/test/resources/gemini/obligations-recorded.json"), pretty + "\n",
                StandardCharsets.UTF_8);
    }
}
