package com.envpermit.extraction;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Deterministic citation_key from citation_raw (design §3.4). ④ version diff matches on these keys, so any rule
 * change here needs a data migration. Table: CitationKeyTest.
 */
public final class CitationKey {

    /** 평가항목 names (design §3.5) that may lead a citation numbered per field, e.g. "대기질 1항" → "대기질-1". */
    private static final List<String> FIELD_NAMES = List.of(
            "동·식물상", "자연환경자산", "기상", "대기질", "악취", "온실가스", "수질", "수리·수문", "해양환경",
            "토지이용", "토양", "지형·지질", "자원순환", "소음·진동", "위락·경관", "위생·공중보건", "전파장해", "일조장해",
            "인구", "주거", "산업").stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();

    // noise words and whitespace; replaced by a separator so "제3항 제2호" stays "3-2", not "32"
    private static final Pattern NOISE = Pattern.compile("협의의견|허가조건|조건|제|항|호|목|\\s+");
    // hierarchy separators; '(' too so inner and outer brackets both fall away
    private static final Pattern SEPARATORS = Pattern.compile("[-.()·]+");

    private CitationKey() {
    }

    /** null when nothing is left (no number in the document) — use {@link #synthetic}. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = digits(Normalizer.normalize(raw, Normalizer.Form.NFC)).strip();
        String prefix = FIELD_NAMES.stream().filter(s::startsWith).findFirst().orElse(null);
        if (prefix != null) {
            s = s.substring(prefix.length());
        }
        s = SEPARATORS.matcher(NOISE.matcher(s).replaceAll("-")).replaceAll("-");
        s = s.replaceAll("^-+|-+$", "");
        if (s.isEmpty()) {
            return null;
        }
        return prefix == null ? s : prefix + "-" + s;
    }

    public static String synthetic(int pageNo, int seq) {
        return "P" + pageNo + "-" + seq;
    }

    /** Full-width digits → ASCII; ①…⑳ and ⑴…⒇ → "-n" (a level of their own). */
    private static String digits(String s) {
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints().forEach(c -> {
            if (c >= '０' && c <= '９') {
                out.append((char) ('0' + c - '０'));
            } else if (c >= 0x2460 && c <= 0x2473) {
                out.append('-').append(c - 0x2460 + 1);
            } else if (c >= 0x2474 && c <= 0x2487) {
                out.append('-').append(c - 0x2474 + 1);
            } else {
                out.appendCodePoint(c);
            }
        });
        return out.toString();
    }
}
