package com.envpermit.extraction;

import com.envpermit.obligation.ObligationPayload.QuoteCheck;
import java.text.Normalizer;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Is the AI's source_quote really on its page (design §3.6)? Korean text layers often break spacing, so all
 * whitespace is ignored; ±1 page covers sentences that cross a page break. Table: QuoteCheckerTest.
 */
public final class QuoteChecker {

    private static final Pattern SPACE = Pattern.compile("[\\s\\p{Z}]+");

    private QuoteChecker() {
    }

    public static QuoteCheck check(String quote, Integer pageNo, Map<Integer, String> pageTexts) {
        if (pageNo == null) {
            return QuoteCheck.MISMATCH;
        }
        StringBuilder around = new StringBuilder();
        for (int p = pageNo - 1; p <= pageNo + 1; p++) {
            around.append(norm(pageTexts.getOrDefault(p, "")));
        }
        if (around.isEmpty()) {
            return QuoteCheck.UNVERIFIABLE; // scanned: no text layer to compare with
        }
        String q = norm(quote == null ? "" : quote);
        return !q.isEmpty() && around.indexOf(q) >= 0 ? QuoteCheck.VERIFIED : QuoteCheck.MISMATCH;
    }

    static String norm(String s) {
        return SPACE.matcher(Normalizer.normalize(s, Normalizer.Form.NFC)).replaceAll("");
    }
}
