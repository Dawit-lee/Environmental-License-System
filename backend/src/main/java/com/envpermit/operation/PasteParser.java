package com.envpermit.operation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * F2 §3.4 엑셀 붙여넣기: text copied from a sheet → rows of (측정일, 항목, 값), each either usable or carrying its
 * error. Pure: which items exist is passed in; whether the point has a limit for the item is the caller's check.
 */
final class PasteParser {

    static final int MAX_ROWS = 500;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-M-d").withResolverStyle(ResolverStyle.STRICT);

    private PasteParser() {
    }

    /** line: 1-based line of the pasted text. error == null: measuredOn, pollutant (code) and value are all set. */
    record Row(int line, String raw, LocalDate measuredOn, String pollutant, BigDecimal value, String error) {
    }

    /**
     * @param items pollutant code → display name; a cell matches either, ignoring case
     * @throws IllegalArgumentException more than {@link #MAX_ROWS} data rows
     */
    static List<Row> parse(String text, Map<String, String> items) {
        Map<String, String> codes = new HashMap<>();
        items.forEach((code, name) -> {
            codes.put(code.toLowerCase(Locale.ROOT), code);
            codes.put(name.toLowerCase(Locale.ROOT), code);
        });
        List<Row> rows = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        String[] lines = text.split("\r?\n|\r", -1);
        boolean first = true;
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i].strip();
            boolean tabs = lines[i].contains("\t");
            String[] cells = lines[i].split(tabs ? "\t" : ",", -1); // not the stripped line: an empty first or last cell is a cell
            for (int c = 0; c < cells.length; c++) {
                cells[c] = cells[c].strip();
            }
            if (String.join("", cells).isEmpty()) {
                continue; // blank line, or a row of empty cells
            }
            boolean header = first && cells[0].chars().noneMatch(Character::isDigit); // a date always has digits
            first = false;
            if (header) {
                continue;
            }
            if (rows.size() == MAX_ROWS) {
                throw new IllegalArgumentException("한 번에 최대 " + MAX_ROWS + "행까지 붙여넣을 수 있습니다.");
            }
            if (cells.length != 3) { // a comma inside a comma-separated value (1,234) lands here rather than being cut
                rows.add(new Row(i + 1, raw, null, null, null, "측정일·항목·값 3열이 아닙니다"));
                continue;
            }
            List<String> errors = new ArrayList<>();
            LocalDate on = date(cells[0]);
            if (on == null) {
                errors.add("날짜 형식 오류");
            }
            String code = codes.get(cells[1].toLowerCase(Locale.ROOT));
            if (code == null) {
                errors.add(cells[1].isEmpty() ? "빈 항목" : "모르는 항목");
            }
            BigDecimal value = null;
            if (cells[2].isEmpty()) {
                errors.add("빈 값");
            } else {
                try {
                    value = new BigDecimal(tabs ? cells[2].replace(",", "") : cells[2]);
                    if (value.signum() < 0) {
                        errors.add("음수 값");
                    }
                } catch (NumberFormatException e) {
                    errors.add("숫자가 아닌 값");
                }
            }
            if (errors.isEmpty()) {
                Integer same = seen.putIfAbsent(on + "|" + code, i + 1);
                if (same != null) {
                    errors.add(same + "행과 측정일·항목 중복");
                }
            }
            rows.add(new Row(i + 1, raw, on, code, value, errors.isEmpty() ? null : String.join(", ", errors)));
        }
        return rows;
    }

    /** 2026-01-05, 2026.1.5, 2026/01/05 and Excel's "2026. 1. 5." */
    private static LocalDate date(String cell) {
        try {
            return LocalDate.parse(cell.replaceAll("\\s", "").replaceAll("[./]", "-").replaceAll("-$", ""), DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
