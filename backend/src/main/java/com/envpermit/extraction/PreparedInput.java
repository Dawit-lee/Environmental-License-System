package com.envpermit.extraction;

import java.util.Map;

/**
 * What an {@link ExtractionInputPreparer} hands on: the content the LLM reads, and per-page text for quote_check
 * (1-based page → text; pages without a text layer are absent, so a scan has an empty map).
 */
public record PreparedInput(Content llmContent, Map<Integer, String> pageTexts) {

    public sealed interface Content {
    }

    /** The original PDF; Claude reads its text and page images together. */
    public record Pdf(byte[] bytes) implements Content {
    }

    /** Per-page OCR text (a CLOVA preparer would produce this). */
    public record Text(String text) implements Content {
    }
}
