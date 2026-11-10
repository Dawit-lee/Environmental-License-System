package com.envpermit.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ExtractionConfig {

    static final String EXECUTOR = "extractionExecutor";

    /** Extraction runs off the request thread. Two at a time keeps API spend and memory (PDF bytes) bounded. */
    @Bean(name = EXECUTOR, destroyMethod = "shutdown")
    ExecutorService extractionExecutor() {
        return Executors.newFixedThreadPool(2);
    }

    /** One provider per server (EXTRACTION_PROVIDER, else whichever key is set). Keys come only from env vars. */
    @Bean
    LlmClient llmClient(ObjectMapper json, @Value("${app.extraction.provider:}") String provider,
                        @Value("${app.extraction.claude.model}") String claudeModel,
                        @Value("${app.extraction.gemini.model}") String geminiModel,
                        @Value("${app.extraction.gemini.base-url}") String geminiBaseUrl,
                        @Value("${app.extraction.openai.model}") String openAiModel,
                        @Value("${app.extraction.openai.base-url}") String openAiBaseUrl) {
        LlmClient client = switch (Provider.choose(provider, System::getenv)) {
            case CLAUDE -> new AnthropicClaudeClient(json, claudeModel);
            case GEMINI -> new GeminiClient(json, geminiBaseUrl, geminiModel, System.getenv(Provider.GEMINI.keyEnv));
            case OPENAI -> new OpenAiClient(json, openAiBaseUrl, openAiModel, System.getenv(Provider.OPENAI.keyEnv));
        };
        LoggerFactory.getLogger(ExtractionConfig.class).info("AI extraction provider: {} ({})", client.provider(), client.model());
        return client;
    }
}
