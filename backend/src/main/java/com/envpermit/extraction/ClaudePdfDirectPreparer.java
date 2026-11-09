package com.envpermit.extraction;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** v1: send the PDF as is; PDFBox fills pageTexts for pages that have a text layer (scans stay empty). */
@Component
@ConditionalOnProperty(name = "app.extraction.preparer", havingValue = ClaudePdfDirectPreparer.NAME, matchIfMissing = true)
class ClaudePdfDirectPreparer implements ExtractionInputPreparer {

    static final String NAME = "CLAUDE_PDF_DIRECT";
    // Messages API PDF limit for 1M-context models; uploads are ≤10MB so the 32MB request limit is never hit.
    // shortcut: no page-range splitting; longer documents fail with a clear message. Split if that ever happens.
    static final int MAX_PAGES = 600;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public PreparedInput prepare(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            int pages = doc.getNumberOfPages();
            if (pages > MAX_PAGES) {
                throw new IllegalArgumentException("PDF가 " + MAX_PAGES + "쪽을 넘습니다(" + pages + "쪽).");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            Map<Integer, String> texts = new HashMap<>();
            for (int p = 1; p <= pages; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String text = stripper.getText(doc);
                if (!text.isBlank()) {
                    texts.put(p, text);
                }
            }
            return new PreparedInput(new PreparedInput.Pdf(pdf), texts);
        } catch (IOException e) {
            throw new IllegalArgumentException("PDF를 읽을 수 없습니다(손상되었거나 암호가 걸린 파일).");
        }
    }
}
