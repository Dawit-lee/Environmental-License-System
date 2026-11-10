package com.envpermit.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderTest {

    private static final Map<String, String> NONE = Map.of();
    private static final Map<String, String> GEMINI_KEY = Map.of("GEMINI_API_KEY", "g");
    private static final Map<String, String> CLAUDE_KEY = Map.of("ANTHROPIC_API_KEY", "a");
    private static final Map<String, String> BOTH = Map.of("GEMINI_API_KEY", "g", "ANTHROPIC_API_KEY", "a");

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void the_setting_wins_in_any_case() {
        assertThat(Provider.choose("gemini", CLAUDE_KEY::get)).isEqualTo(Provider.GEMINI);
        assertThat(Provider.choose(" CLAUDE ", GEMINI_KEY::get)).isEqualTo(Provider.CLAUDE);
    }

    @Test
    void unset_means_the_provider_whose_key_is_present_claude_when_both_or_none() {
        assertThat(Provider.choose("", GEMINI_KEY::get)).isEqualTo(Provider.GEMINI);
        assertThat(Provider.choose(null, CLAUDE_KEY::get)).isEqualTo(Provider.CLAUDE);
        assertThat(Provider.choose("", BOTH::get)).isEqualTo(Provider.CLAUDE);
        assertThat(Provider.choose("", NONE::get)).isEqualTo(Provider.CLAUDE);
        assertThat(Provider.choose("", Map.of("GEMINI_API_KEY", " ")::get)).isEqualTo(Provider.CLAUDE);
    }

    @Test
    void unknown_provider_stops_startup() {
        assertThatThrownBy(() -> Provider.choose("openai", NONE::get))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("claude|gemini");
    }

    @Test
    void gemini_text_skips_thoughts_and_tokens_count_thinking_as_output() throws Exception {
        JsonNode res = json.readTree("""
                {"candidates":[{"content":{"parts":[{"text":"plan","thought":true},{"text":"{\\"obligations\\""},
                 {"text":":[]}"}]},"finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":900,"candidatesTokenCount":120,"thoughtsTokenCount":300}}""");

        assertThat(Provider.GEMINI.text(res)).isEqualTo("{\"obligations\":[]}");
        assertThat(Provider.GEMINI.inputTokens(res)).isEqualTo(900);
        assertThat(Provider.GEMINI.outputTokens(res)).isEqualTo(420);
    }

    @Test
    void gemini_truncation_and_blocks_are_bad_responses() throws Exception {
        assertThatThrownBy(() -> Provider.GEMINI.text(json.readTree(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\"}]},\"finishReason\":\"MAX_TOKENS\"}]}")))
                .isInstanceOf(Extraction.BadResponse.class).hasMessage("AI 응답이 너무 길어 잘렸습니다.");
        assertThatThrownBy(() -> Provider.GEMINI.text(json.readTree(
                "{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}")))
                .isInstanceOf(Extraction.BadResponse.class).hasMessage("AI가 이 문서의 처리를 거절했습니다(SAFETY).");
        assertThatThrownBy(() -> Provider.GEMINI.text(json.readTree(
                "{\"promptFeedback\":{\"blockReason\":\"PROHIBITED_CONTENT\"}}")))
                .isInstanceOf(Extraction.BadResponse.class)
                .hasMessage("AI가 이 문서의 처리를 거절했습니다(PROHIBITED_CONTENT).");
    }

    @Test
    void claude_reading_is_unchanged() throws Exception {
        JsonNode res = json.readTree("""
                {"content":[{"type":"thinking","thinking":""},{"type":"text","text":"{}"}],"stop_reason":"end_turn",
                 "usage":{"input_tokens":10,"cache_read_input_tokens":5,"cache_creation_input_tokens":1,"output_tokens":7}}""");

        assertThat(Provider.CLAUDE.text(res)).isEqualTo("{}");
        assertThat(Provider.CLAUDE.inputTokens(res)).isEqualTo(16);
        assertThat(Provider.CLAUDE.outputTokens(res)).isEqualTo(7);
        assertThatThrownBy(() -> Provider.CLAUDE.text(json.readTree("{\"stop_reason\":\"refusal\"}")))
                .isInstanceOf(Extraction.BadResponse.class).hasMessage("AI가 이 문서의 처리를 거절했습니다.");
    }
}
