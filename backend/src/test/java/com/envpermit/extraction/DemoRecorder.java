package com.envpermit.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Manual, real-API recording for the public demo (costs 4 calls; CI never runs it): sends each fictional sample PDF
 * in /demo/documents with the prompt a real run would use and saves the response to src/main/resources/demo/ai,
 * which DemoAiSeed replays on the demo server instead of calling the API.
 * Run: RECORD_DEMO=1 ANTHROPIC_API_KEY=... ./gradlew test --tests '*DemoRecorder'
 */
@EnabledIfEnvironmentVariable(named = "RECORD_DEMO", matches = "1")
class DemoRecorder {

    @Test
    void record() throws Exception {
        ObjectMapper json = new ObjectMapper();
        AnthropicClaudeClient claude = new AnthropicClaudeClient(json,
                System.getenv().getOrDefault("CLAUDE_MODEL", "claude-sonnet-5-5"));
        for (DemoAiSeed.Doc doc : DemoAiSeed.DOCS) {
            byte[] pdf = DemoRecorder.class.getResourceAsStream(doc.pdfPath()).readAllBytes();
            PreparedInput.Pdf input = new PreparedInput.Pdf(pdf);
            String raw = doc.docType() != null
                    ? claude.send(SchedulePrompt.SYSTEM, input, SchedulePrompt.instruction(doc.docType()), SchedulePrompt.SCHEMA)
                    : claude.send(ObligationPrompt.SYSTEM, input, ObligationPrompt.INSTRUCTION, ObligationPrompt.SCHEMA);
            assertThat(raw).doesNotContain(System.getenv("ANTHROPIC_API_KEY"));
            String pretty = json.writerWithDefaultPrettyPrinter().writeValueAsString(json.readTree(raw));
            Files.writeString(Path.of("src/main/resources" + doc.responsePath()), pretty + "\n", StandardCharsets.UTF_8);
        }
    }
}
