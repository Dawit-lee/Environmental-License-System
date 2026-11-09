package com.envpermit.support;

import com.envpermit.extraction.ClaudeClient;
import com.envpermit.extraction.PreparedInput;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Claude fake: answers with Messages API response bodies from /claude (see its README), in the order queued.
 * Queue a RuntimeException to simulate an API error. No network.
 */
public class FixtureClaudeClient implements ClaudeClient {

    public record Call(String model, String system, PreparedInput.Content content, JsonNode schema) {
    }

    private final Deque<Object> answers = new ArrayDeque<>();
    public final List<Call> calls = new ArrayList<>();

    /** Fixture file names (in /claude) or exceptions, answered in order. */
    public void answer(Object... fixturesOrErrors) {
        answers.addAll(List.of(fixturesOrErrors));
    }

    public void reset() {
        answers.clear();
        calls.clear();
    }

    @Override
    public String send(String model, String system, PreparedInput.Content content, String instruction,
                       JsonNode schema) {
        calls.add(new Call(model, system, content, schema));
        Object next = answers.poll();
        if (next == null) {
            throw new IllegalStateException("no Claude fixture queued");
        }
        if (next instanceof RuntimeException e) {
            throw e;
        }
        return FixtureLawApi.read("/claude/" + next);
    }
}
