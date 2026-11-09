package com.envpermit.extraction;

/**
 * Swappable input step (design §3.2, D4). Chosen by {@code app.extraction.preparer}; the Claude call,
 * post-processing and storage do not change when it does.
 */
public interface ExtractionInputPreparer {

    /** Stored in ai_run.preparer, e.g. CLAUDE_PDF_DIRECT. Also the config value that selects it. */
    String name();

    /** @throws IllegalArgumentException when the document cannot be used (message is shown to the user) */
    PreparedInput prepare(byte[] pdf);
}
