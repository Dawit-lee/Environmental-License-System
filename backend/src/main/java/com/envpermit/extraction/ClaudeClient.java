package com.envpermit.extraction;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Adapter around the Anthropic Messages API. Returns the raw response body (Messages API JSON), which is stored
 * in S3 as is and parsed by {@link Extraction}. Tests replace it with recorded response fixtures.
 */
public interface ClaudeClient {

    /** @throws RuntimeException on any API/network error (the caller retries once) */
    String send(String model, String system, PreparedInput.Content content, String instruction, JsonNode schema);
}
