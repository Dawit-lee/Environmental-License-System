package com.envpermit.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.function.UnaryOperator;

/**
 * LLM provider for extraction, and how to read its response body (text + token usage). Stored on ai_run.provider.
 * {@link #text} throws {@link Extraction.BadResponse} for a refused / blocked / truncated answer.
 */
public enum Provider {

    CLAUDE("ANTHROPIC_API_KEY", "Anthropic Claude") {
        @Override
        String text(JsonNode res) {
            String stop = res.path("stop_reason").asText();
            if ("refusal".equals(stop)) {
                throw new Extraction.BadResponse("AI가 이 문서의 처리를 거절했습니다.");
            }
            if ("max_tokens".equals(stop)) {
                throw new Extraction.BadResponse(TRUNCATED);
            }
            StringBuilder text = new StringBuilder();
            res.path("content").forEach(b -> {
                if ("text".equals(b.path("type").asText())) {
                    text.append(b.path("text").asText());
                }
            });
            return text.toString();
        }

        @Override
        int inputTokens(JsonNode res) {
            JsonNode u = res.path("usage");
            return u.path("input_tokens").asInt() + u.path("cache_read_input_tokens").asInt()
                    + u.path("cache_creation_input_tokens").asInt();
        }

        @Override
        int outputTokens(JsonNode res) {
            return res.path("usage").path("output_tokens").asInt();
        }
    },

    GEMINI("GEMINI_API_KEY", "Google Gemini") {
        @Override
        String text(JsonNode res) {
            String blocked = res.path("promptFeedback").path("blockReason").asText();
            if (!blocked.isEmpty()) {
                throw new Extraction.BadResponse(refused(blocked));
            }
            JsonNode candidate = res.path("candidates").path(0);
            String finish = candidate.path("finishReason").asText();
            if ("MAX_TOKENS".equals(finish)) {
                throw new Extraction.BadResponse(TRUNCATED);
            }
            if (!"STOP".equals(finish)) {
                throw new Extraction.BadResponse(refused(finish.isEmpty() ? "응답 없음" : finish));
            }
            StringBuilder text = new StringBuilder();
            candidate.path("content").path("parts").forEach(p -> {
                if (!p.path("thought").asBoolean()) {
                    text.append(p.path("text").asText());
                }
            });
            return text.toString();
        }

        @Override
        int inputTokens(JsonNode res) {
            return res.path("usageMetadata").path("promptTokenCount").asInt(); // includes cached tokens
        }

        @Override
        int outputTokens(JsonNode res) {
            JsonNode u = res.path("usageMetadata");
            return u.path("candidatesTokenCount").asInt() + u.path("thoughtsTokenCount").asInt(); // thinking is billed as output
        }
    },

    OPENAI("OPENAI_API_KEY", "OpenAI") {
        @Override
        String text(JsonNode res) {
            if (!"completed".equals(res.path("status").asText())) {
                String reason = res.path("incomplete_details").path("reason").asText();
                if ("max_output_tokens".equals(reason)) {
                    throw new Extraction.BadResponse(TRUNCATED);
                }
                throw new Extraction.BadResponse(refused(reason.isEmpty() ? "응답 없음" : reason));
            }
            StringBuilder text = new StringBuilder();
            res.path("output").forEach(item -> item.path("content").forEach(c -> {
                if ("refusal".equals(c.path("type").asText())) {
                    throw new Extraction.BadResponse("AI가 이 문서의 처리를 거절했습니다.");
                }
                if ("output_text".equals(c.path("type").asText())) {
                    text.append(c.path("text").asText());
                }
            }));
            return text.toString();
        }

        @Override
        int inputTokens(JsonNode res) {
            return res.path("usage").path("input_tokens").asInt(); // includes cached tokens
        }

        @Override
        int outputTokens(JsonNode res) {
            return res.path("usage").path("output_tokens").asInt(); // includes reasoning tokens
        }
    };

    private static final String TRUNCATED = "AI 응답이 너무 길어 잘렸습니다.";

    /** Env var holding the API key; the key is read from nowhere else. */
    final String keyEnv;
    /** Service name for the "document goes to an external AI" notice. */
    final String label;

    Provider(String keyEnv, String label) {
        this.keyEnv = keyEnv;
        this.label = label;
    }

    abstract String text(JsonNode res);

    abstract int inputTokens(JsonNode res);

    abstract int outputTokens(JsonNode res);

    private static String refused(String reason) {
        return "AI가 이 문서의 처리를 거절했습니다(" + reason + ").";
    }

    /** The setting (claude|gemini|openai) wins; unset → the first provider (in that order) whose key is present, else Claude. */
    static Provider choose(String configured, UnaryOperator<String> env) {
        if (configured != null && !configured.isBlank()) {
            try {
                return valueOf(configured.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("EXTRACTION_PROVIDER는 claude|gemini|openai 중 하나여야 합니다: " + configured);
            }
        }
        return Stream.of(values()).filter(p -> present(env.apply(p.keyEnv))).findFirst().orElse(CLAUDE);
    }

    private static boolean present(String key) {
        return key != null && !key.isBlank();
    }
}
