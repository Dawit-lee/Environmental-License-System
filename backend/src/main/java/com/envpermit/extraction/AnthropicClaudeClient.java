package com.envpermit.extraction;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.HttpResponseFor;
import com.anthropic.models.messages.Base64PdfSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Real Messages API call: PDF document block + JSON-schema-constrained output (structured outputs).
 * The key comes only from env var ANTHROPIC_API_KEY (SDK fromEnv; ANTHROPIC_BASE_URL also honoured).
 * SDK retries are off: {@link Extraction} retries once, as design §3.9 says.
 */
@Component
class AnthropicClaudeClient implements ClaudeClient {

    private static final long MAX_TOKENS = 16_000;

    private final ObjectMapper json;
    private volatile AnthropicClient client;

    AnthropicClaudeClient(ObjectMapper json) {
        this.json = json;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String send(String model, String system, PreparedInput.Content content, String instruction,
                       JsonNode schema) {
        ContentBlockParam input = content instanceof PreparedInput.Pdf pdf
                ? ContentBlockParam.ofDocument(DocumentBlockParam.builder()
                        .source(Base64PdfSource.builder().data(Base64.getEncoder().encodeToString(pdf.bytes())).build())
                        .build())
                : ContentBlockParam.ofText(TextBlockParam.builder().text(((PreparedInput.Text) content).text()).build());
        JsonOutputFormat.Schema.Builder schemaParam = JsonOutputFormat.Schema.builder();
        json.convertValue(schema, Map.class).forEach((k, v) -> schemaParam.putAdditionalProperty((String) k, JsonValue.from(v)));
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .system(system)
                .outputConfig(OutputConfig.builder().format(JsonOutputFormat.builder().schema(schemaParam.build()).build()).build())
                .addUserMessageOfContentBlockParams(List.of(input,
                        ContentBlockParam.ofText(TextBlockParam.builder().text(instruction).build())))
                .build();
        try (HttpResponseFor<Message> res = client().messages().withRawResponse().create(params)) {
            return new String(res.body().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // built on first use so the app starts without a key (extraction then fails with a clear run error)
    private AnthropicClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    if (System.getenv("ANTHROPIC_API_KEY") == null) {
                        throw new IllegalStateException("서버에 ANTHROPIC_API_KEY가 설정되지 않았습니다.");
                    }
                    client = AnthropicOkHttpClient.builder().fromEnv().maxRetries(0).build();
                }
            }
        }
        return client;
    }
}
