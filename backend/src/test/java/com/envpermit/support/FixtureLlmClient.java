package com.envpermit.support;

import com.envpermit.extraction.LlmClient;
import com.envpermit.extraction.PreparedInput;
import com.envpermit.extraction.Provider;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * LLM fake: answers with response bodies from /claude, /gemini or /openai (by the current provider; see their READMEs),
 * in the order queued. Queue a RuntimeException to simulate an API error. No network. Claude unless {@link #use}.
 */
public class FixtureLlmClient implements LlmClient {

    public record Call(String model, String system, PreparedInput.Content content, JsonNode schema) {
    }

    private final Deque<Object> answers = new ArrayDeque<>();
    public final List<Call> calls = new ArrayList<>();
    private Provider provider;
    private String model;

    public FixtureLlmClient() {
        reset();
    }

    /** Fixture file names (in /claude, /gemini or /openai) or exceptions, answered in order. */
    public void answer(Object... fixturesOrErrors) {
        answers.addAll(List.of(fixturesOrErrors));
    }

    public void use(Provider provider, String model) {
        this.provider = provider;
        this.model = model;
    }

    public void reset() {
        answers.clear();
        calls.clear();
        use(Provider.CLAUDE, "claude-sonnet-5-5");
    }

    @Override
    public Provider provider() {
        return provider;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public String send(String system, PreparedInput.Content content, String instruction, JsonNode schema) {
        calls.add(new Call(model, system, content, schema));
        Object next = answers.poll();
        if (next == null) {
            throw new IllegalStateException("no LLM fixture queued");
        }
        if (next instanceof RuntimeException e) {
            throw e;
        }
        return FixtureLawApi.read("/" + provider.name().toLowerCase() + "/" + next);
    }
}
