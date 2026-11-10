package com.envpermit.extraction;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The extraction LLM call, one implementation per {@link Provider} (chosen by {@code app.extraction.provider}).
 * Returns the raw response body, which is stored in S3 as is and read by {@link Provider}. Tests replace it with
 * recorded response fixtures.
 */
public interface LlmClient {

    Provider provider();

    /** Model id sent to the API and stored on ai_run. */
    String model();

    /** @throws RuntimeException on any API/network error (the caller retries once) */
    String send(String system, PreparedInput.Content content, String instruction, JsonNode schema);
}
