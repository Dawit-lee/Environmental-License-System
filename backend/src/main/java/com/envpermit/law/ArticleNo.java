package com.envpermit.law;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One spelling for an article reference: 제29조, 제29조의2, 별표3, 별표3의2.
 * Accepts "29", "제 29 조", "29조의2", 6-digit JO code "002902" (4 digits 조 + 2 digits 가지번호).
 */
public final class ArticleNo {

    private static final Pattern ANNEX = Pattern.compile("별표(\\d+)(?:의(\\d+))?");
    private static final Pattern JO_CODE = Pattern.compile("(\\d{4})(\\d{2})");
    private static final Pattern ARTICLE = Pattern.compile("제?(\\d+)조?(?:의(\\d+))?");

    private ArticleNo() {
    }

    /** Blank → null. Unrecognized → IllegalArgumentException. */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.replaceAll("\\s", "");
        Matcher m;
        if ((m = ANNEX.matcher(s)).matches()) {
            return "별표" + Integer.parseInt(m.group(1)) + branch(m.group(2));
        }
        if ((m = JO_CODE.matcher(s)).matches()) {
            int b = Integer.parseInt(m.group(2));
            return article(m.group(1), b == 0 ? null : String.valueOf(b));
        }
        if ((m = ARTICLE.matcher(s)).matches()) {
            return article(m.group(1), m.group(2));
        }
        throw new IllegalArgumentException("조문 번호 형식이 아닙니다: " + raw);
    }

    private static String article(String no, String branch) {
        int n = Integer.parseInt(no);
        if (n == 0) {
            throw new IllegalArgumentException("조문 번호는 1 이상이어야 합니다.");
        }
        return "제" + n + "조" + branch(branch);
    }

    private static String branch(String b) {
        return b == null ? "" : "의" + Integer.parseInt(b);
    }
}
